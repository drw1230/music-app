package com.dengdeng.music.player

import android.content.ComponentName
import android.content.Context
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture

/**
 * 播放器连接器 —— 负责创建与后台 PlaybackService 的连接
 *
 * MediaController 是异步连接的（需要时间），
 * 所以这里持有 future，连接完成后调用方再取 controller
 */
object PlayerControllerProvider {

    /** 创建 MediaController 的异步连接 */
    fun createController(context: Context): ListenableFuture<MediaController> {
        val sessionToken = SessionToken(
            context,
            ComponentName(context, PlaybackService::class.java)
        )
        return MediaController.Builder(context, sessionToken).buildAsync()
    }
}
