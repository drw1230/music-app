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
import com.dengdeng.music.data.MetadataEnhancer
import com.dengdeng.music.data.MusicRepository
import com.dengdeng.music.data.Playlist
import com.dengdeng.music.data.Song
import com.dengdeng.music.data.UserLibraryStore
import kotlinx.coroutines.flow.first
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

    /** 排序方式：0=歌名 1=艺术家 2=时长 3=最近添加 4=乱序 */
    var sortMode by mutableStateOf(0)

    // 乱序结果缓存（切到乱序时打乱一次并固定，避免每次重组都重新随机导致列表跳动）
    private var shuffleCache: List<Song>? = null

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
                3 -> filtered.sortedByDescending { it.dateAdded }  // 最近添加
                4 -> {                                          // 乱序（固定随机顺序）
                    val cached = shuffleCache
                    if (cached != null && cached.size == filtered.size) {
                        cached
                    } else {
                        val shuffled = filtered.shuffled()
                        shuffleCache = shuffled
                        shuffled
                    }
                }
                else -> filtered.sortedBy { it.title.lowercase() }
            }
        }

    /** 设置排序方式 */
    fun changeSortMode(mode: Int) {
        sortMode = mode
        // 切到乱序时重新打乱；切出乱序时清空缓存
        if (mode == 4) shuffleCache = null
        // 持久化排序方式（重启后保持）
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            runCatching { UserLibraryStore.saveSortMode(ctx, mode) }
        }
    }

    // ==================== 搜索历史 ====================

    /** 搜索历史（最新在前，最多 10 条） */
    var searchHistory by mutableStateOf<List<String>>(emptyList())
        private set

    /** 记录一条搜索（联网搜索/回车时调用） */
    fun addSearchHistory(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        searchHistory = (listOf(q) + searchHistory.filter { it != q }).take(10)
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            runCatching { UserLibraryStore.addSearchHistory(ctx, q) }
        }
    }

    /** 清空搜索历史 */
    fun clearSearchHistory() {
        searchHistory = emptyList()
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            runCatching { UserLibraryStore.clearSearchHistory(ctx) }
        }
    }

    /**
     * 搜索联想：返回匹配关键词的歌曲（按歌名去重、前缀优先、短名优先）
     * 供搜索框输入时显示智能联想建议
     */
    fun suggestSongs(query: String): List<Song> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val seen = HashSet<String>()
        return songs
            .filter { it.title.lowercase().contains(q) }
            .sortedWith(
                compareBy<Song>(
                    { !it.title.lowercase().startsWith(q) },  // 前缀匹配优先
                    { it.title.length },                        // 短名优先
                    { it.title.lowercase() }
                )
            )
            .filter { seen.add(it.title) }  // 同歌名去重
            .take(8)
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

    /** 记录一次播放（切换歌曲时调用；持久化到 DataStore，重启后保留） */
    fun recordPlay(songId: Long) {
        if (songId <= 0) return
        playHistory = playHistory + (songId to (playHistory[songId] ?: 0) + 1)
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            runCatching { UserLibraryStore.addPlayRecord(ctx, songId) }
        }
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

    /** 待恢复的上次播放状态（从 DataStore 读，songs + controller 就绪后恢复） */
    private var pendingLastPlay: UserLibraryStore.LastPlay? = null
    private var restoreDone = false

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
            // 启动进度轮询（始终运行：播放/暂停/拖动都实时刷新位置，保证歌词与进度同步）
            updatePositionPolling()
            // 恢复循环/乱序模式 + 尝试恢复上次播放
            viewModelScope.launch {
                runCatching {
                    controller?.repeatMode = UserLibraryStore.getRepeatMode(context)
                    controller?.shuffleModeEnabled = UserLibraryStore.getShuffle(context)
                    pendingLastPlay = UserLibraryStore.getLastPlay(context)
                }
                tryRestoreLastPlay()
            }
        }, MoreExecutors.directExecutor())

        // 加载收藏和歌单
        loadFavorites()
        loadPlaylists()
        // 恢复记忆：排序方式 + 播放历史 + 搜索历史 + 歌词偏移（跨重启保持）
        viewModelScope.launch {
            runCatching {
                sortMode = UserLibraryStore.getSortMode(context)
                playHistory = UserLibraryStore.playHistoryFlow(context).first()
                searchHistory = UserLibraryStore.searchHistoryFlow(context).first()
                lyricOffsets = UserLibraryStore.lyricOffsetsFlow(context).first()
            }
        }
    }

    // ==================== 歌词微调 ====================

    /** 歌词偏移映射（songId → 偏移毫秒，正=歌词提前显示） */
    var lyricOffsets by mutableStateOf<Map<Long, Long>>(emptyMap())
        private set

    /** 某首歌的歌词偏移（毫秒） */
    fun lyricOffset(songId: Long): Long = lyricOffsets[songId] ?: 0L

    /** 设置歌词偏移（0 = 重置） */
    fun setLyricOffset(songId: Long, offsetMs: Long) {
        lyricOffsets = lyricOffsets + (songId to offsetMs)
        if (offsetMs == 0L) lyricOffsets = lyricOffsets - songId
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            runCatching { UserLibraryStore.saveLyricOffset(ctx, songId, offsetMs) }
        }
    }

    /** 恢复上次播放：songs + controller 都就绪后调用一次（恢复歌曲、进度、播放状态） */
    private fun tryRestoreLastPlay() {
        if (restoreDone) return
        val ctrl = controller ?: return
        val lp = pendingLastPlay ?: return
        if (songs.isEmpty()) return
        restoreDone = true
        pendingLastPlay = null

        val index = songs.indexOfFirst { it.id == lp.songId }
        if (index < 0) return
        try {
            val infos = songs.map { song ->
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
            val items = PlaybackService.buildMediaItems(infos)
            activeQueue = songs
            ctrl.setMediaItems(items, index, lp.positionMs.coerceAtLeast(0L))
            ctrl.prepare()
            if (lp.isPlaying) ctrl.play()
        } catch (e: Exception) { }
    }

    /** 在线播放（下载完成后播放音源 URL，进入播放界面） */
    fun playOnline(title: String, artist: String, url: String, artUrl: String?, durationMs: Long) {
        val ctrl = controller ?: return
        val info = PlaybackService.SongInfo(
            id = -1L,
            title = title,
            artist = artist,
            album = "在线音乐",
            uri = url,
            albumArtUri = artUrl,
            durationMs = durationMs
        )
        val items = PlaybackService.buildMediaItems(listOf(info))
        activeQueue = emptyList()
        currentIndex = 0
        ctrl.setMediaItems(items, 0, 0L)
        ctrl.prepare()
        ctrl.play()
    }

    /** 单曲元数据增强（"刷新歌词/歌手"触发：整理歌手名/联网补全歌手） */
    fun enhanceSongMetadata(song: Song) {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val enhanced = withContext(Dispatchers.IO) {
                MetadataEnhancer.enhanceOne(ctx, song)
            }
            if (enhanced != null) {
                songs = songs.map { if (it.id == song.id) enhanced else it }
            }
        }
    }

    /** 编辑歌曲标签（歌名/歌手/专辑）：写 MediaStore + 更新内存 + 持久化覆盖防回滚 */
    fun updateSongTags(song: Song, title: String, artist: String, album: String) {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val ok = withContext(Dispatchers.IO) {
                MusicRepository.updateSongMetadata(ctx, song.uri, title, artist, album)
            }
            if (ok) {
                songs = songs.map {
                    if (it.id == song.id) it.copy(title = title, artist = artist, album = album) else it
                }
                // 持久化覆盖，防止下次扫描被原始标签回滚
                runCatching {
                    UserLibraryStore.saveMetadataOverride(ctx, song.id, title, artist)
                }
            }
        }
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
            // 保存上次播放（歌曲 + 初始位置）
            saveLastPlayState(mediaItem?.mediaId?.toLongOrNull() ?: -1L)
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            this@MusicViewModel.repeatMode = repeatMode
            val ctx = getApplication<Application>()
            viewModelScope.launch { runCatching { UserLibraryStore.saveRepeatMode(ctx, repeatMode) } }
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            val ctx = getApplication<Application>()
            viewModelScope.launch { runCatching { UserLibraryStore.saveShuffle(ctx, shuffleModeEnabled) } }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            syncDuration()
        }
    }

    /** 从控制器同步总时长 */
    private fun syncDuration() {
        durationMs = controller?.duration ?: 0L
    }

    /** 进度轮询（始终运行，200ms 刷新一次；暂停/拖动时也实时更新，歌词高亮与进度保持同步） */
    private fun updatePositionPolling() {
        if (positionJob == null) {
            positionJob = viewModelScope.launch {
                var saveCounter = 0
                while (isActive) {
                    currentPositionMs = controller?.currentPosition ?: 0L
                    delay(200)
                    // 每 6 秒保存一次播放进度（恢复进度用，避免频繁写盘）
                    saveCounter++
                    if (saveCounter >= 30) {
                        saveCounter = 0
                        val songId = controller?.currentMediaItem?.mediaId?.toLongOrNull() ?: -1L
                        saveLastPlayState(songId)
                    }
                }
            }
        }
    }

    /** 保存上次播放状态（歌曲 + 进度 + 播放状态） */
    private fun saveLastPlayState(songId: Long) {
        if (songId <= 0) return
        val pos = currentPositionMs
        val playing = isPlaying
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            runCatching { UserLibraryStore.saveLastPlay(ctx, songId, pos, playing) }
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

    /** 根据 ID 集合播放歌单（记录为当前播放队列） */
    fun playSongs(songs: List<Song>, startIndex: Int = 0) {
        if (songs.isEmpty()) return
        val ctrl = controller ?: return
        activeQueue = songs
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

    /** 扫描本地音乐（扫描后自动做元数据匹配：整理歌手名 + 无歌手联网补全） */
    fun scanMusic() {
        val context = getApplication<Application>()
        isLoading = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                MusicRepository.scanSongs(context)
            }
            songs = result
            isLoading = false
            // 恢复上次播放（songs 已就绪，controller 若已连上则立即恢复）
            tryRestoreLastPlay()
            // 后台增强元数据（不阻塞显示；已匹配的持久化跳过，不会重复处理）
            MetadataEnhancer.clearCache()
            viewModelScope.launch {
                val enhanced = MetadataEnhancer.enhanceAll(context, result)
                if (enhanced != result) songs = enhanced
            }
        }
    }

    /** 点击播放某首歌（用当前显示的列表作为播放队列） */
    fun playSong(index: Int) {
        // 用 filteredSongs（当前显示列表）而非原始 songs，保证索引与 UI 一致
        val songList = filteredSongs
        if (songList.isEmpty() || index !in songList.indices) return
        val ctrl = controller ?: return
        activeQueue = songList

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

    /** 暂停播放（拖动进度条时调用，拖动中不发声） */
    fun pause() {
        controller?.pause()
    }

    /** 随机播放：从当前显示列表随机选一首开始播放 */
    fun shufflePlay() {
        val songList = filteredSongs
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

    /** 跳转并播放：拖动/点按进度条后，seek 到目标位置；若暂停则开始播放（播放中 play() 无副作用，可抵消 seek 的瞬时暂停） */
    fun seekAndPlay(positionMs: Long) {
        val ctrl = controller ?: return
        val wasPlaying = ctrl.isPlaying
        ctrl.seekTo(positionMs)
        // 无论之前是否播放，都确保恢复播放（seek 过程中 Media3 可能短暂触发 isPlaying=false，
        // 若不强制 play，播放中拖动会在按钮上闪一下暂停图标）
        if (!wasPlaying || !ctrl.isPlaying) ctrl.play()
    }

    /** 当前播放队列（playSong/playSongs 时记录，供 currentSong 精确取歌） */
    private var activeQueue: List<Song> = emptyList()

    /** 获取当前播放歌曲信息（供 UI 显示）—— 从实际播放队列取，避免索引错位 */
    fun currentSong(): Song? {
        val idx = currentIndex
        return activeQueue.getOrNull(idx)
    }

    override fun onCleared() {
        super.onCleared()
        positionJob?.cancel()
        positionJob = null
        controller?.release()
        controller = null
    }
}
