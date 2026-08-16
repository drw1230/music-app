package com.dengdeng.music.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Album
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.platform.LocalContext
import com.dengdeng.music.data.AlbumGroup
import com.dengdeng.music.data.Playlist
import com.dengdeng.music.data.Song

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
    // 正在查看的专辑（null 表示专辑列表页）
    var viewingAlbum by remember { mutableStateOf<AlbumGroup?>(null) }
    // 联网搜索关键词（非 null 时显示网络搜索界面，覆盖曲库）
    var onlineSearchQuery by remember { mutableStateOf<String?>(null) }
    // 每日电台开关
    var showRadio by remember { mutableStateOf(false) }
    // 待添加到歌单的歌曲 ID（非 null 时显示选择弹窗）
    var songForPlaylist by remember { mutableStateOf<Long?>(null) }
    // 右上角更多菜单
    var menuExpanded by remember { mutableStateOf(false) }
    // 排序菜单
    var sortMenuExpanded by remember { mutableStateOf(false) }
    // 睡眠定时器弹窗
    var showSleepTimer by remember { mutableStateOf(false) }
    // 播放历史弹窗
    var showHistory by remember { mutableStateOf(false) }
    // 关于弹窗
    var showAbout by remember { mutableStateOf(false) }

    if (showPlayer) {
        // 播放页打开时，安卓返回键先关闭播放页回到曲库（再按返回键才退出 App）
        BackHandler { showPlayer = false }
        PlayerScreen(viewModel = viewModel, onClose = { showPlayer = false })
        return
    }

    Scaffold(
        topBar = {
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
                            // 播放历史&排行
                            DropdownMenuItem(
                                text = { Text("播放历史与排行") },
                                leadingIcon = { Icon(Icons.Default.History, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    showHistory = true
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
        },
        bottomBar = {
            if (viewModel.nowPlayingSong() != null) {
                    MiniPlayerBar(
                        viewModel,
                        // 在线试听统一用迷你条控制，不进全屏播放界面
                        onClick = { if (!viewModel.isOnlinePlaying) showPlayer = true }
                    )
            }
        }
    ) { padding ->
        when {
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
            !hasPermission -> PermissionHint(Modifier.padding(padding))
            viewModel.isLoading -> LoadingView(Modifier.padding(padding))
            viewModel.songs.isEmpty() -> EmptyView(Modifier.padding(padding))
            else -> {
                // Tab 栏 + 内容
                Column(Modifier.padding(padding)) {
                    // 歌单/专辑详情页显示返回 + 名称；否则显示 Tab
                    val isDetail = (selectedTab == 2 && viewingAlbum != null) ||
                            (selectedTab == 3 && viewingPlaylist != null)
                    if (isDetail) {
                        if (selectedTab == 2 && viewingAlbum != null) {
                            // 专辑详情头（返回 + 专辑名）
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(onClick = { viewingAlbum = null }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = viewingAlbum!!.name,
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${viewingAlbum!!.songs.size} 首歌曲",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        } else if (selectedTab == 3 && viewingPlaylist != null) {
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
                        2 -> if (viewingAlbum != null) {
                            AlbumDetail(
                                viewModel = viewModel,
                                album = viewingAlbum!!,
                                onAddToPlaylist = { songForPlaylist = it },
                                onDeleteSongs = onDeleteSongs
                            )
                        } else {
                            AlbumList(
                                viewModel = viewModel,
                                onOpen = { viewingAlbum = it }
                            )
                        }
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
                            onRadio = { showRadio = true }
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

    // 播放历史弹窗
    if (showHistory) {
        HistorySheet(
            viewModel = viewModel,
            onDismiss = { showHistory = false }
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
        listOf("全部", "喜欢", "专辑", "歌单").forEachIndexed { index, label ->
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
    onRadio: () -> Unit
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
                onShuffleAll = { viewModel.shufflePlay() },
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

/** 添加到歌单的底部弹窗（由 MainScreen 的状态控制显示） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddToPlaylistSheet(
    viewModel: MusicViewModel,
    songId: Long,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(
                "添加到歌单",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
            )
            if (viewModel.playlists.isEmpty()) {
                Text(
                    "还没有歌单，去歌单 Tab 新建一个吧",
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
        }
    }
}

/** 曲库头部统计 */
@Composable
private fun LibraryHeader(
    songCount: Int,
    totalMinutes: Long,
    onPlayAll: () -> Unit,
    onShuffleAll: () -> Unit,
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
                onClick = onShuffleAll,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 8.dp)
            ) {
                Icon(Icons.Default.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("随机播放", style = MaterialTheme.typography.labelLarge)
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

                if (!isOnline) {
                    IconButton(onClick = { viewModel.previous() }) {
                        Icon(
                            Icons.Default.SkipPrevious,
                            contentDescription = "上一首",
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
                IconButton(onClick = { viewModel.togglePlayPause() }) {
                    Icon(
                        imageVector = if (viewModel.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (viewModel.isPlaying) "暂停" else "播放",
                        modifier = Modifier.size(28.dp)
                    )
                }
                if (!isOnline) {
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

/** 播放历史 + 排行弹窗 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistorySheet(
    viewModel: MusicViewModel,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(
                "最近播放",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
            val recent = viewModel.recentSongs
            if (recent.isEmpty()) {
                Text(
                    "还没有播放记录",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )
            } else {
                recent.take(20).forEachIndexed { index, song ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.playSongs(recent, index)
                                onDismiss()
                            }
                            .padding(horizontal = 20.dp, vertical = 8.dp),
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
                                song.title,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                song.artist,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        // 播放次数
                        val count = viewModel.playHistory[song.id] ?: 0
                        Text(
                            "$count 次",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))

            Text(
                "播放排行",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
            val top = viewModel.topPlayedSongs
            if (top.isEmpty()) {
                Text(
                    "暂无排行数据",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )
            } else {
                top.take(10).forEachIndexed { index, (song, count) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.playSongs(top.map { it.first }, index)
                                onDismiss()
                            }
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${index + 1}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (index < 3) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(28.dp)
                        )
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
                                song.title,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                song.artist,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            "$count 次",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/** 关于弹窗 */
@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("关于 DDmusic") },
        text = {
            Column {
                Text("DDmusic", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "版本 0.3.2",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "本地音乐播放器\n• 智能扫描本地音乐\n• 我的歌单 / 喜欢\n• 睡眠定时 / 播放排行\n• 深色主题",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("好的") }
        }
    )
}
