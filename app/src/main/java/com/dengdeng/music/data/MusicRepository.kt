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
            MediaStore.Audio.Media.TRACK
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

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val title = cursor.getString(titleCol) ?: "未知歌曲"
                val artist = cursor.getString(artistCol) ?: "未知艺术家"
                val album = cursor.getString(albumCol) ?: "未知专辑"
                val duration = cursor.getLong(durationCol)
                val track = cursor.getInt(trackCol)

                // 过滤掉 0 时长的异常文件（如铃声、系统提示音）
                if (duration < 1000) continue

                val contentUri = ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id
                )
                val albumArtUri = getAlbumArtUri(context, album)

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

    /**
     * 通过专辑名查询封面地址
     * MediaStore 的专辑表里存有封面 ID，查不到返回 null
     */
    private fun getAlbumArtUri(context: Context, album: String): Uri? {
        if (album.isEmpty() || album == "未知专辑") return null
        val albumArtUri = Uri.parse("content://media/external/audio/albumart")
        val projection = arrayOf(MediaStore.Audio.Albums.ALBUM_ID)
        val selection = "${MediaStore.Audio.Albums.ALBUM} = ?"
        val selectionArgs = arrayOf(album)

        context.contentResolver.query(
            albumArtUri, projection, selection, selectionArgs, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val albumId = cursor.getLong(0)
                return ContentUris.withAppendedId(albumArtUri, albumId)
            }
        }
        return null
    }
}
