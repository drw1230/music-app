package com.dengdeng.music.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import com.dengdeng.music.data.LyricParser
import com.dengdeng.music.data.Song
import androidx.media3.common.Player
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 全屏播放页 —— v1 的门面（升级版）
 * 模糊封面背景 + 旋转封面光晕 + 切歌动画 + 收藏 + 完整控制
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    viewModel: MusicViewModel,
    onClose: () -> Unit
) {
    val song = viewModel.currentSong()
    if (song == null) {
        LaunchedEffect(Unit) { onClose() }
        return
    }

    var showQueue by remember { mutableStateOf(false) }
    // 封面/歌词切换状态
    var showLyrics by remember { mutableStateOf(false) }
    // 收藏状态从 ViewModel 读取（持久化），切歌时刷新
    val isFavorite = viewModel.isFavorite(song.id)

    val albumArt = song.albumArtUri ?: song.uri

    // ===== 下滑/左滑返回手势 =====
    val scope = rememberCoroutineScope()
    // 当前拖动位移（x 为水平位移，y 为垂直位移）
    val dragX = remember { Animatable(0f) }
    val dragY = remember { Animatable(0f) }
    // 触发关闭的阈值（dp）
    val dismissThreshold = with(LocalDensity.current) { 160.dp.toPx() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // 跟手位移 + 透明度渐变（滑得越多越透明）
            .graphicsLayer {
                translationX = dragX.value
                translationY = dragY.value
                alpha = 1f - (abs(dragX.value) + abs(dragY.value)) / (dismissThreshold * 3f)
            }
            // 手势：水平拖动（左/右滑）或垂直下滑
            .pointerInput(dismissThreshold) {
                detectHorizontalDragGestures(
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        scope.launch { dragX.snapTo(dragX.value + dragAmount) }
                    },
                    onDragEnd = {
                        scope.launch {
                            if (abs(dragX.value) > dismissThreshold) {
                                // 滑出屏幕后关闭
                                dragX.animateTo(
                                    if (dragX.value > 0) 1200f else -1200f,
                                    animationSpec = tween(200)
                                )
                                onClose()
                            } else {
                                // 回弹
                                dragX.animateTo(0f, animationSpec = spring())
                            }
                        }
                    },
                    onDragCancel = {
                        scope.launch {
                            dragX.animateTo(0f, animationSpec = spring())
                            dragY.animateTo(0f, animationSpec = spring())
                        }
                    }
                )
            }
            .pointerInput(dismissThreshold) {
                detectVerticalDragGestures(
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        scope.launch { dragY.snapTo(dragY.value + dragAmount) }
                    },
                    onDragEnd = {
                        scope.launch {
                            if (dragY.value > dismissThreshold) {
                                dragY.animateTo(
                                    1600f,
                                    animationSpec = tween(200)
                                )
                                onClose()
                            } else {
                                dragY.animateTo(0f, animationSpec = spring())
                            }
                        }
                    },
                    onDragCancel = {
                        scope.launch {
                            dragX.animateTo(0f, animationSpec = spring())
                            dragY.animateTo(0f, animationSpec = spring())
                        }
                    }
                )
            }
    ) {
        // ===== 模糊封面背景（动态沉浸感）=====
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(albumArt)
                .crossfade(true)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .blur(60.dp)          // 背景模糊
                .graphicsLayer { alpha = 0.85f }
        )
        // 深色遮罩，保证前景可读
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.55f),
                            Color.Black.copy(alpha = 0.45f),
                            Color.Black.copy(alpha = 0.65f)
                        )
                    )
                )
        )

        // ===== 前景内容 =====
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 顶部栏：下拉关闭 + 标题 + 队列
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "收起",
                        tint = Color.White
                    )
                }
                Text(
                    text = "正在播放",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
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

            // 封面 / 歌词切换（点击封面切换显示）
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clickable { showLyrics = !showLyrics }
            ) {
                Crossfade(
                    targetState = showLyrics,
                    animationSpec = tween(durationMillis = 300),
                    label = "cover-lyrics"
                ) { isLyrics ->
                    if (isLyrics) {
                        LyricsView(
                            viewModel = viewModel,
                            song = song,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        // 旋转封面（带光晕，切歌淡入淡出）
                        Crossfade(
                            targetState = song.id,
                            animationSpec = tween(durationMillis = 400),
                            label = "album-crossfade"
                        ) { _ ->
                            RotatingAlbumArt(song = song, isPlaying = viewModel.isPlaying)
                        }
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            // 歌名 + 艺术家 + 收藏
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
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
                        color = Color.White.copy(alpha = 0.65f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(12.dp))
                IconButton(onClick = { viewModel.toggleFavorite(song.id) }) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = if (isFavorite) "取消收藏" else "收藏",
                        tint = if (isFavorite) Color(0xFFFF5A79) else Color.White.copy(alpha = 0.7f)
                    )
                }
            }

            Spacer(Modifier.height(28.dp))

            // 进度条
            PlayerProgressBar(viewModel)

            Spacer(Modifier.height(20.dp))

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

/** 旋转封面：播放时匀速旋转 + 底部光晕，暂停时静止 */
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
            .size(300.dp)
            // 光晕：多层阴影
            .shadow(
                elevation = 30.dp,
                shape = CircleShape,
                ambientColor = Color.White.copy(alpha = 0.15f),
                spotColor = Color(0xFF4FC3F7).copy(alpha = 0.25f)
            ),
        contentAlignment = Alignment.Center
    ) {
        // 外圈装饰环
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.08f))
        )
        // 封面
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(albumArt)
                .crossfade(true)
                .build(),
            contentDescription = "专辑封面",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize(0.92f)
                .clip(CircleShape)
                .rotate(if (isPlaying) rotation else 0f)
                .background(Color.Black.copy(alpha = 0.2f))
        )
        // 中心唱针点
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF2A2A3E),
                            Color(0xFF14141F)
                        )
                    )
                )
                .shadow(8.dp, CircleShape)
        )
        // 唱针点高光
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.35f))
        )
    }
}

/** 歌词视图：加载 .lrc 歌词 + 滚动跟随当前行高亮 */
@Composable
private fun LyricsView(
    viewModel: MusicViewModel,
    song: Song,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()

    // 歌词数据（切歌时重新加载）
    var lyrics by remember(song.id) { mutableStateOf<List<LyricParser.LyricLine>>(emptyList()) }
    var loading by remember(song.id) { mutableStateOf(true) }

    LaunchedEffect(song.id) {
        loading = true
        lyrics = LyricParser.loadLyrics(context, song.uri, song.title)
        loading = false
    }

    // 无歌词提示
    if (!loading && lyrics.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "暂无歌词\n（点击封面返回）",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.6f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
        return
    }

    // 当前播放进度
    val positionMs = viewModel.currentPositionMs

    // 计算当前行索引：最后一行 timeMs <= 当前进度
    val currentIndex = remember(lyrics, positionMs) {
        val idx = lyrics.indexOfLast { it.timeMs <= positionMs }
        if (idx < 0) 0 else idx
    }

    // 滚动跟随当前行（居中）
    LaunchedEffect(currentIndex) {
        if (lyrics.isNotEmpty()) {
            listState.animateScrollToItem(
                index = (currentIndex - 1).coerceAtLeast(0),
                scrollOffset = -listState.layoutInfo.viewportEndOffset / 2
            )
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 120.dp)
    ) {
        itemsIndexed(lyrics) { index, line ->
            val isCurrent = index == currentIndex
            Text(
                text = line.text,
                style = if (isCurrent) {
                    MaterialTheme.typography.titleMedium
                } else {
                    MaterialTheme.typography.bodyLarge
                },
                fontWeight = if (isCurrent) androidx.compose.ui.text.font.FontWeight.Bold
                else androidx.compose.ui.text.font.FontWeight.Normal,
                color = if (isCurrent) Color.White else Color.White.copy(alpha = 0.5f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        viewModel.seekTo(line.timeMs)
                    },
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

/**
 * 进度条：点击跳转 + 滑动相对偏移（复用 GestureSeekBar）
 * - 点击：直接跳到手指位置
 * - 滑动：拇指从当前播放位置出发，跟手左右移动（滑满条宽 = 整首歌时长）
 */
@Composable
private fun PlayerProgressBar(viewModel: MusicViewModel) {
    val duration = viewModel.durationMs.coerceAtLeast(1L)
    var dragProgress by remember { mutableStateOf<Float?>(null) }  // 拖动中的显示进度（null=未拖动）

    Column(modifier = Modifier.fillMaxWidth()) {
        GestureSeekBar(
            positionMs = viewModel.currentPositionMs,
            durationMs = duration,
            onSeek = { viewModel.seekTo(it) },
            onDragState = { dragging, p ->
                dragProgress = if (dragging) p else null
            },
            thumbColor = Color.White,
            progressColor = Color.White,
            trackColor = Color.White.copy(alpha = 0.25f)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formatDuration((dragProgress ?: (viewModel.currentPositionMs.toFloat() / duration)).let {
                    (it * duration).toLong()
                }),
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

/** 控制按钮行：上一首 / 播放暂停 / 下一首 + 下方循环/随机 */
@Composable
private fun PlayerControls(viewModel: MusicViewModel) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(40.dp)
        ) {
            IconButton(
                onClick = { viewModel.previous() },
                modifier = Modifier.size(52.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.SkipPrevious,
                    contentDescription = "上一首",
                    tint = Color.White,
                    modifier = Modifier.size(36.dp)
                )
            }

            // 播放/暂停大按钮（渐变底 + 阴影）
            Surface(
                shape = CircleShape,
                color = Color.White,
                shadowElevation = 12.dp,
                modifier = Modifier.size(76.dp)
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
                        modifier = Modifier.size(48.dp)
                    )
                }
            }

            IconButton(
                onClick = { viewModel.next() },
                modifier = Modifier.size(52.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = "下一首",
                    tint = Color.White,
                    modifier = Modifier.size(36.dp)
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        // 循环模式切换：顺序 → 列表循环 → 单曲循环 → 乱序
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
                MusicViewModel.REPEAT_MODE_SHUFFLE -> Icon(
                    imageVector = Icons.Default.Shuffle,
                    contentDescription = "乱序播放",
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

/** 播放队列弹窗：当前歌曲高亮，点击切歌，支持随机播放 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueSheet(
    viewModel: MusicViewModel,
    onDismiss: () -> Unit
) {
    // 用当前显示列表（与播放队列一致，避免排序/搜索后索引错位）
    val songs = viewModel.filteredSongs
    val currentIndex = viewModel.currentIndex

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF14141F),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 4.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.3f))
            )
        }
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
                        // 小封面
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(song.albumArtUri ?: song.uri)
                                .crossfade(true)
                                .build(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(42.dp)
                                .clip(RoundedCornerShape(8.dp))
                        )
                        Spacer(Modifier.width(12.dp))

                        // 序号或当前播放图标
                        Box(modifier = Modifier.width(24.dp)) {
                            if (isCurrent) {
                                Icon(
                                    imageVector = if (viewModel.isPlaying) {
                                        Icons.Default.Equalizer
                                    } else {
                                        Icons.Default.PlayArrow
                                    },
                                    contentDescription = null,
                                    tint = Color(0xFF4FC3F7),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        Spacer(Modifier.width(4.dp))

                        // 歌名 + 艺术家
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = song.title,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isCurrent) Color(0xFF4FC3F7) else Color.White.copy(alpha = 0.9f),
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

/** 格式化时长：毫秒 → mm:ss */
private fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
