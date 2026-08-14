package com.dengdeng.music.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * 播放器管理器 —— 封装 Media3，提供简单的播放接口
 * v1 只做核心能力：播放列表、播放/暂停、切歌、循环模式
 */
class PlayerManager(private val context: Context) {

    private val player: ExoPlayer = ExoPlayer.Builder(context).build()

    /** 当前播放列表（Song 对象缓存，用于 UI 显示） */
    private val currentSongs = mutableListOf<SongInfo>()

    init {
        player.repeatMode = Player.REPEAT_MODE_OFF
        player.playWhenReady = true
    }

    /** 简化歌曲信息，避免 UI 层依赖数据层 */
    data class SongInfo(
        val title: String,
        val artist: String,
        val album: String,
        val uri: String,
        val albumArtUri: String?,
        val durationMs: Long
    )

    /** 播放状态监听接口 */
    interface Listener {
        fun onPlaybackStateChanged(isPlaying: Boolean, currentIndex: Int)
    }

    private var listener: Listener? = null
    fun setListener(l: Listener) { listener = l }

    /**
     * 设置并播放歌曲列表
     * @param songs 完整歌曲列表
     * @param startIndex 从第几首开始播放
     */
    fun playSongs(songs: List<SongInfo>, startIndex: Int = 0) {
        if (songs.isEmpty()) return
        currentSongs.clear()
        currentSongs.addAll(songs)

        val mediaItems = songs.map { song ->
            MediaItem.Builder()
                .setUri(song.uri)
                .setMediaMetadata(
                    androidx.media3.common.MediaMetadata.Builder()
                        .setTitle(song.title)
                        .setArtist(song.artist)
                        .setAlbumTitle(song.album)
                        .build()
                )
                .build()
        }

        player.setMediaItems(mediaItems, startIndex, 0L)
        player.prepare()
        player.play()

        listener?.onPlaybackStateChanged(true, startIndex)
    }

    fun play() { player.play() }
    fun pause() { player.pause() }

    fun togglePlayPause() {
        if (player.isPlaying) pause() else play()
    }

    fun next() {
        player.seekToNextMediaItem()
        if (!player.playWhenReady) player.play()
    }

    fun previous() {
        // 播放超过 3 秒时，上一首回到当前曲目开头
        if (player.currentPosition > 3000) {
            player.seekTo(0)
        } else {
            player.seekToPreviousMediaItem()
        }
    }

    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
    }

    /** 切换循环模式：顺序 → 单曲 → 全部循环 */
    fun cycleRepeatMode(): Int {
        player.repeatMode = when (player.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
            Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
            else -> Player.REPEAT_MODE_OFF
        }
        return player.repeatMode
    }

    val isPlaying: Boolean get() = player.isPlaying
    val currentPosition: Long get() = player.currentPosition
    val currentSongIndex: Int get() = player.currentMediaItemIndex
    val currentSong: SongInfo?
        get() = currentSongs.getOrNull(player.currentMediaItemIndex)

    /** 在列表中播放指定歌曲 */
    fun playAt(index: Int) {
        player.seekTo(index, 0L)
        player.play()
        listener?.onPlaybackStateChanged(true, index)
    }

    fun release() {
        player.release()
    }
}
