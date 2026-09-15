package com.dengdeng.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dengdeng.music.data.OnlineMetadataFetcher
import com.dengdeng.music.data.OnlineMetadataFetcher.OnlineSong
import com.dengdeng.music.data.Song
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 智能歌单界面（曲库头部入口，替换「随机播放」按钮）
 * 顶部三个板块按钮：
 *  - 最常听 / 最近播放：统计本地 + 在线听过的所有歌
 *  - 冷门探索：平台冷门歌曲（非本地、没听过，可刷新换一批）
 */
@Composable
fun SmartPlaylistScreen(
    viewModel: MusicViewModel,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    // 当前板块：0=最常听 1=冷门探索（从曲库进入默认冷门探索；从播放页返回保留上次板块）
    var section by remember { mutableStateOf(viewModel.smartSection) }
    // 冷门探索：平台冷门歌（在线），过滤本地/已听
    // 【会话缓存】初始值取自 ViewModel：从播放页返回时直接复用上次生成的歌单（不重新拉取）
    var coldSongs by remember { mutableStateOf(viewModel.coldSessionSongs) }
    var coldLoading by remember { mutableStateOf(viewModel.coldSessionSongs.isEmpty()) }

    // 列表滚动位置：从播放页返回时恢复（key=歌单本体 → 重新生成/刷新时归零回到顶部）
    val coldListState = rememberPersistentListState(
        key = viewModel.coldSessionSongs,
        initialIndex = viewModel.coldScrollIndex,
        initialOffset = viewModel.coldScrollOffset
    ) { i, o ->
        viewModel.coldScrollIndex = i
        viewModel.coldScrollOffset = o
    }
    // 最常听：随播放次数动态变化，不参与"归零"（key 恒定 → 只在界面重建时按记忆恢复）
    val topListState = rememberPersistentListState(
        key = null,
        initialIndex = viewModel.topScrollIndex,
        initialOffset = viewModel.topScrollOffset
    ) { i, o ->
        viewModel.topScrollIndex = i
        viewModel.topScrollOffset = o
    }

    // 加载冷门探索（只在「隔日首次进入」或用户点「刷新」时调用：随机冷门歌单 → 每次不同）
    suspend fun loadCold(shuffle: Boolean) {
        coldLoading = true
        viewModel.coldScrollIndex = 0      // 新一批歌 → 列表从头开始
        viewModel.coldScrollOffset = 0
        val keys = viewModel.listenedSongKeys()
        // 过滤本地/已听 + 上次推荐过的（每次进入刷新大半）+ 加大歌单池；
        // 过滤不足 20 首 → 只过滤本地/已听（不过滤上次），避免过滤清零导致空列表
        val base = OnlineMetadataFetcher.fetchColdSongs(120)
            .filterNot { "${it.title}|${it.artist}".lowercase() in keys }
        val fetched = if (base.size >= 70) {
            // 候选充足：滤掉上次全部推荐 → 大半新歌
            base.filterNot { "${it.title}|${it.artist}".lowercase() in viewModel.recentColdKeys }
        } else {
            // 候选不足（一轮候选过了一遍）：只滤一半上次推荐 → 混入一半旧歌回来，保证有新东西又不空
            val half = viewModel.recentColdKeys.shuffled().take(viewModel.recentColdKeys.size / 2).toSet()
            base.filterNot { "${it.title}|${it.artist}".lowercase() in half }
        }
        // 解析并验证可播性 + 缓存 URL（播放零请求；网易云 CDN 探测实测可靠）
        val playable = coroutineScope {
            val gate = Semaphore(8)
            fetched.map { s -> async {
                val ok = runCatching {
                    gate.withPermit {
                        val url = OnlineMetadataFetcher.resolveOnlineUrl(s)
                        viewModel.cacheUrl(s, url)
                        url != null
                    }
                }.getOrDefault(false)
                ok to s
            } }.awaitAll().filter { it.first }.map { it.second }
        }
        coldSongs = (if (shuffle) playable.shuffled() else playable).take(50)
        // 记住本次推荐（下次进入过滤 → 刷新大半）；列表为空时不覆盖（避免过滤清零循环）
        if (coldSongs.isNotEmpty()) viewModel.rememberColdSongs(coldSongs)
        // 写回会话缓存 + 记录生成日期：同一天内再进入直接复用，隔日或点「刷新」才重新生成
        viewModel.coldSessionSongs = coldSongs
        viewModel.coldSessionDate = viewModel.todayKey()
        coldLoading = false
    }
    // 只有"今天还没生成过"才拉取（隔日刷新）；从播放页返回、同一天内反复进出都不重新生成
    LaunchedEffect(Unit) { if (!viewModel.coldSessionFresh()) loadCold(false) }

    // 各板块数据
    val topPlayed = viewModel.topPlayedSongs.take(50)

    Column(Modifier.fillMaxSize()) {
        // 顶部栏：返回 + 标题 + 说明
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Icon(
                Icons.Default.Star,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "智能歌单",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "本地 + 在线收听统计自动生成",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

        // 板块切换按钮（与曲库头部按钮风格一致）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SectionButton(
                label = "最常听",
                icon = { Icon(Icons.Default.Star, contentDescription = null, modifier = Modifier.size(18.dp)) },
                selected = section == 0,
                onClick = { section = 0; viewModel.smartSection = 0 },
                modifier = Modifier.weight(1f)
            )
            SectionButton(
                label = "冷门探索",
                icon = { Icon(Icons.Default.Explore, contentDescription = null, modifier = Modifier.size(18.dp)) },
                selected = section == 1,
                onClick = { section = 1; viewModel.smartSection = 1 },
                modifier = Modifier.weight(1f)
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

        // 当前板块列表
        when (section) {
            0 -> SongSection(
                title = "最常听",
                count = topPlayed.size,
                subtitle = "播放最多的歌 · 含在线",
                refreshVisible = false,
                onRefresh = {},
                songs = topPlayed.map { it.first to "${it.second} 次 · ${it.first.artist}" },
                viewModel = viewModel,
                listState = topListState,
                playAll = { topPlayed.map { it.first } }
            )
            else -> ColdSection(
                songs = coldSongs,
                loading = coldLoading,
                viewModel = viewModel,
                scope = scope,
                listState = coldListState,
                onRefresh = { scope.launch { loadCold(shuffle = true) } }
            )
        }
    }
}

/** 板块切换按钮（选中填充 / 未选描边，与曲库头部一致；weight 由调用方在 Row 内传入） */
@Composable
private fun SectionButton(
    label: String,
    icon: @Composable () -> Unit,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val btnModifier = modifier.height(44.dp)
    if (selected) {
        Button(
            onClick = onClick,
            modifier = btnModifier,
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 8.dp)
        ) {
            icon()
            Spacer(Modifier.width(4.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = btnModifier,
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 8.dp)
        ) {
            icon()
            Spacer(Modifier.width(4.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** 本地歌板块（最常听/最近播放）：标题 + 歌曲列表 */
@Composable
private fun SongSection(
    title: String,
    count: Int,
    subtitle: String,
    refreshVisible: Boolean,
    onRefresh: () -> Unit,
    songs: List<Pair<Song, String>>,
    viewModel: MusicViewModel,
    listState: LazyListState,
    playAll: () -> List<Song>
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("$subtitle · $count 首", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (refreshVisible) {
                Text(
                    "刷新",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                        .clickable { onRefresh() }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
        }

        if (songs.isEmpty()) {
            EmptyHint()
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 16.dp)) {
                val now = viewModel.nowPlayingSong()
                items(songs, key = { "${it.first.id}_${it.first.title}_${it.first.artist}" }) { (song, subtitleText) ->
                    val sectionSongs = playAll()
                    SmartSongRow(
                        song = song,
                        subtitle = subtitleText,
                        // 在线歌 id 均为 -1，用 歌名+歌手 匹配当前播放
                        isCurrent = now != null && now.title == song.title && now.artist == song.artist,
                        onClick = {
                            val idx = sectionSongs.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
                            if (song.id > 0) {
                                viewModel.playSongs(sectionSongs, idx)
                            } else {
                                viewModel.playOnline(song.title, song.artist, song.uri.toString(), song.albumArtUri?.toString(), song.durationMs)
                            }
                        }
                    )
                }
            }
        }
    }
}

/** 冷门探索板块：平台冷门歌（在线，点击解析播放） */
@Composable
private fun ColdSection(
    songs: List<OnlineSong>,
    loading: Boolean,
    viewModel: MusicViewModel,
    scope: kotlinx.coroutines.CoroutineScope,
    listState: LazyListState,
    onRefresh: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        // 标题行：板块名 + 数量 + 刷新
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("冷门探索", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    if (loading) "正在检测音源（只推荐能播放的歌）…" else "平台冷门歌曲 · 非本地没听过 · ${songs.size} 首",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                "刷新",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                    .clickable(enabled = !loading) { onRefresh() }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            )
        }

        if (loading && songs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (songs.isEmpty()) {
            EmptyHint()
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 16.dp)) {
                val now = viewModel.nowPlayingSong()
                items(songs, key = { "${it.platform}_${it.id}" }) { s ->
                    val tmp = Song(
                        id = -1L,
                        title = s.title,
                        artist = s.artist,
                        album = "冷门探索",
                        durationMs = s.durationMs,
                        uri = android.net.Uri.EMPTY,
                        albumArtUri = s.artUrl?.let { android.net.Uri.parse(it) }
                    )
                    SmartSongRow(
                        song = tmp,
                        subtitle = "${s.artist} · ${fmtDuration(s.durationMs)}",
                        isCurrent = now != null && now.title == s.title && now.artist == s.artist,
                        onClick = {
                            // 以整个冷门列表为队列播放（从点击处开始），支持上一曲/下一曲；第一首时上一曲无效
                            scope.launch {
                                val idx = songs.indexOfFirst { it.id == s.id }.coerceAtLeast(0)
                                val queue = coroutineScope {
                                    val gate = Semaphore(8)
                                    songs.map { cs -> async {
                                        // 优先用缓存 URL（列表生成时已解析验证）
                                        val url = viewModel.cachedUrl(cs) ?: runCatching {
                                            gate.withPermit { OnlineMetadataFetcher.resolveAndVerify(cs) }
                                        }.getOrNull()?.also { viewModel.cacheUrl(cs, it) }
                                        url?.let {
                                            Song(
                                                id = -1L,
                                                title = cs.title,
                                                artist = cs.artist,
                                                album = "冷门探索",
                                                durationMs = cs.durationMs,
                                                uri = android.net.Uri.parse(it),
                                                albumArtUri = cs.artUrl?.let { u -> android.net.Uri.parse(u) }
                                            )
                                        }
                                    } }.awaitAll().filterNotNull()
                                }
                                if (queue.isNotEmpty()) viewModel.playOnlineQueue(queue, isRadio = false, startIndex = idx)
                            }
                        }
                    )
                }
            }
        }
    }
}

/** 空态提示 */
@Composable
private fun EmptyHint() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            "暂无歌曲\n（先播放几首歌，或曲库为空）",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 智能歌单歌曲行（封面 + 歌名 + 副标题；当前播放显示角标） */
@Composable
private fun SmartSongRow(
    song: Song,
    subtitle: String,
    isCurrent: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box {
            SongCover(
                song = song,
                modifier = Modifier.size(48.dp),
                shape = RoundedCornerShape(8.dp)
            )
            if (isCurrent) {
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White)
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                song.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
    }
}

/** 时长格式化 mm:ss */
private fun fmtDuration(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
