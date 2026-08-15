package com.dengdeng.music.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import com.dengdeng.music.data.Song

/**
 * 主界面 —— 顶部操作栏 + 歌曲列表 + 底部迷你播放条
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MusicViewModel, hasPermission: Boolean) {
    // 是否显示全屏播放页
    var showPlayer by remember { mutableStateOf(false) }

    if (showPlayer) {
        PlayerScreen(viewModel = viewModel, onClose = { showPlayer = false })
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("我的音乐") },
                actions = {
                    IconButton(onClick = { viewModel.scanMusic() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "重新扫描")
                    }
                }
            )
        },
        bottomBar = {
            if (viewModel.currentSong() != null) {
                MiniPlayerBar(viewModel, onClick = { showPlayer = true })
            }
        }
    ) { padding ->
        when {
            !hasPermission -> PermissionHint(Modifier.padding(padding))
            viewModel.isLoading -> LoadingView(Modifier.padding(padding))
            viewModel.songs.isEmpty() -> EmptyView(Modifier.padding(padding))
            else -> SongList(viewModel, Modifier.padding(padding))
        }
    }
}

/** 歌曲列表 */
@Composable
private fun SongList(viewModel: MusicViewModel, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 4.dp)
    ) {
        itemsIndexed(viewModel.songs) { index, song ->
            SongRow(
                song = song,
                isCurrent = index == viewModel.currentIndex,
                isPlaying = viewModel.isPlaying && index == viewModel.currentIndex,
                onClick = { viewModel.playSong(index) }
            )
            // 分割线（最后一行不画）
            if (index < viewModel.songs.lastIndex) {
                HorizontalDivider(
                    modifier = Modifier.padding(start = 76.dp),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)
                )
            }
        }
    }
}

/** 单行歌曲 */
@Composable
private fun SongRow(
    song: Song,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 封面图（圆角）
        Box {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(song.albumArtUri ?: song.uri)
                    .crossfade(true)
                    .build(),
                contentDescription = "专辑封面",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(10.dp))
            )
            // 当前播放时盖一层淡色指示
            if (isCurrent) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        // 歌名和艺术家
        Column(Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = song.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // 时长
        Text(
            text = formatDuration(song.durationMs),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // 播放状态指示（带脉冲动画的均衡器图标）
        if (isCurrent) {
            Spacer(Modifier.width(8.dp))
            if (isPlaying) {
                EqualizerIcon(tint = MaterialTheme.colorScheme.primary)
            } else {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/** 播放中的动态均衡器图标（四根跳动柱） */
@Composable
private fun EqualizerIcon(tint: androidx.compose.ui.graphics.Color) {
    val transition = rememberInfiniteTransition(label = "eq")
    val heights = listOf(0.5f, 1f, 0.7f, 0.85f)
    val delays = listOf(0, 120, 240, 360)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.height(16.dp)
    ) {
        heights.forEachIndexed { i, base ->
            val scale by transition.animateFloat(
                initialValue = base,
                targetValue = 1.2f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = 300,
                        delayMillis = delays[i],
                        easing = androidx.compose.animation.core.FastOutSlowInEasing
                    ),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "eq-bar-$i"
            )
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height((16f * scale).dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(tint)
            )
        }
    }
}

/** 底部迷你播放条：小封面 + 歌名 + 控制按钮 + 顶部进度线 */
@Composable
private fun MiniPlayerBar(viewModel: MusicViewModel, onClick: () -> Unit = {}) {
    val song = viewModel.currentSong() ?: return

    // 播放进度（百分比）
    val progress = if (viewModel.durationMs > 0) {
        (viewModel.currentPositionMs.toFloat() / viewModel.durationMs).coerceIn(0f, 1f)
    } else 0f

    Surface(
        tonalElevation = 3.dp,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column {
            // 顶部进度线
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 小封面（圆角）
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(song.albumArtUri ?: song.uri)
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                )

                Spacer(Modifier.width(12.dp))

                // 歌名 + 艺术家
                Column(Modifier.weight(1f)) {
                    Text(
                        text = song.title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = song.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // 控制按钮（原生图标）
                IconButton(onClick = { viewModel.previous() }) {
                    Icon(
                        imageVector = Icons.Default.SkipPrevious,
                        contentDescription = "上一首",
                        modifier = Modifier.size(22.dp)
                    )
                }
                IconButton(onClick = { viewModel.togglePlayPause() }) {
                    Icon(
                        imageVector = if (viewModel.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (viewModel.isPlaying) "暂停" else "播放",
                        modifier = Modifier.size(28.dp)
                    )
                }
                IconButton(onClick = { viewModel.next() }) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "下一首",
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}

/** 权限提示 */
@Composable
private fun PermissionHint(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("需要音乐权限才能扫描本地歌曲", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 加载中 */
@Composable
private fun LoadingView(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/** 空列表 */
@Composable
private fun EmptyView(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("还没有音乐", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "把音乐文件放到手机里，点右上角刷新",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 格式化时长：毫秒 → mm:ss */
private fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
