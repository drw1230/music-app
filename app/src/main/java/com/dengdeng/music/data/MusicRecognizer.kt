package com.dengdeng.music.data

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 听歌识曲（AudD 免注册匿名接口）
 *
 * 流程：麦克风录音 → 转 WAV → multipart 上传 AudD → 解析歌名/歌手
 * 额度：匿名调用每天 10 次；注册 dashboard.audd.io 拿到 token 填到 [AUDD_TOKEN] 后为免费 300 次
 * 隐私：录音只在内存里，上传识别后即丢弃，不写盘、不落库
 *
 * 日志 TAG：DDmusicRec（adb logcat -s DDmusicRec 可单独抓识别链路）
 */
object MusicRecognizer {

    private const val TAG = "DDmusicRec"

    /** AudD API token（留空 = 匿名调用，每天 10 次；注册后填入可提额到 300 次） */
    const val AUDD_TOKEN = ""

    // ===== ACRCloud（主引擎，中文曲库更强；三项都填了才会启用）=====
    // 控制台 → 项目 → 概览页可看到这三项。host 带区域，国内建议用 identify-cn-north-1.acrcloud.cn
    const val ACR_HOST = ""
    const val ACR_ACCESS_KEY = ""
    const val ACR_ACCESS_SECRET = ""

    /** ACRCloud 是否已配置（三项齐全才启用，否则直接用 AudD） */
    val acrConfigured: Boolean
        get() = ACR_HOST.isNotBlank() && ACR_ACCESS_KEY.isNotBlank() && ACR_ACCESS_SECRET.isNotBlank()

    // ===== 主引擎：AHA Music 免登录识曲接口（底层就是 ACRCloud 曲库，中文歌覆盖强）=====
    // 该接口从网站前端逆向得到：不需要账号/密钥，直接用原生录音上传即可。
    // 实测：真实音频 → HTTP 200 / score 100（ACRCloud 原生识别）。
    private const val AHA_IDENTIFY_URL = "https://aha-music.com/identify"

    /** 前端固定盐值（源码里的常量 K2，就是 8 个空格）：base64 前拼在音频字节尾部。
     *  不加会被服务端判 400 Missing audio data */
    private const val AHA_SALT = "        "

    /** 网页用的手机 Chrome UA；站点对非浏览器 UA 可能另作处理 */
    private const val AHA_UA =
        "Mozilla/5.0 (Linux; Android 13; SM-S9180) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Mobile Safari/537.36"

    /** 主引擎开关（关掉即退回 ACRCloud 官方密钥 / AudD） */
    private const val USE_AHA = true

    private const val SAMPLE_RATE = 44100
    private const val API_URL = "https://api.audd.io/"

    data class RecognizeResult(
        val title: String,
        val artist: String,
        val album: String,
        /** 进搜索框用的词（不是简单拼接 title，见 [buildQuery]）；为空时调用方退回用 title */
        val query: String = "",
        /** 来源引擎，仅用于日志排查 */
        val source: String = ""
    )

    /**
     * 拼搜索词：**中文歌手名优先**，再拼歌名。
     *
     * 为什么必须这样拼（实测酷狗）：识曲返回的中文歌标题往往是英文（ACRCloud 走的是
     * Spotify/Deezer 元数据），直接拿英文标题搜会搜到一堆同名英文歌、完全找不到目标；
     * 而「中文歌手名 + 英文标题」第一条就是目标曲（例：`陈楚生 Has Anyone Told You`
     * → 《有没有人告诉你》，时长也对得上）。所以中文名优先级最高。
     */
    private fun buildQuery(title: String, artist: String, zhArtist: String = ""): String {
        val a = if (zhArtist.isNotBlank()) zhArtist.trim() else artist.trim()
        val t = title.trim()
        return when {
            a.isNotBlank() && t.isNotBlank() -> "$a $t"
            a.isNotBlank() -> a
            else -> t
        }
    }

    /**
     * 录音（IO 线程调用）：44.1kHz / 16bit / 单声道 → WAV 字节流
     * @param seconds 录音秒数（AudD 建议 ≥5 秒，识别率随环境噪声下降）
     * @return WAV 数据；权限缺失或录音失败返回 null
     */
    @SuppressLint("MissingPermission")
    fun record(seconds: Int = 7): ByteArray? {
        val t0 = System.currentTimeMillis()
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        Log.i(TAG, "record 开始: seconds=$seconds minBuf=$minBuf")
        if (minBuf <= 0) {
            Log.e(TAG, "record 失败: minBuf<=0 ($minBuf)，设备不支持该录音参数")
            return null
        }

        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 4
            )
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord 构造失败", e)
            return null
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord 未初始化: state=${recorder.state}")
            runCatching { recorder.release() }
            return null
        }
        Log.i(TAG, "AudioRecord 已初始化: state=${recorder.state} buffer=${minBuf * 4}")

        val totalSamples = SAMPLE_RATE * seconds
        val buffer = ShortArray(totalSamples)
        var read = 0
        try {
            recorder.startRecording()
            Log.i(TAG, "startRecording 已调用, recordingState=${recorder.recordingState}")
            while (read < totalSamples) {
                val r = recorder.read(buffer, read, minOf(4096, totalSamples - read))
                if (r <= 0) {
                    Log.e(TAG, "read 返回 $r，中断录音（已读 $read 采样）")
                    break
                }
                read += r
            }
        } catch (e: Exception) {
            Log.e(TAG, "录音过程异常（已读 $read 采样）", e)
            return null
        } finally {
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
        }

        // 录音不足 1 秒视为失败（用户中途退出等）
        if (read < SAMPLE_RATE) {
            Log.e(TAG, "录音不足 1 秒（$read 采样），放弃")
            return null
        }
        val wav = pcmToWav(buffer, read)
        Log.i(TAG, "record 完成: 采样=$read WAV=${wav.size}字节 耗时=${System.currentTimeMillis() - t0}ms")
        return wav
    }

    /** PCM(16bit 单声道) → WAV（标准 44 字节头） */
    private fun pcmToWav(pcm: ShortArray, samples: Int): ByteArray {
        val dataSize = samples * 2
        val out = ByteArrayOutputStream(44 + dataSize)
        val dos = DataOutputStream(out)

        fun ascii(s: String) = dos.writeBytes(s)
        fun intLE(v: Int) {
            dos.writeByte(v and 0xFF)
            dos.writeByte((v shr 8) and 0xFF)
            dos.writeByte((v shr 16) and 0xFF)
            dos.writeByte((v shr 24) and 0xFF)
        }
        fun shortLE(v: Int) {
            dos.writeByte(v and 0xFF)
            dos.writeByte((v shr 8) and 0xFF)
        }

        ascii("RIFF")
        intLE(36 + dataSize)
        ascii("WAVE")
        ascii("fmt ")
        intLE(16)
        shortLE(1)                       // PCM
        shortLE(1)                       // 单声道
        intLE(SAMPLE_RATE)
        intLE(SAMPLE_RATE * 2)           // byte rate
        shortLE(2)                       // block align
        shortLE(16)                      // bits per sample
        ascii("data")
        intLE(dataSize)
        for (i in 0 until samples) {
            val v = pcm[i].toInt()
            dos.writeByte(v and 0xFF)
            dos.writeByte((v shr 8) and 0xFF)
        }
        dos.flush()
        return out.toByteArray()
    }

    /**
     * 识别调度（IO 线程调用）：主引擎 AHA/ACRCloud → ACRCloud 官方密钥（已配置时）→ 兜底 AudD
     * @return 识别结果；未识别 / 超额度 / 网络失败返回 null
     */
    fun recognize(wav: ByteArray): RecognizeResult? {
        if (USE_AHA) {
            Log.i(TAG, "===== 主引擎 AHA(ACRCloud 曲库，免账号) =====")
            val aha = recognizeWithAha(wav)
            if (aha != null) return aha
            Log.i(TAG, "AHA 未识别/失败 → 尝试下一引擎")
        }
        if (acrConfigured) {
            Log.i(TAG, "===== ACRCloud 官方密钥 =====")
            val acr = recognizeWithAcr(wav)
            if (acr != null) return acr
            Log.i(TAG, "ACRCloud 未识别/失败 → 降级 AudD 兜底")
        }
        Log.i(TAG, "===== 兜底 AudD =====")
        return recognizeWithAudD(wav)
    }

    /**
     * AHA Music 识曲（免账号，底层 ACRCloud）
     *
     * 协议（从网页前端逆向）：
     *   POST https://aha-music.com/identify
     *   headers: Content-Type: application/json + **Accept: application/json**
     *            —— 少了 Accept 站点会当成页面请求返回 HTML，拿到的是网页而不是 JSON
     *   body: {"audio": base64(音频字节 + AHA_SALT), "mimeType": "audio/wav"}
     *   （注意不是 multipart，用 multipart 会 400 Missing audio data）
     * 返回：data.title / data.artists[0].name / data.duration_ms / data.external_metadata.deezer…
     */
    private fun recognizeWithAha(wav: ByteArray): RecognizeResult? {
        val t0 = System.currentTimeMillis()
        return try {
            val audio = Base64.encodeToString(
                wav + AHA_SALT.toByteArray(Charsets.UTF_8), Base64.NO_WRAP
            )
            val payload = JSONObject().apply {
                put("audio", audio)
                put("mimeType", "audio/wav")
            }.toString()
            Log.i(TAG, "AHA 上传: ${wav.size}B 音频 → base64 ${audio.length} 字符")

            val conn = (URL(AHA_IDENTIFY_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Referer", "https://aha-music.com/")
                setRequestProperty("Origin", "https://aha-music.com")
                setRequestProperty("User-Agent", AHA_UA)
            }
            DataOutputStream(conn.outputStream).use { it.write(payload.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            if (stream == null) {
                Log.e(TAG, "AHA 响应流为空（code=$code）")
                runCatching { conn.disconnect() }
                return null
            }
            val body = stream.bufferedReader().use { it.readText() }
            Log.i(TAG, "AHA 响应（${System.currentTimeMillis() - t0}ms, code=$code）: ${body.take(400)}")
            runCatching { conn.disconnect() }
            parseAhaResult(body)
        } catch (e: Exception) {
            Log.e(TAG, "AHA 请求失败（${System.currentTimeMillis() - t0}ms）", e)
            null
        }
    }

    /** 解析 AHA 响应；错误时 body 形如 {"error":true,"statusCode":500,"statusMessage":"..."} */
    private fun parseAhaResult(body: String): RecognizeResult? {
        return try {
            val json = JSONObject(body)
            if (json.optBoolean("error", false)) {
                Log.e(TAG, "AHA 返回错误: ${json.optString("statusMessage", "").ifBlank { body.take(200) }}")
                return null
            }
            val data = json.optJSONObject("data") ?: run {
                Log.e(TAG, "AHA 无 data 字段: ${body.take(200)}")
                return null
            }
            val title = data.optString("title", "").trim()
            if (title.isBlank()) return null
            val artistEn = data.optJSONArray("artists")?.optJSONObject(0)?.optString("name", "")?.trim() ?: ""
            val album = data.optJSONObject("album")?.optString("name", "")?.trim() ?: ""
            val zhArtist = extractZhArtist(data)
            val durationMs = data.optLong("duration_ms", 0L)
            Log.i(TAG, "AHA 识别到: title=$title artist=$artistEn 中文名=${zhArtist.ifBlank { "-" }} 时长=${durationMs}ms")
            RecognizeResult(
                title = title,
                // 中文名更好读，优先展示
                artist = if (zhArtist.isNotBlank()) zhArtist else artistEn,
                album = album,
                query = buildQuery(title, artistEn, zhArtist),
                source = "AHA/ACRCloud"
            )
        } catch (e: Exception) {
            Log.e(TAG, "AHA 响应解析失败: ${body.take(200)}", e)
            null
        }
    }

    /**
     * 取歌手中文名 —— 该接口**唯一**能拿到中文的地方（标题永远是英文，加语言头也没用）：
     * external_metadata.deezer.artists[].langs[] = [{code:"zh-hans", name:"陈楚生"}, {zh-hant…}]
     */
    private fun extractZhArtist(data: JSONObject): String {
        val artists = data.optJSONObject("external_metadata")
            ?.optJSONObject("deezer")
            ?.optJSONArray("artists") ?: return ""
        for (i in 0 until artists.length()) {
            val langs = artists.optJSONObject(i)?.optJSONArray("langs") ?: continue
            for (j in 0 until langs.length()) {
                val o = langs.optJSONObject(j) ?: continue
                if (o.optString("code", "").startsWith("zh")) {
                    val name = o.optString("name", "").trim()
                    if (name.isNotBlank()) return name
                }
            }
        }
        return ""
    }

    /**
     * ACRCloud 识别（HMAC-SHA1 签名 + multipart）
     * 官方规格：POST https://{host}/v1/identify
     *   string_to_sign = "POST\n/v1/identify\n{access_key}\naudio\n1\n{timestamp}"
     *   signature = base64(HMAC-SHA1(access_secret, string_to_sign))
     *   status.code: 0=成功 1001=未识别 3003=额度用尽
     */
    private fun recognizeWithAcr(wav: ByteArray): RecognizeResult? {
        val t0 = System.currentTimeMillis()
        return try {
            val timestamp = (System.currentTimeMillis() / 1000).toString()
            val stringToSign = listOf(
                "POST", "/v1/identify", ACR_ACCESS_KEY, "audio", "1", timestamp
            ).joinToString("\n")
            val mac = Mac.getInstance("HmacSHA1")
            mac.init(SecretKeySpec(ACR_ACCESS_SECRET.toByteArray(Charsets.UTF_8), "HmacSHA1"))
            val signature = Base64.encodeToString(
                mac.doFinal(stringToSign.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP
            )

            val boundary = "----DDmusicAcr${System.currentTimeMillis()}"
            val conn = (URL("https://$ACR_HOST/v1/identify").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 15_000
                readTimeout = 25_000
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                setRequestProperty("User-Agent", "DDmusic/1.0 (Android)")
            }
            DataOutputStream(conn.outputStream).use { out ->
                fun textField(name: String, value: String) {
                    out.writeBytes("--$boundary\r\n")
                    out.writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
                    out.writeBytes("$value\r\n")
                }
                textField("access_key", ACR_ACCESS_KEY)
                textField("data_type", "audio")
                textField("signature_version", "1")
                textField("signature", signature)
                textField("sample_bytes", wav.size.toString())
                textField("timestamp", timestamp)
                // 音频部分
                out.writeBytes("--$boundary\r\n")
                out.writeBytes("Content-Disposition: form-data; name=\"sample\"; filename=\"ddmusic.wav\"\r\n")
                out.writeBytes("Content-Type: audio/wav\r\n\r\n")
                out.write(wav)
                out.writeBytes("\r\n--$boundary--\r\n")
                out.flush()
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            if (stream == null) {
                Log.e(TAG, "ACRCloud 响应流为空（code=$code）")
                runCatching { conn.disconnect() }
                return null
            }
            val body = stream.bufferedReader().use { it.readText() }
            Log.i(TAG, "ACRCloud 响应（${System.currentTimeMillis() - t0}ms）: ${body.take(400)}")
            runCatching { conn.disconnect() }
            parseAcrResult(body)
        } catch (e: Exception) {
            Log.e(TAG, "ACRCloud 请求失败（${System.currentTimeMillis() - t0}ms）", e)
            null
        }
    }

    /** 解析 ACRCloud 响应：status.code==0 时取 metadata.music[0] */
    private fun parseAcrResult(body: String): RecognizeResult? {
        return try {
            val json = JSONObject(body)
            val status = json.optJSONObject("status")
            val code = status?.optInt("code", -1) ?: -1
            if (code != 0) {
                Log.e(TAG, "ACRCloud code=$code msg=${status?.optString("msg", "")}（1001=未识别 3003=额度用尽）")
                return null
            }
            val music = json.optJSONObject("metadata")
                ?.optJSONArray("music")
                ?.optJSONObject(0) ?: return null
            val title = music.optString("title", "").trim()
            val artists = music.optJSONArray("artists")
            val artist = if (artists != null && artists.length() > 0) {
                artists.optJSONObject(0)?.optString("name", "")?.trim() ?: ""
            } else ""
            val album = music.optJSONObject("album")?.optString("name", "")?.trim() ?: ""
            if (title.isBlank()) null else RecognizeResult(
                title, artist, album,
                query = buildQuery(title, artist), source = "ACRCloud官方"
            )
        } catch (e: Exception) {
            Log.e(TAG, "ACRCloud 响应解析失败: ${body.take(200)}", e)
            null
        }
    }

    /** 上传 AudD 识别（兜底引擎） */
    private fun recognizeWithAudD(wav: ByteArray): RecognizeResult? {
        val t0 = System.currentTimeMillis()
        val boundary = "----DDmusicBoundary${System.currentTimeMillis()}"
        Log.i(TAG, "recognize 开始(AudD): WAV=${wav.size}字节 token=${if (AUDD_TOKEN.isBlank()) "匿名" else "已配置"}")
        return try {
            val conn = (URL(API_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 15_000
                readTimeout = 25_000
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                setRequestProperty("User-Agent", "DDmusic/1.0 (Android)")
            }
            Log.i(TAG, "连接对象已创建（${System.currentTimeMillis() - t0}ms），准备写入请求体")

            DataOutputStream(conn.outputStream).use { out ->
                Log.i(TAG, "outputStream 已获取（${System.currentTimeMillis() - t0}ms）")
                fun textField(name: String, value: String) {
                    out.writeBytes("--$boundary\r\n")
                    out.writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
                    out.writeBytes("$value\r\n")
                }
                if (AUDD_TOKEN.isNotBlank()) textField("api_token", AUDD_TOKEN)
                textField("return", "apple_music,spotify")
                // 音频部分
                out.writeBytes("--$boundary\r\n")
                out.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"ddmusic.wav\"\r\n")
                out.writeBytes("Content-Type: audio/wav\r\n\r\n")
                out.write(wav)
                out.writeBytes("\r\n--$boundary--\r\n")
                out.flush()
            }
            Log.i(TAG, "请求体已发送（${System.currentTimeMillis() - t0}ms），等待响应")

            val code = conn.responseCode
            Log.i(TAG, "收到响应码 $code（${System.currentTimeMillis() - t0}ms）")
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            if (stream == null) {
                Log.e(TAG, "响应流为空（code=$code）")
                runCatching { conn.disconnect() }
                return null
            }
            val body = stream.bufferedReader().use { it.readText() }
            Log.i(TAG, "响应体（${System.currentTimeMillis() - t0}ms）: ${body.take(400)}")
            runCatching { conn.disconnect() }

            val result = parseResult(body)
            if (result != null) {
                Log.i(TAG, "识别成功: ${result.title} - ${result.artist}")
            } else {
                Log.i(TAG, "未识别到曲目（或响应字段为空）")
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "recognize 失败（${System.currentTimeMillis() - t0}ms）", e)
            null
        }
    }

    /** 解析 AudD 响应：{"status":"success","result":{...}} / result=null（未识别） */
    private fun parseResult(body: String): RecognizeResult? {
        return try {
            val json = JSONObject(body)
            if (json.optString("status") != "success") {
                Log.e(TAG, "AudD 返回非 success: ${json.optString("status")}")
                return null
            }
            val r = json.optJSONObject("result") ?: return null
            val title = r.optString("title", "").trim()
            val artist = r.optString("artist", "").trim()
            if (title.isBlank()) null
            else RecognizeResult(
                title, artist, r.optString("album", "").trim(),
                query = buildQuery(title, artist), source = "AudD"
            )
        } catch (e: Exception) {
            Log.e(TAG, "响应解析失败: ${body.take(200)}", e)
            null
        }
    }
}
