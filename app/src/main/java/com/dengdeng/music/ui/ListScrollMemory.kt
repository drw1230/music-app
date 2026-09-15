package com.dengdeng.music.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow

/**
 * 记住 / 恢复 LazyColumn 的滚动位置（跨"界面离开组合树"存活）。
 *
 * 【为什么需要】`MainScreen` 打开播放页用的是 early-return 全屏接管
 * （`if (showPlayer) { PlayerScreen; return }`）→ 覆盖界面（电台/智能歌单）会**离开组合树**，
 * `rememberLazyListState` 的内部状态随之丢失 → 从播放页返回时列表滚回顶部。
 *
 * 【用法】把位置存在 ViewModel 的**普通字段**（不要用 `mutableStateOf`）里：
 * ```
 * val st = rememberPersistentListState(
 *     key = viewModel.radioSessionSongs,          // key 变化（重新生成歌单）→ 滚动位置归零
 *     initialIndex = viewModel.radioScrollIndex,  // 普通 var，读它不会产生重组订阅
 *     initialOffset = viewModel.radioScrollOffset
 * ) { i, o -> viewModel.radioScrollIndex = i; viewModel.radioScrollOffset = o }
 * LazyColumn(state = st) { ... }
 * ```
 *
 * ⚠️ `initialIndex` 千万别传 `mutableStateOf` 字段：那会在滚动时每帧触发整个界面重组（实测卡顿套路）。
 *
 * @param key 列表"代次"标识：同一次生成的歌单内滚动位置保留；重新生成（换一批歌）时归零
 */
@Composable
fun rememberPersistentListState(
    key: Any?,
    initialIndex: Int,
    initialOffset: Int,
    save: (Int, Int) -> Unit
): LazyListState {
    // key 变化 = 列表被重新生成 → 用（已归零的）初始位置重建，避免"刷新后停在列表中间"
    val state = remember(key) { LazyListState(initialIndex, initialOffset) }
    // 常规路径：滚动过程中持续记录
    LaunchedEffect(state) {
        snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
            .collect { (i, o) -> save(i, o) }
    }
    // 兜底路径：界面被销毁（打开播放页）那一刻再存一次，避免最后一次滚动没来得及记录
    DisposableEffect(state) {
        onDispose { save(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset) }
    }
    return state
}
