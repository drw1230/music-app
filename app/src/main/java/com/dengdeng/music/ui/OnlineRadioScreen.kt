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
import com.dengdeng.music.data.OnlineDownloader
import com.dengdeng.music.data.OnlineMetadataFetcher
import com.dengdeng.music.data.OnlineMetadataFetcher.OnlineSong
import com.dengdeng.music.data.Song
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 每日电台界面：双榜热歌（网易云 + QQ）→ 每日推荐
 * - 播放 <10s 切走的歌自动降权（ViewModel.skipSongs 过滤）
 * - 支持单曲试听（迷你条）、下载、一键播放电台（在线流队列）
 */
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
    // 单曲下载状态：key=title|artist → 文案
    var downloadState by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    // 日期
    val today = remember {
        SimpleDateFormat("M月d日", Locale.getDefault()).format(Date())
    }

    LaunchedEffect(Unit) {
        loading = true
        error = false
        val hot = OnlineMetadataFetcher.fetchHotSongs(20)
        // 过滤用户 10 秒内切走的歌（负反馈）
        val skip = viewModel.skipSongs
        songs = if (skip.isEmpty()) hot else hot.filterNot {
            "${it.title}|${it.artist}" in skip
        }
        loading = false
        if (songs.isEmpty()) error = true
    }

    Column(Modifier.fillMaxSize()) {
        // 顶部栏：返回 + 电台标题
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
                    "$today · 网易云热歌榜 + QQ热歌榜",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
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
                        .clickable(enabled = radioState == null) {
                            // 一键播放电台：逐首解析 URL → 在线流队列
                            scope.launch {
                                val targets = songs
                                radioState = "正在准备电台 0/${targets.size}…"
                                val queue = mutableListOf<Song>()
                                for ((i, s) in targets.withIndex()) {
                                    val url = OnlineMetadataFetcher.resolveOnlineUrl(s)
                                    if (url != null) {
                                        queue.add(
                                            Song(
                                                id = -1L,
                                                title = s.title,
                                                artist = s.artist,
                                                album = "每日电台",
                                                durationMs = s.durationMs,
                                                uri = android.net.Uri.parse(url),
                                                albumArtUri = s.artUrl?.let { android.net.Uri.parse(it) }
                                            )
                                        )
                                    }
                                    radioState = "正在准备电台 ${i + 1}/${targets.size}…"
                                }
                                radioState = null
                                if (queue.isNotEmpty()) {
                                    viewModel.playOnlineQueue(queue)
                                } else {
                                    radioState = "暂无可用音源"
                                }
                            }
                        }
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
                            radioState ?: "播放今日电台",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "${songs.size} 首 · 按你的口味推荐（跳过太快的歌会自动减少）",
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
                            // 点击试听（迷你条播放）
                            scope.launch {
                                val url = OnlineMetadataFetcher.resolveOnlineUrl(song)
                                if (url != null) {
                                    viewModel.playOnline(song.title, song.artist, url, song.artUrl, song.durationMs)
                                }
                            }
                        })
                        // 右侧下载按钮
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 76.dp, end = 16.dp, bottom = 8.dp),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val dKey = "${song.title}|${song.artist}"
                            val dState = downloadState[dKey]
                            when {
                                dState != null -> Text(dState, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                else -> Text(
                                    "下载",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                                        .clickable {
                                            scope.launch {
                                                downloadState = downloadState + (dKey to "下载中…")
                                                val url = OnlineMetadataFetcher.resolveOnlineUrl(song)
                                                val ok = if (url != null) {
                                                    OnlineDownloader.downloadToMusicLibrary(context, url, song.title, song.artist)
                                                } else false
                                                downloadState = downloadState + (dKey to if (ok) "已下载 ✓" else "失败")
                                            }
                                        }
                                        .padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
