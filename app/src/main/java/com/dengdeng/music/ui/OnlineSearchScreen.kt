package com.dengdeng.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.dengdeng.music.data.OnlineDownloader
import com.dengdeng.music.data.OnlineMetadataFetcher
import com.dengdeng.music.data.OnlineMetadataFetcher.AudioSource
import com.dengdeng.music.data.OnlineMetadataFetcher.OnlineSong
import kotlinx.coroutines.launch

/** 联网搜索结果界面：多源搜索歌曲 → 点击进入音源选择 */
@Composable
fun OnlineSearchScreen(
    query: String,
    onBack: () -> Unit,
    onDownloaded: () -> Unit = {},
    onPlay: (OnlineSong, AudioSource) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var loading by remember(query) { mutableStateOf(true) }
    var results by remember(query) { mutableStateOf<List<OnlineSong>>(emptyList()) }
    var error by remember(query) { mutableStateOf(false) }
    // 正在查看音源的歌曲（非 null 时显示音源弹窗）
    var selectedSong by remember { mutableStateOf<OnlineSong?>(null) }
    // 下载状态：key=平台|品质 → 状态文案
    var downloadState by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    LaunchedEffect(query) {
        loading = true
        error = false
        results = OnlineMetadataFetcher.searchSongsOnline(query)
        loading = false
        if (results.isEmpty()) error = true
    }

    Column(Modifier.fillMaxSize()) {
        // 顶部栏：返回 + 搜索词
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Column(Modifier.weight(1f)) {
                Text(
                    "网络搜索",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    if (loading) "搜索中…" else "「$query」共 ${results.size} 个结果",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!loading && results.isNotEmpty()) {
                // 来源统计
                val platforms = results.map { it.platform }.distinct()
                Text(
                    platforms.joinToString(" + "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 16.dp)
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

        when {
            loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(modifier = Modifier.size(36.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("正在联网搜索「$query」…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            error -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("😕", fontSize = 40.sp)
                        Spacer(Modifier.height(8.dp))
                        Text("没有搜到「$query」", style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "试试换一个关键词，或检查网络",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(results, key = { "${it.platform}|${it.id}" }) { song ->
                        OnlineSongRow(song = song, onClick = { selectedSong = song })
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                            modifier = Modifier.padding(start = 76.dp)
                        )
                    }
                }
            }
        }
    }

    // 音源选择弹窗
    selectedSong?.let { song ->
        OnlineSourceSheet(
            song = song,
            downloadState = downloadState,
            onDownload = { source ->
                scope.launch {
                    val key = "${source.platform}|${source.quality}"
                    downloadState = downloadState + (key to "下载中…")
                    val url = source.url ?: run {
                        downloadState = downloadState + (key to "不可用")
                        return@launch
                    }
                    val ok = OnlineDownloader.downloadToMusicLibrary(
                        context, url, song.title, song.artist, source.format
                    )
                    downloadState = downloadState + (key to if (ok) "已下载 ✓" else "下载失败")
                    if (ok) onDownloaded()
                }
            },
            onPlay = { source -> onPlay(song, source) },
            onDismiss = { selectedSong = null }
        )
    }
}

/** 联网搜索结果行：封面 + 歌名/歌手 + 来源标签 + 时长 */
@Composable
internal fun OnlineSongRow(
    song: OnlineSong,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 封面
        AsyncImage(
            model = song.artUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                song.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                song.artist.ifBlank { "未知艺术家" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            // 来源标签
            Text(
                song.platform,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(platformColor(song.platform))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                formatMs(song.durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 音源选择弹窗：展示歌曲信息 + 各平台/品质/格式音源 + 下载 */
@Composable
private fun OnlineSourceSheet(
    song: OnlineSong,
    downloadState: Map<String, String>,
    onDownload: (AudioSource) -> Unit,
    onPlay: (AudioSource) -> Unit = {},
    onDismiss: () -> Unit
) {
    var sources by remember(song.id) { mutableStateOf<List<AudioSource>?>(null) }

    LaunchedEffect(song.id) {
        sources = OnlineMetadataFetcher.fetchAudioSources(song)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = song.artUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(song.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${song.artist.ifBlank { "未知艺术家" }} · ${song.platform} · ${formatMs(song.durationMs)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        text = {
            Column {
                val list = sources
                // 全网最高音质优先：无损 > 高品 > 标准，取第一个可下载的音源
                val qualityOrder = mapOf("无损" to 0, "高品" to 1, "标准" to 2)
                val best = list
                    ?.filter { it.url != null }
                    ?.minByOrNull { qualityOrder[it.quality] ?: 3 }

                if (best != null) {
                    // 一键下载最高音质
                    val bestKey = "${best.platform}|${best.quality}"
                    val bestState = downloadState[bestKey]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                            .clickable(enabled = bestState == null) { onDownload(best) }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (bestState == null) "下载全网最高音质" else bestState,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "自动选择：${best.platform} · ${best.quality} ${best.format}${sourceInfoText(best).let { if (it.isNotEmpty()) " · $it" else "" }}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (bestState == null) {
                            Text(
                                "最优",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "或手动选择音源（点击下载）",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                } else if (list != null && list.isNotEmpty()) {
                    Text(
                        "选择音源（点击下载）",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                }

                when {
                    list == null -> Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("正在查询各平台音源…", style = MaterialTheme.typography.bodyMedium)
                    }
                    list.isEmpty() -> Text(
                        "该歌曲暂无可用音源",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                    else -> list.forEach { source ->
                        AudioSourceRow(
                            source = source,
                            state = downloadState["${source.platform}|${source.quality}"],
                            onClick = { onDownload(source) },
                            onPlay = { onPlay(source) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

/** 音源条目行：整行点击=在线试听/播放，右侧按钮=下载 */
@Composable
private fun AudioSourceRow(
    source: AudioSource,
    state: String?,
    onClick: () -> Unit,
    onPlay: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .clickable(enabled = source.url != null) {
                // 整行点击 = 在线试听/播放（url 可用时）
                onPlay()
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 平台色块
        Text(
            source.platform,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(platformColor(source.platform))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    source.quality,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    source.format,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(3.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                )
                if (source.vip) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Default.Lock, contentDescription = "VIP", tint = Color(0xFFE6A23C), modifier = Modifier.size(14.dp))
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                sourceInfoText(source),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        // 右侧状态/按钮
        when {
            state != null && state == "已下载 ✓" -> Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.PlayArrow, contentDescription = "播放", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(2.dp))
                Text("播放", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            state != null -> Text(state, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            source.url == null -> Text("VIP 受限", style = MaterialTheme.typography.labelSmall, color = Color(0xFFE6A23C))
            else -> Row(verticalAlignment = Alignment.CenterVertically) {
                // 可试听（整行点击）
                Icon(Icons.Default.PlayArrow, contentDescription = "试听", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                // 下载按钮（独立点击）
                Text(
                    "下载",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                        .clickable { onClick() }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
        }
    }
    Spacer(Modifier.height(8.dp))
}

/** 音源信息文本：码率 + 大小 */
private fun sourceInfoText(s: AudioSource): String {
    val parts = mutableListOf<String>()
    if (s.bitrate > 0) parts.add("${s.bitrate / 1000}kbps")
    if (s.sizeBytes > 0) parts.add(formatSize(s.sizeBytes))
    return if (parts.isEmpty()) "大小未知" else parts.joinToString(" · ")
}

/** 文件大小格式化 */
private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "未知"
    return when {
        bytes >= 1024 * 1024 -> "%.1fMB".format(bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> "%.0fKB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}

/** 时长格式化 mm:ss */
internal fun formatMs(ms: Long): String {
    if (ms <= 0) return "--:--"
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}

/** 平台主题色：网易云红 / QQ 绿 / 酷狗蓝 */
internal fun platformColor(platform: String): Color = when (platform) {
    "网易云" -> Color(0xFFC20C0C)
    "QQ音乐" -> Color(0xFF31C27C)
    "酷狗" -> Color(0xFF2F7DF6)
    else -> Color(0xFF666666)
}
