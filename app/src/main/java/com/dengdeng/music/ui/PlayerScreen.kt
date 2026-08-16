package com.dengdeng.music.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Download
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import com.dengdeng.music.data.CoverStore
import com.dengdeng.music.data.LyricParser
import com.dengdeng.music.data.OnlineMetadataFetcher
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
    val song = viewModel.nowPlayingSong()
    if (song == null) {
        LaunchedEffect(Unit) { onClose() }
        return
    }

    // 点击封面 → 大歌词页
    var showFullLyrics by remember { mutableStateOf(false) }

    var showQueue by remember { mutableStateOf(false) }
    val playerContext = LocalContext.current
    // 歌曲菜单 + 封面选择
    var showSongMenu by remember { mutableStateOf(false) }
    var showCoverPicker by remember { mutableStateOf(false) }
    var coverRefreshToken by remember { mutableStateOf(0) }
    var lyricRefreshToken by remember { mutableStateOf(0) }
    var showLyricAdjust by remember { mutableStateOf(false) }
    var showEditTag by remember { mutableStateOf(false) }
    var showEqualizer by remember { mutableStateOf(false) }
    var coverCandidates by remember { mutableStateOf<List<String>>(emptyList()) }
    // 在线下载：非 null 时显示音源选择弹窗（在线歌直接展示当前音源，本地歌查询 3 平台）
    var downloadSong by remember { mutableStateOf<OnlineMetadataFetcher.OnlineSong?>(null) }
    // 封面/歌词切换状态
    var showLyrics by remember { mutableStateOf(false) }
    // 收藏状态从 ViewModel 读取（持久化），切歌时刷新；在线歌用 title|artist 负 key
    val favId = if (viewModel.isOnlinePlaying) {
        viewModel.onlineFavoriteKey(song.title, song.artist)
    } else song.id
    val isFavorite = viewModel.isFavorite(favId)

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
            .background(Color(0xFF101014))   // 播放页深色背景（Fly 风格，白色歌词才清晰）
            // 跟手位移 + 透明度渐变（滑得越多越透明）
            .graphicsLayer {
                translationX = dragX.value
                translationY = dragY.value
                alpha = 1f - (abs(dragX.value) + abs(dragY.value)) / (dismissThreshold * 3f)
            }
            // 手势：水平拖动（左/右滑）或垂直下滑
            .pointerInput(dismissThreshold, showFullLyrics) {
                // 大歌词页显示时禁用手势（防止穿透导致返回/切歌）
                if (showFullLyrics) return@pointerInput
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
            .pointerInput(dismissThreshold, showFullLyrics) {
                // 大歌词页显示时禁用手势
                if (showFullLyrics) return@pointerInput
                detectVerticalDragGestures(
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        scope.launch { dragY.snapTo(dragY.value + dragAmount) }
                    },
                    onDragEnd = {
                        scope.launch {
                            if (dragY.value > dismissThreshold) {
                                // 下滑 → 下一首
                                dragY.animateTo(1600f, animationSpec = tween(200))
                                viewModel.next()
                                dragY.snapTo(0f)
                            } else if (dragY.value < -dismissThreshold) {
                                // 上滑 → 上一首
                                dragY.animateTo(-1600f, animationSpec = tween(200))
                                viewModel.previous()
                                dragY.snapTo(0f)
                            } else {
                                // 回弹
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
            // 顶部栏：仅收起按钮（队列/下载/菜单已移到 Fly 控制栏）
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
                Spacer(Modifier.weight(1f))
            }

            // ==================== Fly 风格中段 ====================
            // 大封面（点击进大歌词页）——固定顶部，简单可靠
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp)
                    .clickable { showFullLyrics = true },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.8f)
                        .aspectRatio(1f),
                    contentAlignment = Alignment.Center
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
                                modifier = Modifier.fillMaxSize(),
                                refreshToken = lyricRefreshToken
                            )
                        } else {
                            Crossfade(
                                targetState = song.id,
                                animationSpec = tween(durationMillis = 400),
                                label = "album-crossfade"
                            ) { _ ->
                                Box(
                                    modifier = Modifier.fillMaxWidth(1f),
                                    contentAlignment = Alignment.Center
                                ) {
                                    RotatingAlbumArt(
                                        song = song,
                                        isPlaying = viewModel.isPlaying,
                                        coverRefreshToken = coverRefreshToken
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 小歌词下移（封面与小歌词之间留白，填充封面与歌名之间的空间）
            Spacer(Modifier.height(72.dp))

            // 小歌词（5 行：前2/前1/当前/后1/后2；点击进大歌词页）
            MiniLyricsView(
                song = song,
                viewModel = viewModel,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp)
                    .clickable { showFullLyrics = true }
            )

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
                IconButton(onClick = {
                    if (viewModel.isOnlinePlaying) {
                        // 在线歌：记录歌曲信息 → 显示在"喜欢"列表
                        viewModel.toggleOnlineFavorite(
                            song.title, song.artist, song.album,
                            song.albumArtUri?.toString(), song.durationMs, song.uri.toString()
                        )
                    } else {
                        viewModel.toggleFavorite(favId)
                    }
                }) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = if (isFavorite) "取消收藏" else "收藏",
                        tint = if (isFavorite) Color(0xFFFF5A79) else Color.White.copy(alpha = 0.7f)
                    )
                }
            }

            // Fly 风格控制栏：收藏 / 下载 / 定时 / 队列 / 更多 / 分享
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 播放模式切换（顺序 → 列表循环 → 单曲循环 → 乱序）
                val mode = viewModel.repeatMode
                val cycleIcon = when (mode) {
                    MusicViewModel.REPEAT_MODE_SHUFFLE -> Icons.Default.Shuffle
                    Player.REPEAT_MODE_ONE -> Icons.Default.RepeatOne
                    else -> Icons.Default.Repeat
                }
                val cycleTint = if (mode == Player.REPEAT_MODE_OFF) {
                    Color.White.copy(alpha = 0.5f)
                } else {
                    Color.White
                }
                IconButton(onClick = { viewModel.cycleRepeatMode() }) {
                    Icon(cycleIcon, contentDescription = "播放模式", tint = cycleTint)
                }
                IconButton(onClick = {
                    downloadSong = OnlineMetadataFetcher.OnlineSong(
                        platform = if (viewModel.isOnlinePlaying) "在线播放" else "",
                        id = "-1", title = song.title, artist = song.artist, album = song.album,
                        artUrl = song.albumArtUri?.toString(), durationMs = song.durationMs
                    )
                }) {
                    Icon(Icons.Default.Download, contentDescription = "下载", tint = Color.White.copy(alpha = 0.8f))
                }
                IconButton(onClick = { showQueue = true }) {
                    Icon(Icons.Default.QueueMusic, contentDescription = "队列", tint = Color.White.copy(alpha = 0.8f))
                }
                // ⋮ 更多菜单（刷新封面/歌词/微调/标签/均衡器/下载）
                Box {
                    IconButton(onClick = { showSongMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "更多", tint = Color.White.copy(alpha = 0.8f))
                    }
                    DropdownMenu(
                        expanded = showSongMenu,
                        onDismissRequest = { showSongMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("刷新封面") },
                            onClick = { showSongMenu = false; showCoverPicker = true }
                        )
                        DropdownMenuItem(
                            text = { Text("刷新歌词/歌手") },
                            onClick = {
                                showSongMenu = false
                                LyricParser.clearLyricCache(playerContext, song.title, song.artist)
                                lyricRefreshToken++
                                if (song.artist.isBlank() || song.artist == "未知艺术家") {
                                    viewModel.enhanceSongMetadata(song)
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("歌词微调") },
                            onClick = { showSongMenu = false; showLyricAdjust = true }
                        )
                        DropdownMenuItem(
                            text = { Text("编辑标签") },
                            onClick = { showSongMenu = false; showEditTag = true }
                        )
                        DropdownMenuItem(
                            text = { Text("均衡器") },
                            onClick = { showSongMenu = false; showEqualizer = true }
                        )
                        DropdownMenuItem(
                            text = { Text("下载歌曲") },
                            onClick = {
                                showSongMenu = false
                                downloadSong = OnlineMetadataFetcher.OnlineSong(
                                    platform = if (viewModel.isOnlinePlaying) "在线播放" else "",
                                    id = "-1",
                                    title = song.title,
                                    artist = song.artist,
                                    album = song.album,
                                    artUrl = song.albumArtUri?.toString(),
                                    durationMs = song.durationMs
                                )
                            }
                        )
                    }
                }
            }

            // 进度条 + 上一首/播放/下一首（Fly 风格：进度条 + 右下大播放按钮）
            PlayerProgressBar(viewModel)

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { viewModel.previous() }) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = "上一首", tint = Color.White)
                }
                IconButton(
                    onClick = { viewModel.togglePlayPause() },
                    modifier = Modifier.size(64.dp)
                ) {
                    Icon(
                        imageVector = if (viewModel.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (viewModel.isPlaying) "暂停" else "播放",
                        tint = Color.White,
                        modifier = Modifier.size(48.dp)
                    )
                }
                IconButton(onClick = { viewModel.next() }) {
                    Icon(Icons.Default.SkipNext, contentDescription = "下一首", tint = Color.White)
                }
            }

            Spacer(Modifier.height(12.dp))
        }

        // 大歌词页（点击封面进入；覆盖层）
        if (showFullLyrics) {
            // 系统返回键 → 回到播放页（不是关闭播放页）
            BackHandler { showFullLyrics = false }
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.92f))
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { showFullLyrics = false }) {
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "收起", tint = Color.White)
                        }
                        Text("歌词", color = Color.White.copy(alpha = 0.85f), modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.width(48.dp))
                    }
                    LyricsView(
                        viewModel = viewModel,
                        song = song,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        refreshToken = lyricRefreshToken
                    )
                    PlayerProgressBar(viewModel)
                    Spacer(Modifier.height(12.dp))
                }
            }
        }

        // 播放队列弹窗
        if (showQueue) {
            QueueSheet(
                viewModel = viewModel,
                onDismiss = { showQueue = false }
            )
        }

        // 刷新封面：多源候选选择弹窗
        if (showCoverPicker) {
            val coverContext = LocalContext.current
            CoverPickerDialog(
                song = song,
                candidates = coverCandidates,
                onFetch = {
                    // 进入弹窗时爬取多源候选封面
                    LaunchedEffect(song.id) {
                        coverCandidates = OnlineMetadataFetcher.searchArtworkCandidates(
                            song.title, song.artist, song.durationMs
                        )
                    }
                },
                onPick = { url ->
                    CoverStore.save(coverContext, song.title, song.artist, url)
                    coverRefreshToken++
                    showCoverPicker = false
                },
                onDismiss = { showCoverPicker = false }
            )
        }

        // 歌词微调弹窗
        if (showLyricAdjust) {
            LyricAdjustDialog(
                song = song,
                offsetMs = viewModel.lyricOffset(song.id),
                onApply = { newOffset ->
                    viewModel.setLyricOffset(song.id, newOffset)
                    showLyricAdjust = false
                },
                onDismiss = { showLyricAdjust = false }
            )
        }

        // 编辑标签弹窗
        if (showEditTag) {
            EditTagDialog(
                song = song,
                onSave = { title, artist, album ->
                    viewModel.updateSongTags(song, title, artist, album)
                    showEditTag = false
                },
                onDismiss = { showEditTag = false }
            )
        }

        // 均衡器弹窗
        if (showEqualizer) {
            EqualizerDialog(
                viewModel = viewModel,
                onDismiss = { showEqualizer = false }
            )
        }

        // 在线下载：音源选择弹窗（在线歌直接展示当前音源；本地歌查询 3 平台音源供选择）
        downloadSong?.let { os ->
            OnlineSourceSheet(
                song = os,
                fixedSource = if (viewModel.isOnlinePlaying) {
                    OnlineMetadataFetcher.AudioSource(
                        platform = "在线播放",
                        quality = "当前音质",
                        format = "MP3",
                        bitrate = 0,
                        url = song.uri.toString(),
                        sizeBytes = 0,
                        vip = false
                    )
                } else null,
                onPlay = null,  // 播放页下载弹窗不需要试听（正在播放当前歌曲）
                onDownloaded = { viewModel.scanMusic() },
                onDismiss = { downloadSong = null }
            )
        }
    }
}

/** 封面候选选择弹窗：展示多源爬取的封面，用户自选 */
@Composable
private fun CoverPickerDialog(
    song: Song,
    candidates: List<String>,
    onFetch: @Composable () -> Unit,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    onFetch()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择封面", style = MaterialTheme.typography.titleMedium) },
        text = {
            if (candidates.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "正在搜索候选封面…",
                        color = Color.White.copy(alpha = 0.6f)
                    )
                }
            } else {
                Column {
                    candidates.forEach { url ->
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(url)
                                .crossfade(true)
                                .build(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(110.dp)
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onPick(url) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 歌词微调弹窗：整体偏移歌词时间线（±步进调节，保存后永久生效） */
@Composable
private fun LyricAdjustDialog(
    song: Song,
    offsetMs: Long,
    onApply: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    var current by remember(song.id, offsetMs) { mutableStateOf(offsetMs) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("歌词微调") },
        text = {
            Column {
                Text(
                    "当前偏移：${formatLyricOffset(current)}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { current -= 1000 }) { Text("-1s") }
                    TextButton(onClick = { current -= 500 }) { Text("-0.5s") }
                    TextButton(onClick = { current = 0 }) { Text("重置") }
                    TextButton(onClick = { current += 500 }) { Text("+0.5s") }
                    TextButton(onClick = { current += 1000 }) { Text("+1s") }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "偏移 +：歌词提前显示\n偏移 -：歌词延后显示\n（云端歌词与本地音频版本有差异时用它对齐）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(current) }) { Text("应用") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 编辑标签弹窗：修改歌名/歌手/专辑（写 MediaStore + 持久化） */
@Composable
private fun EditTagDialog(
    song: Song,
    onSave: (String, String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember(song.id) { mutableStateOf(song.title) }
    var artist by remember(song.id) { mutableStateOf(song.artist) }
    var album by remember(song.id) { mutableStateOf(song.album) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑标签") },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("歌名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = artist,
                    onValueChange = { artist = it },
                    label = { Text("歌手") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = album,
                    onValueChange = { album = it },
                    label = { Text("专辑") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(title.trim(), artist.trim(), album.trim()) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 歌词偏移格式化（毫秒 → +0.5s / -1.0s） */
private fun formatLyricOffset(ms: Long): String {
    val sec = ms / 1000.0
    return if (ms >= 0) "+%.1fs".format(sec) else "%.1fs".format(sec)
}

/** 均衡器弹窗：预设 + 频段滑块 + 低音增强 + 环绕声 */
@Composable
private fun EqualizerDialog(
    viewModel: MusicViewModel,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("均衡器") },
        text = {
            if (!viewModel.eqAvailable) {
                Text(
                    "当前设备不支持均衡器",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            } else {
                Column(
                    Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 4.dp)
                ) {
                    // 总开关
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("启用均衡器", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Switch(
                            checked = viewModel.eqEnabled,
                            onCheckedChange = { viewModel.applyEqEnabled(it) }
                        )
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

                    // 预设选择
                    Text("预设", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        viewModel.eqPresets.forEachIndexed { index, name ->
                            val selected = viewModel.eqPreset == index
                            Text(
                                text = name,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                                    .clickable { viewModel.applyEqPreset(index) }
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))

                    // 频段滑块
                    Text("频段调节", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    viewModel.eqFrequencies.forEachIndexed { band, freqHz ->
                        val level = viewModel.eqLevels[band] ?: 0
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (freqHz >= 1000) "${freqHz / 1000}k" else "$freqHz",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(44.dp)
                            )
                            Slider(
                                value = level.toFloat(),
                                onValueChange = { viewModel.setEqBandLevel(band, it.roundToInt()) },
                                valueRange = -1500f..1500f,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "%.1f".format(level / 100.0),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(36.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))

                    // 低音增强
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("低音增强", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Switch(
                            checked = viewModel.bassEnabled,
                            onCheckedChange = { viewModel.applyBassEnabled(it) }
                        )
                    }
                    if (viewModel.bassEnabled) {
                        Slider(
                            value = viewModel.bassStrength.toFloat(),
                            onValueChange = { viewModel.applyBassStrength(it.toInt()) },
                            valueRange = 0f..1000f
                        )
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    // 环绕声
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("环绕声", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Switch(
                            checked = viewModel.virtualizerEnabled,
                            onCheckedChange = { viewModel.applyVirtualizerEnabled(it) }
                        )
                    }

                    // 重置
                    TextButton(
                        onClick = { viewModel.resetEq() },
                        modifier = Modifier.align(Alignment.End)
                    ) { Text("重置全部") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        }
    )
}

/** 旋转封面：播放时匀速旋转 + 底部光晕，暂停时静止 */
@Composable
private fun RotatingAlbumArt(song: Song, isPlaying: Boolean, coverRefreshToken: Int = 0) {
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

    Box(
        modifier = Modifier
            .fillMaxWidth(1f)
            .aspectRatio(1f)
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
        // 封面（本地缺失时联网获取）
        SongCover(
            song = song,
            contentScale = ContentScale.Crop,
            shape = CircleShape,
            refreshToken = coverRefreshToken,
            modifier = Modifier
                .fillMaxSize(0.92f)
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

/** 小歌词：封面下方三行（上一句/当前句/下一句），随播放进度滚动，点击进大歌词页 */
@Composable
private fun MiniLyricsView(
    song: Song,
    viewModel: MusicViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var lyrics by remember(song.id) { mutableStateOf<List<LyricParser.LyricLine>>(emptyList()) }
    var loaded by remember(song.id) { mutableStateOf(false) }

    LaunchedEffect(song.id) {
        loaded = false
        lyrics = LyricParser.loadLyrics(context, song.uri, song.title, song.artist)
        loaded = true
    }

    // 当前行索引（与 LyricsView 同一套计算：进度 - 歌词微调偏移）
    val positionMs = viewModel.currentPositionMs
    val adjustMs = viewModel.lyricOffset(song.id)
    val currentIndex = remember(lyrics, positionMs, adjustMs) {
        if (lyrics.isEmpty()) -1
        else {
            val idx = lyrics.indexOfLast { it.timeMs <= positionMs - adjustMs }
            if (idx < 0) 0 else idx
        }
    }

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        when {
            !loaded -> Text(
                "歌词加载中…",
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 28.sp, fontWeight = FontWeight.Medium),
                color = Color.White
            )
            lyrics.isEmpty() -> Text(
                "暂无歌词 · 点击查看",
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 28.sp, fontWeight = FontWeight.Medium),
                color = Color.White
            )
            else -> {
                // 前二句（更暗）
                Text(
                    lyrics.getOrNull(currentIndex - 2)?.text ?: " ",
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 24.sp),
                    color = Color.White.copy(alpha = 0.4f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                // 前一句
                Text(
                    lyrics.getOrNull(currentIndex - 1)?.text ?: " ",
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 24.sp),
                    color = Color.White.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                // 当前句（高亮）
                Text(
                    lyrics.getOrNull(currentIndex)?.text ?: " ",
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 30.sp, fontWeight = FontWeight.Bold),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                // 下一句
                Text(
                    lyrics.getOrNull(currentIndex + 1)?.text ?: " ",
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 24.sp),
                    color = Color.White.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                // 后二句（更暗）
                Text(
                    lyrics.getOrNull(currentIndex + 2)?.text ?: " ",
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 24.sp),
                    color = Color.White.copy(alpha = 0.4f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** 歌词视图：加载 .lrc 歌词 + 滚动跟随当前行高亮，点击歌词行跳转进度 */
@Composable
private fun LyricsView(
    viewModel: MusicViewModel,
    song: Song,
    modifier: Modifier = Modifier,
    refreshToken: Int = 0
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()

    // 歌词数据（切歌或 refreshToken 变化时重新加载）
    var lyrics by remember(song.id, refreshToken) { mutableStateOf<List<LyricParser.LyricLine>>(emptyList()) }
    var loading by remember(song.id, refreshToken) { mutableStateOf(true) }

    LaunchedEffect(song.id, refreshToken) {
        loading = true
        lyrics = LyricParser.loadLyrics(context, song.uri, song.title, song.artist)
        loading = false
    }

    // 加载中提示
    if (loading) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "正在获取歌词…",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.6f)
            )
        }
        return
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

    // 当前播放进度（减去歌词偏移，等效于歌词时间线平移）
    val positionMs = viewModel.currentPositionMs
    val lyricAdjustMs = viewModel.lyricOffset(song.id)

    // 计算当前行索引：最后一行 timeMs <= 当前进度（含微调偏移）
    val currentIndex = remember(lyrics, positionMs, lyricAdjustMs) {
        val idx = lyrics.indexOfLast { it.timeMs <= positionMs - lyricAdjustMs }
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
                        viewModel.seekAndPlay(line.timeMs + lyricAdjustMs)
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
            onSeek = { viewModel.seekAndPlay(it) },
            onDragStart = { viewModel.pause() },
            onSeekPreview = { viewModel.seekTo(it) },
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
                        SongCover(
                            song = song,
                            contentScale = ContentScale.Crop,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.size(44.dp)
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
