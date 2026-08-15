package com.dengdeng.music.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.dengdeng.music.data.AlbumGroup
import com.dengdeng.music.data.MusicRepository
import com.dengdeng.music.data.Playlist
import com.dengdeng.music.data.Song
import com.dengdeng.music.data.UserLibraryStore
import com.dengdeng.music.player.PlaybackService
import com.dengdeng.music.player.PlayerControllerProvider
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 主界面 ViewModel —— UI 与数据/播放服务的桥梁
 *
 * 播放控制全部通过 MediaController 转发给后台 PlaybackService：
 * - App 退到后台，音乐由服务继续播放
 * - 通知栏/锁屏控制由 Media3 自动处理
 */
class MusicViewModel(application: Application) : AndroidViewModel(application) {

    /** 扫描到的歌曲列表 */
    var songs by mutableStateOf<List<Song>>(emptyList())

    /** 搜索关键词（空表示不搜索） */
    var searchQuery by mutableStateOf("")

    /** 排序方式：0=歌名 1=艺术家 2=时长 3=最近添加 */
    var sortMode by mutableStateOf(0)

    /** 搜索结果（按关键词过滤 + 排序后的歌曲） */
    val filteredSongs: List<Song>
        get() {
            val q = searchQuery.trim()
            val filtered = if (q.isEmpty()) {
                songs
            } else {
                val lower = q.lowercase()
                songs.filter {
                    it.title.lowercase().contains(lower) ||
                    it.artist.lowercase().contains(lower) ||
                    it.album.lowercase().contains(lower)
                }
            }
            return when (sortMode) {
                1 -> filtered.sortedBy { it.artist.lowercase() }
                2 -> filtered.sortedByDescending { it.durationMs }
                else -> filtered.sortedBy { it.title.lowercase() }
            }
        }

    /** 设置排序方式 */
    fun changeSortMode(mode: Int) {
        sortMode = mode
    }

    // ==================== 专辑分组 ====================

    /** 专辑列表（按专辑名分组，含封面/艺术家/歌曲数） */
    val albums: List<AlbumGroup>
        get() {
            return songs.groupBy { it.albumId to it.album }
                .values
                .map { list ->
                    val first = list.first()
                    AlbumGroup(
                        id = first.albumId,
                        name = first.album,
                        artist = list.firstOrNull { it.artist != "未知艺术家" }?.artist ?: first.artist,
                        albumArtUri = first.albumArtUri,
                        songs = list.sortedBy { it.trackNumber }
                    )
                }
                .sortedBy { it.name.lowercase() }
        }

    // ==================== 播放历史 ====================

    /** 播放历史：歌曲 ID -> 播放次数（LinkedHashMap 保持顺序） */
    var playHistory by mutableStateOf<Map<Long, Int>>(emptyMap())
        private set

    /** 记录一次播放（切换歌曲时调用） */
    fun recordPlay(songId: Long) {
        playHistory = playHistory + (songId to (playHistory[songId] ?: 0) + 1)
    }

    /** 最近播放的歌曲列表（按历史顺序倒序） */
    val recentSongs: List<Song>
        get() = playHistory.keys.reversed()
            .mapNotNull { id -> songs.firstOrNull { it.id == id } }

    /** 播放次数排行（按次数倒序） */
    val topPlayedSongs: List<Pair<Song, Int>>
        get() = playHistory.entries
            .sortedByDescending { it.value }
            .mapNotNull { entry ->
                songs.firstOrNull { it.id == entry.key }?.let { it to entry.value }
            }

    // ==================== 睡眠定时器 ====================

    /** 睡眠定时剩余秒数（0 = 未开启） */
    var sleepTimerRemaining by mutableStateOf(0L)
        private set

    /** 睡眠定时任务 */
    private var sleepJob: Job? = null

    /** 启动睡眠定时器（分钟） */
    fun startSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        sleepTimerRemaining = minutes * 60L
        sleepJob = viewModelScope.launch {
            while (sleepTimerRemaining > 0) {
                delay(1000)
                sleepTimerRemaining--
            }
            // 时间到，暂停播放
            controller?.pause()
        }
    }

    /** 取消睡眠定时器 */
    fun cancelSleepTimer() {
        sleepJob?.cancel()
        sleepJob = null
        sleepTimerRemaining = 0L
    }

    /** 是否正在扫描 */
    var isLoading by mutableStateOf(false)

    /** 当前播放的歌曲索引 */
    var currentIndex by mutableStateOf(-1)

    /** 是否正在播放 */
    var isPlaying by mutableStateOf(false)

    /** 当前播放进度（毫秒） */
    var currentPositionMs by mutableStateOf(0L)

    /** 当前歌曲总时长（毫秒） */
    var durationMs by mutableStateOf(0L)

    /** 循环模式：0=顺序 1=单曲 2=全部 */
    var repeatMode by mutableStateOf(Player.REPEAT_MODE_OFF)

    /** 进度轮询协程 */
    private var positionJob: Job? = null

    /** MediaController（异步连接，可能为 null） */
    private var controller: MediaController? = null

    init {
        // 连接后台播放服务
        val context = getApplication<Application>()
        val controllerFuture = PlayerControllerProvider.createController(context)
        controllerFuture.addListener({
            controller = try {
                controllerFuture.get()
            } catch (e: Exception) {
                null
            }
            controller?.addListener(playerListener)
        }, MoreExecutors.directExecutor())

        // 加载收藏和歌单
        loadFavorites()
        loadPlaylists()
    }

    /** 监听播放器状态变化，同步到 UI */
    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            this@MusicViewModel.isPlaying = isPlaying
            updatePositionPolling()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            currentIndex = controller?.currentMediaItemIndex ?: -1
            syncDuration()
            // 记录播放历史（切歌/开始播放时）
            mediaItem?.mediaId?.toLongOrNull()?.let { recordPlay(it) }
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            this@MusicViewModel.repeatMode = repeatMode
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            syncDuration()
        }
    }

    /** 从控制器同步总时长 */
    private fun syncDuration() {
        durationMs = controller?.duration ?: 0L
    }

    /** 根据播放状态启停进度轮询（每 500ms 刷新一次） */
    private fun updatePositionPolling() {
        if (isPlaying) {
            if (positionJob == null) {
                positionJob = viewModelScope.launch {
                    while (isActive) {
                        currentPositionMs = controller?.currentPosition ?: 0L
                        delay(500)
                    }
                }
            }
        } else {
            positionJob?.cancel()
            positionJob = null
        }
    }

    /** 切换循环模式：顺序 → 列表循环 → 单曲循环 → 乱序 → 顺序 */
    fun cycleRepeatMode() {
        val ctrl = controller ?: return
        val next = when (ctrl.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL       // 顺序 → 列表循环
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE       // 列表循环 → 单曲循环
            Player.REPEAT_MODE_ONE -> REPEAT_MODE_SHUFFLE          // 单曲循环 → 乱序
            else -> Player.REPEAT_MODE_OFF                          // 乱序 → 顺序
        }
        applyRepeatMode(next)
    }

    /** 应用循环模式（也处理乱序——基于 Media3 的 shuffleModeEnabled） */
    private fun applyRepeatMode(mode: Int) {
        val ctrl = controller ?: return
        when (mode) {
            REPEAT_MODE_SHUFFLE -> {
                ctrl.shuffleModeEnabled = true
                ctrl.repeatMode = Player.REPEAT_MODE_OFF
            }
            else -> {
                ctrl.shuffleModeEnabled = false
                ctrl.repeatMode = mode
            }
        }
        repeatMode = mode
    }

    /** ViewModel 启动时同步一次模式（兜底） */
    private fun syncModeFromController() {
        val ctrl = controller ?: return
        repeatMode = if (ctrl.shuffleModeEnabled) REPEAT_MODE_SHUFFLE else ctrl.repeatMode
    }

    companion object {
        /** 自定义循环模式：乱序（Media3 没有 REPEAT_MODE_SHUFFLE，用一个非常量值表示） */
        const val REPEAT_MODE_SHUFFLE = 99
    }

    // ==================== 收藏 ====================

    /** 收藏的歌曲 ID 集合 */
    var favoriteIds by mutableStateOf<Set<Long>>(emptySet())
        private set

    /** 收藏的歌曲列表（按原顺序过滤） */
    val favoriteSongs: List<Song>
        get() = songs.filter { it.id in favoriteIds }

    /** 加载收藏（init 或扫描后调用） */
    fun loadFavorites() {
        val context = getApplication<Application>()
        viewModelScope.launch {
            UserLibraryStore.favoriteIdsFlow(context).collect { ids ->
                favoriteIds = ids
            }
        }
    }

    /** 切换收藏状态 */
    fun toggleFavorite(songId: Long) {
        val context = getApplication<Application>()
        viewModelScope.launch {
            val nowFav = UserLibraryStore.toggleFavorite(context, songId)
            favoriteIds = if (nowFav) favoriteIds + songId else favoriteIds - songId
        }
    }

    /** 某首歌是否已收藏 */
    fun isFavorite(songId: Long): Boolean = songId in favoriteIds

    // ==================== 歌单 ====================

    /** 歌单列表 */
    var playlists by mutableStateOf<List<Playlist>>(emptyList())
        private set

    /** 加载歌单 */
    fun loadPlaylists() {
        val context = getApplication<Application>()
        viewModelScope.launch {
            UserLibraryStore.playlistsFlow(context).collect { lists ->
                playlists = lists
            }
        }
    }

    /** 新建歌单 */
    fun createPlaylist(name: String) {
        val context = getApplication<Application>()
        viewModelScope.launch {
            UserLibraryStore.createPlaylist(context, name)
        }
    }

    /** 删除歌单 */
    fun deletePlaylist(playlistId: Long) {
        val context = getApplication<Application>()
        viewModelScope.launch {
            UserLibraryStore.deletePlaylist(context, playlistId)
        }
    }

    /** 往歌单添加歌曲 */
    fun addSongToPlaylist(playlistId: Long, songId: Long) {
        val context = getApplication<Application>()
        viewModelScope.launch {
            UserLibraryStore.addSongToPlaylist(context, playlistId, songId)
        }
    }

    /** 从歌单移除歌曲 */
    fun removeSongFromPlaylist(playlistId: Long, songId: Long) {
        val context = getApplication<Application>()
        viewModelScope.launch {
            UserLibraryStore.removeSongFromPlaylist(context, playlistId, songId)
        }
    }

    /** 获取歌单的歌曲列表 */
    fun songsOfPlaylist(playlistId: Long): List<Song> {
        val p = playlists.firstOrNull { it.id == playlistId } ?: return emptyList()
        return songs.filter { it.id in p.songIds }
    }

    /** 根据 ID 集合播放歌单 */
    fun playSongs(songs: List<Song>, startIndex: Int = 0) {
        if (songs.isEmpty()) return
        val ctrl = controller ?: return
        val songInfos = songs.map { song ->
            PlaybackService.SongInfo(
                id = song.id,
                title = song.title,
                artist = song.artist,
                album = song.album,
                uri = song.uri.toString(),
                albumArtUri = song.albumArtUri?.toString(),
                durationMs = song.durationMs
            )
        }
        val items = PlaybackService.buildMediaItems(songInfos)
        ctrl.setMediaItems(items, startIndex, 0L)
        ctrl.prepare()
        ctrl.play()
    }

    /** 下一首播放：把歌曲插入到当前播放位置之后，不打断当前播放 */
    fun playNext(songId: Long) {
        val song = songs.firstOrNull { it.id == songId } ?: return
        val ctrl = controller ?: return
        val songInfo = PlaybackService.SongInfo(
            id = song.id,
            title = song.title,
            artist = song.artist,
            album = song.album,
            uri = song.uri.toString(),
            albumArtUri = song.albumArtUri?.toString(),
            durationMs = song.durationMs
        )
        val mediaItem = PlaybackService.buildMediaItems(listOf(songInfo)).first()

        val currentIndex = ctrl.currentMediaItemIndex
        val insertAt = currentIndex + 1
        // 如果这首歌已经在队列里，先移除再插到当前位置（避免重复）
        val existingIndex = (0 until ctrl.mediaItemCount).firstOrNull { i ->
            ctrl.getMediaItemAt(i).mediaId == mediaItem.mediaId
        }
        if (existingIndex != null) {
            ctrl.removeMediaItem(existingIndex)
        }
        val finalIndex = if (existingIndex != null && existingIndex <= insertAt) insertAt - 1 else insertAt
        ctrl.addMediaItem(finalIndex.coerceAtLeast(0), mediaItem)
    }

    /** 扫描本地音乐 */
    fun scanMusic() {
        val context = getApplication<Application>()
        isLoading = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                MusicRepository.scanSongs(context)
            }
            songs = result
            isLoading = false
        }
    }

    /** 点击播放某首歌（用整张列表作为播放队列） */
    fun playSong(index: Int) {
        val songList = songs
        if (songList.isEmpty() || index !in songList.indices) return
        val ctrl = controller ?: return

        val songInfos = songList.map { song ->
            PlaybackService.SongInfo(
                id = song.id,
                title = song.title,
                artist = song.artist,
                album = song.album,
                uri = song.uri.toString(),
                albumArtUri = song.albumArtUri?.toString(),
                durationMs = song.durationMs
            )
        }
        val mediaItems = PlaybackService.buildMediaItems(songInfos)

        ctrl.setMediaItems(mediaItems, index, 0L)
        ctrl.prepare()
        ctrl.play()
        currentIndex = index
    }

    fun togglePlayPause() {
        val ctrl = controller ?: return
        if (ctrl.isPlaying) ctrl.pause() else ctrl.play()
    }

    /** 随机播放：从当前列表随机选一首开始播放 */
    fun shufflePlay() {
        val songList = songs
        if (songList.isEmpty()) return
        val randomIndex = (0 until songList.size).random()
        playSong(randomIndex)
    }

    /** 播放队列中的指定歌曲（队列弹窗点击切歌） */
    fun playFromQueue(index: Int) {
        val ctrl = controller ?: return
        if (index < 0 || index >= ctrl.mediaItemCount) return
        ctrl.seekTo(index, 0L)
        ctrl.play()
        currentIndex = index
    }

    fun next() {
        val ctrl = controller ?: return
        ctrl.seekToNextMediaItem()
        if (!ctrl.isPlaying) ctrl.play()
    }

    fun previous() {
        val ctrl = controller ?: return
        if (ctrl.currentPosition > 3000) {
            ctrl.seekTo(0)
        } else {
            ctrl.seekToPreviousMediaItem()
        }
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
    }

    /** 获取当前播放歌曲信息（供 UI 显示） */
    fun currentSong(): Song? {
        val idx = currentIndex
        return songs.getOrNull(idx)
    }

    override fun onCleared() {
        super.onCleared()
        positionJob?.cancel()
        positionJob = null
        controller?.release()
        controller = null
    }
}
