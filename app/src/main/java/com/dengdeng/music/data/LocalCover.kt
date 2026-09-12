package com.dengdeng.music.data

import android.content.Context
import java.io.File

/**
 * 本地封面文件管理（下载歌曲时把封面图片落盘，SongCover 优先读取，离线可用）
 * 存储位置：filesDir/covers/<歌名|歌手 哈希>.jpg
 */
object LocalCover {

    /** 文件名（title|artist 的稳定哈希，与下载保存/读取一致） */
    fun fileName(title: String, artist: String): String =
        "${(title + "|" + artist).hashCode() and 0x7FFFFFFF}.jpg"

    /** 获取本地封面文件（不存在返回 null） */
    fun getFile(context: Context, title: String, artist: String): File? {
        val f = File(context.filesDir, "covers/${fileName(title, artist)}")
        return if (f.exists()) f else null
    }

    /** 封面文件目标（下载时写入） */
    fun targetFile(context: Context, title: String, artist: String): File {
        val dir = File(context.filesDir, "covers")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, fileName(title, artist))
    }
}
