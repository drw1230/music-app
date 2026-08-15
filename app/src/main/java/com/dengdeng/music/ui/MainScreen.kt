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
import androidx.compose.material.icons.filled.Shuffle
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
import androidx.activity.compose.BackHandler
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
        // 播放页打开时，安卓返回键先关闭播放页回到曲库（再按返回键才退出 App）
        BackHandler { showPlayer = false }
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

/** 歌曲列表：顶部统计 + 全部播放按钮 + 卡片化列表 */
@Composable
private fun SongList(viewModel: MusicViewModel, modifier: Modifier = Modifier) {
    val songs = viewModel.songs
    val totalDuration = songs.sumOf { it.durationMs }
    val totalMinutes = totalDuration / 60_000

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(top = 0.dp, bottom = 16.dp)
    ) {
        // 顶部统计卡片
        item {
            LibraryHeader(
                songCount = songs.size,
                totalMinutes = totalMinutes,
                onPlayAll = { viewModel.playSong(0) },
                onShuffleAll = { viewModel.shufflePlay() }
            )
        }

        // 歌曲列表
        itemsIndexed(songs) { index, song ->
            SongRow(
                song = song,
                index = index,
                isCurrent = index == viewModel.currentIndex,
                isPlaying = viewModel.isPlaying && index == viewModel.currentIndex,
                onClick = { viewModel.playSong(index) }
            )
        }
    }
}

/** 曲库顶部：标题 + 统计 + 全部播放/随机播放按钮 */
@Composable
private fun LibraryHeader(
    songCount: Int,
    totalMinutes: Long,
    onPlayAll: () -> Unit,
    onShuffleAll: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 20.dp)
    ) {
        Text(
            text = "曲库",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "$songCount 首歌曲 · 共 ${formatTotalMinutes(totalMinutes)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))

        // 两个大按钮并排
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 全部播放
            Button(
                onClick = onPlayAll,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text("全部播放")
            }
            // 随机播放
            OutlinedButton(
                onClick = onShuffleAll,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Shuffle,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text("随机播放")
            }
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
        )
    }
}

/** 把总分钟数格式化为 "X 小时 Y 分钟" */
private fun formatTotalMinutes(minutes: Long): String {
    val hours = minutes / 60
    val mins = minutes % 60
    return when {
        hours > 0 -> "${hours}小时${mins}分钟"
        else -> "${mins}分钟"
    }
}

/** 单行歌曲卡片：封面 + 歌名艺术家 + 时长 + 当前播放左侧高亮条 */
@Composable
private fun SongRow(
    song: Song,
    index: Int,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 当前播放左侧高亮条（4dp 宽的竖条）
        if (isCurrent) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(56.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )
        } else {
            Spacer(Modifier.width(4.dp))
        }

        Spacer(Modifier.width(12.dp))

        // 序号或当前播放图标
        Box(
            modifier = Modifier.width(32.dp),
            contentAlignment = Alignment.Center
        ) {
            if (isCurrent && isPlaying) {
                EqualizerIcon(tint = MaterialTheme.colorScheme.primary)
            } else if (isCurrent) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            } else {
                Text(
                    text = "${index + 1}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }

        Spacer(Modifier.width(8.dp))

        // 封面图（卡片化：圆角+微阴影）
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
                fontWeight = if (isCurrent) androidx.compose.ui.text.font.FontWeight.Medium else androidx.compose.ui.text.font.FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
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
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )

        Spacer(Modifier.width(4.dp))
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
