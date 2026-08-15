package com.dengdeng.music.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import com.dengdeng.music.data.Song
import androidx.media3.common.Player

/**
 * 全屏播放页 —— v1 的门面
 * 大封面（播放时旋转）+ 歌名/艺术家 + 可拖动进度条 + 完整控制
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    viewModel: MusicViewModel,
    onClose: () -> Unit
) {
    val song = viewModel.currentSong()
    if (song == null) {
        // 没有播放中的歌曲，直接返回
        LaunchedEffect(Unit) { onClose() }
        return
    }

    // 是否显示播放队列弹窗
    var showQueue by remember { mutableStateOf(false) }

    // 深色背景渐变（播放页沉浸感）
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF1A1A2E),
                        Color(0xFF16213E),
                        Color(0xFF0F3460)
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 顶部栏：返回按钮 + 队列入口
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = Color.White
                    )
                }
                Text(
                    text = "正在播放",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { showQueue = true }) {
                    Icon(
                        imageVector = Icons.Default.QueueMusic,
                        contentDescription = "播放队列",
                        tint = Color.White
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            // 旋转封面
            RotatingAlbumArt(song = song, isPlaying = viewModel.isPlaying)

            Spacer(Modifier.weight(1f))

            // 歌名 + 艺术家
            Text(
                text = song.title,
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = song.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(24.dp))

            // 进度条
            PlayerProgressBar(viewModel)

            Spacer(Modifier.height(16.dp))

            // 控制按钮行
            PlayerControls(viewModel)

            Spacer(Modifier.height(24.dp))
        }

        // 播放队列弹窗
        if (showQueue) {
            QueueSheet(
                viewModel = viewModel,
                onDismiss = { showQueue = false }
            )
        }
    }
}

/** 播放队列弹窗：当前歌曲高亮，点击切歌，支持随机播放 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueSheet(
    viewModel: MusicViewModel,
    onDismiss: () -> Unit
) {
    val songs = viewModel.songs
    val currentIndex = viewModel.currentIndex

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1A1A2E)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp)
        ) {
            // 标题栏：播放队列 + 随机播放
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "播放队列（${songs.size}）",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = {
                    viewModel.shufflePlay()
                    onDismiss()
                }) {
                    Icon(
                        imageVector = Icons.Default.Shuffle,
                        contentDescription = "随机播放",
                        tint = Color.White
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("随机播放", color = Color.White)
                }
            }

            // 歌曲列表
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
            ) {
                itemsIndexed(songs) { index, song ->
                    val isCurrent = index == currentIndex
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.playFromQueue(index)
                                onDismiss()
                            }
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 序号或当前播放图标
                        Box(modifier = Modifier.width(28.dp)) {
                            if (isCurrent) {
                                Icon(
                                    imageVector = if (viewModel.isPlaying) {
                                        Icons.Default.Equalizer
                                    } else {
                                        Icons.Default.PlayArrow
                                    },
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                            } else {
                                Text(
                                    text = "${index + 1}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.4f)
                                )
                            }
                        }

                        Spacer(Modifier.width(8.dp))

                        // 歌名 + 艺术家
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = song.title,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isCurrent) Color.White else Color.White.copy(alpha = 0.85f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = song.artist,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.5f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        // 时长
                        Text(
                            text = formatDuration(song.durationMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.5f)
                        )
                    }
                }
            }
        }
    }
}

/** 旋转封面：播放时匀速旋转，暂停时静止 */
@Composable
private fun RotatingAlbumArt(song: Song, isPlaying: Boolean) {
    val transition = rememberInfiniteTransition(label = "album-rotate")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 20000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    val albumArt = song.albumArtUri ?: song.uri

    Box(
        modifier = Modifier
            .size(280.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.1f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { /* 点击封面无动作，v2 可做歌词 */ },
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(albumArt)
                .crossfade(true)
                .build(),
            contentDescription = "专辑封面",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .rotate(if (isPlaying) rotation else 0f)
        )
        // 中间小圆点装饰（唱片风格）
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(Color(0xFF1A1A2E))
        )
    }
}

/** 进度条：显示当前时间/总时长，支持拖动 */
@Composable
private fun PlayerProgressBar(viewModel: MusicViewModel) {
    val position = viewModel.currentPositionMs
    val duration = viewModel.durationMs.coerceAtLeast(1L)
    val progress = (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f)

    // 拖动中的临时进度（null = 未拖动，显示真实进度）
    var dragProgress by remember { mutableStateOf<Float?>(null) }
    val displayProgress = dragProgress ?: progress

    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = displayProgress,
            onValueChange = { dragProgress = it },
            onValueChangeFinished = {
                dragProgress?.let {
                    viewModel.seekTo((it * duration).toLong())
                }
                dragProgress = null
            },
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color.White.copy(alpha = 0.3f)
            )
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formatDuration((displayProgress * duration).toLong()),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.7f)
            )
            Text(
                text = formatDuration(duration),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.7f)
            )
        }
    }
}

/** 控制按钮行：上一首 / 播放暂停 / 下一首 + 下方循环模式 */
@Composable
private fun PlayerControls(viewModel: MusicViewModel) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(32.dp)
        ) {
            IconButton(
                onClick = { viewModel.previous() },
                modifier = Modifier.size(56.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.SkipPrevious,
                    contentDescription = "上一首",
                    tint = Color.White,
                    modifier = Modifier.size(40.dp)
                )
            }

            // 播放/暂停大按钮
            Surface(
                shape = CircleShape,
                color = Color.White,
                modifier = Modifier.size(72.dp)
            ) {
                IconButton(
                    onClick = { viewModel.togglePlayPause() },
                    modifier = Modifier.fillMaxSize()
                ) {
                    Icon(
                        imageVector = if (viewModel.isPlaying) {
                            Icons.Default.Pause
                        } else {
                            Icons.Default.PlayArrow
                        },
                        contentDescription = if (viewModel.isPlaying) "暂停" else "播放",
                        tint = Color(0xFF16213E),
                        modifier = Modifier.size(44.dp)
                    )
                }
            }

            IconButton(
                onClick = { viewModel.next() },
                modifier = Modifier.size(56.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = "下一首",
                    tint = Color.White,
                    modifier = Modifier.size(40.dp)
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // 循环模式切换
        IconButton(onClick = { viewModel.cycleRepeatMode() }) {
            when (viewModel.repeatMode) {
                Player.REPEAT_MODE_ONE -> Icon(
                    imageVector = Icons.Default.RepeatOne,
                    contentDescription = "单曲循环",
                    tint = Color.White
                )
                Player.REPEAT_MODE_ALL -> Icon(
                    imageVector = Icons.Default.Repeat,
                    contentDescription = "列表循环",
                    tint = Color.White
                )
                else -> Icon(
                    imageVector = Icons.Default.Repeat,
                    contentDescription = "顺序播放",
                    tint = Color.White.copy(alpha = 0.4f)
                )
            }
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
