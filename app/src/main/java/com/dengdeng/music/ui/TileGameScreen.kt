package com.dengdeng.music.ui

import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.AudioAttributes as M3AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.dengdeng.music.data.ChartAnalyzer
import com.dengdeng.music.data.Song
import com.dengdeng.music.data.TileStore
import com.dengdeng.music.data.WhackMoleStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.sin

/**
 * 别踩白块（听歌模式）—— Batch 1
 *
 * 玩法：4 条轨道，黑块随歌曲节拍下落，落到判定线时点掉。
 * 三种模式（用户 2026-09-14 拍板）：
 * - 经典：前 50 块，比完成用时（用时受歌曲本身影响，同歌对比更有意义；另记平均偏差）
 * - 街机：整首歌冲最高分
 * - 接力：每 50 块为一段，段内零失误即"接上"→ 恢复 1 次失误额度、连击倍率加成；成绩 = 最长连续通过段数
 * 特殊块（用户 2026-09-14 追加）：
 * - **长按块**：长条，按住到条尾才算完成（松手在最后 140ms 内仍算按满）；中途松手 = 断，记一次失误
 * - **双押块**：并排两块（中间一条亮线），要两根手指同时按
 * 规则：允许 3 次失误（漏块 / 踩白块 / 长按断），连续零失误打满 30 块回 1 次机会（额度上限始终 3）
 * 美术：像素风；方块颜色跟随 App 主题色（亮背景压深成深色块，暗背景提亮，保证对比度）
 *
 * 待做（Batch 3）：分模式联网排行榜、背景接 MoleArt 场景图、打地鼠主题化
 */

// ════════════════════════════ 模式 ════════════════════════════

internal enum class TileMode(val id: Int, val label: String, val rule: String) {
    CLASSIC(0, "经典", "打完本局方块 · 比完成用时（最多 50 块）"),
    ARCADE(1, "街机", "本局片段 · 冲最高分"),
    RELAY(2, "接力", "每 50 块一段 · 零失误接上，成绩看连续段数")
}

/**
 * 体验时长分档（用户 2026-09-15："原歌曲的太长了，像打地鼠那样分时长模式"）。
 *
 * 口径与打地鼠完全一致（`WhackMoleScreen` 的 35s / 120s / 整首）：
 * 选完歌弹窗选档 → `ChartAnalyzer.pickWindow` 取能量最高的连续片段 → 只玩这一段。
 * `targetMs = 0` 表示整首（不做窗口截取）。
 */
internal enum class TileDuration(val label: String, val desc: String, val targetMs: Long) {
    QUICK("⚡ 快速模式（推荐）", "只玩 30~40 秒精彩片段", 35_000L),
    NORMAL("🎮 正常模式", "约 2 分钟的精华段落", 120_000L),
    FULL("🎧 完整模式", "跟着整首歌打到底", 0L)
}

// ════════════════════════════ 谱面 → 方块 ════════════════════════════

/**
 * 一个下落方块。
 * - `holdMs > 0` → **长按块**：按住到 `timeMs + holdMs` 才算完成，中途松手算断
 * - `pairLane >= 0` → **双押块**：与 `pairLane` 轨道上**同一时刻**的方块凑成一对，要同时按
 */
internal data class TileNote(
    val timeMs: Long,
    val lane: Int,
    val holdMs: Long = 0L,
    val pairLane: Int = -1
)

internal enum class NoteState { PENDING, HOLDING, PERFECT, GREAT, GOOD, MISS, EMPTY_TAP }

// ── 长按块 / 双押块的生成参数（用户 2026-09-14 拍板做这两个玩法）──
/** 后方空档 ≥ 此值才值得按住（长按块占住空档） */
private const val HOLD_MIN_GAP_MS = 1400L
/** 长按实际时长：空档 - 500ms，再夹到这个区间 */
private const val HOLD_MIN_MS = 900L
private const val HOLD_MAX_MS = 2200L
/** 两个长按块至少隔这么久（否则一段里全是长按，累手） */
private const val HOLD_SPACING_MS = 15_000L
/** 双押块：前后都要够空（孤立重拍）才并排加一块 */
private const val DOUBLE_GAP_MS = 520L
/** 两个双押至少隔这么久 */
private const val DOUBLE_SPACING_MS = 9_000L
/** 孤立重拍变成双押的概率（不是每个都变，避免套路化） */
private const val DOUBLE_CHANCE = 0.55f

/**
 * 由打地鼠同一套谱面（onset 锚定）生成方块，结果是确定性的（同歌同结果）：
 * - 连打（相邻峰间隔 < 350ms）→ 走相邻轨道"跑动"，像音阶爬升
 * - 稀疏（间隔 ≥ 350ms）→ 随机换轨（不与上一块同轨）
 * - **长按块**：后面有大空档（≥1.4s）的节拍 → 按住约 0.9~2.2s（空档越长得越长），
 *   且两个长按块至少隔 15s
 * - **双押块**：前后都够空（≥0.52s）的孤立重拍 → 跨 2 条轨道以上并排再补一块（一眼看得出），
 *   至少隔 9s，且只有 55% 概率变双押（不套路化）
 *
 * ⚠️ 改动这里的规则会让同歌谱面变化（缓存 key 不含规则版本，见 PROJECT_STATUS 的注意事项）
 */
internal fun buildTiles(chart: ChartAnalyzer.Chart): List<TileNote> {
    val peaks = chart.events
        .filter { it.type == ChartAnalyzer.TYPE_NORMAL || it.type == ChartAnalyzer.TYPE_BONUS }
        .sortedBy { it.timeMs }
    if (peaks.isEmpty()) return emptyList()
    val rnd = java.util.Random(chart.durationMs * 7L + peaks.size * 31L)
    val out = ArrayList<TileNote>(peaks.size + peaks.size / 8)
    var lastLane = 1
    var dir = 1
    var lastT = -10_000L
    var lastHoldAt = -1_000_000L
    var lastDoubleAt = -1_000_000L
    for (i in peaks.indices) {
        val e = peaks[i]
        val gap = e.timeMs - lastT
        val lane = if (gap < 350L) {
            var l = lastLane + dir
            if (l !in 0..3) {
                dir = -dir
                l = lastLane + dir
            }
            if (l !in 0..3) l = (0..3).first { it != lastLane }
            l
        } else {
            dir = if (rnd.nextBoolean()) 1 else -1
            var l = rnd.nextInt(4)
            if (l == lastLane) l = (l + 1 + rnd.nextInt(3)) % 4
            l
        }
        // 下一拍的间隔（最后一拍没有下一拍 → 永不做长按/双押）
        val gapNext = if (i + 1 < peaks.size) peaks[i + 1].timeMs - e.timeMs else Long.MAX_VALUE

        val holdMs = if (gapNext in HOLD_MIN_GAP_MS..(HOLD_MIN_GAP_MS * 8) &&
            e.timeMs - lastHoldAt >= HOLD_SPACING_MS
        ) {
            lastHoldAt = e.timeMs
            (gapNext - 500L).coerceIn(HOLD_MIN_MS, HOLD_MAX_MS)
        } else {
            0L
        }

        val asDouble = holdMs == 0L &&
            gap >= DOUBLE_GAP_MS && gapNext >= DOUBLE_GAP_MS &&
            e.timeMs - lastDoubleAt >= DOUBLE_SPACING_MS &&
            rnd.nextFloat() < DOUBLE_CHANCE

        if (asDouble) {
            val lane2 = doubleLane(lane, rnd)
            out.add(TileNote(e.timeMs, lane, 0L, lane2))
            out.add(TileNote(e.timeMs, lane2, 0L, lane))
            lastDoubleAt = e.timeMs
        } else {
            out.add(TileNote(e.timeMs, lane, holdMs))
        }
        lastLane = lane
        lastT = e.timeMs
    }
    return out
}

/** 双押的另一条轨道：优先隔 2 条轨道（视觉上一眼看出是两个），实在不行取任意异轨 */
private fun doubleLane(lane: Int, rnd: java.util.Random): Int {
    val far = (0..3).filter { abs(it - lane) >= 2 }
    return if (far.isNotEmpty()) far[rnd.nextInt(far.size)] else (0..3).first { it != lane }
}

// ════════════════════════════ 引擎（纯逻辑） ════════════════════════════

internal class TileEngine(
    chart: ChartAnalyzer.Chart,
    private val startMs: Long,
    private val endMs: Long,
    private val mode: TileMode
) {
    companion object {
        const val PERFECT_MS = 80L
        const val GREAT_MS = 150L
        const val GOOD_MS = 220L
        const val MAX_MISS = 3
        const val CLASSIC_TILES = 50
        const val SEGMENT_TILES = 50

        /** 长按块：在这个宽限内松手仍算按满 */
        const val HOLD_RELEASE_GRACE_MS = 140L
        /** 长按按满的额外奖励分（基础分之外） */
        const val HOLD_BONUS = 60L
        /**
         * 失误额度恢复节奏（用户 2026-09-14："打到多少之后可以回一次机会，总数不超过三次"）。
         * 定 **30 块**：经典一局只有 50 块（最多回 1 次，不会让经典失去压力）；
         * 街机一首 4 分钟约 250 块（理论最多 8 次，但**必须连续零失误**才累计，
         * 断一次就从头数 —— 既给容错又不放水）。20 太松（街机可回 12 次）、50 太紧（经典等于拿不到）。
         * 想改用 `RECOVER_TILES`；想改成"按分数恢复"就在这里加个分数阈值。
         */
        const val RECOVER_TILES = 30

        /**
         * 窗口起点之后留出的下落可见时间（2026-09-15，配合「体验时长分档」）。
         * 方块只有在落进屏幕后才看得见（可见时间 ≈ speedMs，最快档 620→200ms），
         * 若窗口第一块紧贴起点 → 玩家**看不见就被判漏**。所以从窗口中途开始时
         * 忽略前 1.5s 的方块（完整模式起点为 0，保持原样不动）。
         */
        const val LEAD_MS = 1500L
    }

    /** 本局窗口时长（速度递增按它算进度；窗口 = 完整时长时等于整首） */
    val windowMs: Long get() = (endMs - startMs).coerceAtLeast(1L)

    // 从窗口中途开始时跳过开头 1.5s 的方块（见 LEAD_MS）
    private val noteFromMs = if (startMs > 0L) startMs + LEAD_MS else startMs

    /**
     * 本局要打的方块。
     * - 经典模式只取前 50 块 → `notes.size` 就是本局目标块数（窗口短时可能不足 50）
     * - 其余模式取窗口内全部
     */
    val notes: List<TileNote> = buildTiles(chart)
        .filter { it.timeMs >= noteFromMs && it.timeMs <= endMs }
        .let { if (mode == TileMode.CLASSIC) it.take(CLASSIC_TILES) else it }

    /**
     * 接力模式的"一段"块数。
     * 短窗口（快速档 35s 只有 ~45 块）不足 50 块时按实际块数算 —— 否则一段永远接不上，
     * 接力成绩恒为 0（2026-09-15 上时长分档时发现并处理）。
     */
    private val segmentTiles: Int =
        if (mode == TileMode.RELAY) minOf(SEGMENT_TILES, notes.size).coerceAtLeast(1)
        else SEGMENT_TILES

    private val states = Array(notes.size) { mutableStateOf(NoteState.PENDING) }
    private val judgedAt = LongArray(notes.size) { -1L }
    /** 长按块的结束时刻 */
    private val holdEnds = LongArray(notes.size) { notes[it].timeMs + notes[it].holdMs }
    /** 每条轨道上正在被按住的长按块下标（-1 = 没有） */
    private val holdLanes = IntArray(4) { -1 }
    /** 长按"咬住"时头部的判定档位（按满时用它结算，避免头部先给分、断了又扣） */
    private val holdHead = arrayOfNulls<NoteState>(notes.size)
    private var hitSinceRecover = 0

    var now by mutableStateOf(startMs); private set
    var finished by mutableStateOf(false); private set
    var failed by mutableStateOf(false); private set          // 3 次失误用完
    var score by mutableStateOf(0L); private set
    var combo by mutableStateOf(0); private set
    var maxCombo = 0; private set
    var perfect = 0; private set
    var great = 0; private set
    var good = 0; private set
    var hitTiles = 0; private set
    var missCount = 0; private set                            // 0..3（失误额度）
    var recoveredCount = 0; private set                       // 本局已恢复过的机会次数
    /** 距离下一次恢复还差多少块（UI 显示"再连打 N 块回 1 次机会"） */
    var hitToRecover = RECOVER_TILES; private set
    var relayStreak = 0; private set                          // 接力：当前连续段数
    var relayMaxStreak = 0; private set
    var relaySegments = 0; private set                        // 接力：已走完的段数（含失败段）
    var banner by mutableStateOf(""); private set             // 短暂提示（接上 / 失误）
    // bannerAt 也做成状态：提示条只订阅它就能在**每次** showBanner 时刷新
    // （banner 文案连续两次相同时字符串相等 → 不会触发状态变更，光靠 banner 会漏刷新）
    var bannerAt by mutableStateOf(0L); private set
    private var bannerId = 0

    private var firstHitMs = -1L
    private var lastHitMs = -1L
    private var sumAbsDevMs = 0L
    private var devCount = 0
    private var segMissed = false
    private var segProgress = 0                              // 本段已结算块数
    private var resolved = 0

    val totalNotes: Int get() = notes.size
    val missLeft: Int get() = (MAX_MISS - missCount).coerceAtLeast(0)
    /** 经典模式进度（已命中块数 / 50） */
    val classicProgress: Int get() = hitTiles
    /** 用时（经典：第一块命中 → 最后一块命中） */
    val usedMs: Long get() = if (firstHitMs > 0 && lastHitMs > 0) lastHitMs - firstHitMs else 0L
    /** 平均偏差（越接近 0 越准；与歌曲无关的硬指标） */
    val avgDevMs: Long get() = if (devCount == 0) 0L else sumAbsDevMs / devCount

    fun stateOf(i: Int): NoteState = states[i].value
    fun judgedAtOf(i: Int): Long = judgedAt[i]

    private fun showBanner(text: String) {
        banner = text
        bannerAt = SystemClock.elapsedRealtime()
        bannerId++
    }

    private fun comboMultiplier(): Double = when {
        combo >= 200 -> 3.0
        combo >= 100 -> 2.0
        combo >= 50 -> 1.5
        else -> 1.0
    }

    private fun miss(idx: Int, reason: NoteState) {
        if (states[idx].value != NoteState.PENDING) return
        states[idx].value = reason
        judgedAt[idx] = SystemClock.elapsedRealtime()
        combo = 0
        missCount++
        segMissed = true
        // 恢复机会要求"连续零失误"：断一次就从 0 重新数
        hitSinceRecover = 0
        hitToRecover = RECOVER_TILES
        showBanner(if (reason == NoteState.EMPTY_TAP) "踩白块！" else "漏了！")
        resolved++
        segProgress++
        if (missCount >= MAX_MISS) {
            failed = true
            finished = true
        }
        advanceSegment()
    }

    /**
     * 每命中一块都要过这里：连续零失误满 [RECOVER_TILES] 块 → 回 1 次失误额度。
     * 满 3 次时不需要回（有缺口才回），所以不会"记账白费"。
     */
    private fun noteHitForRecover() {
        hitSinceRecover++
        hitToRecover = RECOVER_TILES - hitSinceRecover
        if (hitSinceRecover < RECOVER_TILES) return
        hitSinceRecover = 0
        hitToRecover = RECOVER_TILES
        if (missCount > 0) {
            missCount--
            recoveredCount++
            showBanner("零失误 $RECOVER_TILES 块 · 回 1 次机会")
        }
    }

    /** 接力：每段结束结算（零失误 → 接上，恢复 1 次失误额度） */
    private fun advanceSegment() {
        if (mode != TileMode.RELAY) return
        if (segProgress < segmentTiles) return
        relaySegments++
        segProgress = 0
        if (!segMissed) {
            relayStreak++
            if (relayStreak > relayMaxStreak) relayMaxStreak = relayStreak
            if (missCount > 0) missCount--
            showBanner("接上！+1 容错")
        } else {
            relayStreak = 0
        }
        segMissed = false
    }

    /** 每帧调用；nowMs = 播放位置 - 延迟校准 */
    fun tick(nowMs: Long) {
        if (finished) return
        now = nowMs
        // 长按块：按满尾点就结算（手指还按着；提前松手由 releaseLane 判断）
        for (lane in 0..3) {
            val i = holdLanes[lane]
            if (i >= 0 && nowMs >= holdEnds[i]) completeHold(i, lane)
        }
        for (i in 0 until notes.size) {
            if (states[i].value != NoteState.PENDING) continue
            val t = notes[i].timeMs
            if (t - GOOD_MS > nowMs) break
            if (nowMs > t + GOOD_MS) miss(i, NoteState.MISS)
        }
        if (mode == TileMode.CLASSIC) {
            // 目标块数 = notes.size（窗口短时可能不足 50 块 → 打完本局全部方块即通关；
            // 用 CLASSIC_TILES 会让短窗口永远不结算）
            if (notes.isNotEmpty() && hitTiles >= notes.size) finished = true
        } else if (notes.isEmpty() || resolved >= notes.size) {
            finished = true
        }
    }

    /** 手指抬起。只有长按块在乎这个 —— 松手在尾点前 [HOLD_RELEASE_GRACE_MS] 内仍算按满 */
    fun releaseLane(lane: Int, tapMs: Long) {
        val i = holdLanes[lane]
        if (i < 0) return
        if (tapMs >= holdEnds[i] - HOLD_RELEASE_GRACE_MS) completeHold(i, lane)
        else breakHold(i, lane)
    }

    /** 长按按满：分数/连击/命中数都在这一刻结算（头部只"咬住"，不给分） */
    private fun completeHold(i: Int, lane: Int) {
        holdLanes[lane] = -1
        if (states[i].value != NoteState.HOLDING) return
        val st = holdHead[i] ?: NoteState.GOOD
        holdHead[i] = null
        judgedAt[i] = SystemClock.elapsedRealtime()
        states[i].value = st
        combo++
        if (combo > maxCombo) maxCombo = combo
        val base = when (st) {
            NoteState.PERFECT -> 100
            NoteState.GREAT -> 80
            else -> 50
        }
        score += (base * comboMultiplier()).toLong() + HOLD_BONUS
        hitTiles++
        when (st) {
            NoteState.PERFECT -> perfect++
            NoteState.GREAT -> great++
            else -> good++
        }
        resolved++
        segProgress++
        if (firstHitMs < 0) firstHitMs = notes[i].timeMs
        lastHitMs = holdEnds[i]
        noteHitForRecover()
        if (mode == TileMode.CLASSIC && hitTiles >= notes.size) finished = true
        advanceSegment()
    }

    /** 长按中途松手 → 断，记一次失误 */
    private fun breakHold(i: Int, lane: Int) {
        holdLanes[lane] = -1
        if (states[i].value != NoteState.HOLDING) return
        holdHead[i] = null
        states[i].value = NoteState.MISS
        judgedAt[i] = SystemClock.elapsedRealtime()
        combo = 0
        missCount++
        segMissed = true
        hitSinceRecover = 0
        hitToRecover = RECOVER_TILES
        showBanner(if (missCount >= MAX_MISS) "长按断了！失误用尽" else "长按断了！")
        resolved++
        segProgress++
        if (missCount >= MAX_MISS) {
            failed = true
            finished = true
        }
        advanceSegment()
    }

    /** 按下某条轨道。@return (判定档位, 得分/0, 是否失误) */
    fun tap(lane: Int, tapMs: Long): Triple<NoteState, Long, Boolean> {
        if (finished) return Triple(NoteState.PENDING, 0L, false)
        var bestIdx = -1
        var bestDev = Long.MAX_VALUE
        for (i in 0 until notes.size) {
            val n = notes[i]
            if (n.lane != lane || states[i].value != NoteState.PENDING) continue
            val dev = abs(n.timeMs - tapMs)
            if (dev <= GOOD_MS && dev < bestDev) {
                bestDev = dev
                bestIdx = i
            }
        }
        if (bestIdx < 0) {
            // 踩白块（空点）→ 记一次失误
            missCount++
            combo = 0
            segMissed = true
            hitSinceRecover = 0
            hitToRecover = RECOVER_TILES
            showBanner(if (missCount >= MAX_MISS) "踩白块！失误用尽" else "踩白块！")
            if (missCount >= MAX_MISS) {
                failed = true
                finished = true
            }
            return Triple(NoteState.EMPTY_TAP, 0L, true)
        }
        val st = when {
            bestDev <= PERFECT_MS -> NoteState.PERFECT
            bestDev <= GREAT_MS -> NoteState.GREAT
            else -> NoteState.GOOD
        }
        if (notes[bestIdx].holdMs > 0L) {
            // 长按块：头部先"咬住"，分数/连击等按满再结算（断了就不给分，避免先给后扣）
            states[bestIdx].value = NoteState.HOLDING
            holdHead[bestIdx] = st
            holdLanes[lane] = bestIdx
            judgedAt[bestIdx] = SystemClock.elapsedRealtime()
            return Triple(st, 0L, false)
        }
        states[bestIdx].value = st
        judgedAt[bestIdx] = SystemClock.elapsedRealtime()
        combo++
        if (combo > maxCombo) maxCombo = combo
        val base = when (st) {
            NoteState.PERFECT -> 100
            NoteState.GREAT -> 80
            else -> 50
        }
        val pts = (base * comboMultiplier()).toLong()
        score += pts
        hitTiles++
        when (st) {
            NoteState.PERFECT -> perfect++
            NoteState.GREAT -> great++
            else -> good++
        }
        sumAbsDevMs += bestDev
        devCount++
        if (firstHitMs < 0) firstHitMs = tapMs
        lastHitMs = tapMs
        resolved++
        segProgress++
        noteHitForRecover()
        if (mode == TileMode.CLASSIC && hitTiles >= notes.size) finished = true
        advanceSegment()
        return Triple(st, pts, false)
    }

    /** 该方块此刻是否正被按住（绘制用） */
    fun isHolding(i: Int): Boolean = states[i].value == NoteState.HOLDING
}

// ════════════════════════════ 主题色取色 ════════════════════════════

/** 一套与 App 主题色相近、且保证对比度的游戏配色 */
internal class TilePalette(
    val bgTop: Color,        // 渐变底：上端（主题色淡调）
    val bgBottom: Color,     // 渐变底：下端（比上端略深，白线才看得出来）
    val star: Color,         // 背景小星星（主题色深调，低透明度使用）
    val block: Color,        // 方块主色（经典纯黑）
    val blockTop: Color,     // 兼容字段（经典版与 block 同色）
    val blockBottom: Color,  // 兼容字段（经典版与 block 同色）
    val laneLine: Color,     // 轨道分隔线（白色）
    val hitLine: Color,      // 主题色（UI 强调用，不画在场地里）
    val flash: Color,        // 命中闪光
    val good: Color,         // 完美/精彩/良好文字色
    val bad: Color           // 失误色
)

/**
 * 背景小星星表（确定性生成一次，运行时只按时间算位置/亮度 → 不会每帧乱跳）。
 * 每颗 7 个数：x(0..1) y(0..1) 边长(屏宽比例) 相位 闪烁速度 上浮速度 基础亮度
 *
 * ⚠️ 2026-09-14 返工：旧版边长写死 2~4 **像素**（1440 宽屏上只有 0.2mm）、亮度上限 0.45、
 * 闪烁周期 9~35 秒 → 用户反馈"背景没有动态效果"。现在边长按屏宽比例算（≈6~17px）、
 * 亮度上限 0.78、闪烁周期 1.4~3.5s、上浮 60~200px/s，肉眼一眼能看出在动。
 */
private const val TILE_STAR_COUNT = 40

private val TILE_STARS: FloatArray = run {
    val rnd = java.util.Random(20260914L)
    FloatArray(TILE_STAR_COUNT * 7) { i ->
        when (i % 7) {
            0 -> rnd.nextFloat()                          // x
            1 -> rnd.nextFloat()                          // y
            2 -> 0.0045f + rnd.nextFloat() * 0.0075f      // 边长：屏宽 0.45%~1.2%
            3 -> rnd.nextFloat() * 6.2832f                // 相位
            4 -> 0.9f + rnd.nextFloat() * 2.4f            // 闪烁速度（周期 ≈1.4~3.5s）
            5 -> 0.020f + rnd.nextFloat() * 0.045f        // 上浮速度（屏高/秒）
            else -> 0.30f + rnd.nextFloat() * 0.45f       // 基础亮度
        }
    }
}

/**
 * 经典《别踩白块儿》配色（用户 2026-09-14 定稿版）：
 * - 底色 = 主题色相生成的**浅色渐变**（顶近乎白 → 底中等亮主题色，明度差 ≈82 灰阶）
 * - 轨道分隔线 = **白色**（靠渐变上下的色差显出来）
 * - 背景叠一层**动态小星星**（上浮 + 闪烁，边长按屏宽比例 ~6~17px）
 * - 方块 = 纯黑（亮底）/ 近白（暗底）—— 经典钢琴块就是黑白两色，不掺主题色
 */
internal fun buildTilePalette(primary: Color, background: Color): TilePalette {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(primary.toArgb(), hsv)
    val bgLight = background.luminance() > 0.5f

    fun hsl(h: Float, s: Float, v: Float): Color {
        val a = floatArrayOf(h, s.coerceIn(0f, 1f), v.coerceIn(0f, 1f))
        return Color(android.graphics.Color.HSVToColor(a))
    }

    val hue = hsv[0]
    val sat = hsv[1].let { if (it < 0.12f) 0.35f else it }
    // 亮底：顶近乎白（只有一丝主题色）→ 底压到中等亮的主题色，明度差 ≈82 灰阶
    // 暗底：反过来（0.075 → 0.230）
    // ⚠️ 旧值 0.972→0.862 只差 39 灰阶，手机实测"看不出渐变"（用户 2026-09-14 反馈）
    // 即便压到 0.76，纯黑方块对比度仍有 ~6.6:1，不会影响黑块的辨识
    val vTop = if (bgLight) 0.985f else 0.075f
    val vBot = if (bgLight) 0.760f else 0.230f

    val block = if (bgLight) Color(0xFF0A0A0A) else Color(0xFFF7F7F7)

    return TilePalette(
        bgTop = hsl(hue, sat * 0.18f, vTop),
        bgBottom = hsl(hue, sat * 0.68f, vBot),
        star = hsl(hue, sat * 0.90f, if (bgLight) 0.52f else 0.94f),
        block = block,
        blockTop = block,
        blockBottom = block,
        laneLine = Color.White,
        hitLine = primary,
        flash = block,
        good = hsl(hue, sat, if (bgLight) 0.42f else 0.86f),
        bad = Color(0xFFE24B4A)
    )
}

// ════════════════════════════ 入口壳（主页 / 选歌 / 对局） ════════════════════════════

private enum class TilePage { HOME, PICK, GAME }

@Composable
fun TileGameScreen(
    onBack: () -> Unit,
    songs: List<Song>,
    onPauseMainPlayback: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf(TilePage.HOME) }

    var mode by remember { mutableStateOf(TileMode.CLASSIC) }
    var speed by remember { mutableStateOf(1) }                 // 0 慢 / 1 中 / 2 快
    var latencyMs by remember { mutableStateOf(120L) }
    var bestClassic by remember { mutableStateOf(0L) }
    var bestArcade by remember { mutableStateOf(0L) }
    var bestRelay by remember { mutableStateOf(0) }

    var gameSong by remember { mutableStateOf<Song?>(null) }
    var gameChart by remember { mutableStateOf<ChartAnalyzer.Chart?>(null) }
    // 本局片段窗口（快速/正常档由 pickWindow 选出；完整档 = 0 到整首）
    var gameStartMs by remember { mutableStateOf(0L) }
    var gameEndMs by remember { mutableStateOf(0L) }
    var analyzing by remember { mutableStateOf(false) }
    // 选歌后待选时长的歌（弹「体验时长分档」窗）
    var pendingSong by remember { mutableStateOf<Song?>(null) }
    // 玩过的歌（选歌页"玩过置顶"，与打地鼠同一套排序口径）
    var playedMap by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }

    LaunchedEffect(Unit) {
        speed = TileStore.speed(ctx)
        latencyMs = WhackMoleStore.latencyMs(ctx)
        bestClassic = TileStore.classicBestMs(ctx)
        bestArcade = TileStore.arcadeBest(ctx)
        bestRelay = TileStore.relayBestStreak(ctx)
    }

    LaunchedEffect(page) {
        if (page == TilePage.GAME) onPauseMainPlayback()
        if (page == TilePage.PICK) playedMap = TileStore.lastPlayedMap(ctx)
    }

    // 选歌 → 选时长 → 分析谱面（含片段窗口）→ 进对局
    val beginGame: (Song, Long) -> Unit = { s, targetMs ->
        analyzing = true
        scope.launch(Dispatchers.IO) {
            val chart = runCatching { ChartAnalyzer.analyze(ctx, s.uri, s.id, s.durationMs) }.getOrNull()
            // 没找到可用节拍 → 不进对局（否则会出现"一条方块都没有"的空局）
            val window: Pair<Long, Long>? = if (chart == null) null else {
                val hasBeat = chart.events.any {
                    it.type == ChartAnalyzer.TYPE_NORMAL || it.type == ChartAnalyzer.TYPE_BONUS
                }
                when {
                    !hasBeat -> null
                    targetMs > 0L -> ChartAnalyzer.pickWindow(chart, targetMs)
                    else -> 0L to chart.durationMs
                }
            }
            withContext(Dispatchers.Main) {
                analyzing = false
                when {
                    chart == null ->
                        Toast.makeText(ctx, "这首歌分析失败，换一首试试", Toast.LENGTH_SHORT).show()
                    window == null ->
                        Toast.makeText(ctx, "这首歌没找到可用的节拍，换一首试试", Toast.LENGTH_SHORT).show()
                    else -> {
                        gameSong = s
                        gameChart = chart
                        gameStartMs = window.first
                        gameEndMs = window.second
                        // 记忆歌单：记下"玩过这首"（选歌页下次会把它置顶）
                        scope.launch { TileStore.markPlayed(ctx, s.id, s.durationMs) }
                        page = TilePage.GAME
                    }
                }
            }
        }
    }

    BackHandler(enabled = page != TilePage.GAME && pendingSong == null) {
        if (page == TilePage.PICK) page = TilePage.HOME else onBack()
    }

    when (page) {
        TilePage.HOME -> TileHomeScreen(
            mode = mode,
            speed = speed,
            bestClassic = bestClassic,
            bestArcade = bestArcade,
            bestRelay = bestRelay,
            onMode = { mode = it },
            onSpeed = {
                speed = it
                scope.launch { TileStore.setSpeed(ctx, it) }
            },
            onBack = onBack,
            onStart = { if (songs.isEmpty()) Toast.makeText(ctx, "本地曲库是空的，先去扫描一些歌", Toast.LENGTH_SHORT).show() else page = TilePage.PICK }
        )

        TilePage.PICK -> TilePickSongScreen(
            songs = songs,
            playedMap = playedMap,
            onBack = { page = TilePage.HOME },
            onPick = { pendingSong = it }   // 先选时长（快速/正常/完整），确认后才分析谱面
        )

        TilePage.GAME -> {
            val song = gameSong
            val chart = gameChart
            if (song == null || chart == null) {
                page = TilePage.HOME
            } else {
                TilePlayScreen(
                    song = song,
                    chart = chart,
                    startMs = gameStartMs,
                    endMs = gameEndMs,
                    mode = mode,
                    speed = speed,
                    latencyMs = latencyMs,
                    onLatency = {
                        latencyMs = it
                        scope.launch { WhackMoleStore.setLatencyMs(ctx, it) }
                    },
                    onResult = { usedMs, score, streak, completed ->
                        scope.launch {
                            when (mode) {
                                TileMode.CLASSIC -> {
                                    // 未打满 50 块不记成绩（避免把纪录冲成 0）
                                    if (completed && usedMs > 0) {
                                        val (b, _) = TileStore.submitClassic(ctx, usedMs)
                                        bestClassic = b
                                    }
                                }
                                TileMode.ARCADE -> {
                                    val (b, _) = TileStore.submitArcade(ctx, score)
                                    bestArcade = b
                                }
                                TileMode.RELAY -> {
                                    val (b, _) = TileStore.submitRelay(ctx, streak)
                                    bestRelay = b
                                }
                            }
                        }
                    },
                    onQuit = { page = TilePage.HOME },
                    onPickAnother = { page = TilePage.PICK }
                )
            }
        }
    }

    // ── 体验时长分档（选歌后弹窗；档位与打地鼠一致，快速=推荐）──
    val pending = pendingSong
    if (pending != null) {
        AlertDialog(
            onDismissRequest = { pendingSong = null },
            title = { Text("选择体验时长") },
            text = {
                Column {
                    Text(
                        "《${pending.title}》",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(10.dp))
                    TileDuration.entries.forEach { d ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (d == TileDuration.QUICK)
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    pendingSong = null
                                    beginGame(pending, d.targetMs)
                                }
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(d.label, fontWeight = FontWeight.Bold)
                                Text(d.desc, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            },
            confirmButton = {}
        )
    }

    if (analyzing) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("正在分析歌曲节奏…") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("每首歌只需分析一次", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {}
        )
    }
}

// ════════════════════════════ 主页：模式 + 速度 + 纪录 ════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TileHomeScreen(
    mode: TileMode,
    speed: Int,
    bestClassic: Long,
    bestArcade: Long,
    bestRelay: Int,
    onMode: (TileMode) -> Unit,
    onSpeed: (Int) -> Unit,
    onBack: () -> Unit,
    onStart: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("别踩白块") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Text(
                "4 条轨道 · 白块别碰 · 黑色方块跟着歌曲节拍下落，块底落到底就点掉；长条要按住、并排两块要同时按。\n" +
                    "选好歌后再选体验时长：快速 30~40 秒 / 正常约 2 分钟 / 完整整首。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))

            Text("模式", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            TileMode.entries.forEach { m ->
                val selected = m.id == mode.id
                val record = when (m) {
                    TileMode.CLASSIC -> if (bestClassic > 0) "最佳用时 ${fmtSec(bestClassic)}" else "暂无记录"
                    TileMode.ARCADE -> if (bestArcade > 0) "最高分 $bestArcade" else "暂无记录"
                    TileMode.RELAY -> if (bestRelay > 0) "最长连续 $bestRelay 段" else "暂无记录"
                }
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable { onMode(m) }
                ) {
                    Row(
                        Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = selected, onClick = { onMode(m) })
                        Spacer(Modifier.width(4.dp))
                        Column(Modifier.weight(1f)) {
                            Text(m.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text(m.rule, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(record, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Text("下落速度", fontWeight = FontWeight.Bold)
            Text(
                "越打越快：经典按块数、街机按歌曲进度自动加速到该档位极限（快档≈每秒 5 块）。只影响反应窗口，不改判定容差。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("慢" to 0, "中" to 1, "快" to 2).forEach { (label, v) ->
                    FilterChip(
                        selected = speed == v,
                        onClick = { onSpeed(v) },
                        label = { Text(label) }
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            Button(
                onClick = onStart,
                modifier = Modifier.fillMaxWidth()
            ) { Text("选歌开始") }

            Spacer(Modifier.height(10.dp))
            Text(
                "规则：允许 3 次失误（漏块、踩白块、长按中途松手），失误用尽即结束；连续 30 块零失误回 1 次机会（上限始终 3 次）。经典模式打完本局全部方块即通关（最多取前 50 块）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(20.dp))
        }
    }
}

private fun fmtSec(ms: Long): String {
    val s = ms / 1000.0
    return String.format(java.util.Locale.CHINA, "%.1f 秒", s)
}

// ════════════════════════════ 下落速度递增 ════════════════════════════

/**
 * 当前「方块从屏幕顶落到判定线」的时长（ms）——越小越快、反应窗口越窄。
 *
 * p = 本局进度 0..1：经典按已打块数 /50，街机按对局时间 /歌曲时长；
 * 按 p^1.15（微 ease-in）从「起手看得清」加速到该档位的极限值。
 *
 * 极限值（用户 2026-09-14 要求"最快的档位应该加速到玩家速度的极限"）：
 * 快档终点 200ms ≈ 每秒 5 块，已接近单指连点的人类反应极限——到这个速度基本
 * 只能靠听歌预判落点，而不是靠眼睛看到再反应。想改手感就调这三个数字。
 */
private fun rampSpeedMs(speed: Int, p: Float): Long {
    val start: Float
    val limit: Float
    when (speed) {
        0 -> { start = 1150f; limit = 430f }   // 慢
        2 -> { start = 620f; limit = 200f }    // 快（极限）
        else -> { start = 880f; limit = 300f } // 中
    }
    val e = Math.pow(p.coerceIn(0f, 1f).toDouble(), 1.15).toFloat()
    return (start + (limit - start) * e).toLong().coerceAtLeast(120L)
}

/** 接力模式不做递增（用户只要求经典/街机），沿用原固定档位 */
private fun fixedSpeedMs(speed: Int): Long = when (speed) {
    0 -> 1200L
    2 -> 560L
    else -> 800L
}

// ════════════════════════════ 选歌页 ════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TilePickSongScreen(
    songs: List<Song>,
    playedMap: Map<String, Long>,
    onBack: () -> Unit,
    onPick: (Song) -> Unit
) {
    // 排序（用户 2026-09-15："别踩白块同样需要记忆歌单，参考打地鼠的歌单排序"）：
    // 玩过的全部置顶（最近玩过在前），没玩过的按添加时间从新到旧 —— 与 MolePickSongScreen 同一口径
    val sorted = remember(songs, playedMap) {
        songs.sortedWith(
            compareByDescending<Song> { playedMap["${it.id}_${it.durationMs}"] ?: 0L }
                .thenByDescending { it.dateAdded }
        )
    }
    // playedMap 是进页面后异步加载的：加载完成触发"玩过置顶"重排时，LazyColumn 会按 key
    // 保持第一可见项不滚走 → 视觉上列表没在最顶端。map 变化时强制回顶部（同打地鼠）
    val listState = rememberLazyListState()
    LaunchedEffect(playedMap) { listState.scrollToItem(0) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("选择一首歌") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                }
            )
        }
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), state = listState) {
            items(sorted, key = { it.id }) { s ->
                ListItem(
                    headlineContent = { Text(s.title, maxLines = 1) },
                    supportingContent = { Text(s.artist, maxLines = 1) },
                    trailingContent = {
                        Text(fmtSec(s.durationMs), style = MaterialTheme.typography.bodySmall)
                    },
                    modifier = Modifier.clickable { onPick(s) }
                )
            }
        }
    }
}

// ════════════════════════════ 对局页 ════════════════════════════

private enum class TilePhase { COUNTDOWN, PLAY, RESULT }

@Composable
private fun TilePlayScreen(
    song: Song,
    chart: ChartAnalyzer.Chart,
    /** 本局片段起点（完整模式 = 0；快速/正常 = `ChartAnalyzer.pickWindow` 选出的精彩段起点） */
    startMs: Long,
    /** 本局片段终点（完整模式 = 歌曲时长） */
    endMs: Long,
    mode: TileMode,
    speed: Int,
    latencyMs: Long,
    onLatency: (Long) -> Unit,
    onResult: (usedMs: Long, score: Long, streak: Int, completed: Boolean) -> Unit,
    onQuit: () -> Unit,
    onPickAnother: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val sounds = remember { HitSounds() }
    var gameKey by remember { mutableStateOf(0) }
    val engine = remember(gameKey) { TileEngine(chart, startMs, endMs, mode) }
    val palette = rememberTilePalette()
    // 难度已整体上调（用户 2026-09-14 反馈太简单）→ 现在再叠一层「速度递增」：
    // 经典按块数进度、街机按对局时间，从起手速度加速到该档位极限；
    // 接力不递增，保持固定档位。speedMs 越小 = 反应窗口越窄。
    // ⚠️ 必须是「函数」而不是组合阶段的 val：这两个输入每帧都在变，在 composable 体内读
    // 它 = 整屏每秒重组 120 次（真机实测 22% 掉帧、UI 线程 7ms，预算只有 8.33ms）。
    // 传给 Canvas 的 draw lambda，在**绘制阶段**调用 → 只重绘画布、不触发重组。
    fun currentSpeedMs(): Long = when (mode) {
        TileMode.CLASSIC -> rampSpeedMs(
            speed,
            engine.hitTiles.toFloat() / engine.totalNotes.coerceAtLeast(1)
        )
        TileMode.ARCADE -> rampSpeedMs(
            speed,
            ((engine.now - startMs).toFloat() / engine.windowMs).coerceIn(0f, 1f)
        )
        TileMode.RELAY -> fixedSpeedMs(speed)
    }

    val player = remember {
        ExoPlayer.Builder(ctx).build().apply {
            setAudioAttributes(
                M3AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
        }
    }

    var phase by remember(gameKey) { mutableStateOf(TilePhase.COUNTDOWN) }
    var countdown by remember(gameKey) { mutableStateOf(3) }
    var paused by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    // 背景渐变刷子按配色缓存一次复用（每帧 new 一个 Brush = 每帧重建 Skia 渐变 shader）
    // 不传 startY/endY → 默认铺满 drawRect 的区域，所以不用等 Canvas 尺寸
    val bgBrush = remember(palette) {
        Brush.verticalGradient(listOf(palette.bgTop, palette.bgBottom))
    }
    // 星星动效时钟（毫秒）：**只被绘制阶段读取** → 每帧只重绘、不触发重组
    var bgTime by remember { mutableStateOf(0L) }
    var reported by remember(gameKey) { mutableStateOf(false) }

    fun finishGame() {
        if (reported) return
        reported = true
        player.pause()
        phase = TilePhase.RESULT
        // 经典：只有打满本局全部方块才算有效成绩——中途失误用尽时 usedMs 是残局数据，
        // 直接提交会把「最佳用时」冲成 0（2026-09-14 真机验证踩到的坑）。
        // 目标块数用 engine.totalNotes（快速/正常档的窗口可能不足 50 块）
        val completed = if (mode == TileMode.CLASSIC) {
            engine.totalNotes > 0 && engine.hitTiles >= engine.totalNotes
        } else {
            !engine.failed
        }
        onResult(engine.usedMs, engine.score, engine.relayMaxStreak, completed)
        scope.launch { TileStore.addTiles(ctx, engine.hitTiles) }
    }

    LaunchedEffect(gameKey) {
        paused = false
        reported = false
        player.setMediaItem(MediaItem.fromUri(song.uri))
        // 片段模式：从精彩段起点开始播（判定时间轴 = 歌曲绝对时间，所以 seek 后直接对上）
        if (startMs > 0L) player.seekTo(startMs)
        player.prepare()
        phase = TilePhase.COUNTDOWN
        countdown = 3
        delay(700)
        countdown = 2
        delay(700)
        countdown = 1
        delay(700)
        countdown = 0
        player.play()
        phase = TilePhase.PLAY
    }

    LaunchedEffect(phase, gameKey, latencyMs) {
        if (phase == TilePhase.PLAY) {
            while (isActive) {
                val pos = (player.currentPosition - latencyMs).coerceAtLeast(0L)
                engine.tick(pos)   // tick 内部写 engine.now（Compose 状态）→ 只重绘画布
                if (engine.finished || player.playbackState == Player.STATE_ENDED) {
                    finishGame()
                    break
                }
                // 帧时钟（vsync）而不是 delay(16)：delay 是计时器、不对齐 vsync，
                // 真机实测内容每秒只更新 57.4 次
                withFrameNanos { }
            }
        }
    }

    // 🔥 专职「重绘时钟」（对齐打地鼠的结构）：打地鼠稳定 120.0fps，是因为它除了游戏循环
    // 还有一个一直在跑的 withInfiniteAnimationFrameNanos（刷背景时钟）。单独一个帧回调
    // 一旦续订晚一拍，这一帧就没人再请求了 → IntendedVsync 从 8.3 跳到 16.7ms（白块实测 22%）。
    // 这个循环只写一个只被**绘制阶段**读的状态（星星动效），不参与重组。
    LaunchedEffect(Unit) {
        while (true) {
            withInfiniteAnimationFrameNanos { bgTime = it / 1_000_000L }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            player.release()
            sounds.release()
        }
    }

    BackHandler(enabled = phase != TilePhase.RESULT) {
        if (phase == TilePhase.PLAY && !paused) {
            paused = true
            player.pause()
        }
        showMenu = true
    }

    Box(Modifier.fillMaxSize()) {
        // ── 全屏下落区：铺满整屏（含状态栏 / 导航栏区域），无留边 ──
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(gameKey) {
                    // 多指按下/抬起。**不能用 detectTapGestures**：
                    // ① 它只在手指抬起后才回调 → 长按块没法做（需要"按住"这个状态）
                    // ② 它按手指串行处理 → 双押的第二根手指要等第一根抬起才轮到
                    // 这里逐个 PointerEvent 遍历 changes，几根手指同时按都能各自立刻判定，
                    // 并且抬起时通知引擎（长按块靠它判断有没有提前松手）。
                    val laneW = size.width / 4f
                    awaitPointerEventScope {
                        val laneOf = HashMap<PointerId, Int>()
                        while (true) {
                            val ev = awaitPointerEvent()
                            for (ch in ev.changes) {
                                val held = laneOf[ch.id]
                                if (ch.pressed) {
                                    if (held != null) continue
                                    val lane = (ch.position.x / laneW).toInt().coerceIn(0, 3)
                                    laneOf[ch.id] = lane
                                    if (phase == TilePhase.PLAY && !paused) {
                                        val (st, _, isMiss) = engine.tap(lane, engine.now)
                                        when {
                                            isMiss -> sounds.play(MISS_FREQ, 0.5f, 0)
                                            st == NoteState.PERFECT -> sounds.play(laneFreq(lane), 0.45f, 0)
                                            st == NoteState.GREAT -> sounds.play(laneFreq(lane) * 0.9f, 0.4f, 0)
                                            else -> sounds.play(laneFreq(lane) * 0.8f, 0.35f, 0)
                                        }
                                    }
                                } else if (held != null) {
                                    laneOf.remove(ch.id)
                                    if (phase == TilePhase.PLAY && !paused) {
                                        engine.releaseLane(held, engine.now)
                                    }
                                }
                            }
                        }
                    }
                }
        ) {
            // 这里读到的 now / speed 都发生在绘制阶段（不再让整屏每帧重组）：
            // engine.now 是 Compose 状态 → 它一变只让这块画布失效重绘
            drawTileField(engine, palette, bgBrush, { engine.now }, { currentSpeedMs() }, { bgTime })
        }

        // ── 顶部信息条（浮层：黑块会从文字下穿过，所以垫一层半透明底衬保证可读） ──
        Row(
            Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .background(
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.35f),
                    RoundedCornerShape(12.dp)
                )
                .padding(horizontal = 12.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("${engine.score}", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                val sub = when (mode) {
                    TileMode.CLASSIC -> "经典 · ${engine.classicProgress}/${engine.totalNotes}"
                    TileMode.ARCADE -> "街机 · ${engine.hitTiles} 块"
                    TileMode.RELAY -> "接力 · 第 ${engine.relayStreak + 1} 段 · 已接 ${engine.relayMaxStreak}"
                }
                Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (engine.combo >= 2) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "${engine.combo} 连",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.hitLine
                    )
                    Text(
                        "x${comboMulText(engine.combo)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(12.dp))
            }
            // 失误额度（3 颗方块，暗掉 = 已用）+ 恢复进度提示
            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    repeat(TileEngine.MAX_MISS) { i ->
                        Box(
                            Modifier
                                .padding(end = 4.dp)
                                .size(12.dp)
                                .background(
                                    if (i < engine.missLeft) palette.bad else palette.bad.copy(alpha = 0.18f),
                                    RoundedCornerShape(2.dp)
                                )
                        )
                    }
                }
                // 有缺口时才提示"还差多少块回一次机会"
                if (engine.missLeft < TileEngine.MAX_MISS) {
                    Text(
                        "连打 ${engine.hitToRecover} 块回 1",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.width(6.dp))
            IconButton(onClick = {
                if (phase == TilePhase.PLAY && !paused) {
                    paused = true
                    player.pause()
                }
                showMenu = true
            }) {
                Icon(Icons.Default.Pause, contentDescription = "暂停")
            }
        }

        // ── 提示条（接上 / 失误） ──
        // 独立 composable：以前靠整屏每帧重组顺带淡出，现在整屏不再每帧重组，
        // 就由它自己在**显示期间**按 ~16 次/秒 刷新（不显示时完全不刷新）
        TileBanner(
            engine = engine,
            hitColor = palette.hitLine,
            badColor = palette.bad,
            modifier = Modifier.align(Alignment.TopCenter)
        )

        // ── 倒计时 ──
        if (phase == TilePhase.COUNTDOWN) {
            Column(
                Modifier
                    .align(Alignment.Center)
                    .background(
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                        RoundedCornerShape(16.dp)
                    )
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    if (countdown > 0) "$countdown" else "开始!",
                    fontSize = 52.sp,
                    fontWeight = FontWeight.Bold
                )
                Text("黑块到底就点 · 长条按住不舍手 · 并排两块一起点", style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    if (showMenu) {
        AlertDialog(
            onDismissRequest = { showMenu = false },
            title = { Text("暂停") },
            text = {
                Column {
                    Row {
                        Button(
                            onClick = {
                                showMenu = false
                                if (phase == TilePhase.PLAY && paused) {
                                    paused = false
                                    player.play()
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("▶ 继续") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = onQuit, modifier = Modifier.weight(1f)) { Text("退出") }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("延迟校准", fontWeight = FontWeight.Bold)
                    Text(
                        "方块和音乐节拍对不上时微调（调大 = 判定更晚）",
                        style = MaterialTheme.typography.bodySmall
                    )
                    var draft by remember(latencyMs) { mutableStateOf(latencyMs.toFloat()) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Slider(
                            value = draft,
                            onValueChange = { draft = it },
                            onValueChangeFinished = { onLatency(draft.toLong()) },
                            valueRange = -200f..400f,
                            steps = 11,
                            modifier = Modifier.weight(1f)
                        )
                        Text("${draft.toInt()}ms", style = MaterialTheme.typography.labelMedium)
                    }
                }
            },
            confirmButton = {}
        )
    }

    if (phase == TilePhase.RESULT) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(if (engine.failed) "失误用尽 · 结束" else "完成") },
            text = {
                Column {
                    val head = when (mode) {
                        TileMode.CLASSIC -> if (engine.hitTiles >= engine.totalNotes) "用时 ${fmtSec(engine.usedMs)}" else "打完 ${engine.hitTiles}/${engine.totalNotes} 块"
                        TileMode.ARCADE -> "得分 ${engine.score}"
                        TileMode.RELAY -> "最长连续 ${engine.relayMaxStreak} 段"
                    }
                    Text(head, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "完美 ${engine.perfect} · 精彩 ${engine.great} · 良好 ${engine.good} · 失误 ${engine.missCount}" +
                            if (engine.recoveredCount > 0) " · 回机会 ${engine.recoveredCount} 次" else "",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "最大连击 ${engine.maxCombo} · 命中 ${engine.hitTiles} 块 · 平均偏差 ${engine.avgDevMs}ms",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (mode == TileMode.CLASSIC) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "提示：用时受歌曲本身长度影响，同歌对比更有意义",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { gameKey++ }) { Text("再来一局") } },
            dismissButton = {
                Row {
                    TextButton(onClick = onPickAnother) { Text("换一首") }
                    TextButton(onClick = onQuit) { Text("返回") }
                }
            }
        )
    }
}

/**
 * 「接上 / 失误」提示条：显示 900ms 内逐渐淡出。
 *
 * 单独抽成 composable 是为了把刷新限制在这一个小节点内 —— 主对局页已经不再每帧重组
 * （见 TilePlayScreen 里 currentSpeedMs 的注释），如果还用父级状态去驱动淡出，
 * 就会把整屏重新拉回每秒 120 次重组的老路。
 */
@Composable
private fun TileBanner(
    engine: TileEngine,
    hitColor: Color,
    badColor: Color,
    modifier: Modifier = Modifier
) {
    var nowRt by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    val since = nowRt - engine.bannerAt
    val visible = engine.banner.isNotBlank() && since < 900

    // 只在显示期间小步刷新（60ms ≈ 16 次/秒，淡出足够顺滑；不显示时不占任何开销）
    LaunchedEffect(engine.banner, visible) {
        while (visible && isActive) {
            delay(60)
            nowRt = SystemClock.elapsedRealtime()
        }
    }
    if (!visible) return

    val alpha = (1f - since / 900f).coerceIn(0f, 1f)
    Text(
        engine.banner,
        fontWeight = FontWeight.Bold,
        color = if (engine.banner.startsWith("接上")) hitColor else badColor,
        modifier = modifier
            .statusBarsPadding()
            .padding(top = 74.dp)
            .background(
                MaterialTheme.colorScheme.surface.copy(alpha = 0.7f * alpha),
                RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

private const val MISS_FREQ = 98f

private fun laneFreq(lane: Int): Float = when (lane) {
    0 -> 523f
    1 -> 587f
    2 -> 659f
    else -> 784f
}

private fun comboMulText(combo: Int): String = when {
    combo >= 200 -> "3.0"
    combo >= 100 -> "2.0"
    combo >= 50 -> "1.5"
    else -> "1.0"
}

@Composable
private fun rememberTilePalette(): TilePalette {
    val primary = MaterialTheme.colorScheme.primary
    val bg = MaterialTheme.colorScheme.background
    return remember(primary, bg) { buildTilePalette(primary, bg) }
}

// ════════════════════════════ 下落渲染（像素风） ════════════════════════════

/**
 * 经典《别踩白块儿》画面：4 条同色轨道 + 1px 细分隔线 + 纯色方块。
 * 没有判定线、没有高光/暗边/缺口——经典版就是「块触底就点」，底部即终点。
 * 所有坐标取整，保持硬边；命中即消失（经典反馈），漏块短暂泛红再消失。
 */
/**
 * 长按块「细黑线」上的流光：一段亮光沿线上循环流动（由头向尾 = 向上）。
 *
 * 纯绘制（不进重组、不影响逻辑），用**墙钟** bgMs 驱动 → 暂停/倒计时时也在动
 * （与背景星星同一套时钟）。线太短（<10px）就不画，避免糊成一团。
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHoldFlow(
    p: TilePalette,
    lineX: Float,
    lineW: Float,
    tailY: Float,
    headY: Float,
    bgMs: Long,
    periodMs: Long
) {
    val span = headY - tailY
    if (span < 10f) return
    val phase = ((bgMs % periodMs).toFloat() / periodMs.toFloat()).coerceIn(0f, 1f)
    val ly = headY - span * phase
    // 外发光 + 亮核，两段叠出"流光"感（像素硬边，不做模糊）
    drawRect(
        p.star.copy(alpha = 0.30f),
        topLeft = Offset(lineX - 1.5f, ly - 5f),
        size = Size(lineW + 3f, 10f)
    )
    drawRect(
        p.star.copy(alpha = 0.92f),
        topLeft = Offset(lineX - 0.5f, ly - 2.5f),
        size = Size(lineW + 1f, 5f)
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTileField(
    engine: TileEngine,
    p: TilePalette,
    bgBrush: Brush,
    nowProvider: () -> Long,
    speedProvider: () -> Long,
    bgProvider: () -> Long
) {
    // 这三个值每帧都在变，所以**只能在绘制阶段读**（见调用处注释）：在 composable 体内
    // 求值会让整屏每秒重组 120 次，真机实测 UI 线程 7ms、22% 掉帧（预算只有 8.33ms）
    val now = nowProvider()
    val bgMs = bgProvider()
    val w = size.width
    val h = size.height
    val laneWi = (w / 4f).toInt().coerceAtLeast(1)
    // 判定位置贴近屏幕底部：块底边碰到下方就点（经典手感）
    val hitY = (h * 0.93f).toInt()
    // 方块是「竖向为长边」的黄金比例长方形：宽 = 轨道宽，高 = 宽 × 1.618
    // （0.46 倍太薄 → 正方形太方 → 用户 2026-09-14 定：竖向长边黄金比例）
    val noteH = (laneWi * 1.618f).toInt()
        .coerceIn(60, (h * 0.30f).toInt().coerceAtLeast(60))
    val pxPerMs = hitY.toFloat() / speedProvider().coerceAtLeast(1L).toFloat()

    // ① 浅色渐变底（刷子由调用方按尺寸缓存后传入 —— 每帧 new 一个 Brush 会
    //    每帧重建一次 Skia 渐变 shader，是掉帧的主因之一）
    drawRect(brush = bgBrush)

    // ② 背景小星星：上浮 + 闪烁（像素方块，压在轨道线之下）
    // 边长按屏宽比例算（不写死像素，否则高分辨率屏上小到看不见）
    // 用独立的墙钟 bgMs（不是歌曲位置）→ 暂停/倒计时时星星也在动（同打地鼠背景）
    val tSec = bgMs / 1000f
    for (s in 0 until TILE_STAR_COUNT) {
        val bx = TILE_STARS[s * 7]
        val by = TILE_STARS[s * 7 + 1]
        val sz = (TILE_STARS[s * 7 + 2] * w).coerceAtLeast(3f)
        val ph = TILE_STARS[s * 7 + 3]
        val tw = TILE_STARS[s * 7 + 4]
        val dp = TILE_STARS[s * 7 + 5]
        val base = TILE_STARS[s * 7 + 6]
        val yNorm = ((by - tSec * dp) % 1f + 1f) % 1f
        val a = (base * (0.25f + 0.75f * abs(sin(tSec * tw + ph)))).coerceIn(0f, 0.78f)
        if (a <= 0.02f) continue
        drawRect(
            p.star.copy(alpha = a),
            topLeft = Offset((bx * w).toInt().toFloat(), (yNorm * h).toInt().toFloat()),
            size = Size(sz, sz)
        )
    }

    // ③ 白色轨道分隔线（2px，切出 4 条轨道）
    for (i in 1..3) {
        drawRect(
            p.laneLine.copy(alpha = 0.92f),
            topLeft = Offset((i * laneWi - 1).toFloat(), 0f),
            size = Size(2f, h)
        )
    }

    val nowRt = SystemClock.elapsedRealtime()

    // ── 双押块：一对块之间拉一条横向细线（先画线，再画块，避免线压住块）──
    for (i in 0 until engine.notes.size) {
        val n = engine.notes[i]
        if (n.pairLane <= n.lane) continue            // 每对只画一次
        if (engine.stateOf(i) != NoteState.PENDING) continue
        val y = hitY - ((n.timeMs - now) * pxPerMs).toInt()
        if (y - noteH > h || y < -noteH) continue
        val x0 = n.lane * laneWi + laneWi / 2f
        val x1 = n.pairLane * laneWi + laneWi / 2f
        drawRect(
            p.hitLine.copy(alpha = 0.85f),
            topLeft = Offset(x0, y - noteH * 0.5f),
            size = Size(x1 - x0, 4f)
        )
    }

    for (i in 0 until engine.notes.size) {
        val n = engine.notes[i]
        val dt = n.timeMs - now
        val y = hitY - (dt * pxPerMs).toInt()
        // 长按条向上延伸 holdPx，所以要按"条的尾端"来裁
        val holdPx = n.holdMs * pxPerMs
        // 上方留一个块高、下方多留一点（判定后块还会继续滑出去一点）
        if ((y - holdPx) - noteH > h || y < -noteH) continue

        val st = engine.stateOf(i)
        val jAt = engine.judgedAtOf(i)
        val laneX = n.lane * laneWi + 1
        val boxW = (laneWi - 2).coerceAtLeast(8)
        val top = (y - noteH).toFloat()

        // ── 长按块（2026-09-15 返工：不再画成一根粗黑长条）──
        // 用户："长按块不用长黑块，设置一开始黑块后面用黑线，并且添加特效"
        // 头部 = 普通黑方块（与普通块同尺寸，一眼看出"按这里"）；
        // 后面（要按住的段落）= 一条**细黑线** + 线上循环跑的流光；
        // 按满 = 头部爆闪一下（220ms）。
        if (n.holdMs > 0L) {
            // 线宽按轨道宽取比例（写死像素在 1440 宽屏上会看不见、在低分屏上又会过粗）。
            // 0.045 实测太细（1080 屏上 12px、只有块宽的 1/22，像根头发）→ 0.085（≈23px）
            // 仍明显是"一条线"（块宽的 1/11），但在手机上看得清流向
            val lineW = (laneWi * 0.085f).coerceIn(6f, laneWi * 0.16f)
            val lineX = laneX + (boxW - lineW) * 0.5f

            // ① 按满：头部爆闪（"白光" + 一圈向外扩的方环）
            if (st != NoteState.PENDING && st != NoteState.HOLDING && st != NoteState.MISS) {
                val age = if (jAt > 0) nowRt - jAt else 999L
                if (age < 220) {
                    val k = age / 220f
                    val cx = n.lane * laneWi + laneWi * 0.5f
                    val cy = hitY - noteH * 0.35f
                    val r = laneWi * (0.30f + 0.95f * k)
                    val a = 1f - k
                    // 方环用方块色（亮底=黑环、暗底=白环，两种主题都看得清）
                    drawRect(
                        p.block.copy(alpha = a * 0.85f),
                        topLeft = Offset(cx - r, cy - r),
                        size = Size(r * 2f, r * 2f),
                        style = Stroke(width = 4f)
                    )
                    // 白光十字（爆闪本体）——先垫一层方块色描边：纯白在**亮底**上根本看不见
                    // （预览图实测），垫边后亮底=白芯黑边、暗底=白芯，两种主题都读得出来
                    val arm = 7f
                    val armLen = r * 0.72f
                    drawRect(
                        p.block.copy(alpha = a * 0.9f),
                        topLeft = Offset(cx - armLen, cy - arm),
                        size = Size(armLen * 2f, arm * 2f)
                    )
                    drawRect(
                        p.block.copy(alpha = a * 0.9f),
                        topLeft = Offset(cx - arm, cy - armLen),
                        size = Size(arm * 2f, armLen * 2f)
                    )
                    drawRect(
                        Color.White.copy(alpha = a * 0.95f),
                        topLeft = Offset(cx - armLen * 0.94f, cy - 2.5f),
                        size = Size(armLen * 1.88f, 5f)
                    )
                    drawRect(
                        Color.White.copy(alpha = a * 0.95f),
                        topLeft = Offset(cx - 2.5f, cy - armLen * 0.94f),
                        size = Size(5f, armLen * 1.88f)
                    )
                }
                continue
            }

            if (st == NoteState.HOLDING) {
                // 按住中：头吸在判定线上，尾巴继续下落 → 线从头部被"吃掉"
                val remainPx = (((n.timeMs + n.holdMs) - now) * pxPerMs).toFloat()
                val tailY = hitY - remainPx
                if (remainPx > 2f && tailY < h) {
                    // 剩下要按的段落：细黑线
                    drawRect(
                        p.block.copy(alpha = 0.88f),
                        topLeft = Offset(lineX, tailY),
                        size = Size(lineW, (hitY - tailY).coerceAtLeast(1f))
                    )
                    drawHoldFlow(p, lineX, lineW, tailY, hitY.toFloat(), bgMs, 420L)
                }
                // 判定线上的锚点：脉动（提示"手指还按着"）
                val pulse = (0.72f + 0.28f * sin(bgMs / 120f)).coerceIn(0f, 1f)
                drawRect(
                    p.block.copy(alpha = pulse),
                    topLeft = Offset(laneX.toFloat(), hitY - noteH * 0.45f),
                    size = Size(boxW.toFloat(), noteH * 0.45f)
                )
            } else {
                val tailY = y - holdPx
                if (tailY < h) {
                    // 要按住的段落：细黑线
                    drawRect(
                        p.block.copy(alpha = 0.88f),
                        topLeft = Offset(lineX, tailY),
                        size = Size(lineW, holdPx.coerceAtLeast(1f))
                    )
                    // 流光（沿线由头向尾流动：线是"活的"，一眼区分长按块与普通块）
                    drawHoldFlow(p, lineX, lineW, tailY, y.toFloat(), bgMs, 700L)
                    // 尾端亮线：提示"要按到这里"
                    drawRect(
                        p.hitLine.copy(alpha = 0.9f),
                        topLeft = Offset(lineX - lineW * 0.4f, tailY),
                        size = Size(lineW * 1.8f, 3f)
                    )
                    // 头部：普通黑方块（按点）
                    drawRect(
                        p.block,
                        topLeft = Offset(laneX.toFloat(), top),
                        size = Size(boxW.toFloat(), noteH.toFloat())
                    )
                }
            }
            continue
        }

        if (st == NoteState.PENDING) {
            drawRect(
                p.block,
                topLeft = Offset(laneX.toFloat(), top),
                size = Size(boxW.toFloat(), noteH.toFloat())
            )
        } else if (st == NoteState.MISS) {
            // 漏块 / 长按断了：短暂泛红后消失（不给残影，保持画面干净）
            val age = if (jAt > 0) nowRt - jAt else 999L
            if (age < 190) {
                drawRect(
                    p.bad.copy(alpha = 1f - age / 190f),
                    topLeft = Offset(laneX.toFloat(), top),
                    size = Size(boxW.toFloat(), noteH.toFloat())
                )
            }
        }
        // 命中：经典做法 = 瞬间消失，不画任何东西
    }
}
