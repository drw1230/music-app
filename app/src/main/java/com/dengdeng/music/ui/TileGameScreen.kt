package com.dengdeng.music.ui

import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
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

/**
 * 别踩白块（听歌模式）—— Batch 1
 *
 * 玩法：4 条轨道，黑块随歌曲节拍下落，落到判定线时点掉。
 * 三种模式（用户 2026-09-14 拍板）：
 * - 经典：前 50 块，比完成用时（用时受歌曲本身影响，同歌对比更有意义；另记平均偏差）
 * - 街机：整首歌冲最高分
 * - 接力：每 50 块为一段，段内零失误即"接上"→ 恢复 1 次失误额度、连击倍率加成；成绩 = 最长连续通过段数
 * 规则：允许 3 次失误（漏块 / 踩白块），失误有专属提示音
 * 美术：像素风；方块颜色跟随 App 主题色（亮背景压深成深色块，暗背景提亮，保证对比度）
 *
 * 待做（Batch 2）：长按块 / 双押块、分模式联网排行榜、背景接 MoleArt 场景图、打地鼠主题化
 */

// ════════════════════════════ 模式 ════════════════════════════

internal enum class TileMode(val id: Int, val label: String, val rule: String) {
    CLASSIC(0, "经典", "前 50 块 · 比完成用时"),
    ARCADE(1, "街机", "整首歌 · 冲最高分"),
    RELAY(2, "接力", "每 50 块一段 · 零失误接上，成绩看连续段数")
}

// ════════════════════════════ 谱面 → 方块 ════════════════════════════

/** 一个下落方块：命中时刻 + 轨道（0..3） */
internal data class TileNote(val timeMs: Long, val lane: Int)

internal enum class NoteState { PENDING, PERFECT, GREAT, GOOD, MISS, EMPTY_TAP }

/**
 * 由打地鼠同一套谱面（onset 锚定）生成方块，结果是确定性的（同歌同结果）：
 * - 连打（相邻峰间隔 < 350ms）→ 走相邻轨道"跑动"，像音阶爬升
 * - 稀疏（间隔 ≥ 350ms）→ 随机换轨（不与上一块同轨）
 */
internal fun buildTiles(chart: ChartAnalyzer.Chart): List<TileNote> {
    val peaks = chart.events
        .filter { it.type == ChartAnalyzer.TYPE_NORMAL || it.type == ChartAnalyzer.TYPE_BONUS }
        .sortedBy { it.timeMs }
    if (peaks.isEmpty()) return emptyList()
    val rnd = java.util.Random(chart.durationMs * 7L + peaks.size * 31L)
    val out = ArrayList<TileNote>(peaks.size)
    var lastLane = 1
    var dir = 1
    var lastT = -10_000L
    for (e in peaks) {
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
        out.add(TileNote(e.timeMs, lane))
        lastLane = lane
        lastT = e.timeMs
    }
    return out
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
    }

    /** 本局要打的方块（经典模式只取前 50 块） */
    val notes: List<TileNote> = buildTiles(chart)
        .filter { it.timeMs >= startMs && it.timeMs <= endMs }
        .let { if (mode == TileMode.CLASSIC) it.take(CLASSIC_TILES) else it }

    private val states = Array(notes.size) { mutableStateOf(NoteState.PENDING) }
    private val judgedAt = LongArray(notes.size) { -1L }

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
    var relayStreak = 0; private set                          // 接力：当前连续段数
    var relayMaxStreak = 0; private set
    var relaySegments = 0; private set                        // 接力：已走完的段数（含失败段）
    var banner by mutableStateOf(""); private set             // 短暂提示（接上 / 失误）
    var bannerAt = 0L; private set
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
        showBanner(if (reason == NoteState.EMPTY_TAP) "踩白块！" else "漏了！")
        resolved++
        segProgress++
        if (missCount >= MAX_MISS) {
            failed = true
            finished = true
        }
        advanceSegment()
    }

    /** 接力：每段结束结算（零失误 → 接上，恢复 1 次失误额度） */
    private fun advanceSegment() {
        if (mode != TileMode.RELAY) return
        if (segProgress < SEGMENT_TILES) return
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
        for (i in 0 until notes.size) {
            if (states[i].value != NoteState.PENDING) continue
            val t = notes[i].timeMs
            if (t - GOOD_MS > nowMs) break
            if (nowMs > t + GOOD_MS) miss(i, NoteState.MISS)
        }
        if (mode == TileMode.CLASSIC) {
            if (hitTiles >= CLASSIC_TILES) finished = true
        } else if (resolved >= notes.size) {
            finished = true
        }
    }

    /** 点某条轨道。@return (判定档位, 得分/0, 是否失误) */
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
        if (mode == TileMode.CLASSIC && hitTiles >= CLASSIC_TILES) finished = true
        advanceSegment()
        return Triple(st, pts, false)
    }
}

// ════════════════════════════ 主题色取色 ════════════════════════════

/** 一套与 App 主题色相近、且保证对比度的游戏配色 */
internal class TilePalette(
    val block: Color,        // 方块主色（跟随主题色相）
    val blockTop: Color,     // 方块顶部像素高光
    val blockBottom: Color,  // 方块底部像素暗边
    val laneBg: Color,       // 轨道底
    val laneAlt: Color,      // 相邻轨道交替色
    val laneLine: Color,     // 轨道分隔线
    val hitLine: Color,      // 判定线
    val flash: Color,        // 命中闪光
    val good: Color,         // 完美/精彩/良好文字色
    val bad: Color           // 失误色
)

/**
 * 取色规则（用户 2026-09-14 要求：与主题色相近，太浅就加深）：
 * - 亮背景：方块 = 主题色相压深（明度 ~0.20），即"深色块带一点主题味"
 * - 暗背景：方块 = 主题色相提亮（明度 ~0.88），否则深块在暗底上看不见
 * - 轨道底 / 分隔线 / 判定线 同源派生，保证整套颜色一致
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
    val sat = hsv[1].let { if (it < 0.12f) 0.30f else it }   // 主题色是灰调时给一点饱和度，避免块变纯灰

    val blockV = if (bgLight) 0.20f else 0.88f
    val block = hsl(hue, sat * 0.92f, blockV)
    val blockTop = hsl(hue, sat * 0.72f, (blockV + if (bgLight) 0.10f else 0.08f))
    val blockBottom = hsl(hue, sat, (blockV - if (bgLight) 0.08f else 0.14f))
    val laneBg = hsl(hue, if (bgLight) 0.10f else 0.32f, if (bgLight) 0.965f else 0.115f)
    val laneAlt = hsl(hue, if (bgLight) 0.14f else 0.36f, if (bgLight) 0.935f else 0.145f)
    val laneLine = hsl(hue, if (bgLight) 0.30f else 0.45f, if (bgLight) 0.80f else 0.32f)

    return TilePalette(
        block = block,
        blockTop = blockTop,
        blockBottom = blockBottom,
        laneBg = laneBg,
        laneAlt = laneAlt,
        laneLine = laneLine,
        hitLine = primary,
        flash = hsl(hue, sat * 0.35f, 0.99f),
        good = hsl(hue, sat, if (bgLight) 0.45f else 0.85f),
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
    var analyzing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        speed = TileStore.speed(ctx)
        latencyMs = WhackMoleStore.latencyMs(ctx)
        bestClassic = TileStore.classicBestMs(ctx)
        bestArcade = TileStore.arcadeBest(ctx)
        bestRelay = TileStore.relayBestStreak(ctx)
    }

    LaunchedEffect(page) {
        if (page == TilePage.GAME) onPauseMainPlayback()
    }

    // 选歌 → 分析谱面 → 进对局
    val beginGame: (Song) -> Unit = { s ->
        analyzing = true
        scope.launch(Dispatchers.IO) {
            val chart = runCatching { ChartAnalyzer.analyze(ctx, s.uri, s.id, s.durationMs) }.getOrNull()
            withContext(Dispatchers.Main) {
                analyzing = false
                if (chart == null) {
                    Toast.makeText(ctx, "这首歌分析失败，换一首试试", Toast.LENGTH_SHORT).show()
                } else {
                    gameSong = s
                    gameChart = chart
                    page = TilePage.GAME
                }
            }
        }
    }

    BackHandler(enabled = page != TilePage.GAME) { if (page == TilePage.PICK) page = TilePage.HOME else onBack() }

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
            onBack = { page = TilePage.HOME },
            onPick = beginGame
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
                    mode = mode,
                    speed = speed,
                    latencyMs = latencyMs,
                    onLatency = {
                        latencyMs = it
                        scope.launch { WhackMoleStore.setLatencyMs(ctx, it) }
                    },
                    onResult = { usedMs, score, streak ->
                        scope.launch {
                            when (mode) {
                                TileMode.CLASSIC -> {
                                    val (b, _) = TileStore.submitClassic(ctx, usedMs)
                                    bestClassic = b
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
                "4 条轨道 · 白块别碰 · 方块跟着歌曲节拍下落，落到判定线时点掉",
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
                "只影响方块可见时间（反应压力），不改判定容差",
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
                "规则：允许 3 次失误（漏块或踩到白块），失误用尽即结束。经典模式只取前 50 块。",
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

// ════════════════════════════ 选歌页 ════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TilePickSongScreen(
    songs: List<Song>,
    onBack: () -> Unit,
    onPick: (Song) -> Unit
) {
    val sorted = remember(songs) { songs.sortedByDescending { it.dateAdded } }
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
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
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
    mode: TileMode,
    speed: Int,
    latencyMs: Long,
    onLatency: (Long) -> Unit,
    onResult: (usedMs: Long, score: Long, streak: Int) -> Unit,
    onQuit: () -> Unit,
    onPickAnother: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val sounds = remember { HitSounds() }
    var gameKey by remember { mutableStateOf(0) }
    val engine = remember(gameKey) { TileEngine(chart, 0L, chart.durationMs, mode) }
    val palette = rememberTilePalette()
    val speedMs = when (speed) {
        0 -> 1500L
        2 -> 750L
        else -> 1050L
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
    var frameNow by remember { mutableStateOf(0L) }
    var bannerTick by remember { mutableStateOf(0) }   // 触发 banner 重绘
    var reported by remember(gameKey) { mutableStateOf(false) }

    fun finishGame() {
        if (reported) return
        reported = true
        player.pause()
        phase = TilePhase.RESULT
        onResult(engine.usedMs, engine.score, engine.relayMaxStreak)
        scope.launch { TileStore.addTiles(ctx, engine.hitTiles) }
    }

    LaunchedEffect(gameKey) {
        paused = false
        reported = false
        player.setMediaItem(MediaItem.fromUri(song.uri))
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
                engine.tick(pos)
                frameNow = pos
                bannerTick = engine.bannerAt.toInt()
                if (engine.finished || player.playbackState == Player.STATE_ENDED) {
                    finishGame()
                    break
                }
                delay(16)
            }
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

    Scaffold { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            // ── 顶部信息条 ──
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("${engine.score}", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    val sub = when (mode) {
                        TileMode.CLASSIC -> "经典 · ${engine.classicProgress}/${TileEngine.CLASSIC_TILES}"
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
                // 失误额度（3 颗像素方块，暗掉 = 已用）
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

            // ── 下落区 ──
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(gameKey) {
                            detectTapGestures { offset ->
                                if (phase != TilePhase.PLAY || paused) return@detectTapGestures
                                val laneW = size.width / 4f
                                val lane = (offset.x / laneW).toInt().coerceIn(0, 3)
                                val res = engine.tap(lane, engine.now)
                                val (st, _, isMiss) = res
                                when {
                                    isMiss -> sounds.play(MISS_FREQ, 0.5f, 0)
                                    st == NoteState.PERFECT -> sounds.play(laneFreq(lane), 0.45f, 0)
                                    st == NoteState.GREAT -> sounds.play(laneFreq(lane) * 0.9f, 0.4f, 0)
                                    else -> sounds.play(laneFreq(lane) * 0.8f, 0.35f, 0)
                                }
                            }
                        }
                ) {
                    drawTileField(engine, palette, frameNow, speedMs)
                }

                // 提示条（接上 / 失误）
                val sinceBanner = SystemClock.elapsedRealtime() - engine.bannerAt
                if (engine.banner.isNotBlank() && sinceBanner < 900) {
                    val alpha = 1f - sinceBanner / 900f
                    Text(
                        engine.banner,
                        fontWeight = FontWeight.Bold,
                        color = if (engine.banner.startsWith("接上")) palette.hitLine else palette.bad,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 18.dp)
                            .background(
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.7f * alpha),
                                RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }

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
                        Text("黑块到线就点 · 白块别碰", style = MaterialTheme.typography.bodySmall)
                    }
                }
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
                        TileMode.CLASSIC -> if (engine.hitTiles >= TileEngine.CLASSIC_TILES) "用时 ${fmtSec(engine.usedMs)}" else "打完 ${engine.hitTiles}/${TileEngine.CLASSIC_TILES} 块"
                        TileMode.ARCADE -> "得分 ${engine.score}"
                        TileMode.RELAY -> "最长连续 ${engine.relayMaxStreak} 段"
                    }
                    Text(head, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "完美 ${engine.perfect} · 精彩 ${engine.great} · 良好 ${engine.good} · 失误 ${engine.missCount}",
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
 * 像素风下落场：轨道底 + 分隔线 + 判定线 + 方块（顶部高光/底部暗边/像素缺口）
 * 所有矩形坐标取整 → 无抗锯齿模糊，和打地鼠的像素观感一致
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTileField(
    engine: TileEngine,
    p: TilePalette,
    now: Long,
    speedMs: Long
) {
    val w = size.width
    val h = size.height
    val laneWi = (w / 4f).toInt().coerceAtLeast(1)
    val hitY = (h * 0.82f).toInt()
    val noteH = (laneWi * 0.42f).toInt().coerceIn(26, 92)
    val pxPerMs = hitY.toFloat() / speedMs.toFloat()

    // 轨道底（交替色）
    for (i in 0..3) {
        drawRect(
            if (i % 2 == 0) p.laneBg else p.laneAlt,
            topLeft = Offset((i * laneWi).toFloat(), 0f),
            size = Size(laneWi.toFloat(), h)
        )
    }
    // 分隔线（2px 像素线）
    for (i in 1..3) {
        drawRect(
            p.laneLine.copy(alpha = 0.55f),
            topLeft = Offset((i * laneWi - 1).toFloat(), 0f),
            size = Size(2f, h)
        )
    }

    // 判定线：3px 实线 + 上下各 1px 暗边，像素味
    drawRect(
        p.hitLine.copy(alpha = 0.22f),
        topLeft = Offset(0f, (hitY - 5).toFloat()),
        size = Size(w, 2f)
    )
    drawRect(p.hitLine, topLeft = Offset(0f, (hitY - 2).toFloat()), size = Size(w, 3f))
    drawRect(
        p.hitLine.copy(alpha = 0.35f),
        topLeft = Offset(0f, (hitY + 1).toFloat()),
        size = Size(w, 1f)
    )

    val nowRt = SystemClock.elapsedRealtime()

    for (i in 0 until engine.notes.size) {
        val n = engine.notes[i]
        val dt = n.timeMs - now
        val y = hitY - (dt * pxPerMs).toInt()
        // 出屏裁剪（上方留一个块高、下方多留一点给判定后的闪光）
        if (y - noteH > h || y < -noteH) continue

        val st = engine.stateOf(i)
        val jAt = engine.judgedAtOf(i)
        val fading = st != NoteState.PENDING && jAt > 0 && nowRt - jAt < 200
        val alpha = if (fading) 1f - (nowRt - jAt) / 200f else 1f
        val laneX = n.lane * laneWi + 3
        val boxW = (laneWi - 6).coerceAtLeast(8)

        when (st) {
            NoteState.PENDING -> {
                drawRect(
                    p.block,
                    topLeft = Offset(laneX.toFloat(), (y - noteH).toFloat()),
                    size = Size(boxW.toFloat(), noteH.toFloat())
                )
                // 顶部像素高光 + 底部像素暗边
                drawRect(
                    p.blockTop,
                    topLeft = Offset(laneX.toFloat(), (y - noteH).toFloat()),
                    size = Size(boxW.toFloat(), 3f)
                )
                drawRect(
                    p.blockBottom,
                    topLeft = Offset(laneX.toFloat(), (y - 3).toFloat()),
                    size = Size(boxW.toFloat(), 3f)
                )
                // 像素缺口（左侧小凹口，纯装饰）
                drawRect(
                    p.blockBottom,
                    topLeft = Offset((laneX + 4).toFloat(), (y - noteH + 7).toFloat()),
                    size = Size(4f, 4f)
                )
            }
            NoteState.EMPTY_TAP -> {}
            else -> if (fading) {
                val c = if (st == NoteState.MISS || st == NoteState.EMPTY_TAP) p.bad else p.flash
                drawRect(
                    c.copy(alpha = alpha * 0.85f),
                    topLeft = Offset(laneX.toFloat(), (y - noteH).toFloat()),
                    size = Size(boxW.toFloat(), noteH.toFloat())
                )
            }
        }
    }

    // 判定线附近的"脉冲"（最近一次点击位置由 engine.banner 间接体现，这里只画静态刻度）
    for (i in 0..3) {
        val cx = i * laneWi + laneWi / 2
        drawRect(
            p.hitLine.copy(alpha = 0.5f),
            topLeft = Offset((cx - 2).toFloat(), (hitY + 5).toFloat()),
            size = Size(4f, 8f)
        )
    }
}
