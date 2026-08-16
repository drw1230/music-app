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
import com.dengdeng.music.data.CoverStore
import com.dengdeng.music.data.OnlineMetadataFetcher
import com.dengdeng.music.data.Song

/**
 * 歌曲封面组件（已确定封面不自动刷新）：
 * 1. 用户已确定/历史确定的封面（CoverStore 持久化）→ 直接使用，不联网
 * 2. 本地 MediaStore 封面（albumArtUri）→ 加载失败才联网兜底
 * 3. 以上都没有 → 联网候选第一张 → 存入 CoverStore（之后不再自动变）
 */
@Composable
fun SongCover(
    song: Song,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    shape: Shape = androidx.compose.ui.graphics.RectangleShape,
    refreshToken: Int = 0
) {
    val context = LocalContext.current
    // 已确定的封面（持久化，用户手动选过或首次联网确定）；refreshToken 变化时重新读取
    val savedUrl = remember(song.id, refreshToken) { CoverStore.get(context, song.title, song.artist) }
    // 联网候选第一张（仅当无本地封面且无已确定封面时获取）
    var autoUrl by remember(song.id, refreshToken) { mutableStateOf<String?>(null) }
    // 是否需要联网（本地无封面）
    var needOnline by remember(song.id, refreshToken) { mutableStateOf(song.albumArtUri == null) }
    // 本地封面是否加载失败
    var localFailed by remember(song.id, refreshToken) { mutableStateOf(false) }

    LaunchedEffect(song.id, needOnline, localFailed) {
        // 有持久化封面 → 不联网
        if (CoverStore.get(context, song.title, song.artist) != null) return@LaunchedEffect
        // 本地封面有效（未失败）→ 不联网
        if (song.albumArtUri != null && !localFailed) return@LaunchedEffect
        if (autoUrl == null) {
            val first = OnlineMetadataFetcher.searchArtworkCandidates(song.title, song.artist, song.durationMs)
                .firstOrNull()
            if (first != null) {
                autoUrl = first
                CoverStore.save(context, song.title, song.artist, first)  // 确定后持久化
            }
        }
    }

    val model: Any = when {
        // 持久化封面优先（用户确定的不变）
        savedUrl != null -> savedUrl!!
        // 本地封面（还没失败）
        song.albumArtUri != null && !localFailed -> song.albumArtUri
        // 联网候选
        autoUrl != null -> autoUrl!!
        // 兜底：音频文件本身
        else -> song.uri
    }

    AsyncImage(
        model = ImageRequest.Builder(context)
            .data(model)
            .crossfade(true)
            .build(),
        contentDescription = null,
        contentScale = contentScale,
        onError = {
            // 本地封面加载失败 → 标记联网兜底（对 song.uri 兜底失败不重复触发）
            if (song.albumArtUri != null && !localFailed) localFailed = true
        },
        modifier = modifier.clip(shape)
    )
}
