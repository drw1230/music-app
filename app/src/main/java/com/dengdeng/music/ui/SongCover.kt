package com.dengdeng.music.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Shape
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.dengdeng.music.data.OnlineMetadataFetcher
import com.dengdeng.music.data.Song

/**
 * 歌曲封面组件：
 * 优先用本地 MediaStore 封面（albumArtUri）；缺失时联网搜索（网易云）拿封面 URL 兜底，
 * 用 coil 加载（coil 自带磁盘缓存，二次展示不耗流量）。
 */
@Composable
fun SongCover(
    song: Song,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    shape: Shape = androidx.compose.ui.graphics.RectangleShape
) {
    // 本地封面缺失时，联网搜索封面 URL（内存缓存，不重复请求）
    var artUrl by remember(song.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(song.id, song.albumArtUri) {
        if (song.albumArtUri == null) {
            artUrl = OnlineMetadataFetcher.searchSong(song.title, song.artist)?.albumArtUrl
        }
    }

    val model: Any = when {
        song.albumArtUri != null -> song.albumArtUri
        artUrl != null -> artUrl!!
        else -> song.uri   // 兜底：用音频文件本身渲染（MediaStore 常见做法）
    }

    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
            .data(model)
            .crossfade(true)
            .build(),
        contentDescription = null,
        contentScale = contentScale,
        modifier = modifier.clip(shape)
    )
}
