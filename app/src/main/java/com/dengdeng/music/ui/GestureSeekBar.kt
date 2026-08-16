package com.dengdeng.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 自定义手势进度条（播放页与迷你条共用）：
 * - 点击：直接跳到手指位置
 * - 滑动：拇指从当前播放位置出发，跟手左右移动（滑满条宽 = 整首歌时长），松手 seek
 * - 播放/暂停状态均可操作（手势只依赖 positionMs，不依赖播放状态）
 */
@Composable
fun GestureSeekBar(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onDragState: (dragging: Boolean, progress: Float) -> Unit = { _, _ -> },
    thumbColor: Color = Color.White,
    progressColor: Color = Color.White,
    trackColor: Color = Color.White.copy(alpha = 0.25f),
    thumbSize: Dp = 16.dp,
    barHeight: Dp = 4.dp
) {
    // rememberUpdatedState：手势闭包内始终读到最新的 position/duration（修复"跳一下"）
    val currentPos by rememberUpdatedState(positionMs)
    val currentDur by rememberUpdatedState(durationMs)

    var barWidthPx by remember { mutableStateOf(1f) }  // 进度条宽度（px）
    var dragStartMs by remember { mutableStateOf(0L) } // 滑动起始基准（按下时的播放位置）
    var dragDeltaPx by remember { mutableStateOf(0f) } // 累计位移（px）
    var isDragging by remember { mutableStateOf(false) }

    val displayProgress = if (isDragging && currentDur > 0) {
        val deltaMs = (dragDeltaPx / barWidthPx * currentDur).toLong()
        ((dragStartMs + deltaMs).toFloat() / currentDur).coerceIn(0f, 1f)
    } else if (currentDur > 0) {
        (currentPos.toFloat() / currentDur).coerceIn(0f, 1f)
    } else 0f

    val density = LocalDensity.current
    val thumbHalfPx = with(density) { thumbSize.toPx() / 2f }
    val touchSlopPx = with(density) { 6.dp.toPx() }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(32.dp)
            .onGloballyPositioned { barWidthPx = it.size.width.toFloat() }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val startX = down.position.x
                    var accumulated = 0f
                    var isDrag = false

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || change.changedToUp()) {
                            if (isDrag) {
                                // 滑动松手：相对 seek（基准 + 位移比例 * 时长）
                                val target = dragStartMs + (accumulated / barWidthPx * currentDur).toLong()
                                onSeek(target.coerceIn(0L, currentDur.coerceAtLeast(0L)))
                            } else {
                                // 点击松手：绝对跳转到手指位置
                                val ratio = (startX / barWidthPx).coerceIn(0f, 1f)
                                onSeek((ratio * currentDur).toLong())
                            }
                            isDragging = false
                            dragDeltaPx = 0f
                            onDragState(false, 0f)
                            break
                        }
                        if (change.positionChanged()) {
                            val newX = change.position.x
                            accumulated = newX - startX
                            if (!isDrag && abs(accumulated) > touchSlopPx) {
                                isDrag = true
                                // 基准 = 判定时刻最新位置 - 已累计位移（拇指从当前显示位置无缝衔接，避免闪跳）
                                dragStartMs = currentPos - (accumulated / barWidthPx * currentDur).toLong()
                            }
                            if (isDrag) {
                                dragDeltaPx = accumulated
                                isDragging = true
                                // 实时回调当前显示进度（供时间文字跟随）
                                val dp = if (currentDur > 0) {
                                    ((dragStartMs + (accumulated / barWidthPx * currentDur).toLong()).toFloat() / currentDur).coerceIn(0f, 1f)
                                } else 0f
                                onDragState(true, dp)
                                change.consume()
                            }
                        }
                    }
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        // 底轨
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight)
                .clip(RoundedCornerShape(barHeight / 2))
                .background(trackColor)
        )
        // 已播放高亮轨
        Box(
            modifier = Modifier
                .fillMaxWidth(displayProgress)
                .height(barHeight)
                .clip(RoundedCornerShape(barHeight / 2))
                .background(progressColor)
        )
        // 拇指（圆心对准进度位置）
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        x = (displayProgress * barWidthPx - thumbHalfPx).roundToInt(),
                        y = 0
                    )
                }
                .size(thumbSize)
                .clip(CircleShape)
                .background(thumbColor)
                .shadow(4.dp, CircleShape)
        )
    }
}
