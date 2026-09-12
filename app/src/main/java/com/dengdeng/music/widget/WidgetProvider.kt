package com.dengdeng.music.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * DDmusic 4×2 桌面小组件 Provider
 *
 * 同时处理两类广播：
 * 1. 系统的 APPWIDGET_UPDATE（添加到桌面/launcher 刷新）→ 连接播放器 + 推送状态
 * 2. 组件按钮的 WIDGET_CMD（播放/切歌/模式/电台/喜欢/音浪/跳转进度）→ WidgetUpdater.handleCommand
 *
 * 所有按钮都是"广播命令"，实际执行在 WidgetUpdater（MediaController 驱动 PlaybackService）。
 */
class WidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        WidgetUpdater.ensureConnected(context)
        WidgetUpdater.pushUpdate(context)
    }

    /** 用户在桌面拉伸/缩小组件时重新按尺寸选布局 */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle
    ) {
        WidgetUpdater.ensureConnected(context)
        WidgetUpdater.pushUpdate(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            CMD_PLAYPAUSE, CMD_NEXT, CMD_PREV, CMD_MODE, CMD_RADIO, CMD_LIKE, CMD_WAVE, CMD_SEEK -> {
                WidgetUpdater.ensureConnected(context)
                WidgetUpdater.handleCommand(
                    context,
                    intent.getStringExtra(EXTRA_CMD) ?: CMD_PLAYPAUSE,
                    intent.getFloatExtra(EXTRA_FRACTION, 0.5f)
                )
            }
            else -> super.onReceive(context, intent)
        }
    }

    companion object {
        const val ACTION_CMD = "com.dengdeng.music.WIDGET_CMD"
        const val EXTRA_CMD = "cmd"
        const val EXTRA_FRACTION = "fraction"

        const val CMD_PLAYPAUSE = "playpause"
        const val CMD_NEXT = "next"
        const val CMD_PREV = "prev"
        const val CMD_MODE = "mode"
        const val CMD_RADIO = "radio"
        const val CMD_LIKE = "like"
        const val CMD_WAVE = "wave"
        const val CMD_SEEK = "seek"

        /** 组件按钮广播（固定命令） */
        internal fun commandPending(
            context: Context,
            cmd: String,
            vararg extras: Pair<String, Any>
        ): PendingIntent {
            val intent = Intent(context, WidgetProvider::class.java).apply {
                action = ACTION_CMD
                putExtra(EXTRA_CMD, cmd)
                extras.forEach { (k, v) ->
                    when (v) {
                        is Float -> putExtra(k, v)
                        is Long -> putExtra(k, v)
                        is String -> putExtra(k, v)
                    }
                }
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            return PendingIntent.getBroadcast(context, cmd.hashCode(), intent, flags)
        }

        /** 封面/歌名点击 → 打开 App（进播放页由 App 内部状态自然衔接） */
        internal fun openAppPending(context: Context): PendingIntent {
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?: Intent()
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            return PendingIntent.getActivity(context, 1001, intent, flags)
        }
    }
}
