package com.dengdeng.music.ui

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
 * 1. 优先用本地 MediaStore 封面（albumArtUri）
 * 2. 本地缺失（null）或加载失败（onError）时，联网搜索（网易云）拿封面 URL 兜底
 * 3. 最后兜底用音频文件本身渲染
 * coil 自带磁盘缓存，网络封面二次展示不耗流量。
 */
@Composable
fun SongCover(
    song: Song,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    shape: Shape = androidx.compose.ui.graphics.RectangleShape
) {
    // 网络封面 URL（本地封面缺失/失败时搜索获得）
    var artUrl by remember(song.id) { mutableStateOf<String?>(null) }
    // 是否需要联网搜索（初始：本地无封面才需要）
    var needOnline by remember(song.id) { mutableStateOf(song.albumArtUri == null) }

    LaunchedEffect(song.id, needOnline) {
        if (needOnline && artUrl == null) {
            artUrl = OnlineMetadataFetcher.searchSong(song.title, song.artist)?.albumArtUrl
        }
    }

    val model: Any = when {
        song.albumArtUri != null && artUrl == null -> song.albumArtUri  // 本地封面（还没失败）
        artUrl != null -> artUrl!!                                      // 网络封面
        else -> song.uri                                                // 兜底：音频文件本身
    }

    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
            .data(model)
            .crossfade(true)
            .build(),
        contentDescription = null,
        contentScale = contentScale,
        onError = {
            // 本地封面加载失败 → 标记联网搜索（避免对 song.uri 兜底反复触发）
            if (song.albumArtUri != null && artUrl == null) needOnline = true
        },
        modifier = modifier.clip(shape)
    )
}
