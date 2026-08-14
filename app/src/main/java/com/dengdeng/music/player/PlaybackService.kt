package com.dengdeng.music.player

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * 后台播放服务 —— v1 的核心
 *
 * 继承 MediaSessionService 后，Media3 自动处理：
 * 1. 前台服务生命周期（App 退到后台，音乐继续播放）
 * 2. 通知栏媒体控制（播放/暂停/上一首/下一首）
 * 3. 锁屏媒体控制
 *
 * UI 层通过 MediaController 连接本服务，不直接持有播放器
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        val player = ExoPlayer.Builder(this)
            .setHandleAudioBecomingNoisy(true)  // 拔出耳机自动暂停
            .build()

        player.repeatMode = Player.REPEAT_MODE_OFF

        mediaSession = MediaSession.Builder(this, player)
            .build()
    }

    /** MediaSessionService 要求实现的回调：返回会话 */
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    /** 便捷方法：让服务在后台播放一组歌曲（由 ViewModel 通过 Controller 调用） */
    companion object {
        /**
         * 把 Song 列表转换成 MediaItem 列表
         * 通知栏显示的歌名/艺术家/封面都来自 MediaItem 的 metadata
         */
        fun buildMediaItems(songs: List<SongInfo>): List<MediaItem> {
            return songs.map { song ->
                MediaItem.Builder()
                    .setUri(song.uri)
                    .setMediaMetadata(
                        androidx.media3.common.MediaMetadata.Builder()
                            .setTitle(song.title)
                            .setArtist(song.artist)
                            .setAlbumTitle(song.album)
                            .setArtworkUri(song.albumArtUri?.let { android.net.Uri.parse(it) })
                            .build()
                    )
                    .build()
            }
        }
    }

    /** 简化歌曲信息，供 UI 层传递 */
    data class SongInfo(
        val title: String,
        val artist: String,
        val album: String,
        val uri: String,
        val albumArtUri: String?,
        val durationMs: Long
    )

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
