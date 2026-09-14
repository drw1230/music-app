package com.dengdeng.music.ui

import android.graphics.Bitmap
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.AudioAttributes as M3AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.dengdeng.music.data.ChartAnalyzer
import com.dengdeng.music.data.Song
import com.dengdeng.music.data.WhackMoleStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 打地鼠（基本版）——听歌模式
 *
 * 流程：游戏中心 → 打地鼠 → [听歌模式/标准模式(占位)/游戏记录] → 选本地歌 → 快速(默认)/完整 → 3-2-1 → 对局 → 结算
 * 记录（用户 2026-09-13 拍板）：总分(累计) + 单曲最高分 + 最大Combo，存本机不联网；
 * 延迟校准无感方案不可靠 → 放「!」说明里手动微调（滑条，立即保存）。
 * 炸弹鼠扣分当前默认 -50 + 连击减半（常量 WhackMoleEngine.BOMB_PENALTY，数值待用户试玩后定）。
 */
private const val TAG = "DDmusicMole"

// ════════════════════════════ 游戏引擎（纯逻辑，可独立测试） ════════════════════════════

class WhackMoleEngine(
    chart: ChartAnalyzer.Chart,
    private val startMs: Long,
    private val endMs: Long
) {
    companion object {
        const val LEAD_MS = 500L          // 地鼠提前冒头时间（给玩家反应）
        const val HIT_WINDOW_MS = 220L    // 拍点后仍可命中的窗口
        const val BOMB_PENALTY = 50L      // 炸弹误击扣分（待用户试玩后定值）
        const val BONUS_SCORE = 30L       // 加分鼠命中得分（固定值，待用户试玩后定）
    }

    class Floating(val id: Int, val cell: Int, val text: String, val kind: Int, val createdAt: Long) // kind: 0=得分 1=扣分 2=漏 3=加分鼠

    /** 命中粒子：从命中点向 dx/dy 方向飞散的小像素方块 */
    class Particle(val id: Int, val cell: Int, val dx: Float, val dy: Float, val color: Int, val createdAt: Long)

    class Mole(val event: ChartAnalyzer.ChartEvent, val cell: Int, val appearMs: Long, val deadlineMs: Long)

    private val events = chart.events.filter { it.timeMs >= startMs + LEAD_MS && it.timeMs <= endMs - HIT_WINDOW_MS }
    private var idx = 0
    private var now = startMs

    val moles = List(9) { mutableStateOf<Mole?>(null) }
    val floatings = mutableStateListOf<Floating>()
    val particles = mutableStateListOf<Particle>()
    val cellFlashMs = LongArray(9)      // 每格最近一次被点击的时间戳（点击闪光反馈用）
    var score by mutableStateOf(0L); private set
    var combo by mutableStateOf(0); private set
    var maxCombo = 0; private set
    var hits = 0; private set
    var missed = 0; private set
    var bombHits = 0; private set
    var bombPenalty = 0L; private set
    var bonusHits = 0; private set

    val finished: Boolean get() = now >= endMs

    private var fid = 0
    private var pid = 0
    private fun addFloat(cell: Int, text: String, kind: Int) {
        floatings.add(Floating(fid++, cell, text, kind, SystemClock.elapsedRealtime()))
        if (floatings.size > 15) floatings.removeRange(0, 5)
    }

    fun gcFloats(nowRt: Long) {
        if (floatings.isNotEmpty()) floatings.removeAll { nowRt - it.createdAt > 650 }
        if (particles.isNotEmpty()) particles.removeAll { nowRt - it.createdAt > 420 }
    }

    /** 命中点像素粒子迸溅：style 0=普通/空点 1=炸弹 2=加分鼠（金色） */
    private fun burst(cell: Int, style: Int) {
        val colors = when (style) {
            1 -> intArrayOf(0xFFD33B2F.toInt(), 0xFF803020.toInt(), 0xFF666666.toInt())
            2 -> intArrayOf(0xFFFFD700.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFA726.toInt())
            else -> intArrayOf(0xFFFFD54F.toInt(), 0xFFFF8A3D.toInt(), 0xFFF5F0E8.toInt())
        }
        repeat(6) {
            val ang = Math.random() * 2 * Math.PI
            val dist = 0.5 + Math.random() * 0.5
            particles.add(
                Particle(
                    pid++, cell,
                    (Math.cos(ang) * dist).toFloat(),
                    (Math.sin(ang) * dist).toFloat(),
                    colors[(Math.random() * colors.size).toInt()],
                    SystemClock.elapsedRealtime()
                )
            )
        }
        if (particles.size > 48) particles.removeRange(0, 12)
    }

    /** 每帧调用；nowMs = 播放位置 - 延迟校准偏移 */
    fun tick(nowMs: Long) {
        now = nowMs
        // 冒头
        while (idx < events.size && events[idx].timeMs - LEAD_MS <= nowMs) {
            val ev = events[idx++]
            val free = (0..8).filter { moles[it].value == null }
            if (free.isEmpty()) continue
            val cell = free.random()
            moles[cell].value = Mole(ev, cell, ev.timeMs - LEAD_MS, ev.timeMs + HIT_WINDOW_MS)
        }
        // 过期（普通鼠漏掉 → 连击减半）
        for (i in 0..8) {
            val m = moles[i].value ?: continue
            if (nowMs > m.deadlineMs) {
                if (m.event.type == ChartAnalyzer.TYPE_NORMAL) {
                    missed++
                    combo /= 2
                    addFloat(i, "漏!", 2)
                }
                moles[i].value = null
            }
        }
    }

    /** 点击某格：null=空格无事件；否则返回 (分值/扣分, 鼠类型)。任何点击都记录闪光与粒子 */
    fun tap(cell: Int): Pair<Long, Int>? {
        cellFlashMs[cell] = SystemClock.elapsedRealtime()
        val m = moles[cell].value
        if (m == null) {
            burst(cell, 0)   // 空点也有轻微反馈粒子
            return null
        }
        return when (m.event.type) {
            ChartAnalyzer.TYPE_NORMAL -> {
                moles[cell].value = null
                val p = (now - startMs).toFloat() / (endMs - startMs).coerceAtLeast(1)
                val mult = 1.0 + (p * 5).toInt() * 0.2      // 分段难度系数 1.0→1.8
                val pts = (10 * mult).toLong()
                score += pts; hits++; combo++
                if (combo > maxCombo) maxCombo = combo
                addFloat(cell, "+$pts", 0)
                burst(cell, 0)
                pts to ChartAnalyzer.TYPE_NORMAL
            }
            ChartAnalyzer.TYPE_BOMB -> {
                moles[cell].value = null
                bombHits++
                bombPenalty += BOMB_PENALTY
                score = (score - BOMB_PENALTY).coerceAtLeast(0)
                combo /= 2
                addFloat(cell, "-$BOMB_PENALTY", 1)
                burst(cell, 1)
                -BOMB_PENALTY to ChartAnalyzer.TYPE_BOMB
            }
            ChartAnalyzer.TYPE_BONUS -> {
                moles[cell].value = null
                bonusHits++
                score += BONUS_SCORE; hits++; combo++
                if (combo > maxCombo) maxCombo = combo
                addFloat(cell, "+$BONUS_SCORE", 3)
                burst(cell, 2)
                BONUS_SCORE to ChartAnalyzer.TYPE_BONUS
            }
            else -> null
        }
    }
}

// ════════════════════════════ 敲击音效（五声音阶，AudioTrack 静态流） ════════════════════════════

/** 九宫格音高：上排高音 → 下排低音（G5 E5 D5 / C5 A4 G4 / E4 D4 C4）；空点=轻"噗"165Hz，炸弹=低沉 75Hz */
private val CELL_FREQS = floatArrayOf(784f, 659f, 587f, 523f, 440f, 392f, 330f, 294f, 262f)

/** 按键音效风格（13 种，全部纯代码合成零素材；键盘/打击类固定音高，音乐类音高跟格子） */
private data class MoleSoundStyle(val id: Int, val name: String, val desc: String)
private val MOLE_SOUND_STYLES = listOf(
    MoleSoundStyle(0, "像素方波", "8-bit 芯片音 · 音高跟格子走"),
    MoleSoundStyle(1, "苹果键盘", "iOS 输入法 · 均匀嗒嗒感"),
    MoleSoundStyle(2, "青轴机械", "段落咔哒 · 清脆吵闹"),
    MoleSoundStyle(3, "红轴机械", "圆润咚咚 · 安静"),
    MoleSoundStyle(4, "街机跳跃", "8-bit 上跳音 · 音高跟格子走"),
    MoleSoundStyle(5, "木琴", "清亮木质 · 音高跟格子走"),
    MoleSoundStyle(6, "水滴", "清脆上滑水珠 · 音高跟格子走"),
    MoleSoundStyle(7, "木鱼", "笃笃木质 · 音高跟格子走"),
    MoleSoundStyle(8, "军鼓", "噪声爆响 · 固定"),
    MoleSoundStyle(9, "钢琴", "泛音柔和 · 音高跟格子走"),
    MoleSoundStyle(10, "电子 click", "极短哒 · 固定"),
    MoleSoundStyle(11, "泡泡", "Q 弹下滑 · 音高跟格子走"),
    MoleSoundStyle(12, "拍手", "双击掌声 · 固定")
)

/**
 * 菜单试听用的固定音高（中音 C5；固定音高的键盘/打击类会忽略此参数）。
 * 常用位不再固定写死，改由 WhackMoleStore.sndRecent 维护"最近用过的 3 个"。
 */
private const val PREVIEW_FREQ = 523f

// ════════════════════════════ 像素画（MoleArt 字符矩阵 → 预烘焙位图，零素材文件） ════════════════════════════
// 美术数据见 MoleArt.kt（美术脚本生成的唯一来源，勿手改）；这里只负责「烘焙贴图 + 四层合成」。
// 一格 36×42 像素，地鼠精灵 24×28 放在格内 (6,10)。四层顺序：
//   ① holeBack 土丘+洞口深色 → ② 地鼠（按洞口下弧收窄）→ ③ dim 洞内阴影 → ④ holeFront 前沿
// ⚠️ 逐像素 drawRect 画一格要 5000+ 次调用（9 格 4.5 万次/帧）会卡 → 必须先烘焙成位图。

/** 字符矩阵 → 1:1 位图；绘制时按整数倍最近邻放大，像素边缘不糊 */
private fun bakePattern(rows: Array<String>): ImageBitmap {
    val w = rows[0].length
    val h = rows.size
    val px = IntArray(w * h)
    for (y in 0 until h) {
        val row = rows[y]
        for (x in 0 until w) {
            px[y * w + x] = MoleArt.PAL[row[x]] ?: 0
        }
    }
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    bmp.setPixels(px, 0, w, 0, 0, w, h)
    return bmp.asImageBitmap()
}

/** 洞内阴影：每字符是 alpha/17 的十六进制（0 = 透明） */
private fun bakeDim(rows: Array<String>): ImageBitmap {
    val w = rows[0].length
    val h = rows.size
    val px = IntArray(w * h)
    for (y in 0 until h) {
        val row = rows[y]
        for (x in 0 until w) {
            val a = Character.digit(row[x], 16)
            if (a > 0) px[y * w + x] = (a * 17 shl 24) or 0x0C0600
        }
    }
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    bmp.setPixels(px, 0, w, 0, 0, w, h)
    return bmp.asImageBitmap()
}

/**
 * 背景：按行 RLE（"字符x次数"，空格分隔）解码成位图。
 * 108×192 的字符矩阵是 20.7KB/套，4 套直接写进代码要 83KB → RLE 后每套约 1~2KB。
 */
private fun bakeBg(rows: Array<String>): ImageBitmap {
    val w = MoleArt.SCENE_W
    val h = rows.size
    val px = IntArray(w * h)
    for (y in 0 until h) {
        var x = 0
        for (run in rows[y].split(' ')) {
            val xi = run.indexOf('x')
            if (xi <= 0) continue
            val color = MoleArt.PAL[run[xi - 1]] ?: 0
            val end = minOf(w, x + run.substring(xi + 1).toInt())
            var i = x
            while (i < end) {
                px[y * w + i] = color
                i++
            }
            x = end
        }
    }
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    bmp.setPixels(px, 0, w, 0, 0, w, h)
    return bmp.asImageBitmap()
}

/**
 * 按当前时段自动选背景（与美术预览的时段表完全一致）：
 * 凌晨/深夜=极简暗色，清晨+白天=白天草地，黄昏=落日，夜晚=星空。
 */
private fun pickBgByTime(): Int {
    val c = java.util.Calendar.getInstance()
    val h = c.get(java.util.Calendar.HOUR_OF_DAY) + c.get(java.util.Calendar.MINUTE) / 60f
    return when {
        h < 6f -> MoleArt.BG_MINIMAL      // 凌晨
        h < 8f -> MoleArt.BG_DAY          // 清晨
        h < 17f -> MoleArt.BG_DAY         // 白天
        h < 19.5f -> MoleArt.BG_DUSK      // 黄昏
        h < 22.5f -> MoleArt.BG_NIGHT     // 夜晚
        else -> MoleArt.BG_MINIMAL        // 深夜
    }
}

/**
 * 全屏铺底：把 108×192 的场景图「边缘延展」铺满整屏，而不是拉伸缩放。
 *
 * 为什么不用 cover 缩放：场景图里**草地和九宫格洞口本身就是背景的一部分**（土丘画在图上），
 * 一旦按 cover 放大，草地线就会和九宫格盖上去的洞口错位（地鼠浮在天上）。
 * 所以做法是：场景图仍按整数倍 k 画在它该在的位置（sw×sh @ sx,sy），
 * 场景之外的四块区域分别用「最近的一条边框像素」拉出去补——
 * 上方补顶行（夜空/天空，接缝同色看不出）、下方补底行（草地）、左右补最边上一列。
 * 结果：整屏无黑边、无变形、九宫格与草地严丝合缝。
 */
private fun DrawScope.drawFullBleedBg(
    kind: Int,
    nowMs: Long,
    sx: Float, sy: Float, sw: Float, sh: Float,
    k: Int
) {
    val img = MoleSprites.scenes[kind]
    val src = IntSize(MoleArt.SCENE_W, MoleArt.SCENE_H)
    val W = size.width
    val H = size.height
    val sxI = sx.roundToInt()
    val syI = sy.roundToInt()
    val swI = sw.roundToInt()
    val shI = sh.roundToInt()

    fun patch(sx0: Int, sy0: Int, sw0: Int, sh0: Int, dx: Int, dy: Int, dw: Int, dh: Int) {
        if (dw <= 0 || dh <= 0) return
        drawImage(
            img, IntOffset(sx0, sy0), IntSize(sw0, sh0),
            IntOffset(dx, dy), IntSize(dw, dh), filterQuality = FilterQuality.None
        )
    }

    // 上：把场景第一行拉满整个顶部横带（含左右上角）
    val topH = syI
    patch(0, 0, MoleArt.SCENE_W, 1, 0, 0, W.roundToInt(), topH)
    // 下：把场景最后一行拉满整个底部横带（含左右下角）
    val botY = syI + shI
    patch(0, MoleArt.SCENE_H - 1, MoleArt.SCENE_W, 1, 0, botY, W.roundToInt(), H.roundToInt() - botY)
    // 左：最左一列
    patch(0, 0, 1, MoleArt.SCENE_H, 0, syI, sxI, shI)
    // 右：最右一列
    patch(MoleArt.SCENE_W - 1, 0, 1, MoleArt.SCENE_H, sxI + swI, syI, W.roundToInt() - sxI - swI, shI)
    // 场景本体
    patch(0, 0, MoleArt.SCENE_W, MoleArt.SCENE_H, sxI, syI, swI, shI)

    // 动态层（云/星/萤火虫）与场景同一坐标系 → 平移到场景原点再按 k 画
    translate(sxI.toFloat(), syI.toFloat()) { drawDynBg(kind, nowMs, k) }
}

/**
 * 动态背景（叠在静态背景之上、九宫格之下，所以萤火虫会从格子间的草地缝里透出来）：
 * - 云：白天/黄昏，向右缓慢飘动，出屏后从左侧绕回
 * - 星星：夜景/极简全部显示，黄昏只留一半；各自按周期闪烁，部分带十字光芒
 * - 萤火虫：夜景/极简/黄昏，在草地上缓慢游走 + 呼吸式明暗
 * 坐标全是美术像素（×k 后取整到设备像素）→ 和背景一样是像素风，不会糊。
 */
private fun DrawScope.drawDynBg(kind: Int, now: Long, k: Int) {
    val night = kind == MoleArt.BG_NIGHT || kind == MoleArt.BG_MINIMAL
    val dusk = kind == MoleArt.BG_DUSK
    val t = now / 1000f

    /** 画一个美术像素（对齐到设备像素网格，保持硬边） */
    fun dot(color: Color, x: Float, y: Float) = drawRect(
        color,
        topLeft = Offset((x * k).roundToInt().toFloat(), (y * k).roundToInt().toFloat()),
        size = Size(k.toFloat(), k.toFloat())
    )

    // ── 云（白天 / 黄昏）──
    if (kind == MoleArt.BG_DAY || dusk) {
        val col = if (kind == MoleArt.BG_DAY) Color.White else Color(0xFF5A3350)
        val alphaK = if (kind == MoleArt.BG_DAY) 1f else 0.85f
        for (ci in 0 until MoleArt.CLOUDS.size / 5) {
            val x0 = MoleArt.CLOUDS[ci * 5]
            val y0 = MoleArt.CLOUDS[ci * 5 + 1]
            val sp = MoleArt.CLOUDS[ci * 5 + 2]
            val s = MoleArt.CLOUDS[ci * 5 + 3]
            val a = MoleArt.CLOUDS[ci * 5 + 4]
            val cw = MoleArt.CLOUD_M[0].length * s * 2f
            val span = MoleArt.SCENE_W + cw
            val bx = ((((x0 + t * sp * 0.8f) % span) + span) % span - cw) * k
            val by = y0 * k
            val cell = s * 2f * k
            for (my in MoleArt.CLOUD_M.indices) {
                val row = MoleArt.CLOUD_M[my]
                for (mx in row.indices) {
                    if (row[mx] != '#') continue
                    drawRect(
                        col.copy(alpha = a * alphaK),
                        topLeft = Offset(bx + mx * cell, by + my * cell),
                        size = Size(cell, cell)
                    )
                }
            }
        }
    }

    // ── 星星（夜景 / 极简 / 黄昏）──
    if (night || dusk) {
        val total = MoleArt.STARS.size / 6
        val n = if (kind == MoleArt.BG_MINIMAL) 10 else if (dusk) 7 else total
        val starCol = Color(0xFFFFF6D0)
        for (i in 0 until n) {
            if (dusk && i >= 3 && i % 2 == 1) continue
            val x = MoleArt.STARS[i * 6]
            val y = MoleArt.STARS[i * 6 + 1]
            val ph = MoleArt.STARS[i * 6 + 2]
            val sp = MoleArt.STARS[i * 6 + 3]
            val big = MoleArt.STARS[i * 6 + 4] > 0.5f
            val base = MoleArt.STARS[i * 6 + 5]
            val a = ((if (base > 0f) 0.15f else 0.2f) +
                0.8f * abs(sin(now / (1000f / sp) * 1.6f + ph))).coerceAtMost(1f)
            val c = starCol.copy(alpha = a)
            dot(c, x, y)
            if (big) {
                dot(c, x - 1f, y)
                dot(c, x + 1f, y)
                dot(c, x, y - 1f)
                dot(c, x, y + 1f)
            }
        }
    }

    // ── 萤火虫（夜景 / 极简 / 黄昏）──
    if (night || dusk) {
        val n = if (kind == MoleArt.BG_MINIMAL) 3 else 6
        val fireCol = Color(0xFFFFE08A)
        for (i in 0 until n) {
            val fx = MoleArt.FIRES[i * 4]
            val fy = MoleArt.FIRES[i * 4 + 1]
            val ph = MoleArt.FIRES[i * 4 + 2]
            val sp = MoleArt.FIRES[i * 4 + 3]
            val x = fx + sin(t * 1.6f + ph) * 6f
            val y = fy + cos(t / 1.3f * 1.6f + ph * 1.7f) * 4f
            val a = 0.25f + 0.75f * abs(sin(now / (1000f / sp) * 2.1f + ph))
            dot(fireCol.copy(alpha = a), x, y)
        }
    }
}

/** 预烘焙贴图（9 格共用；每帧每格 4 次整图 drawImage + 地鼠逐行收窄） */
private object MoleSprites {
    val holeBack by lazy { bakePattern(MoleArt.HOLE_BACK) }
    val holeFront by lazy { bakePattern(MoleArt.HOLE_FRONT) }
    val dim by lazy { bakeDim(MoleArt.DIM) }
    val bodies by lazy { List(3) { bakePattern(MoleArt.idle(it)) } }

    /** 4 套场景背景（按时段选） */
    val scenes by lazy { List(4) { bakeBg(MoleArt.bg(it)) } }

    /**
     * 每个「格内行」可见的格内列区间 [lo, hi]；lo > hi 表示该行整个看不见。
     *
     * 这就是「洞口裁剪罩」：土丘后层在土丘轮廓之外是透明的，地鼠只要越出洞口
     * 就会直接露在背景上（表现为「洞的下边缘漏出一条地鼠」）。
     * ARC_MAX_ROW 单峰 → 每行可见列必然连续，所以只要把 drawImage 的源矩形按
     * 这个区间收窄，就等于逐列裁剪，且不需要任何 clip 变换（坐标全整数，像素不糊）。
     */
    val rowCols: Array<IntArray> by lazy {
        Array(MoleArt.CELL_H) { cy ->
            var lo = -1
            var hi = -2
            for (cx in 0 until MoleArt.CELL_W) {
                if (MoleArt.ARC_MAX_ROW[cx] >= cy) {
                    if (lo < 0) lo = cx
                    hi = cx
                }
            }
            intArrayOf(lo, hi)
        }
    }
}

private const val EMERGE_MS = 200f     // 冒头时长（完全沉没 → 全升）
private const val RETREAT_MS = 130L    // 缩回洞里时长

/** 冒头曲线：末段轻微过冲（冒出来更有"顶"的感觉） */
private fun easeOutBack(p: Float): Float {
    val c1 = 1.70158f
    val q = p - 1f
    return 1f + (c1 + 1f) * q * q * q + c1 * q * q
}

internal class HitSounds {
    private val sampleRate = 44100
    private val samples = sampleRate * 90 / 1000
    private var track: AudioTrack? = null
    private var builtFreq = 0f
    private var builtAmp = 0f
    private var builtStyle = -1

    /** 按风格合成波形（style 见 MOLE_SOUND_STYLES；键盘类风格音高固定，音乐类音高跟格子） */
    private fun synth(freq: Float, amp: Float, style: Int, out: ShortArray) {
        val f = freq.toDouble()
        for (i in out.indices) {
            val t = i.toDouble() / sampleRate
            val v: Double = when (style) {
                1 -> {   // 苹果键盘：高频短嗒 + 细微噪声
                    val env = Math.exp(-t * 110.0)
                    (Math.sin(2 * Math.PI * 1900 * t) * 0.8 + (Math.random() - 0.5) * 0.4) * env
                }
                2 -> {   // 青轴：前 3ms 噪声 click + 3.2kHz ping
                    val env = Math.exp(-t * 80.0)
                    val click = if (t < 0.003) (Math.random() - 0.5) else 0.0
                    (click * 1.2 + Math.sin(2 * Math.PI * 3200 * t) * 0.5) * env
                }
                3 -> {   // 红轴：闷响 thock（原 150Hz 太低，手机小喇叭根本推不动 → 抬到中频 + 塑料撞击成分）
                    val env = Math.exp(-t * 32.0)
                    val click = Math.exp(-t * 300.0)
                    (Math.sin(2 * Math.PI * 460 * t) * 0.60 +
                            Math.sin(2 * Math.PI * 230 * t) * 0.25) * env +
                            ((Math.random() - 0.5) * 0.5 +
                                    Math.sin(2 * Math.PI * 2600 * t) * 0.16) * click
                }
                4 -> {   // 街机跳跃：方波两段升调
                    val ff = if (t < 0.045) f * 1.335 else f * 1.78
                    val env = Math.exp(-t * 26.0)
                    (if (Math.sin(2 * Math.PI * ff * t) >= 0) 1.0 else -1.0) * 0.8 * env
                }
                5 -> {   // 木琴：正弦 + 4 倍频 + 9.2 倍频泛音
                    val env = Math.exp(-t * 20.0)
                    (Math.sin(2 * Math.PI * f * t) +
                            Math.sin(2 * Math.PI * f * 4 * t) * 0.35 +
                            Math.sin(2 * Math.PI * f * 9.2 * t) * 0.12) * 0.7 * env
                }
                6 -> {   // 水滴：正弦快速上滑
                    val env = Math.exp(-t * 45.0)
                    val ff = f * (1.0 + 1.2 * (t / 0.09))
                    Math.sin(2 * Math.PI * ff * t) * env
                }
                7 -> {   // 木鱼：高频短促 + 快衰减
                    val env = Math.exp(-t * 70.0)
                    (Math.sin(2 * Math.PI * f * 2.4 * t) * 0.7 +
                            Math.sin(2 * Math.PI * f * 5.1 * t) * 0.3) * env
                }
                8 -> {   // 军鼓：噪声爆响 + 低频体
                    val env = Math.exp(-t * 55.0)
                    ((Math.random() - 0.5) * 1.4 + Math.sin(2 * Math.PI * 180 * t) * 0.5) * env
                }
                9 -> {   // 钢琴：多泛音慢衰减
                    val env = Math.exp(-t * 9.0)
                    (Math.sin(2 * Math.PI * f * t) +
                            Math.sin(2 * Math.PI * f * 2 * t) * 0.5 +
                            Math.sin(2 * Math.PI * f * 3 * t) * 0.25 +
                            Math.sin(2 * Math.PI * f * 4 * t) * 0.12) * 0.6 * env
                }
                10 -> {  // 电子 click：极短高频方波
                    val env = Math.exp(-t * 160.0)
                    (if (Math.sin(2 * Math.PI * 2400 * t) >= 0) 1.0 else -1.0) * 0.9 * env
                }
                11 -> {  // 泡泡：正弦下滑 pop
                    val env = Math.exp(-t * 40.0)
                    val ff = f * (1.8 - 0.9 * (t / 0.09))
                    Math.sin(2 * Math.PI * ff * t) * env
                }
                12 -> {  // 拍手：双段噪声（主拍 + 12ms 后弱拍）
                    val e1 = Math.exp(-t * 90.0)
                    val e2 = if (t > 0.012) Math.exp(-(t - 0.012) * 70.0) * 0.7 else 0.0
                    ((Math.random() - 0.5) * 1.2) * (e1 + e2)
                }
                else -> { // 0 像素方波：基频 + 高八度
                    val env = Math.exp(-t * 30.0)
                    val sq = { x: Double -> if (Math.sin(2 * Math.PI * x * t) >= 0) 1.0 else -1.0 }
                    (0.72 * sq(f) + 0.28 * sq(f * 2)) * env
                }
            }
            out[i] = (v * amp * Short.MAX_VALUE).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    fun play(freq: Float, amp: Float = 0.4f, style: Int = 0) {
        try {
            val bytes = samples * 2
            val t = track ?: AudioTrack(
                AudioManager.STREAM_MUSIC, sampleRate,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, bytes,
                AudioTrack.MODE_STATIC
            ).also { track = it }
            if (builtFreq != freq || builtAmp != amp || builtStyle != style) {
                val buf = ShortArray(samples)
                synth(freq, amp, style, buf)
                t.write(buf, 0, samples)
                builtFreq = freq; builtAmp = amp; builtStyle = style
            }
            runCatching { t.pause() }
            t.reloadStaticData()
            t.play()
        } catch (_: Exception) {
        }
    }

    fun release() {
        runCatching { track?.release() }
        track = null
    }
}

// ════════════════════════════ 入口页（模式选择 / 记录 / 选歌 / 对局 的路由） ════════════════════════════

private enum class MolePage { HOME, PICK, GAME, RECORDS }

@Composable
fun WhackMoleScreen(onBack: () -> Unit, songs: List<Song>, onPauseMainPlayback: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf(MolePage.HOME) }
    var showMenu by remember { mutableStateOf(false) }

    // 菜单设置（音效风格 / 触觉震动 / 延迟校准），全局一份，对局内同步使用
    var sndStyle by remember { mutableStateOf(0) }
    var hapticsOn by remember { mutableStateOf(true) }
    // 常用位：最近用过的 3 个手感（用户 2026-09-13 定：三个常用位都参与轮换）
    var sndRecent by remember { mutableStateOf(listOf(0, 1, 2)) }
    // 背景主题：-1=按时段自动，其余为 MoleArt.BG_*（新对局默认按时段，可在菜单手动固定）
    var bgKind by remember { mutableStateOf(-1) }

    // 对局参数
    var gameSong by remember { mutableStateOf<Song?>(null) }
    var gameChart by remember { mutableStateOf<ChartAnalyzer.Chart?>(null) }
    var gameStart by remember { mutableStateOf(0L) }
    var gameEnd by remember { mutableStateOf(0L) }
    var analyzing by remember { mutableStateOf(false) }
    var pendingSong by remember { mutableStateOf<Song?>(null) }
    var showModeDialog by remember { mutableStateOf(false) }

    // 延迟校准偏移 + 菜单设置（!说明里调）
    var latencyMs by remember { mutableStateOf(120L) }
    LaunchedEffect(Unit) {
        latencyMs = WhackMoleStore.latencyMs(ctx)
        sndStyle = WhackMoleStore.sndStyle(ctx)
        hapticsOn = WhackMoleStore.hapticsOn(ctx)
        sndRecent = WhackMoleStore.sndRecent(ctx)
        bgKind = WhackMoleStore.bgKind(ctx)
    }

    // 选中手感：持久化 + 置顶到常用位（最久没选的被挤出常用栏）
    val pickSnd: (Int) -> Unit = { v ->
        sndStyle = v
        scope.launch {
            WhackMoleStore.setSndStyle(ctx, v)
            sndRecent = WhackMoleStore.touchSndRecent(ctx, v)
        }
    }

    // 切背景主题：立即生效 + 持久化（-1=恢复按时段自动）
    val pickBg: (Int) -> Unit = { v ->
        bgKind = v
        scope.launch { WhackMoleStore.setBgKind(ctx, v) }
    }

    // 进对局前暂停主播放，避免游戏音乐和主播放器两首歌同时响；
    // 进选歌页刷新"玩过的歌"映射（选歌页置顶排序用）
    var playedMap by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    LaunchedEffect(page) {
        if (page == MolePage.GAME) onPauseMainPlayback()
        if (page == MolePage.PICK) playedMap = WhackMoleStore.lastPlayedMap(ctx)
    }

    val startPlay: (Song, ChartAnalyzer.Chart, Long, Long) -> Unit = { s, c, st, en ->
        gameSong = s; gameChart = c; gameStart = st; gameEnd = en
        page = MolePage.GAME
    }

    // 选歌后：先做谱面分析（缓存后秒开），再进对局；modeMs>0=精彩片段时长，0=整首
    val beginGame: (Song, Long) -> Unit = { s, modeMs ->
        analyzing = true
        scope.launch(Dispatchers.IO) {
            val chart = runCatching { ChartAnalyzer.analyze(ctx, s.uri, s.id, s.durationMs) }.getOrNull()
            withContext(Dispatchers.Main) {
                analyzing = false
                if (chart == null) {
                    Toast.makeText(ctx, "这首歌分析失败，换一首试试", Toast.LENGTH_SHORT).show()
                } else {
                    val (st, en) = if (modeMs > 0) ChartAnalyzer.pickWindow(chart, modeMs) else 0L to chart.durationMs
                    startPlay(s, chart, st, en)
                }
            }
        }
    }

    BackHandler(enabled = page == MolePage.HOME) { onBack() }

    when (page) {
        MolePage.HOME -> MoleHomeScreen(
            onBack = onBack,
            onListen = { page = MolePage.PICK },
            onRecords = { page = MolePage.RECORDS },
            onMenu = { showMenu = true }
        )

        MolePage.PICK -> MolePickSongScreen(
            songs = songs,
            playedMap = playedMap,
            onBack = { page = MolePage.HOME },
            onPick = { s -> pendingSong = s; showModeDialog = true },
            onMenu = { showMenu = true }
        )

        MolePage.GAME -> {
            val song = gameSong
            val chart = gameChart
            if (song == null || chart == null) {
                page = MolePage.HOME
            } else {
                WhackMoleGameScreen(
                    song = song,
                    chart = chart,
                    startMs = gameStart,
                    endMs = gameEnd,
                    latencyMs = latencyMs,
                    sndStyle = sndStyle,
                    sndRecent = sndRecent,
                    hapticsOn = hapticsOn,
                    bgKindSetting = bgKind,
                    onSndStyle = pickSnd,
                    onBgKind = pickBg,
                    onHaptics = { v ->
                        hapticsOn = v
                        scope.launch { WhackMoleStore.setHapticsOn(ctx, v) }
                    },
                    onLatency = { v ->
                        latencyMs = v
                        scope.launch { WhackMoleStore.setLatencyMs(ctx, v) }
                    },
                    onQuitNoRecord = { page = MolePage.HOME },
                    onPickAnother = { page = MolePage.PICK }
                )
            }
        }

        MolePage.RECORDS -> MoleRecordsScreen(
            onBack = { page = MolePage.HOME },
            onMenu = { showMenu = true }
        )
    }

    // ── 模式选择弹窗（选歌后：快速=默认） ──
    if (showModeDialog) {
        val song = pendingSong
        AlertDialog(
            onDismissRequest = { showModeDialog = false; pendingSong = null },
            title = { Text("选择玩法") },
            text = {
                Column {
                    Text("《${song?.title}》", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    // 快速模式（默认推荐）
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showModeDialog = false
                                song?.let { beginGame(it, 35_000L) }
                            }
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text("⚡ 快速模式（推荐）", fontWeight = FontWeight.Bold)
                            Text("只玩 30~40 秒精彩片段", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    // 正常模式
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showModeDialog = false
                                song?.let { beginGame(it, 120_000L) }
                            }
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text("🎮 正常模式", fontWeight = FontWeight.Bold)
                            Text("约 2 分钟的精华段落", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    // 完整模式
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showModeDialog = false
                                song?.let { beginGame(it, 0L) }
                            }
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text("🎧 完整模式", fontWeight = FontWeight.Bold)
                            Text("跟着整首歌打到底", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showModeDialog = false; pendingSong = null }) { Text("取消") }
            }
        )
    }

    // ── 分析中遮罩 ──
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

    // ── 游戏菜单（☰：按键手感 / 震动 / 延迟校准 / 玩法说明） ──
    if (showMenu) {
        MoleMenuDialog(
            inGame = false,
            sndStyle = sndStyle,
            sndRecent = sndRecent,
            hapticsOn = hapticsOn,
            latencyMs = latencyMs,
            bgKind = bgKind,
            onSndStyle = pickSnd,
            onBgKind = pickBg,
            onHaptics = { v ->
                hapticsOn = v
                scope.launch { WhackMoleStore.setHapticsOn(ctx, v) }
            },
            onLatency = { v ->
                latencyMs = v
                scope.launch { WhackMoleStore.setLatencyMs(ctx, v) }
            },
            onResume = null,
            onQuit = null,
            onDismiss = { showMenu = false }
        )
    }
}

// ════════════════════════════ 游戏菜单弹窗（全局共用；对局内带继续/退出） ════════════════════════════

@Composable
private fun MoleMenuDialog(
    inGame: Boolean,
    sndStyle: Int,
    sndRecent: List<Int>,
    hapticsOn: Boolean,
    latencyMs: Long,
    bgKind: Int,
    onSndStyle: (Int) -> Unit,
    onBgKind: (Int) -> Unit,
    onHaptics: (Boolean) -> Unit,
    onLatency: (Long) -> Unit,
    onResume: (() -> Unit)?,
    onQuit: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    var latencyDraft by remember(latencyMs) { mutableStateOf(latencyMs.toFloat()) }

    // 试听：菜单自己持有一个合成器 —— 点任意手感立即出声，点几次响几次
    val preview = remember { HitSounds() }
    DisposableEffect(Unit) { onDispose { preview.release() } }
    val audition: (Int) -> Unit = { id ->
        onSndStyle(id)
        preview.play(PREVIEW_FREQ, 0.42f, id)
    }

    // 常用位 = 最近用过的 3 个（存储层维护：选中即置顶，最久没选的被挤出去 → 三个位置都会轮换）
    val quickStyles = sndRecent.mapNotNull { id -> MOLE_SOUND_STYLES.firstOrNull { it.id == id } }
    val moreStyles = MOLE_SOUND_STYLES.filter { st -> quickStyles.none { it.id == st.id } }
    var moreOpen by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("打地鼠 · 菜单") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // 对局内：继续 / 退出
                if (inGame && onResume != null && onQuit != null) {
                    Row {
                        Button(onClick = onResume, modifier = Modifier.weight(1f)) { Text("▶ 继续") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = onQuit, modifier = Modifier.weight(1f)) { Text("退出当局") }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                // 按键手感：3 个常用直显 + 其余收进下拉
                Text("按键手感", fontWeight = FontWeight.Bold)
                quickStyles.forEach { st ->
                    SoundStyleRow(st, selected = sndStyle == st.id) { audition(st.id) }
                }
                Box {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { moreOpen = true }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "更多音效",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "(${moreStyles.size})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.weight(1f))
                        Text(if (moreOpen) "▲" else "▼", color = MaterialTheme.colorScheme.primary)
                    }
                    DropdownMenu(
                        expanded = moreOpen,
                        onDismissRequest = { moreOpen = false },
                        modifier = Modifier.heightIn(max = 360.dp)
                    ) {
                        moreStyles.forEach { st ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(
                                            if (sndStyle == st.id) "${st.name}  ✓" else st.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            st.desc,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                onClick = {
                                    audition(st.id)
                                    moreOpen = false
                                }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                // 触觉震动开关
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("触觉震动", fontWeight = FontWeight.Bold)
                        Text("命中/误击时的手机振动反馈", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = hapticsOn, onCheckedChange = onHaptics)
                }
                Spacer(Modifier.height(10.dp))
                // 背景主题：自动=按当前时段选（凌晨极简/白天草地/黄昏落日/夜晚星空）；手动固定一种
                Text("背景", fontWeight = FontWeight.Bold)
                Text(
                    "「自动」按当前时间选；手动选定后，之后新开的对局都用这个主题",
                    style = MaterialTheme.typography.bodySmall
                )
                val bgOpts = listOf(
                    -1 to "自动",
                    MoleArt.BG_DAY to "白天",
                    MoleArt.BG_DUSK to "黄昏",
                    MoleArt.BG_NIGHT to "夜景",
                    MoleArt.BG_MINIMAL to "极简"
                )
                Row(
                    Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    bgOpts.take(3).forEach { (v, label) ->
                        FilterChip(
                            selected = bgKind == v,
                            onClick = { onBgKind(v) },
                            label = { Text(label) }
                        )
                    }
                }
                Row(
                    Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    bgOpts.drop(3).forEach { (v, label) ->
                        FilterChip(
                            selected = bgKind == v,
                            onClick = { onBgKind(v) },
                            label = { Text(label) }
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                // 延迟校准
                Text("延迟校准", fontWeight = FontWeight.Bold)
                Text(
                    "地鼠和音乐节拍对不上时微调（调大=地鼠出现更晚）",
                    style = MaterialTheme.typography.bodySmall
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Slider(
                        value = latencyDraft,
                        onValueChange = { latencyDraft = it },
                        onValueChangeFinished = { onLatency(latencyDraft.toLong()) },
                        valueRange = -200f..400f,
                        steps = 11,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "${latencyDraft.toInt()}ms",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.width(64.dp),
                        textAlign = TextAlign.End
                    )
                }
                Spacer(Modifier.height(10.dp))
                // 玩法说明
                Text("玩法说明", fontWeight = FontWeight.Bold)
                Text("🐹 普通鼠：拍点出现，点掉得分", style = MaterialTheme.typography.bodySmall)
                Text("💣 炸弹鼠：只在没节奏的空拍出现，千万别碰！误击扣 ${WhackMoleEngine.BOMB_PENALTY} 分且连击减半", style = MaterialTheme.typography.bodySmall)
                Text("🌟 加分鼠：只出现在节奏高潮处，打到 +${WhackMoleEngine.BONUS_SCORE} 分（漏掉不扣）", style = MaterialTheme.typography.bodySmall)
                Text("🔥 连击：连续命中加分，漏掉/碰炸弹 → 连击减半", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } }
    )
}

/** 手感选项行（RadioButton + 名称/描述）；点整行都会选中并试听一次 */
@Composable
private fun SoundStyleRow(st: MoleSoundStyle, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.weight(1f)) {
            Text(st.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            Text(
                st.desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ════════════════════════════ 首页（三个入口） ════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoleHomeScreen(
    onBack: () -> Unit,
    onListen: () -> Unit,
    onRecords: () -> Unit,
    onMenu: () -> Unit
) {
    val ctx = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("打地鼠") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
                actions = {
                    IconButton(onClick = onMenu) { Icon(Icons.Default.Menu, "菜单") }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            // 听歌模式
            MoleEntryCard(
                icon = { Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(38.dp)) },
                title = "听歌模式",
                desc = "选一首本地歌，跟着节奏打地鼠",
                enabled = true
            ) {
                // 本地曲库为空提示
                onListen()
            }
            Spacer(Modifier.height(12.dp))
            // 标准模式（占位）
            MoleEntryCard(
                icon = { Icon(Icons.Default.SportsEsports, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(38.dp)) },
                title = "标准模式",
                desc = "内置旋律闯关 · 接入联网排行榜（打磨中）",
                enabled = false
            ) {
                Toast.makeText(ctx, "标准模式打磨中，敬请期待", Toast.LENGTH_SHORT).show()
            }
            Spacer(Modifier.height(12.dp))
            // 游戏记录
            MoleEntryCard(
                icon = { Icon(Icons.Default.History, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(38.dp)) },
                title = "游戏记录",
                desc = "听歌模式的最高分/总分 · 标准模式的联网排名",
                enabled = true
            ) {
                onRecords()
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "玩法：跟着拍点点地鼠得 combo，别碰炸弹鼠；记录只存本机，标准模式上线后成绩才上联网榜",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MoleEntryCard(
    icon: @Composable () -> Unit,
    title: String,
    desc: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onClick() }
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            icon()
            Spacer(Modifier.width(14.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ════════════════════════════ 选歌页 ════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MolePickSongScreen(
    songs: List<Song>,
    playedMap: Map<String, Long>,
    onBack: () -> Unit,
    onPick: (Song) -> Unit,
    onMenu: () -> Unit
) {
    // 排序（用户 2026-09-13 要求）：玩过的全部置顶（按最近玩过在前），没玩过的按添加时间从新到旧
    val sorted = remember(songs, playedMap) {
        songs.sortedWith(
            compareByDescending<Song> { playedMap["${it.id}_${it.durationMs}"] ?: 0L }
                .thenByDescending { it.dateAdded }
        )
    }
    // playedMap 是进页面后异步加载的：加载完成触发"玩过置顶"重排时，LazyColumn 会按 key
    // 保持第一可见项不滚走 → 视觉上列表"没在最顶端"。这里在 map 变化时强制回到顶部
    val listState = rememberLazyListState()
    LaunchedEffect(playedMap) { listState.scrollToItem(0) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("选择一首歌") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
                actions = { IconButton(onClick = onMenu) { Icon(Icons.Default.Menu, "菜单") } }
            )
        }
    ) { padding ->
        if (songs.isEmpty()) {
            Column(
                Modifier.padding(padding).fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("本地曲库是空的，先去扫描一些歌吧")
            }
        } else {
            LazyColumn(Modifier.padding(padding).fillMaxSize(), state = listState) {
                items(sorted, key = { it.id }) { s ->
                    ListItem(
                        headlineContent = { Text(s.title, maxLines = 1) },
                        supportingContent = { Text(s.artist, maxLines = 1) },
                        trailingContent = { Text(fmtDur(s.durationMs), style = MaterialTheme.typography.bodySmall) },
                        modifier = Modifier.clickable { onPick(s) }
                    )
                }
            }
        }
    }
}

// ════════════════════════════ 对局页 ════════════════════════════

private enum class GamePhase { COUNTDOWN, PLAY, RESULT }

private data class ResultInfo(
    val score: Long,
    val best: Long,
    val isNewBest: Boolean,
    val maxCombo: Int,
    val hits: Int,
    val missed: Int,
    val bombHits: Int,
    val bombPenalty: Long,
    val bonusHits: Int
)

@Composable
private fun WhackMoleGameScreen(
    song: Song,
    chart: ChartAnalyzer.Chart,
    startMs: Long,
    endMs: Long,
    latencyMs: Long,
    sndStyle: Int,
    sndRecent: List<Int>,
    hapticsOn: Boolean,
    bgKindSetting: Int,
    onSndStyle: (Int) -> Unit,
    onBgKind: (Int) -> Unit,
    onHaptics: (Boolean) -> Unit,
    onLatency: (Long) -> Unit,
    onQuitNoRecord: () -> Unit,
    onPickAnother: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var gameKey by remember { mutableStateOf(0) }   // 再来一局时 +1 重建引擎/倒计时
    val engine = remember(gameKey) { WhackMoleEngine(chart, startMs, endMs) }
    val sounds = remember { HitSounds() }
    val haptics = LocalHapticFeedback.current
    var shakeUntil by remember { mutableStateOf(0L) }   // 炸弹误击时整屏震动截止时间

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

    var phase by remember(gameKey) { mutableStateOf(GamePhase.COUNTDOWN) }
    var countdown by remember(gameKey) { mutableStateOf(3) }
    var paused by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }   // 对局内暂停菜单（继续/退出/手感/延迟）
    var frameNow by remember { mutableStateOf(startMs) }
    var result by remember(gameKey) { mutableStateOf<ResultInfo?>(null) }

    fun finishGame() {
        if (result != null) return
        player.pause()
        scope.launch {
            val (best, isNew) = WhackMoleStore.submit(ctx, song, engine.score, engine.maxCombo)
            result = ResultInfo(
                engine.score, best, isNew, engine.maxCombo,
                engine.hits, engine.missed, engine.bombHits, engine.bombPenalty,
                engine.bonusHits
            )
            phase = GamePhase.RESULT
        }
    }

    // 播放器准备 + 倒计时（gameKey 变化时重跑 → 再来一局）
    LaunchedEffect(gameKey) {
        paused = false
        player.setMediaItem(MediaItem.fromUri(song.uri), startMs)
        player.prepare()
        phase = GamePhase.COUNTDOWN
        countdown = 3
        delay(700)
        countdown = 2
        delay(700)
        countdown = 1
        delay(700)
        countdown = 0
        player.play()
        phase = GamePhase.PLAY
    }

    // 游戏主循环（时钟 = 播放位置 - 延迟偏移；延迟在菜单里改动时重启循环以生效）
    LaunchedEffect(phase, gameKey, latencyMs) {
        if (phase == GamePhase.PLAY) {
            while (isActive) {
                val pos = player.currentPosition - latencyMs
                engine.tick(pos)
                frameNow = pos
                engine.gcFloats(SystemClock.elapsedRealtime())
                if (engine.finished || player.playbackState == Player.STATE_ENDED) {
                    finishGame()
                    break
                }
                delay(16)
            }
        }
    }

    // 对局中按返回 → 暂停并打开暂停菜单
    BackHandler(enabled = phase != GamePhase.RESULT) {
        if (phase == GamePhase.PLAY && !paused) {
            paused = true
            player.pause()
        }
        showMenu = true
    }

    DisposableEffect(Unit) {
        onDispose {
            player.release()
            sounds.release()
        }
    }

    // 场景背景：菜单设置 -1=按当前时段自动（每开一局重算），否则固定用户所选主题
    val autoBg = remember(gameKey) { pickBgByTime() }
    val bgKind = if (bgKindSetting < 0) autoBg else bgKindSetting
    // 动态背景层（云/星/萤火虫）的时钟：只在 draw 阶段读取这个 State，
    // 每帧只重绘不重组 —— 倒计时/暂停时动画也不会冻结，且不影响游戏逻辑性能
    val bgTime = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            withInfiniteAnimationFrameNanos { bgTime.longValue = it / 1_000_000L }
        }
    }
    val remainSec = ((endMs - frameNow).coerceAtLeast(0)) / 1000
    val progress = ((frameNow - startMs).toFloat() / (endMs - startMs).coerceAtLeast(1)).coerceIn(0f, 1f)

    Scaffold(containerColor = Color.Transparent) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val dens = LocalDensity.current
            val scrW = with(dens) { maxWidth.toPx() }
            val scrH = with(dens) { maxHeight.toPx() }
            val safeB = with(dens) { padding.calculateBottomPadding().toPx() }
            // 顶部信息条是浮层，但仍要给它留出高度，免得九宫格顶到它身上
            val infoReserve = with(dens) { padding.calculateTopPadding().toPx() + 62.dp.toPx() }
            val areaH = (scrH - infoReserve - safeB).coerceAtLeast(1f)

            // 场景与九宫格共用一把尺：k = 1 个美术像素占几个设备像素，必须是整数倍
            // （非整数倍时最近邻放大会让格子之间露缝、和背景土丘对不齐）
            val k = minOf(scrW / MoleArt.SCENE_W, areaH / MoleArt.SCENE_H)
                .toInt().coerceAtLeast(1)
            val sw = (MoleArt.SCENE_W * k).toFloat()
            val sh = (MoleArt.SCENE_H * k).toFloat()
            val sx = (scrW - sw) / 2f                       // 水平居中
            // 九宫格整体上提一点，下方多留一截草地（用户 2026-09-14 反馈格子太贴底）。
            // 背景是「边缘延展」铺的，下方多出来的部分自动由草地那一行拉长补满。
            val gridLift = (sh * 0.13f).roundToInt()
            val sy = scrH - safeB - sh - gridLift
            val gridTop = sy + (MoleArt.SCENE_H - MoleArt.GRID_H) * k

            // ① 全屏像素背景：边缘延展铺满整屏（含状态栏 / 导航栏区域，无黑边无变形）
            Canvas(Modifier.fillMaxSize()) {
                drawFullBleedBg(bgKind, bgTime.longValue, sx, sy, sw, sh, k)
            }

            // ② 九宫格：几何上绑定到场景的草地区 → 与背景图里画好的 9 个土丘严丝合缝
            Box(
                Modifier
                    .offset { IntOffset(sx.roundToInt(), gridTop.roundToInt()) }
                    .size(with(dens) { sw.toDp() }, with(dens) { (MoleArt.GRID_H * k).toDp() })
                    .graphicsLayer {
                        if (SystemClock.elapsedRealtime() < shakeUntil) {
                            translationX = (Math.random() - 0.5).toFloat() * 16f
                            translationY = (Math.random() - 0.5).toFloat() * 16f
                        }
                    }
            ) {
                Column(Modifier.fillMaxSize()) {
                    repeat(3) { row ->
                        Row(Modifier.fillMaxWidth().weight(1f)) {
                            repeat(3) { col ->
                                val cell = row * 3 + col
                                MoleCellView(
                                    mole = engine.moles[cell].value,
                                    now = frameNow,
                                    floating = engine.floatings.lastOrNull { it.cell == cell },
                                    flashAge = SystemClock.elapsedRealtime() - engine.cellFlashMs[cell],
                                    particles = engine.particles.filter { it.cell == cell },
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                    onTap = {
                                        val r = engine.tap(cell)
                                        when {
                                            // 空点：轻"噗"声，无惩罚
                                            r == null -> sounds.play(165f, 0.12f, sndStyle)
                                            // 炸弹：低沉音 + 重震动 + 整屏抖动
                                            r.second == ChartAnalyzer.TYPE_BOMB -> {
                                                sounds.play(75f, 0.5f, sndStyle)
                                                if (hapticsOn) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                shakeUntil = SystemClock.elapsedRealtime() + 220
                                            }
                                            // 加分鼠：高八度亮音（金色粒子）
                                            r.second == ChartAnalyzer.TYPE_BONUS -> {
                                                sounds.play(CELL_FREQS[cell] * 2f, 0.5f, sndStyle)
                                                if (hapticsOn) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            }
                                            // 命中：按当前手感风格发音 + 轻震动
                                            else -> {
                                                sounds.play(CELL_FREQS[cell], 0.42f, sndStyle)
                                                if (hapticsOn) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // ③ 顶部信息条（浮层：压在像素背景上，加一层半透明底衬保证可读）
            Column(
                Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.35f),
                            RoundedCornerShape(12.dp)
                        )
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "分数 ${engine.score}",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        if (engine.combo >= 2) {
                            Text(
                                "连击 x${engine.combo}",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Text(
                        "剩余 ${fmtDur(remainSec * 1000)}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.width(6.dp))
                    // 暂停键：暂停并弹出暂停菜单（继续/退出/手感/延迟）
                    IconButton(onClick = {
                        if (phase == GamePhase.PLAY && !paused) {
                            paused = true
                            player.pause()
                        }
                        showMenu = true
                    }) {
                        Icon(Icons.Default.Pause, contentDescription = "暂停菜单")
                    }
                }
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(4.dp)
                )
            }

            // ④ 倒计时
            if (phase == GamePhase.COUNTDOWN) {
                Column(
                    Modifier
                        .align(Alignment.Center)
                        .background(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
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
                    Text("拍普通鼠 · 捡加分鼠🌟 · 避开炸弹💣", style = MaterialTheme.typography.bodySmall)
                }
            }

            // 暂停态由暂停菜单接管（顶部 ⏸ / 返回键打开），不再显示旧遮罩
        }
    }

    // ── 对局内暂停菜单（继续 / 退出 / 按键手感 / 触觉震动 / 延迟校准 / 说明） ──
    if (showMenu) {
        MoleMenuDialog(
            inGame = true,
            sndStyle = sndStyle,
            sndRecent = sndRecent,
            hapticsOn = hapticsOn,
            latencyMs = latencyMs,
            bgKind = bgKindSetting,
            onSndStyle = onSndStyle,
            onBgKind = onBgKind,
            onHaptics = onHaptics,
            onLatency = onLatency,
            onResume = {
                showMenu = false
                paused = false
                if (phase == GamePhase.PLAY) player.play()
            },
            onQuit = {
                showMenu = false
                player.pause()
                onQuitNoRecord()
            },
            onDismiss = {
                // 点对话框外部关闭 = 继续游戏，避免停留在无提示的暂停态
                showMenu = false
                if (phase == GamePhase.PLAY && paused) {
                    paused = false
                    player.play()
                }
            }
        )
    }

    // ── 结算 ──
    result?.let { r ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("本局结束") },
            text = {
                Column {
                    Text(
                        "${r.score} 分",
                        fontSize = 40.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (r.isNewBest) {
                        Text("🎉 打破这首歌的纪录!", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    } else {
                        Text("这首歌的最高纪录：${r.best} 分", style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("最大连击：x${r.maxCombo}", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "命中 ${r.hits} · 漏掉 ${r.missed} · 加分鼠 ${r.bonusHits} · 误击炸弹 ${r.bombHits} 次(扣 ${r.bombPenalty})",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text("总分（累计）已存入游戏记录", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(onClick = { gameKey++ }) { Text("再来一局") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = onPickAnother) { Text("换一首") }
                    TextButton(onClick = onQuitNoRecord) { Text("返回") }
                }
            }
        )
    }
}

@Composable
private fun MoleCellView(
    mole: WhackMoleEngine.Mole?,
    now: Long,
    floating: WhackMoleEngine.Floating?,
    flashAge: Long,
    particles: List<WhackMoleEngine.Particle>,
    modifier: Modifier = Modifier,
    onTap: () -> Unit
) {
    // 地鼠消失（被拍掉 / 漏掉）不硬切：留 130ms 播「缩回洞里」——纯表现层，不改玩法
    var ghost by remember { mutableStateOf<WhackMoleEngine.Mole?>(null) }
    var sinkAt by remember { mutableStateOf(0L) }
    LaunchedEffect(mole) {
        if (mole != null) {
            ghost = mole
            sinkAt = 0L
        } else if (ghost != null) {
            sinkAt = SystemClock.elapsedRealtime()
            delay(RETREAT_MS)
            ghost = null
        }
    }
    val shown = mole ?: ghost

    Box(
        modifier = modifier.clickable { onTap() },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            // 像素必须落在整数倍上：非整数倍时最近邻放大每格宽窄不一，收窄出的裁剪边还会发虚
            val px = minOf(
                size.width.toInt() / MoleArt.CELL_W,
                size.height.toInt() / MoleArt.CELL_H
            ).coerceAtLeast(1)
            val dw = px * MoleArt.CELL_W
            val dh = px * MoleArt.CELL_H
            val ox = ((size.width - dw) / 2f).roundToInt()
            val oy = ((size.height - dh) / 2f).roundToInt()
            val src = IntSize(MoleArt.CELL_W, MoleArt.CELL_H)
            val at = IntOffset(ox, oy)
            val to = IntSize(dw, dh)

            // ① 后层：土丘 + 完整椭圆洞口（深色）
            drawImage(MoleSprites.holeBack, IntOffset.Zero, src, at, to, filterQuality = FilterQuality.None)

            if (shown != null) {
                // ② 地鼠：竖向冒头 / 缩回 + 待机 1px 左右抖动
                val dy = if (mole != null) {
                    val p = ((now - mole.appearMs).toFloat() / EMERGE_MS).coerceIn(0f, 1f)
                    (MoleArt.SINK_ROWS * (1f - easeOutBack(p))).roundToInt().coerceAtLeast(-2)
                } else {
                    val q = ((SystemClock.elapsedRealtime() - sinkAt).toFloat() / RETREAT_MS.toFloat())
                        .coerceIn(0f, 1f)
                    (MoleArt.SINK_ROWS * q * q * q).roundToInt()
                }
                val jx = if (now / 130 % 2 == 0L) 0 else -1
                val kind = when (shown.event.type) {
                    ChartAnalyzer.TYPE_BOMB -> 1
                    ChartAnalyzer.TYPE_BONUS -> 2
                    else -> 0
                }
                val spr = MoleSprites.bodies[kind]
                // 逐行把源矩形收窄到「洞口下弧以内」→ 等价于逐列裁剪，且不引入任何 clip 变换
                for (sy in 0 until MoleArt.MOLE_H) {
                    val cy = MoleArt.ROLE_OY + dy + sy
                    if (cy < 0 || cy >= MoleArt.CELL_H) continue
                    val cols = MoleSprites.rowCols[cy]
                    val sx0 = maxOf(0, cols[0] - MoleArt.ROLE_OX - jx)
                    val sx1 = minOf(MoleArt.MOLE_W - 1, cols[1] - MoleArt.ROLE_OX - jx)
                    if (sx1 < sx0) continue
                    val w = sx1 - sx0 + 1
                    drawImage(
                        spr,
                        srcOffset = IntOffset(sx0, sy), srcSize = IntSize(w, 1),
                        dstOffset = IntOffset(ox + (MoleArt.ROLE_OX + jx + sx0) * px, oy + cy * px),
                        dstSize = IntSize(w * px, px),
                        filterQuality = FilterQuality.None
                    )
                }
                // ③ 洞内阴影：压在身体上，越往洞底越暗（固定 alpha 会在肚子上留一圈硬边）
                drawImage(MoleSprites.dim, IntOffset.Zero, src, at, to, filterQuality = FilterQuality.None)
            }

            // ④ 前沿：月牙形土丘 + 洞口近沿，盖住地鼠下半身
            drawImage(MoleSprites.holeFront, IntOffset.Zero, src, at, to, filterQuality = FilterQuality.None)
        }
        // 点击闪光（任何点击都有即时反馈）
        if (flashAge < 160) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.White.copy(alpha = 0.22f * (1f - flashAge / 160f)))
            )
        }
        // 命中像素粒子迸溅
        val nowRt = SystemClock.elapsedRealtime()
        particles.forEach { p ->
            val q = (nowRt - p.createdAt).coerceIn(0, 420) / 420f
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(6.dp)
                    .graphicsLayer {
                        translationX = p.dx * q * 90f
                        translationY = p.dy * q * 90f
                        alpha = 1f - q
                    }
                    .background(Color(p.color))
            )
        }
        // 漂浮分数
        if (floating != null) {
            val age = (SystemClock.elapsedRealtime() - floating.createdAt).coerceIn(0, 650)
            val alpha = 1f - age / 650f
            Text(
                floating.text,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = when (floating.kind) {
                    0 -> Color(0xFF2E9E4F)
                    1 -> Color(0xFFD33B2F)
                    3 -> Color(0xFFE8A213)
                    else -> Color.Gray
                },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .graphicsLayer { this.alpha = alpha }
            )
        }
    }
}

// ════════════════════════════ 游戏记录页 ════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoleRecordsScreen(onBack: () -> Unit, onMenu: () -> Unit) {
    val ctx = LocalContext.current
    var records by remember { mutableStateOf<List<WhackMoleStore.SongRecord>>(emptyList()) }
    var total by remember { mutableStateOf(0L) }

    LaunchedEffect(Unit) {
        records = WhackMoleStore.songRecords(ctx)
        total = WhackMoleStore.totalScore(ctx)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("游戏记录") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
                actions = { IconButton(onClick = onMenu) { Icon(Icons.Default.Menu, "菜单") } }
            )
        }
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp)) {
            item {
                Spacer(Modifier.height(8.dp))
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("听歌模式 · 总分（累计）", style = MaterialTheme.typography.bodySmall)
                            Text(
                                "$total",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text("单曲最高纪录", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                if (records.isEmpty()) {
                    Text(
                        "还没玩过，去打几局吧",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 10.dp)
                    )
                }
            }
            items(records, key = { it.songKey }) { r ->
                ListItem(
                    headlineContent = { Text(r.title, maxLines = 1) },
                    supportingContent = {
                        Text(
                            "${r.artist} · 最大连击 x${r.maxCombo} · 玩过 ${r.plays} 次",
                            maxLines = 1,
                            style = MaterialTheme.typography.bodySmall
                        )
                    },
                    trailingContent = {
                        Text(
                            "${r.best}",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                )
            }
            item {
                Spacer(Modifier.height(14.dp))
                Text("标准模式 · 联网排行榜", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    "标准模式上线后，这里展示各关的联网排名",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 10.dp)
                )
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

// ════════════════════════════ 工具 ════════════════════════════

private fun fmtDur(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
