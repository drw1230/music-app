package com.dengdeng.music.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

/**
 * 音乐库扫描器 —— 通过系统 MediaStore 扫描本地音频文件
 * 带智能过滤：时长阈值 + 文件大小 + 系统音排除 + 关键词过滤，只保留真正的歌曲
 */
object MusicRepository {

    /** 最短歌曲时长（毫秒）：30 秒以下的文件基本是提示音/铃声/杂碎录音 */
    private const val MIN_DURATION_MS = 30_000L

    /** 最小文件大小（字节）：200KB 以下的音频几乎不可能是完整歌曲 */
    private const val MIN_FILE_SIZE = 200 * 1024L

    /** 非歌曲文件的关键词（文件名/标题命中即过滤） */
    private val NON_SONG_KEYWORDS = listOf(
        // 录音/语音类
        "录音", "语音", "音频", "voice", "voice memo", "recording", "record", "memo", "备忘录", "口述",
        // 微信/QQ 语音
        "微信语音", "weixin", "wechat", "qq语音",
        // 系统音/铃声类
        "铃声", "通知音", "提示音", "ringtone", "notification", "alarm", "系统音", "音效",
        // 其他杂碎
        "测试", "test", "sample", "demo", "临时", "temp", "截屏", "screenshot"
    )

    /**
     * 扫描设备上的所有音频文件（智能过滤）
     * @return 按歌名排序的歌曲列表
     */
    fun scanSongs(context: Context): List<Song> {
        val songs = mutableListOf<Song>()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DISPLAY_NAME
        )

        // 数据库层过滤：必须是音乐标记 + 时长达标 + 不是铃声/闹钟/通知音/播客
        val selection = buildString {
            append("${MediaStore.Audio.Media.IS_MUSIC} != 0")
            append(" AND ${MediaStore.Audio.Media.DURATION} >= $MIN_DURATION_MS")
            append(" AND (${MediaStore.Audio.Media.IS_RINGTONE} = 0 OR ${MediaStore.Audio.Media.IS_RINGTONE} IS NULL)")
            append(" AND (${MediaStore.Audio.Media.IS_ALARM} = 0 OR ${MediaStore.Audio.Media.IS_ALARM} IS NULL)")
            append(" AND (${MediaStore.Audio.Media.IS_NOTIFICATION} = 0 OR ${MediaStore.Audio.Media.IS_NOTIFICATION} IS NULL)")
            append(" AND (${MediaStore.Audio.Media.IS_PODCAST} = 0 OR ${MediaStore.Audio.Media.IS_PODCAST} IS NULL)")
        }
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        context.contentResolver.query(
            collection,
            projection,
            selection,
            null,
            sortOrder
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val trackCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val displayNameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val title = cursor.getString(titleCol) ?: "未知歌曲"
                val artist = cursor.getString(artistCol) ?: "未知艺术家"
                val album = cursor.getString(albumCol) ?: "未知专辑"
                val duration = cursor.getLong(durationCol)
                val track = cursor.getInt(trackCol)
                val albumId = cursor.getLong(albumIdCol)
                val size = cursor.getLong(sizeCol)
                val displayName = cursor.getString(displayNameCol) ?: title

                // ===== 代码层二次过滤 =====
                // 1. 时长兜底（数据库层已过滤，这里防部分设备查询异常）
                if (duration < MIN_DURATION_MS) continue
                // 2. 文件大小过滤：太小基本是杂碎音频
                if (size in 1..MIN_FILE_SIZE) continue
                // 3. 文件名/标题关键词过滤
                val haystack = "$title $displayName".lowercase()
                if (NON_SONG_KEYWORDS.any { haystack.contains(it) }) continue

                val contentUri = ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id
                )
                // 直接用专辑 ID 构造封面地址（Android 16 不允许用专辑名字符串查询，会抛 Invalid token album）
                val albumArtUri = if (albumId >= 0) {
                    ContentUris.withAppendedId(
                        Uri.parse("content://media/external/audio/albumart"), albumId
                    )
                } else {
                    null
                }

                songs.add(
                    Song(
                        id = id,
                        title = title,
                        artist = artist,
                        album = album,
                        durationMs = duration,
                        uri = contentUri,
                        albumArtUri = albumArtUri,
                        trackNumber = track
                    )
                )
            }
        }
        return songs
    }
}
