package com.dengdeng.music.ui

import android.widget.Toast
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.media3.common.Player
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import com.dengdeng.music.data.AlbumGroup
import com.dengdeng.music.data.GitHubUpdater
import com.dengdeng.music.data.Playlist
import com.dengdeng.music.data.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 主界面 —— Tab 切换（全部/喜欢/歌单）+ 歌曲列表 + 底部迷你播放条
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MusicViewModel,
    hasPermission: Boolean,
    onDeleteSongs: (List<android.net.Uri>) -> Unit,
    themeMode: Int,
    onThemeModeChange: (Int) -> Unit
) {
    // 是否显示全屏播放页
    var showPlayer by remember { mutableStateOf(false) }
    // 当前 Tab：0=全部 1=喜欢 2=歌单
    var selectedTab by remember { mutableStateOf(0) }
    // 正在查看的歌单（null 表示歌单列表页）
    var viewingPlaylist by remember { mutableStateOf<Playlist?>(null) }

    // 返回键层级："全部"tab 是最基础界面。
    // 歌单详情 → 先关详情；非"全部"tab（喜欢/最近/歌单）→ 先回"全部"；
    // 已在"全部"tab → 放行系统返回键（退出到桌面）。
    // 注意：放在函数体最前（最早注册）→ 优先级最低，不抢占电台/搜索/智能歌单/播放页的返回处理。
    val tabBackEnabled = viewingPlaylist != null || selectedTab != 0
    BackHandler(enabled = tabBackEnabled) {
        if (viewingPlaylist != null) viewingPlaylist = null else selectedTab = 0
    }
    // 正在查看的专辑（null 表示专辑列表页）
    var viewingAlbum by remember { mutableStateOf<AlbumGroup?>(null) }
    // 联网搜索关键词（非 null 时显示网络搜索界面，覆盖曲库）
    var onlineSearchQuery by remember { mutableStateOf<String?>(null) }
    // 每日电台开关
    var showRadio by remember { mutableStateOf(false) }
    // 智能歌单开关（曲库头部入口，覆盖曲库区）
    var showSmartPlaylist by remember { mutableStateOf(false) }
    // 待添加到歌单的歌曲 ID（非 null 时显示选择弹窗）
    var songForPlaylist by remember { mutableStateOf<Long?>(null) }
    // 右上角更多菜单
    var menuExpanded by remember { mutableStateOf(false) }
    // 排序菜单
    var sortMenuExpanded by remember { mutableStateOf(false) }
    // 睡眠定时器弹窗
    var showSleepTimer by remember { mutableStateOf(false) }
    // 关于弹窗
    var showAbout by remember { mutableStateOf(false) }
    // 软件升级①：蓝奏云网盘下载页（App 内 WebView）
    var showUpgrade by remember { mutableStateOf(false) }
    // 软件升级②：GitHub 最新版本（api.github.com 直连查版本 + 下载，不打开网页）
    var showGitHubUpdate by remember { mutableStateOf(false) }
    // 软件升级下载源选择菜单
    var upgradeMenuExpanded by remember { mutableStateOf(false) }
    // 听歌识曲界面
    var showRecognize by remember { mutableStateOf(false) }

    // 新版本提醒：启动时后台查一次 GitHub 最新 Release，远端更新则点亮"软件升级"旁的小圆点
    val appContext = LocalContext.current
    val currentVersionName = remember {
        try {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: ""
        } catch (_: Exception) { "" }
    }
    var hasNewVersion by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val newer = withContext(Dispatchers.IO) {
            when (val r = GitHubUpdater.fetchLatest()) {
                is GitHubUpdater.CheckResult.Found ->
                    isVersionNewer(r.release.tag, currentVersionName)
                else -> false
            }
        }
        hasNewVersion = newer
    }

    if (showPlayer) {
        // 播放页打开时，安卓返回键先关闭播放页回到曲库（再按返回键才退出 App）
        BackHandler { showPlayer = false }
        PlayerScreen(viewModel = viewModel, onClose = { showPlayer = false })
        return
    }

    Scaffold(
        topBar = {
            // 覆盖界面（智能歌单/电台/搜索）隐藏 DDmusic 主顶栏，让覆盖界面用自己的顶栏更沉浸；
            // 主菜单（睡眠定时/主题/关于/排序/刷新扫描）只在曲库首页通过 ⋮ 进入
            val inOverlay = showRadio || showSmartPlaylist || onlineSearchQuery != null ||
                    showUpgrade || showRecognize
            if (!inOverlay) {
                TopAppBar(
                    title = { Text("DDmusic") },
                    actions = {
                        // 右上角更多菜单
                        Box {
                            IconButton(onClick = { menuExpanded = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "更多")
                            }
                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false }
                            ) {
                            // 刷新扫描
                            DropdownMenuItem(
                                text = { Text("刷新扫描") },
                                leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    viewModel.scanMusic()
                                }
                            )
                            // 排序（带二级菜单）
                            DropdownMenuItem(
                                text = { Text("排序") },
                                leadingIcon = { Icon(Icons.Default.Sort, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    sortMenuExpanded = true
                                }
                            )
                            // 睡眠定时器
                            DropdownMenuItem(
                                text = { Text("睡眠定时器") },
                                leadingIcon = { Icon(Icons.Default.Timer, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    showSleepTimer = true
                                }
                            )
                            // 深色主题切换
                            DropdownMenuItem(
                                text = { Text("深色模式") },
                                leadingIcon = {
                                    Icon(
                                        if (themeMode != 0) Icons.Default.DarkMode else Icons.Default.LightMode,
                                        contentDescription = null
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    onThemeModeChange(if (themeMode == 0) 2 else 0)
                                }
                            )
                            // 听歌识曲（原生录音 → AHA/ACRCloud 识别 → 自动带词进搜索）
                            DropdownMenuItem(
                                text = { Text("听歌识曲") },
                                leadingIcon = { Icon(Icons.Default.Mic, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    showRecognize = true
                                }
                            )
                            // 软件升级（先弹二级菜单选下载源：蓝奏云网盘 / GitHub Releases）
                            // 有新版本时，文字旁亮一个主题紫小圆点提醒
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("软件升级")
                                        if (hasNewVersion) {
                                            Spacer(Modifier.width(7.dp))
                                            Box(
                                                Modifier
                                                    .size(8.dp)
                                                    .clip(CircleShape)
                                                    .background(Color(0xFF9C6ADE))
                                            )
                                        }
                                    }
                                },
                                leadingIcon = { Icon(Icons.Default.SystemUpdate, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    upgradeMenuExpanded = true
                                }
                            )
                            // 关于
                            DropdownMenuItem(
                                text = { Text("关于 DDmusic") },
                                leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    showAbout = true
                                }
                            )
                        }
                    }
                }
            )
            } else {
                // 覆盖界面保留状态栏占位，让覆盖界面的顶栏从状态栏下方开始
                Spacer(Modifier.statusBarsPadding())
            }
        },
        bottomBar = {
            if (viewModel.nowPlayingSong() != null) {
                    MiniPlayerBar(
                        viewModel,
                        // 统一用迷你条控制试听；点击迷你条（本地/在线歌均可）进入全屏播放界面操作
                        onClick = { showPlayer = true }
                    )
            }
        }
    ) { padding ->
        when {
            // 智能歌单界面（覆盖整个曲库区）
            showSmartPlaylist -> {
                BackHandler { showSmartPlaylist = false }
                Box(Modifier.padding(padding)) {
                    SmartPlaylistScreen(
                        viewModel = viewModel,
                        onBack = { showSmartPlaylist = false }
                    )
                }
            }
            // 软件升级界面（蓝奏云网页，覆盖整个曲库区；返回键由界面内处理，支持回网页上一页）
            showUpgrade -> {
                Box(Modifier.padding(padding)) {
                    UpgradeScreen(url = UPGRADE_URL_LANZOU, onBack = { showUpgrade = false })
                }
            }
            // 听歌识曲界面（覆盖整个曲库区；识别成功 → 自动带搜索词进入联网搜索）
            showRecognize -> {
                BackHandler { showRecognize = false }
                Box(Modifier.padding(padding)) {
                    RecognizeScreen(
                        onBack = { showRecognize = false },
                        onPickQuery = { q ->
                            showRecognize = false
                            viewModel.addSearchHistory(q)
                            onlineSearchQuery = q
                        }
                    )
                }
            }
            // 每日电台界面（覆盖整个曲库区）
            showRadio -> {
                BackHandler { showRadio = false }
                Box(Modifier.padding(padding)) {
                    OnlineRadioScreen(
                        viewModel = viewModel,
                        onBack = { showRadio = false }
                    )
                }
            }
            // 联网搜索界面（覆盖整个曲库区，优先级最高，避免下载后重扫顶掉界面）
            onlineSearchQuery != null -> {
                val q = onlineSearchQuery!!
                BackHandler { onlineSearchQuery = null }
                Box(Modifier.padding(padding)) {
                    OnlineSearchScreen(
                        query = q,
                        onBack = { onlineSearchQuery = null },
                        onDownloaded = { viewModel.scanMusic() },
                        onRepairSources = { viewModel.clearSourceCaches() },
                        onPlay = { song, source ->
                            source.url?.let { url ->
                                // 在线试听：走迷你条播放（不进入全屏播放界面）
                                viewModel.playOnline(
                                    song.title, song.artist, url, song.artUrl, song.durationMs
                                )
                            }
                        }
                    )
                }
            }
            // 无音乐权限 / 曲库为空：仍可用在线功能（每日电台/智能歌单/在线搜索）
            !hasPermission -> OnlineOnlyView(
                modifier = Modifier.padding(padding),
                hasPermission = false,
                onRadio = { showRadio = true },
                onSmartPlaylist = { showSmartPlaylist = true },
                onSearch = { onlineSearchQuery = "" }
            )
            viewModel.isLoading -> LoadingView(Modifier.padding(padding))
            viewModel.songs.isEmpty() -> OnlineOnlyView(
                modifier = Modifier.padding(padding),
                hasPermission = true,
                onRadio = { showRadio = true },
                onSmartPlaylist = { showSmartPlaylist = true },
                onSearch = { onlineSearchQuery = "" }
            )
            else -> {
                // Tab 栏 + 内容
                Column(Modifier.padding(padding)) {
                    // 歌单详情页显示返回 + 名称；否则显示 Tab
                    val isDetail = selectedTab == 3 && viewingPlaylist != null
                    if (isDetail) {
                        if (selectedTab == 3 && viewingPlaylist != null) {
                            PlaylistDetailHeader(
                                playlist = viewingPlaylist!!,
                                songCount = viewModel.songsOfPlaylist(viewingPlaylist!!.id).size,
                                onBack = { viewingPlaylist = null },
                                onDelete = {
                                    viewModel.deletePlaylist(viewingPlaylist!!.id)
                                    viewingPlaylist = null
                                }
                            )
                        }
                    } else {
                        LibraryTabs(
                            selected = selectedTab,
                            onSelect = { selectedTab = it }
                        )
                    }

                    when (selectedTab) {
                        1 -> FavoriteList(
                            viewModel = viewModel,
                            onAddToPlaylist = { songForPlaylist = it },
                            onDeleteSongs = onDeleteSongs
                        )
                        2 -> RecentList(
                            viewModel = viewModel,
                            onAddToPlaylist = { songForPlaylist = it },
                            onDeleteSongs = onDeleteSongs
                        )
                        3 -> if (viewingPlaylist != null) {
                            PlaylistDetail(
                                viewModel = viewModel,
                                playlist = viewingPlaylist!!,
                                onAddToPlaylist = { songForPlaylist = it },
                                onDeleteSongs = onDeleteSongs
                            )
                        } else {
                            PlaylistList(
                                viewModel = viewModel,
                                onOpen = { viewingPlaylist = it }
                            )
                        }
                        else -> SongList(
                            viewModel = viewModel,
                            onAddToPlaylist = { songForPlaylist = it },
                            onDeleteSongs = onDeleteSongs,
                            onOnlineSearch = { q ->
                                viewModel.addSearchHistory(q)
                                onlineSearchQuery = q
                            },
                            onRadio = { showRadio = true },
                            onSmartPlaylist = { showSmartPlaylist = true }
                        )
                    }
                }

                // 添加到歌单弹窗
                songForPlaylist?.let { songId ->
                    AddToPlaylistSheet(
                        viewModel = viewModel,
                        songId = songId,
                        onDismiss = { songForPlaylist = null }
                    )
                }
            }
        }
    }

    // ===== 右上角菜单触发的弹窗 =====

    // 软件升级：下载源选择（二级菜单）
    if (upgradeMenuExpanded) {
        AlertDialog(
            onDismissRequest = { upgradeMenuExpanded = false },
            title = { Text("软件升级") },
            text = {
                Column {
                    UpgradeSourceRow(
                        name = "蓝奏云网盘",
                        desc = "网盘分享页，访问密码 1234",
                        onClick = {
                            upgradeMenuExpanded = false
                            showUpgrade = true
                        }
                    )
                    UpgradeSourceRow(
                        name = "GitHub Releases",
                        desc = "官方仓库最新版本（直连下载）",
                        onClick = {
                            upgradeMenuExpanded = false
                            showGitHubUpdate = true
                        }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { upgradeMenuExpanded = false }) { Text("取消") }
            }
        )
    }

    // 软件升级：GitHub 最新版本（进来自动查 api.github.com，不走网页）
    if (showGitHubUpdate) {
        GitHubUpdateDialog(onDismiss = { showGitHubUpdate = false })
    }

    // 排序菜单（AlertDialog 形式）
    if (sortMenuExpanded) {
        val sortNames = listOf("按歌名", "按艺术家", "按时长", "最近添加", "乱序")
        AlertDialog(
            onDismissRequest = { sortMenuExpanded = false },
            title = { Text("排序方式") },
            text = {
                Column {
                    sortNames.forEachIndexed { index, name ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.changeSortMode(index)
                                    sortMenuExpanded = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = name,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (viewModel.sortMode == index) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                modifier = Modifier.weight(1f)
                            )
                            if (viewModel.sortMode == index) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { sortMenuExpanded = false }) { Text("取消") }
            }
        )
    }

    // 睡眠定时器弹窗
    if (showSleepTimer) {
        SleepTimerDialog(
            viewModel = viewModel,
            onDismiss = { showSleepTimer = false }
        )
    }

    // 关于弹窗
    if (showAbout) {
        AboutDialog(onDismiss = { showAbout = false })
    }
}

/** 曲库 Tab 栏 */
@Composable
private fun LibraryTabs(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        listOf("全部", "喜欢", "最近", "歌单").forEachIndexed { index, label ->
            val isSelected = selected == index
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.clickable { onSelect(index) }
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)
                )
            }
        }
    }
}

/** 歌单详情顶部：返回 + 名称 + 删除 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaylistDetailHeader(
    playlist: Playlist,
    songCount: Int,
    onBack: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = playlist.name,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "$songCount 首歌曲",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "删除歌单",
                tint = MaterialTheme.colorScheme.error
            )
        }
    }
}

/** 全部歌曲列表（带搜索框 + 智能联想 + 搜索历史 + 联网搜索入口） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SongList(
    viewModel: MusicViewModel,
    onAddToPlaylist: (Long) -> Unit,
    onDeleteSongs: (List<android.net.Uri>) -> Unit,
    onOnlineSearch: (String) -> Unit,
    onRadio: () -> Unit,
    onSmartPlaylist: () -> Unit
) {
    val songs = viewModel.filteredSongs
    val totalDuration = songs.sumOf { it.durationMs }
    val totalMinutes = totalDuration / 60_000

    // 搜索框状态
    var query by remember { mutableStateOf("") }
    // 搜索框是否聚焦（聚焦且为空时在搜索框下方显示搜索历史，不占曲库位置）
    var searchFocused by remember { mutableStateOf(false) }
    // 联想面板开关（点击联想项后关闭，继续输入重新打开）
    var suggestOpen by remember { mutableStateOf(true) }
    // 联想建议（去重歌名，前缀优先）
    val suggestions = remember(query, viewModel.songs) { viewModel.suggestSongs(query) }
    // 同步到 ViewModel（用于播放时保持过滤）
    androidx.compose.runtime.SideEffect { viewModel.searchQuery = query }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        item {
            // 搜索框（输入联想 + 回车/点击搜索直接联网搜索）
            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    suggestOpen = true
                },
                placeholder = { Text("搜索歌曲、艺术家、专辑") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        if (query.isNotBlank()) onOnlineSearch(query.trim())
                    }
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .onFocusChanged { searchFocused = it.isFocused }
            )
        }
        // 搜索历史区（点击搜索框获得焦点时，在搜索框下方显示；不搜索时不占曲库位置）
        if (searchFocused && query.isBlank() && viewModel.searchHistory.isNotEmpty()) {
            item {
                val allHistory = viewModel.searchHistory
                // 默认只显示一排（前 5 个），点击"历史 N"展开全部
                var historyExpanded by remember { mutableStateOf(false) }
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "搜索历史",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "清空",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { viewModel.clearSearchHistory() }
                        )
                    }
                    // 历史词标签流式排列
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val visible = if (historyExpanded) allHistory else allHistory.take(5)
                        visible.forEach { word ->
                            Text(
                                text = word,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                                    .clickable {
                                        query = word
                                        suggestOpen = true
                                    }
                                    .padding(horizontal = 14.dp, vertical = 6.dp)
                            )
                        }
                        // 最边上的"历史"入口：未展开时显示数量，点击展开全部；展开后变"收起"
                        if (!historyExpanded && allHistory.size > 5) {
                            Text(
                                text = "历史 ${allHistory.size}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                                    .clickable { historyExpanded = true }
                                    .padding(horizontal = 14.dp, vertical = 6.dp)
                            )
                        } else if (historyExpanded) {
                            Text(
                                text = "收起",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                                    .clickable { historyExpanded = false }
                                    .padding(horizontal = 14.dp, vertical = 6.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
        // 智能联想区：输入关键词时显示匹配的歌曲名建议
        if (query.isNotBlank() && suggestOpen && suggestions.isNotEmpty()) {
            item {
                Column(Modifier.padding(bottom = 4.dp)) {
                    Text(
                        "搜索建议",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp)
                    )
                    suggestions.forEach { song ->
                        SuggestionRow(
                            song = song,
                            query = query.trim(),
                            onClick = {
                                // 点击联想歌曲 → 直接进入该歌曲的联网搜索界面
                                onOnlineSearch(song.title)
                            }
                        )
                    }
                    // 联网搜索入口
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOnlineSearch(query.trim()) }
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Language,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(14.dp))
                        Text(
                            buildAnnotatedString {
                                append("在网络上搜索「")
                                withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)) {
                                    append(query.trim())
                                }
                                append("」")
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            "联网",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
            }
        }
        // 无本地联想时也提供联网搜索入口
        if (query.isNotBlank() && suggestOpen && suggestions.isEmpty()) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOnlineSearch(query.trim()) }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Language,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(14.dp))
                    Text(
                        buildAnnotatedString {
                            append("没有本地匹配，在网络上搜索「")
                            withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)) {
                                append(query.trim())
                            }
                            append("」")
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
        item {
            LibraryHeader(
                songCount = songs.size,
                totalMinutes = totalMinutes,
                onPlayAll = { viewModel.playSong(0) },
                onSmartPlaylist = onSmartPlaylist,
                onRadio = onRadio
            )
        }
        if (songs.isEmpty() && query.isNotBlank()) {
            // 搜索无结果
            item {
                Box(Modifier.fillMaxWidth().padding(top = 32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "没有找到「${query.trim()}」相关歌曲",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        itemsIndexed(songs) { index, song ->
            SongRow(
                viewModel = viewModel,
                song = song,
                index = index,
                isCurrent = index == viewModel.currentIndex,
                isPlaying = viewModel.isPlaying && index == viewModel.currentIndex,
                isFavorite = viewModel.isFavorite(song.id),
                onClick = { viewModel.playSong(index) },
                onAddToPlaylist = { onAddToPlaylist(song.id) },
                onDeleteFromDisk = { onDeleteSongs(listOf(song.uri)) }
            )
        }
    }
}

/** 喜欢歌曲列表 */
@Composable
private fun RecentList(
    viewModel: MusicViewModel,
    onAddToPlaylist: (Long) -> Unit,
    onDeleteSongs: (List<android.net.Uri>) -> Unit
) {
    val recent = viewModel.recentSongs
    if (recent.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.History,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(Modifier.height(8.dp))
                Text("还没有播放记录", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("播放过的歌（本地 + 在线）会显示在这里", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
            }
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.History, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("最近播放（${recent.size}）", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        }
        // 在线歌 id 均为负，用 歌名+歌手 匹配当前播放
        val now = viewModel.nowPlayingSong()
        itemsIndexed(recent) { index, song ->
            SongRow(
                viewModel = viewModel,
                song = song,
                index = index,
                isCurrent = now != null && now.title == song.title && now.artist == song.artist,
                isPlaying = viewModel.isPlaying && now != null && now.title == song.title && now.artist == song.artist,
                isFavorite = viewModel.isFavorite(song.id),
                onClick = { viewModel.playSongs(recent, index) },
                onAddToPlaylist = { onAddToPlaylist(song.id) },
                onDeleteFromDisk = { onDeleteSongs(listOf(song.uri)) }
            )
        }
    }
}

@Composable
private fun FavoriteList(
    viewModel: MusicViewModel,
    onAddToPlaylist: (Long) -> Unit,
    onDeleteSongs: (List<android.net.Uri>) -> Unit
) {
    val favorites = viewModel.favoriteSongs
    if (favorites.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.FavoriteBorder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "还没有喜欢的歌曲",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "播放页点 ♡ 喜欢喜欢的歌",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
        return
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Favorite,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "我喜欢（${favorites.size}）",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        itemsIndexed(favorites) { index, song ->
            SongRow(
                viewModel = viewModel,
                song = song,
                index = index,
                isCurrent = song.id == viewModel.currentSong()?.id,
                isPlaying = viewModel.isPlaying && song.id == viewModel.currentSong()?.id,
                isFavorite = true,
                onClick = { viewModel.playSongs(favorites, index) },
                // 喜欢列表里"从列表移除" = 取消喜欢
                onRemoveFromList = { viewModel.toggleFavorite(song.id) },
                onAddToPlaylist = { onAddToPlaylist(song.id) },
                onDeleteFromDisk = { onDeleteSongs(listOf(song.uri)) }
            )
        }
    }
}

/** 专辑列表页：网格卡片展示所有专辑 */
@Composable
private fun AlbumList(
    viewModel: MusicViewModel,
    onOpen: (AlbumGroup) -> Unit
) {
    val albums = viewModel.albums
    if (albums.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "暂无专辑",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    // 两列网格
    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
        columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        items(albums.size) { index ->
            val album = albums[index]
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(album) }
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(album.albumArtUri)
                        .crossfade(true)
                        .build(),
                    contentDescription = album.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = album.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${album.songs.size} 首",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}

/** 专辑详情页：专辑内歌曲列表 */
@Composable
private fun AlbumDetail(
    viewModel: MusicViewModel,
    album: AlbumGroup,
    onAddToPlaylist: (Long) -> Unit,
    onDeleteSongs: (List<android.net.Uri>) -> Unit
) {
    val songs = album.songs
    LazyColumn(Modifier.fillMaxSize()) {
        // 专辑头：大封面 + 信息
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(album.albumArtUri)
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(96.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = album.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = album.artist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${songs.size} 首歌曲",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
        }

        itemsIndexed(songs) { index, song ->
            SongRow(
                viewModel = viewModel,
                song = song,
                index = index,
                isCurrent = song.id == viewModel.currentSong()?.id,
                isPlaying = viewModel.isPlaying && song.id == viewModel.currentSong()?.id,
                isFavorite = viewModel.isFavorite(song.id),
                onClick = { viewModel.playSongs(songs, index) },
                onAddToPlaylist = { onAddToPlaylist(song.id) },
                onDeleteFromDisk = { onDeleteSongs(listOf(song.uri)) }
            )
        }
    }
}

/** 歌单列表页 */
@Composable
private fun PlaylistList(
    viewModel: MusicViewModel,
    onOpen: (Playlist) -> Unit
) {
    val playlists = viewModel.playlists
    var showCreateDialog by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "我的歌单（${playlists.size}）",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                // 新建歌单按钮
                Button(
                    onClick = { showCreateDialog = true },
                    shape = RoundedCornerShape(20.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("新建歌单", style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        if (playlists.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.LibraryMusic,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "还没有歌单",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "点击右上角新建",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }

        itemsIndexed(playlists) { index, playlist ->
            val songsInPlaylist = viewModel.songsOfPlaylist(playlist.id)
            // 歌单卡片
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 5.dp)
                    .clickable { onOpen(playlist) }
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 歌单封面（第一首歌的封面或图标）
                    val coverSong = songsInPlaylist.firstOrNull()
                    if (coverSong != null) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(coverSong.albumArtUri ?: coverSong.uri)
                                .crossfade(true)
                                .build(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(52.dp)
                                .clip(RoundedCornerShape(10.dp))
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Album,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = playlist.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${songsInPlaylist.size} 首歌曲",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    // 新建歌单对话框
    if (showCreateDialog) {
        CreatePlaylistDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { name ->
                viewModel.createPlaylist(name)
                showCreateDialog = false
            }
        )
    }
}

/** 歌单详情页 */
@Composable
private fun PlaylistDetail(
    viewModel: MusicViewModel,
    playlist: Playlist,
    onAddToPlaylist: (Long) -> Unit,
    onDeleteSongs: (List<android.net.Uri>) -> Unit
) {
    val songs = viewModel.songsOfPlaylist(playlist.id)
    if (songs.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "歌单是空的，去全部歌曲里点 + 添加",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    LazyColumn(Modifier.fillMaxSize()) {
        itemsIndexed(songs) { index, song ->
            SongRow(
                viewModel = viewModel,
                song = song,
                index = index,
                isCurrent = song.id == viewModel.currentSong()?.id,
                isPlaying = viewModel.isPlaying && song.id == viewModel.currentSong()?.id,
                isFavorite = viewModel.isFavorite(song.id),
                onClick = { viewModel.playSongs(songs, index) },
                // 歌单里"从列表移除" = 从歌单移除
                onRemoveFromList = { viewModel.removeSongFromPlaylist(playlist.id, song.id) },
                onAddToPlaylist = { onAddToPlaylist(song.id) },
                onDeleteFromDisk = { onDeleteSongs(listOf(song.uri)) }
            )
        }
    }
}

/** 新建歌单对话框 */
@Composable
private fun CreatePlaylistDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建歌单") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text("歌单名称") },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onCreate(name.trim()) },
                enabled = name.isNotBlank()
            ) { Text("创建") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 添加到歌单的底部弹窗（曲库列表 / 播放页共用）：选已有歌单直接添加，或新建歌单并顺手加入 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddToPlaylistSheet(
    viewModel: MusicViewModel,
    songId: Long,
    onDismiss: () -> Unit
) {
    // 新建歌单：输入模式 + 名称
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .padding(bottom = 24.dp)
                .imePadding()
        ) {
            Text(
                "添加到歌单",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
            )
            if (viewModel.playlists.isEmpty() && !creating) {
                Text(
                    "还没有歌单，点下面「新建歌单」创建一个",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )
            } else {
                viewModel.playlists.forEach { playlist ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.addSongToPlaylist(playlist.id, songId)
                                onDismiss()
                            }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.PlaylistAdd,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            playlist.name,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                modifier = Modifier.padding(vertical = 4.dp)
            )

            if (creating) {
                // 输入歌单名 → 创建并直接把这首歌加进去
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        placeholder = { Text("歌单名称") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = {
                        viewModel.createPlaylistWithSong(newName.trim().ifBlank { "我的歌单" }, songId)
                        onDismiss()
                    }) {
                        Text("创建并添加")
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { creating = true }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "新建歌单",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }
    }
}

/** 曲库头部统计 */
@Composable
private fun LibraryHeader(
    songCount: Int,
    totalMinutes: Long,
    onPlayAll: () -> Unit,
    onSmartPlaylist: () -> Unit,
    onRadio: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Text(
            text = "曲库",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "$songCount 首歌曲 · 共 ${formatTotalMinutes(totalMinutes)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onPlayAll,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 8.dp)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("全部播放", style = MaterialTheme.typography.labelLarge)
            }
            OutlinedButton(
                onClick = onSmartPlaylist,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 8.dp)
            ) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("智能歌单", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            OutlinedButton(
                onClick = onRadio,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 8.dp)
            ) {
                Icon(Icons.Default.Radio, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("每日电台", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
    }
}

/** 总分钟数格式化 */
private fun formatTotalMinutes(minutes: Long): String {
    val hours = minutes / 60
    val mins = minutes % 60
    return when {
        hours > 0 -> "${hours}小时${mins}分钟"
        else -> "${mins}分钟"
    }
}

/** 搜索联想行：小封面 + 高亮匹配的歌名 + 歌手 */
@Composable
private fun SuggestionRow(
    song: Song,
    query: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 搜索图标替代封面（联想行更轻量）
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(14.dp))
        Column {
            Text(
                text = highlightMatch(song.title, query),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (song.artist.isNotBlank() && song.artist != "未知艺术家") {
                Text(
                    text = song.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Text(
            text = "搜索",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

/** 高亮文本中匹配关键词的部分（匹配段用主题色加粗） */
@Composable
private fun highlightMatch(text: String, query: String): AnnotatedString {
    val q = query.trim().lowercase()
    val lower = text.lowercase()
    val start = lower.indexOf(q)
    if (q.isEmpty() || start < 0) return AnnotatedString(text)
    val end = start + q.length
    return buildAnnotatedString {
        append(text, 0, start)
        withStyle(
            SpanStyle(
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        ) {
            append(text, start, end)
        }
        append(text, end, text.length)
    }
}

/** 单行歌曲（点击播放 + 长按/⋮ 弹出右侧小菜单） */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SongRow(
    viewModel: MusicViewModel,
    song: Song,
    index: Int,
    isCurrent: Boolean,
    isPlaying: Boolean,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onRemoveFromList: (() -> Unit)? = null,
    onAddToPlaylist: () -> Unit,
    onDeleteFromDisk: () -> Unit
) {
    // 菜单展开状态
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = { menuExpanded = true }
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 当前播放左侧高亮条
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
                    Icons.Default.PlayArrow,
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

        // 封面
        Box {
            SongCover(
                song = song,
                contentScale = ContentScale.Crop,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .size(48.dp)
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

        // 歌名 + 艺术家
        Column(Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (isCurrent) FontWeight.Medium else FontWeight.Normal,
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

        // 收藏标记（小爱心）
        if (isFavorite) {
            Icon(
                Icons.Default.Favorite,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(8.dp))
        }

        // 时长
        Text(
            text = formatDuration(song.durationMs),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )

        // ⋮ 更多按钮 + 右侧下拉菜单
        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = "更多操作",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                modifier = Modifier.background(MaterialTheme.colorScheme.surface)
            ) {
                // 播放
                DropdownMenuItem(
                    text = { Text("播放") },
                    leadingIcon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                    onClick = { menuExpanded = false; onClick() }
                )
                // 下一首播放
                DropdownMenuItem(
                    text = { Text("下一首播放") },
                    leadingIcon = { Icon(Icons.Default.SkipNext, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        viewModel.playNext(song.id)
                    }
                )
                // 喜欢 / 取消喜欢
                DropdownMenuItem(
                    text = { Text(if (isFavorite) "取消喜欢" else "喜欢") },
                    leadingIcon = {
                        Icon(
                            if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = null,
                            tint = if (isFavorite) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        viewModel.toggleFavorite(song.id)
                    }
                )
                // 添加到歌单
                DropdownMenuItem(
                    text = { Text("添加到歌单") },
                    leadingIcon = { Icon(Icons.Default.PlaylistAdd, contentDescription = null) },
                    onClick = { menuExpanded = false; onAddToPlaylist() }
                )
                // 从列表移除（仅喜欢/歌单列表显示）
                if (onRemoveFromList != null) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
                    DropdownMenuItem(
                        text = { Text("从列表移除", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(
                                Icons.Default.RemoveCircleOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                        },
                        onClick = { menuExpanded = false; onRemoveFromList() }
                    )
                }
                // 从本地删除
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
                DropdownMenuItem(
                    text = { Text("从本地删除", color = MaterialTheme.colorScheme.error) },
                    leadingIcon = {
                        Icon(
                            Icons.Default.DeleteOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                    },
                    onClick = { menuExpanded = false; onDeleteFromDisk() }
                )
            }
        }
    }
}

/**
 * 版本号比较：remote 是否比 current 新。
 * 兼容 tag 前缀（v1.2.0 / V1.2.0）与不等长段（1.2 vs 1.2.0），逐段比数字。
 */
private fun isVersionNewer(remote: String, current: String): Boolean {
    fun parts(v: String) = v.trim().removePrefix("v").removePrefix("V")
        .split('.').map { it.trim().toIntOrNull() ?: 0 }
    val r = parts(remote)
    val c = parts(current)
    for (i in 0 until maxOf(r.size, c.size)) {
        val rv = r.getOrElse(i) { 0 }
        val cv = c.getOrElse(i) { 0 }
        if (rv != cv) return rv > cv
    }
    return false
}

/** 软件升级二级菜单里的下载源选项行（名称 + 说明 + 右箭头，整行可点） */
@Composable
private fun UpgradeSourceRow(name: String, desc: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = name, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 播放中的动态均衡器图标 */
@Composable
private fun EqualizerIcon(tint: Color) {
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
                        easing = FastOutSlowInEasing
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

/** 底部迷你播放条：小封面 + 歌名 + 控制按钮 + 可拖动进度条 */
@Composable
private fun MiniPlayerBar(viewModel: MusicViewModel, onClick: () -> Unit = {}) {
    val song = viewModel.nowPlayingSong() ?: return
    val isOnline = viewModel.isOnlinePlaying

    Surface(
        tonalElevation = 3.dp,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column {
            // 自定义手势进度条（点击跳转 + 从播放位置相对滑动，与播放页一致）
            GestureSeekBar(
                positionMs = viewModel.currentPositionMs,
                durationMs = viewModel.durationMs,
                onSeek = { viewModel.seekAndPlay(it) },
                onDragStart = { viewModel.pause() },
                onSeekPreview = { viewModel.seekTo(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                thumbColor = MaterialTheme.colorScheme.primary,
                progressColor = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SongCover(
                    song = song,
                    contentScale = ContentScale.Crop,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .size(40.dp)
                )

                Spacer(Modifier.width(12.dp))

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

                // 播放模式切换（顺序 → 列表循环 → 单曲循环 → 乱序 → 顺序）——放在播放控制组左侧（上一首左边）
                val mode = viewModel.repeatMode
                val cycleIcon = when (mode) {
                    MusicViewModel.REPEAT_MODE_SHUFFLE -> Icons.Default.Shuffle
                    Player.REPEAT_MODE_ONE -> Icons.Default.RepeatOne
                    else -> Icons.Default.Repeat   // 顺序(OFF) / 列表循环(ALL)
                }
                val cycleTint = if (mode == Player.REPEAT_MODE_OFF) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                }
                IconButton(onClick = { viewModel.cycleRepeatMode() }) {
                    Icon(
                        cycleIcon,
                        contentDescription = "循环模式",
                        modifier = Modifier.size(22.dp),
                        tint = cycleTint
                    )
                }
                // 在线歌（电台/冷门探索列表队列）也显示上一首/下一首
                IconButton(onClick = { viewModel.previous() }) {
                    Icon(
                        Icons.Default.SkipPrevious,
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
                        Icons.Default.SkipNext,
                        contentDescription = "下一首",
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}

/** 加载中（普通转圈，不用品牌页样式） */
@Composable
private fun LoadingView(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/** 无本地音乐 / 无权限视图：提示 + 在线功能入口（电台/智能歌单/搜索，均不依赖本地曲库） */
@Composable
private fun OnlineOnlyView(
    modifier: Modifier = Modifier,
    hasPermission: Boolean,
    onRadio: () -> Unit,
    onSmartPlaylist: () -> Unit,
    onSearch: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp)
    ) {
        // 状态提示
        Text(
            if (hasPermission) "本地暂无音乐，可先使用在线功能" else "未授予音乐权限：本地歌曲不可用，在线功能不受影响",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (hasPermission) "把音乐文件放入手机后，点右上角 ⋮ → 刷新扫描即可出现曲库" else "可在系统设置中授予音乐权限，之后点右上角 ⋮ → 刷新扫描",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))

        // 三个在线功能入口卡片
        OnlineEntryCard(
            icon = { Icon(Icons.Default.Radio, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            title = "每日电台",
            subtitle = "探索版（榜单+随机歌单）+ 熟悉版（相似曲目）",
            onClick = onRadio
        )
        Spacer(Modifier.height(12.dp))
        OnlineEntryCard(
            icon = { Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            title = "智能歌单",
            subtitle = "最常听（本地+在线统计）· 冷门探索（平台冷门新歌）",
            onClick = onSmartPlaylist
        )
        Spacer(Modifier.height(12.dp))
        OnlineEntryCard(
            icon = { Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            title = "在线搜索",
            subtitle = "网易云 / QQ / 酷狗 3 源聚合，试听 / 下载",
            onClick = onSearch
        )
    }
}

/** 在线功能入口卡片（圆角 + 图标 + 标题 + 说明 + 右箭头） */
@Composable
private fun OnlineEntryCard(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.07f))
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) { icon() }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
    }
}

/** 格式化时长：毫秒 → mm:ss */
private fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

/** 睡眠定时器弹窗 */
@Composable
private fun SleepTimerDialog(
    viewModel: MusicViewModel,
    onDismiss: () -> Unit
) {
    val remaining = viewModel.sleepTimerRemaining

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("睡眠定时器") },
        text = {
            Column {
                if (remaining > 0) {
                    Text(
                        "剩余 ${remaining / 60} 分 ${remaining % 60} 秒后自动暂停",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { viewModel.cancelSleepTimer() }) {
                        Text("取消定时", color = MaterialTheme.colorScheme.error)
                    }
                } else {
                    Text(
                        "设定时间后自动暂停播放",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(15, 30, 60).forEach { minutes ->
                        OutlinedButton(
                            onClick = {
                                viewModel.startSleepTimer(minutes)
                                onDismiss()
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("$minutes 分钟")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}
/** 关于弹窗（⚠️ 功能清单要随版本更新维护；版本号动态读取，不用改这里） */
@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    // 版本号动态读取（跟 build.gradle.kts 的 versionName 走）
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrDefault("?")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("关于 DDmusic") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("DDmusic", style = MaterialTheme.typography.titleMedium)
                Text(
                    "版本 $versionName",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // 联系作者：点击复制邮箱
                Text(
                    "联系作者： 244029088@qq.com（点击复制）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        clipboard.setText(AnnotatedString("244029088@qq.com"))
                        Toast.makeText(context, "邮箱已复制", Toast.LENGTH_SHORT).show()
                    }
                )
                Spacer(Modifier.height(4.dp))
                FeatureSection("🎵 本地音乐") {
                    "智能扫描手机音乐；封面 / 歌词自动获取；播放排行、收藏、歌单管理"
                }
                FeatureSection("▶️ 播放体验") {
                    "Fly 风格播放页：大封面、歌词页（拖动控制进度）、均衡器、播放模式切换（顺序 / 循环 / 乱序）、迷你播放条集成循环按钮"
                }
                FeatureSection("🌐 在线音乐") {
                    "网易云 / QQ / 酷狗 3 源搜索试听；多音质下载（标准 / 高品 / 无损）；下载自动保存封面与歌词；音源多平台兜底（主源失败自动换源修复）"
                }
                FeatureSection("🎤 听歌识曲") {
                    "原生录音识别（ACRCloud 曲库），识别到直接带歌名进搜索"
                }
                FeatureSection("📻 每日电台") {
                    "探索版（榜单 + 随机歌单混合）、熟悉版（相似曲目推荐）；音源检测只推荐能播的歌；每日更新大半新歌"
                }
                FeatureSection("✨ 智能歌单") {
                    "最常听（本地 + 在线合并统计）、冷门探索（平台冷门歌，非本地没听过，一键刷新换一批）"
                }
                FeatureSection("🧠 记忆持久化") {
                    "收藏 / 歌单 / 播放历史 / 搜索历史 / 循环模式 / 上次播放位置 / 主题模式 / 歌词偏移 全部跨重启保留"
                }
                FeatureSection("⚙️ 其他") {
                    "深色主题、睡眠定时、自定义封面、标签编辑、歌词微调、启动曲库秒显（缓存）；App 内双通道升级（蓝奏云 / GitHub）"
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("好的") }
        }
    )
}

/** 关于弹窗功能区块 */
@Composable
private fun FeatureSection(title: String, content: () -> String) {
    Column {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(2.dp))
        Text(
            content(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
