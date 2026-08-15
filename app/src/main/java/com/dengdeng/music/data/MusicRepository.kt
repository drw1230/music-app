package com.dengdeng.music.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

/**
 * 音乐库扫描器 —— 通过系统 MediaStore 扫描本地音频文件
 * v1 版本不建数据库，直接读取系统索引，简单可靠
 */
object MusicRepository {

    /**
     * 扫描设备上的所有音频文件
     * @return 按歌名排序的歌曲列表
     */
    fun scanSongs(context: Context): List<Song> {
        val songs = mutableListOf<Song>()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

        // 只查音频且未被删除的文件，按标题排序
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.ALBUM_ID
        )

        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
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

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val title = cursor.getString(titleCol) ?: "未知歌曲"
                val artist = cursor.getString(artistCol) ?: "未知艺术家"
                val album = cursor.getString(albumCol) ?: "未知专辑"
                val duration = cursor.getLong(durationCol)
                val track = cursor.getInt(trackCol)
                val albumId = cursor.getLong(albumIdCol)

                // 过滤掉 0 时长的异常文件（如铃声、系统提示音）
                if (duration < 1000) continue

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
