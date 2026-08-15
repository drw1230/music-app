package com.dengdeng.music.data

import android.net.Uri

/** 专辑分组 */
data class AlbumGroup(
    val id: Long,
    val name: String,
    val artist: String,
    val albumArtUri: Uri?,
    val songs: List<Song>
)
