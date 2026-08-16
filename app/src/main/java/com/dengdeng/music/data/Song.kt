package com.dengdeng.music.data

import android.net.Uri

/**
 * 歌曲数据模型 —— 整个 App 的核心数据类
 * v1 从 MediaStore 读取，v2 可扩展在线歌曲
 */
data class Song(
    val id: Long,               // MediaStore 中的唯一 ID
    val title: String,          // 歌名
    val artist: String,         // 艺术家
    val album: String,          // 专辑名
    val durationMs: Long,       // 时长（毫秒）
    val uri: Uri,               // 音频文件地址
    val albumArtUri: Uri?,      // 封面地址（可为空）
    val trackNumber: Int = 0,   // 专辑内曲目序号
    val albumId: Long = -1,     // 专辑 ID（分组用）
    val dateAdded: Long = 0     // 添加时间（秒，MediaStore DATE_ADDED）
)
