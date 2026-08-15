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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import com.dengdeng.music.data.Playlist
import com.dengdeng.music.data.Song

/**
 * 主界面 —— Tab 切换（全部/收藏/歌单）+ 歌曲列表 + 底部迷你播放条
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MusicViewModel, hasPermission: Boolean) {
    // 是否显示全屏播放页
    var showPlayer by remember { mutableStateOf(false) }
    // 当前 Tab：0=全部 1=收藏 2=歌单
    var selectedTab by remember { mutableStateOf(0) }
    // 正在查看的歌单（null 表示歌单列表页）
    var viewingPlaylist by remember { mutableStateOf<Playlist?>(null) }
    // 待添加到歌单的歌曲 ID（非 null 时显示选择弹窗）
    var songForPlaylist by remember { mutableStateOf<Long?>(null) }
    // 长按选中的歌曲（非 null 时显示操作菜单）
    var songMenu by remember { mutableStateOf<Song?>(null) }

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
            else -> {
                // Tab 栏 + 内容
                Column(Modifier.padding(padding)) {
                    // 歌单详情页显示返回 + 歌单名；否则显示 Tab
                    if (selectedTab == 2 && viewingPlaylist != null) {
                        PlaylistDetailHeader(
                            playlist = viewingPlaylist!!,
                            songCount = viewModel.songsOfPlaylist(viewingPlaylist!!.id).size,
                            onBack = { viewingPlaylist = null },
                            onDelete = {
                                viewModel.deletePlaylist(viewingPlaylist!!.id)
                                viewingPlaylist = null
                            }
                        )
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
                            onSongLongPress = { songMenu = it }
                        )
                        2 -> if (viewingPlaylist != null) {
                            PlaylistDetail(
                                viewModel = viewModel,
                                playlist = viewingPlaylist!!,
                                onAddToPlaylist = { songForPlaylist = it },
                                onSongLongPress = { songMenu = it }
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
                            onSongLongPress = { songMenu = it }
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

                // 长按歌曲操作菜单
                songMenu?.let { song ->
                    SongActionSheet(
                        viewModel = viewModel,
                        song = song,
                        onDismiss = { songMenu = null },
                        onAddToPlaylist = {
                            songMenu = null
                            songForPlaylist = song.id
                        }
                    )
                }
            }
        }
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
        listOf("全部", "喜欢", "歌单").forEachIndexed { index, label ->
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

/** 全部歌曲列表（带搜索框） */
@Composable
private fun SongList(
    viewModel: MusicViewModel,
    onAddToPlaylist: (Long) -> Unit,
    onSongLongPress: (Song) -> Unit
) {
    val songs = viewModel.filteredSongs
    val totalDuration = songs.sumOf { it.durationMs }
    val totalMinutes = totalDuration / 60_000

    // 搜索框状态
    var query by remember { mutableStateOf("") }
    // 同步到 ViewModel（用于播放时保持过滤）
    androidx.compose.runtime.SideEffect { viewModel.searchQuery = query }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        item {
            // 搜索框
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("搜索歌曲、艺术家、专辑") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            )
        }
        item {
            LibraryHeader(
                songCount = songs.size,
                totalMinutes = totalMinutes,
                onPlayAll = { viewModel.playSong(0) },
                onShuffleAll = { viewModel.shufflePlay() }
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
                song = song,
                index = index,
                isCurrent = index == viewModel.currentIndex,
                isPlaying = viewModel.isPlaying && index == viewModel.currentIndex,
                isFavorite = viewModel.isFavorite(song.id),
                onClick = { viewModel.playSong(index) },
                onLongPress = { onSongLongPress(song) },
                onAddToPlaylist = { onAddToPlaylist(song.id) }
            )
        }
    }
}

/** 喜欢歌曲列表 */
@Composable
private fun FavoriteList(
    viewModel: MusicViewModel,
    onAddToPlaylist: (Long) -> Unit,
    onSongLongPress: (Song) -> Unit
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
                song = song,
                index = index,
                isCurrent = song.id == viewModel.currentSong()?.id,
                isPlaying = viewModel.isPlaying && song.id == viewModel.currentSong()?.id,
                isFavorite = true,
                onClick = { viewModel.playSongs(favorites, index) },
                onLongPress = { onSongLongPress(song) },
                onAddToPlaylist = { onAddToPlaylist(song.id) }
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
    onSongLongPress: (Song) -> Unit
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
                song = song,
                index = index,
                isCurrent = song.id == viewModel.currentSong()?.id,
                isPlaying = viewModel.isPlaying && song.id == viewModel.currentSong()?.id,
                isFavorite = viewModel.isFavorite(song.id),
                onClick = { viewModel.playSongs(songs, index) },
                onLongPress = { onSongLongPress(song) },
                onAddToPlaylist = { onAddToPlaylist(song.id) }
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

/** 长按歌曲弹出的操作菜单（参考主流音乐 App） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SongActionSheet(
    viewModel: MusicViewModel,
    song: Song,
    onDismiss: () -> Unit,
    onAddToPlaylist: () -> Unit
) {
    val isLiked = viewModel.isFavorite(song.id)

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            // 歌曲信息头
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(song.albumArtUri ?: song.uri)
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(10.dp))
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = song.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = song.artist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))

            // 菜单项：播放
            SongActionItem(
                icon = { Icon(Icons.Default.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                label = "播放",
                onClick = {
                    val idx = viewModel.songs.indexOfFirst { it.id == song.id }
                    if (idx >= 0) viewModel.playSong(idx)
                    onDismiss()
                }
            )
            // 菜单项：下一首播放
            SongActionItem(
                icon = { Icon(Icons.Default.SkipNext, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                label = "下一首播放",
                onClick = {
                    viewModel.playNext(song.id)
                    onDismiss()
                }
            )
            // 菜单项：喜欢 / 取消喜欢
            SongActionItem(
                icon = {
                    Icon(
                        if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = null,
                        tint = if (isLiked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                },
                label = if (isLiked) "取消喜欢" else "喜欢",
                onClick = {
                    viewModel.toggleFavorite(song.id)
                    onDismiss()
                }
            )
            // 菜单项：添加到歌单
            SongActionItem(
                icon = { Icon(Icons.Default.PlaylistAdd, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                label = "添加到歌单",
                onClick = onAddToPlaylist
            )
        }
    }
}

/** 操作菜单单项：图标 + 文字，整行可点 */
@Composable
private fun SongActionItem(
    icon: @Composable () -> Unit,
    label: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        Spacer(Modifier.width(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

/** 曲库头部统计 */
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
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onPlayAll,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text("全部播放")
            }
            OutlinedButton(
                onClick = onShuffleAll,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Shuffle, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text("随机播放")
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

/** 单行歌曲（支持点击播放 + 长按弹出操作菜单） */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SongRow(
    song: Song,
    index: Int,
    isCurrent: Boolean,
    isPlaying: Boolean,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onAddToPlaylist: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongPress
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

        Spacer(Modifier.width(4.dp))
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
    val song = viewModel.currentSong() ?: return

    val progress = if (viewModel.durationMs > 0) {
        (viewModel.currentPositionMs.toFloat() / viewModel.durationMs).coerceIn(0f, 1f)
    } else 0f

    // 拖动中的进度（null 表示未拖动）
    var dragProgress by remember { mutableStateOf<Float?>(null) }
    val displayProgress = dragProgress ?: progress

    Surface(
        tonalElevation = 3.dp,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column {
            // 可拖动的进度条（点击/拖动 seek）
            Slider(
                value = displayProgress,
                onValueChange = { dragProgress = it },
                onValueChangeFinished = {
                    dragProgress?.let {
                        viewModel.seekTo((it * viewModel.durationMs).toLong())
                    }
                    dragProgress = null
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .padding(horizontal = 8.dp),
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                )
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
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
