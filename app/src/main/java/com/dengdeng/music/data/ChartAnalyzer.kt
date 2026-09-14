package com.dengdeng.music.data

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 打地鼠·听歌模式 谱面分析器
 *
 * 思路（基本版，刻意克制）：
 * 1. MediaExtractor+MediaCodec 把整首歌解成 PCM，按 100ms 窗口算 RMS 能量曲线（不存音频，只存曲线）
 * 2. 由能量曲线生成谱面事件（绝对时间）：
 *    - 能量抬升（相对局部均值 +15%）→ 普通鼠
 *    - 普通鼠之间的长间隔（≥1.1s）中点 → 炸弹鼠（"两拍之间别乱点"，约每 30s 一只）
 *    - 节奏高潮（能量前 15%）处的普通鼠按概率转化 → 加分鼠
 *    - 平缓段按概率放普通鼠，保证密度；密度随进度从 ~650ms 一只 收紧到 ~420ms
 * 3. 结果缓存到 cacheDir（key = songId + 时长），每首歌只分析一次
 *
 * 快速模式窗口：滑动窗口取能量总和最高的 ~75s；全曲能量过平（方差小）时兜底"30% 处起 75s"
 */
object ChartAnalyzer {

    const val TYPE_NORMAL = 0
    const val TYPE_BOMB = 1
    const val TYPE_BONUS = 2
    const val WINDOW_MS = 100L

    data class ChartEvent(val timeMs: Long, val type: Int)

    data class Chart(
        val durationMs: Long,
        val energies: FloatArray,       // 每 WINDOW_MS 一个 RMS（0~1，已平滑前的原值）
        val events: List<ChartEvent>    // 整首歌的谱面事件（绝对时间轴）
    )

    // ---------- 对外入口（带磁盘缓存） ----------

    suspend fun analyze(ctx: Context, uri: Uri, songId: Long, durationMs: Long): Chart =
        withContext(Dispatchers.Default) {
            // v4：炸弹鼠改插「普通鼠之间的长间隔中点」（旧版要前后 900ms 无事件，
            //     实测 3/4 首歌生不出炸弹）——老缓存作废
            val cache = File(ctx.cacheDir, "wm_chart_v4_${songId}_${durationMs}.json")
            runCatching { fromJson(JSONObject(cache.readText())) }.getOrNull() ?: run {
                val chart = decode(ctx, uri, songId)
                runCatching { cache.writeText(toJson(chart).toString()) }
                chart
            }
        }

    /**
     * 精彩片段窗口：返回 (startMs, endMs)
     * targetMs = 目标时长（快速 30-40s 用 35000，正常模式用 120000）
     * 优先取能量总和最高的连续窗口；能量曲线过平（几乎没有起伏）则兜底 30% 处起
     */
    fun pickWindow(chart: Chart, targetMs: Long): Pair<Long, Long> {
        val winMs = targetMs
        if (chart.durationMs <= winMs + 10_000) return 0L to chart.durationMs
        val n = chart.energies.size
        val w = (winMs / WINDOW_MS).toInt().coerceIn(1, n)

        // 能量起伏度（平滑序列的标准差）：过平说明分析不可信 → 走固定兜底
        var mean = 0f
        for (e in chart.energies) mean += e
        mean /= n
        var sq = 0f
        for (e in chart.energies) sq += (e - mean) * (e - mean)
        val std = sqrt(sq / n)
        if (std < 0.04f) {
            val s = (chart.durationMs * 0.30).toLong()
            return s to min(chart.durationMs, s + winMs)
        }

        // 滑动窗口求能量和最大的起点
        var sum = 0.0
        for (i in 0 until w) sum += chart.energies[i]
        var bestSum = sum
        var bestStart = 0
        for (s in 1..n - w) {
            sum += chart.energies[s + w - 1] - chart.energies[s - 1]
            if (sum > bestSum) { bestSum = sum; bestStart = s }
        }
        // 稍微往左退 2s，避免正好切在副歌第一拍前
        val startMs = (bestStart.toLong() * WINDOW_MS).coerceAtLeast(0L)
            .coerceAtMost((chart.durationMs - winMs).coerceAtLeast(0L))
        return startMs to min(chart.durationMs, startMs + winMs)
    }

    // ---------- 谱面生成 ----------

    private fun decode(ctx: Context, uri: Uri, songId: Long): Chart {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(ctx, uri, null)
            var trackIdx = -1
            var format: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) { trackIdx = i; format = f; break }
            }
            val fmt = format ?: throw IllegalStateException("无音频轨")
            ex.selectTrack(trackIdx)
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            val sampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val samplesPerWin = (sampleRate.toLong() * WINDOW_MS / 1000L).toInt().coerceAtLeast(1)

            codec = MediaCodec.createDecoderByType(mime).also {
                it.configure(fmt, null, null, 0)
                it.start()
            }
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            val energies = ArrayList<Float>(4096)
            var winSamples = 0
            var winSq = 0.0

            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val sz = ex.readSampleData(buf, 0)
                        if (sz < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, sz, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                if (outIdx >= 0) {
                    val buf = codec.getOutputBuffer(outIdx)!!
                    buf.order(ByteOrder.LITTLE_ENDIAN)
                    val shorts = buf.asShortBuffer()
                    val n = info.size / 2
                    var i = 0
                    while (i < n) {
                        val v = shorts.get(i).toInt()
                        winSq += (v * v).toDouble()
                        winSamples++
                        if (winSamples >= samplesPerWin) {
                            val rms = (sqrt(winSq / winSamples) / 32768.0).toFloat()
                            energies.add(rms.coerceIn(0f, 1f))
                            winSq = 0.0; winSamples = 0
                        }
                        i += channels   // 只取第一声道即可
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
                // INFO_OUTPUT_FORMAT_CHANGED / INFO_TRY_AGAIN_LATER：直接继续循环
            }
            codec.stop()
            val durationMs = energies.size * WINDOW_MS
            return buildChart(durationMs, energies.toFloatArray(), songId)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            ex.release()
        }
    }

    private fun buildChart(durationMs: Long, energies: FloatArray, seed: Long): Chart {
        val n = energies.size
        if (n < 15) return Chart(durationMs, energies, emptyList())
        val rnd = java.util.Random(seed * 31 + durationMs)

        // 平滑（1-2-1）
        val smooth = FloatArray(n)
        for (i in 0 until n) {
            val a = energies[max(0, i - 1)]
            val b = energies[i]
            val c = energies[min(n - 1, i + 1)]
            smooth[i] = (a + 2 * b + c) / 4f
        }
        // 局部均值（前后各 0.8s）
        val localMean = FloatArray(n)
        val r = 8
        for (i in 0 until n) {
            var s = 0f; var c = 0
            for (j in max(0, i - r)..min(n - 1, i + r)) { s += smooth[j]; c++ }
            localMean[i] = s / c
        }

        val events = ArrayList<ChartEvent>(2048)

        fun gapAt(tMs: Long): Long {
            val progress = tMs.toFloat() / durationMs
            val gap = 620f + (400f - 620f) * progress
            return (gap * (90 + rnd.nextInt(20)) / 100).toLong()
        }

        // ── 1) 主扫：普通鼠锚定在真正的 onset 能量峰（局部极大 + 明显抬升）──
        // 时间 = 峰位×100ms - 50ms，补偿 RMS 窗口对起音的滞后
        var lastT = -10_000L
        for (i in 2 until n - 2) {
            val v = smooth[i]
            val isPeak = v >= smooth[i - 1] && v >= smooth[i + 1] && v > smooth[i - 2] && v > smooth[i + 2]
            if (!isPeak) continue
            if (v <= localMean[i] * 1.08f || v < 0.09f) continue
            val t = i * WINDOW_MS - 50L
            if (t < 1000 || t > durationMs - 600) continue
            if (t - lastT < gapAt(t)) continue
            events.add(ChartEvent(t, TYPE_NORMAL))
            lastT = t
        }

        // ── 2) 副扫（歌曲能量太平、主扫太稀时）：放宽阈值补密度，仍锚定局部峰 ──
        if (events.size < durationMs / 1000) {
            lastT = -10_000L
            for (i in 1 until n - 1) {
                val v = smooth[i]
                val isPeak = v >= smooth[i - 1] && v >= smooth[i + 1]
                if (!isPeak || v < localMean[i] * 0.85f || v < 0.05f) continue
                val t = i * WINDOW_MS - 50L
                if (t < 1000 || t > durationMs - 600) continue
                if (t - lastT < 900) continue
                if (events.any { abs(it.timeMs - t) < 500 }) continue
                events.add(ChartEvent(t, TYPE_NORMAL))
                lastT = t
            }
        }

        // 炸弹鼠数量目标：约每 30 秒一只（上限 12 只）
        val bombTarget = (durationMs / 30_000L).toInt().coerceIn(1, 12)

        // ── 3) 炸弹鼠：插在两拍之间的「长间隔」里 —— 干扰但不设陷阱 ──
        // ⚠️ 旧版要求「前后 900ms 内没有任何谱面事件 + 能量深谷」，而普通鼠中位间隔只有
        //    800~900ms（手机实测 4 首歌里 3 首炸弹数 = 0），等于根本没有炸弹鼠。
        //    现改为：取相邻普通鼠间隔 ≥1.1s 的对，在间隔 42% 处落点
        //    （≥1.5s 的间隔取正中点）——前方留 ≥460ms、后方留 ≥640ms 刹车余量。
        val normals = events.filter { it.type == TYPE_NORMAL }.map { it.timeMs }
        val cand = ArrayList<Long>()
        for (k in 0 until normals.size - 1) {
            val a = normals[k]
            val b = normals[k + 1]
            val gapLen = b - a
            if (gapLen < 1100) continue
            val t = if (gapLen >= 1500) (a + b) / 2 else a + (gapLen * 42 / 100)
            if (t < 1500 || t > durationMs - 600) continue
            cand.add(t)
        }
        // 候选乱序 + 概率抽取：保证间距 ≥2s，且不贴着任何谱面事件
        java.util.Collections.shuffle(cand, rnd)
        val bombTimes = ArrayList<Long>()
        for (t in cand) {
            if (bombTimes.size >= bombTarget) break
            if (bombTimes.any { abs(it - t) < 2000 }) continue
            if (events.any { abs(it.timeMs - t) < 460 }) continue
            if (rnd.nextFloat() > 0.75f) continue
            bombTimes.add(t)
        }
        for (t in bombTimes) events.add(ChartEvent(t, TYPE_BOMB))

        // ── 4) 加分鼠：节奏高潮处的普通鼠按概率转化（高潮 = 峰能量进入全曲前 15%，
        //        且显著高于局部均值）；最少间隔 3s、数量封顶 ≈5%，漏掉不扣 ──
        run {
            val es = smooth.filter { it > 0.09f }.toFloatArray()
            if (es.isNotEmpty()) {
                es.sort()
                val q = es[(es.size * 0.85f).toInt().coerceAtMost(es.size - 1)]
                var lastBonus = -10_000L
                var bonuses = 0
                for (k in events.indices) {
                    val ev = events[k]
                    if (ev.type != TYPE_NORMAL) continue
                    val t = ev.timeMs
                    if (t - lastBonus < 3000) continue
                    val ei = ((t + 50L) / WINDOW_MS).toInt().coerceIn(0, n - 1)
                    if (smooth[ei] < q || smooth[ei] < localMean[ei] * 1.15f) continue
                    if (bonuses * 20 >= events.size + 1) break
                    if (rnd.nextFloat() > 0.3f) continue   // 按概率刷新（30%）
                    events[k] = ChartEvent(t, TYPE_BONUS)
                    bonuses++
                    lastBonus = t
                }
            }
        }

        events.sortBy { it.timeMs }
        return Chart(durationMs, energies, events)
    }

    // ---------- 缓存序列化 ----------

    private fun toJson(c: Chart): JSONObject {
        val e = JSONArray()
        for (v in c.energies) e.put(v.toDouble())
        val v = JSONArray()
        for (ev in c.events) v.put(JSONObject().put("t", ev.timeMs).put("ty", ev.type))
        return JSONObject().put("d", c.durationMs).put("e", e).put("v", v)
    }

    private fun fromJson(o: JSONObject): Chart {
        val eArr = o.getJSONArray("e")
        val energies = FloatArray(eArr.length()) { eArr.getDouble(it).toFloat() }
        val vArr = o.getJSONArray("v")
        val events = ArrayList<ChartEvent>(vArr.length())
        for (i in 0 until vArr.length()) {
            val jo = vArr.getJSONObject(i)
            events.add(ChartEvent(jo.getLong("t"), jo.getInt("ty")))
        }
        return Chart(o.getLong("d"), energies, events)
    }
}
