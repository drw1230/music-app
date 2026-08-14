package com.dengdeng.music.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dengdeng.music.data.MusicRepository
import com.dengdeng.music.data.Song
import com.dengdeng.music.player.PlayerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 主界面 ViewModel —— UI 与数据/播放层的桥梁
 * 遵循三层架构：UI 层不直接碰 MediaStore 和 Player
 */
class MusicViewModel(application: Application) : AndroidViewModel(application) {

    /** 扫描到的歌曲列表 */
    var songs by mutableStateOf<List<Song>>(emptyList())
        private set

    /** 是否正在扫描 */
    var isLoading by mutableStateOf(false)
        private set

    /** 当前播放的歌曲索引 */
    var currentIndex by mutableStateOf(-1)

    /** 是否正在播放 */
    var isPlaying by mutableStateOf(false)

    /** 播放器状态回调（由监听器调用） */
    private fun onPlayerStateChanged(playing: Boolean, index: Int) {
        isPlaying = playing
        currentIndex = index
    }

    private val playerManager by lazy {
        PlayerManager(getApplication()).apply {
            setListener(object : PlayerManager.Listener {
                override fun onPlaybackStateChanged(playing: Boolean, index: Int) {
                    onPlayerStateChanged(playing, index)
                }
            })
        }
    }

    /** 扫描本地音乐 */
    fun scanMusic() {
        val context = getApplication<Application>()
        isLoading = true
        // 在 IO 线程扫描，完成后切回主线程更新 UI 状态
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                MusicRepository.scanSongs(context)
            }
            songs = result
            isLoading = false
        }
    }

    /** 点击播放某首歌 */
    fun playSong(index: Int) {
        val songList = songs
        if (songList.isEmpty() || index !in songList.indices) return

        val infos = songList.map { song ->
            PlayerManager.SongInfo(
                title = song.title,
                artist = song.artist,
                album = song.album,
                uri = song.uri.toString(),
                albumArtUri = song.albumArtUri?.toString(),
                durationMs = song.durationMs
            )
        }
        playerManager.playSongs(infos, index)
    }

    fun togglePlayPause() = playerManager.togglePlayPause()
    fun next() = playerManager.next()
    fun previous() = playerManager.previous()
    fun seekTo(pos: Long) = playerManager.seekTo(pos)

    /** 获取当前播放歌曲信息（供迷你播放条显示） */
    fun currentSong(): Song? {
        val idx = currentIndex
        return songs.getOrNull(idx)
    }

    override fun onCleared() {
        super.onCleared()
        playerManager.release()
    }
}
