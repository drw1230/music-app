package com.dengdeng.music.player

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
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

        // 在线流（网易云/QQ/酷狗）需要浏览器 UA 才能正常拉取，否则部分 URL 403/重定向失败一直缓冲
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Linux; Android 14; Pixel) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10000)
            .setReadTimeoutMs(30000)

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(this).setDataSourceFactory(httpFactory)
            )
            .setHandleAudioBecomingNoisy(true)  // 拔出耳机自动暂停
            .build()

        player.repeatMode = Player.REPEAT_MODE_OFF
        // 暴露 audioSessionId 给同进程的 UI 层（均衡器附加用）
        currentAudioSessionId = player.audioSessionId

        mediaSession = MediaSession.Builder(this, player)
            .build()
    }

    /** MediaSessionService 要求实现的回调：返回会话 */
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    /** 便捷方法：让服务在后台播放一组歌曲（由 ViewModel 通过 Controller 调用） */
    companion object {
        /** 当前播放器的 audioSessionId（同进程共享，供 UI 层附加均衡器等音效） */
        @Volatile
        var currentAudioSessionId: Int = 0
            private set

        /**
         * 把 Song 列表转换成 MediaItem 列表
         * 通知栏显示的歌名/艺术家/封面都来自 MediaItem 的 metadata
         */
        fun buildMediaItems(songs: List<SongInfo>): List<MediaItem> {
            return songs.map { song ->
                MediaItem.Builder()
                    .setMediaId(song.id.toString())
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
        val id: Long,
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
