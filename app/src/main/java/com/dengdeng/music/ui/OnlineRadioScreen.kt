package com.dengdeng.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dengdeng.music.data.DailyRadioFetcher
import com.dengdeng.music.data.OnlineDownloader
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
 * 每日电台界面：双榜热歌（网易云 + QQ）→ 每日推荐
 * - 播放 <10s 切走的歌自动降权（ViewModel.skipSongs 过滤）
 * - 进入界面自动开始播放；支持刷新电台、单曲试听（曲库迷你条控制）、下载
 * - 探索版：榜单可轮换（热歌榜/飙升榜/新歌榜/流行指数…），刷新按钮切换下一组榜单
 * - 熟悉版：本地常听（已听）+ 常听歌手相似新歌（过滤已听、按歌名去重）
 */
// 探索版候选榜单组合已抽到 data/DailyRadioFetcher.kt（与桌面小组件电台键共用，保证内容同源）
private val hotVariants get() = DailyRadioFetcher.hotVariants
private val hotVariantNames get() = DailyRadioFetcher.hotVariantNames
@Composable
fun OnlineRadioScreen(
    viewModel: MusicViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var loading by remember { mutableStateOf(true) }
    var songs by remember { mutableStateOf<List<OnlineSong>>(emptyList()) }
    var error by remember { mutableStateOf(false) }
    // 电台播放状态："准备电台 x/30…" / null
    var radioState by remember { mutableStateOf<String?>(null) }
    // 是否已自动播放过（true=进入默认不自动播放，用户点"播放电台"才播；电台播完自动刷新时置 false 续播）
    var autoPlayed by remember { mutableStateOf(true) }
    // 电台模式：false=探索版（平台热榜为主） true=熟悉版（已听 + 相似推荐）
    var familiarMode by remember { mutableStateOf(false) }
    // 探索版榜单轮换序号（点「刷新电台」递增，切到下一组榜单）
    var refreshRound by remember { mutableStateOf(0) }
    // 熟悉版：本地常听歌（已听过，直接入队无需解析 URL）
    var familiarLocalSongs by remember { mutableStateOf<List<Song>>(emptyList()) }

    /** 播放电台（并行解析 URL + 预检音源可播性 → 过滤坏音源后入队；熟悉版=本地已听 + 平台相似） */
    fun playRadio() {
        if (songs.isEmpty() || radioState != null) return
        scope.launch {
            val targets = songs
            radioState = "正在检测音源（只推荐能播放的歌）…"
            val localPart = if (familiarMode) familiarLocalSongs else emptyList()
            val onlinePart = coroutineScope {
                val gate = Semaphore(8)
                targets.map { s -> async {
                    // 优先用缓存 URL（列表已解析验证）；无缓存再解析
                    val url = viewModel.cachedUrl(s) ?: runCatching {
                        gate.withPermit { OnlineMetadataFetcher.resolveOnlineUrl(s) }
                    }.getOrNull()?.also { viewModel.cacheUrl(s, it) }
                    if (url != null) {
                        Song(
                            id = -1L,
                            title = s.title,
                            artist = s.artist,
                            album = "每日电台",
                            durationMs = s.durationMs,
                            uri = android.net.Uri.parse(url),
                            albumArtUri = s.artUrl?.let { u -> android.net.Uri.parse(u) }
                        )
                    } else null   // 解析不出音源 → 跳过
                } }.awaitAll().filterNotNull()
            }
            radioState = null
            val queue = localPart + onlinePart
            if (queue.isNotEmpty()) {
                viewModel.playOnlineQueue(queue, isRadio = true)
            } else {
                radioState = "暂无可用音源"
            }
        }
    }

    /** 加载电台（force=强制重新拉取；autoPlay=加载完成后自动播放——切模式用） */
    fun loadRadio(force: Boolean, autoPlay: Boolean = false) {
        scope.launch {
            // 每次进入都重新生成（用户偏好：每次点进来歌要变；榜单轮换 + 过滤上次推荐）
            loading = true
            error = false
            val hot = if (familiarMode) {
                // 熟悉版 v4：纯平台相似曲目（不要本地、不要相似歌手）+ 随机歌单混合
                // 种子随机化（候选 8 抽 4）+ 每次随机歌单 → 每次打开歌不同
                familiarLocalSongs = emptyList()
                val seeds = viewModel.seedSongs(15).shuffled().take(4)
                val listened = viewModel.listenedSongKeys()
                val similar = runCatching {
                    OnlineMetadataFetcher.fetchSimilarSongs(seeds, listened, perSeed = 12, limit = 40)
                }.getOrDefault(emptyList())
                val extra = runCatching {
                    OnlineMetadataFetcher.fetchRandomSongs(10)
                }.getOrDefault(emptyList())
                val familiar = (similar + extra)
                    .distinctBy { "${it.title}|${it.artist}".lowercase() }
                    .filterNot { "${it.title}|${it.artist}".lowercase() in listened }
                if (force) familiar.shuffled() else familiar   // 刷新：相似歌打乱顺序
            } else {
                // 探索版：混合方案（B 多榜单 + C 常听歌手 + D 随机歌单）——与小组件电台键共用 DailyRadioFetcher
                familiarLocalSongs = emptyList()
                DailyRadioFetcher.fetchCandidates(
                    viewModel.topArtists(3), viewModel.listenedSongKeys()
                )
            }
            val filtered = hot
            // 解析并验证可播性（8 并发；实测网易云 CDN 探测可靠）+ 缓存 URL（播放零请求）
            songs = coroutineScope {
                val gate = Semaphore(8)
                filtered.map { s -> async {
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
            // 记住本次推荐（下次刷新过滤 → 大半新歌）；列表为空时不覆盖（避免过滤清零循环）
            if (songs.isNotEmpty()) viewModel.rememberRadioSongs(songs)
            loading = false
            if (songs.isEmpty()) error = true
            else if (autoPlay) playRadio()   // 切模式后直接播新模式
        }
    }

    LaunchedEffect(Unit) {
        loadRadio(false)
    }

    // 进入界面后自动开始播放（点击"每日电台"按钮即直接播放）
    LaunchedEffect(songs.isNotEmpty(), autoPlayed) {
        if (songs.isNotEmpty() && !autoPlayed) {
            autoPlayed = true
            playRadio()
        }
    }

    // 电台整单播完（40 首都听完）→ 自动刷新榜单（过滤已跳过歌）并继续播放新歌
    LaunchedEffect(viewModel.radioQueueEnded) {
        if (viewModel.radioQueueEnded) {
            viewModel.radioQueueEnded = false
            autoPlayed = false   // 允许加载完成后自动开始播放新歌
            loadRadio(true)
        }
    }

    Column(Modifier.fillMaxSize()) {
        // 顶部栏：返回 + 电台标题 + 刷新
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
                Icons.Default.Radio,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "每日电台",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    if (familiarMode) "熟悉版 · 相似曲目推荐 ${songs.size} 首"
                    else "探索版 · ${hotVariantNames[refreshRound % hotVariants.size]}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // 模式切换：探索版 / 熟悉版
            listOf("探索版" to false, "熟悉版" to true).forEach { (label, mode) ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (familiarMode == mode) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (familiarMode == mode) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                            else Color.Transparent
                        )
                        .clickable(enabled = !loading) {
                            if (familiarMode != mode) {
                                familiarMode = mode
                                autoPlayed = true   // 切模式不自动播放（与进入一致），用户点"播放电台"才播
                                loadRadio(true)
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
            // 刷新电台
            Text(
                text = if (loading) "加载中…" else "刷新电台",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                    .clickable(enabled = !loading) {
                        autoPlayed = true   // 刷新不自动播放
                        loadRadio(true)
                    }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            )
            Spacer(Modifier.width(8.dp))
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(modifier = Modifier.size(36.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("正在生成今日电台…", style = MaterialTheme.typography.bodyMedium)
                }
            }
            error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "今日电台生成失败\n（网络异常或全部歌曲被过滤）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
            else -> {
                // 播放电台按钮
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                        .clickable(enabled = radioState == null) { playRadio() }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            radioState ?: "重新播放今日电台",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "${songs.size} 首",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    items(songs, key = { "${it.platform}|${it.id}" }) { song ->
                        OnlineSongRow(song = song, onClick = {
                            // 点击歌曲 → 以整个电台列表为队列播放（从点击处开始），支持上一曲/下一曲；
                            // 列表第一首时上一曲无效（前面无歌）
                            scope.launch {
                                val idx = songs.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
                                radioState = "正在准备播放…"
                                val queue = coroutineScope {
                                    val gate = Semaphore(8)
                                    songs.map { s -> async {
                                        // 优先用缓存 URL（列表生成时已解析验证）；无缓存再解析
                                        val url = viewModel.cachedUrl(s) ?: runCatching {
                                            gate.withPermit { OnlineMetadataFetcher.resolveOnlineUrl(s) }
                                        }.getOrNull()?.also { viewModel.cacheUrl(s, it) }
                                        url?.let {
                                            Song(
                                                id = -1L,
                                                title = s.title,
                                                artist = s.artist,
                                                album = "每日电台",
                                                durationMs = s.durationMs,
                                                uri = android.net.Uri.parse(it),
                                                albumArtUri = s.artUrl?.let { u -> android.net.Uri.parse(u) }
                                            )
                                        }
                                    } }.awaitAll().filterNotNull()
                                }
                                radioState = null
                                if (queue.isNotEmpty()) viewModel.playOnlineQueue(queue, isRadio = false, startIndex = idx)
                            }
                        })
                    }
                }
            }
        }
    }
}
