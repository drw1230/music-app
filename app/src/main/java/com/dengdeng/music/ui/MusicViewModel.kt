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
import com.dengdeng.music.data.MusicRepository
import com.dengdeng.music.data.Song
import com.dengdeng.music.player.PlaybackService
import com.dengdeng.music.player.PlayerControllerProvider
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.Dispatchers
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

    /** 是否正在扫描 */
    var isLoading by mutableStateOf(false)

    /** 当前播放的歌曲索引 */
    var currentIndex by mutableStateOf(-1)

    /** 是否正在播放 */
    var isPlaying by mutableStateOf(false)

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
    }

    /** 监听播放器状态变化，同步到 UI */
    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            this@MusicViewModel.isPlaying = isPlaying
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            currentIndex = controller?.currentMediaItemIndex ?: -1
        }
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
}
