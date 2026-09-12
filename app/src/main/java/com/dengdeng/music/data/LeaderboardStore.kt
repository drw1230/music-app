package com.dengdeng.music.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 联网排行榜（腾讯云 COS 对象存储）——方案 B 实现
 *
 * 存储：COS 桶里一个 leaderboard.json（全量榜单在这一个文件里）
 * - 读：桶开「公有读」→ 匿名 GET，无鉴权无限流，国内直连最快
 * - 写：PUT Object + COS CAM 签名（HMAC-SHA1，纯标准库实现，无需 SDK）
 * - 合并策略：上传前先拉最新全量 → 按「名字+游戏」保留较高分 → 整包写回
 *
 * 配置（三行常量，空 = 只读/未配置状态，页面有提示）：
 * 1. 注册腾讯云 → 对象存储 COS → 创建桶（地域选 ap-chongqing 最快），权限设「公有读私有写」
 * 2. BUCKET_URL = 桶的「访问域名」(形如 https://<桶名>-<appid>.cos.ap-chongqing.myqcloud.com)
 * 3. 访问管理 CAM → 新建子用户 → 只授权该桶读写 → 填 SECRET_ID / SECRET_KEY
 *    （建议用子用户最小权限，勿用主账号密钥；泄露风险=仅该桶数据，可随时在 CAM 吊销）
 *
 * 升级路线：公开运营需防刷时，把本文件换成云函数(SCF)实现即可，页面接口不变。
 */
object LeaderboardStore {

    /** 桶访问域名（外网），例：https://ddmusic-125xxxxxxx.cos.ap-chongqing.myqcloud.com */
    const val BUCKET_URL = "https://ddmusic-1416625633.cos.ap-chongqing.myqcloud.com"

    /** CAM 子用户密钥（仅授权榜单桶读写）；空 = 上传不可用（只读演示） */
    const val SECRET_ID = "REDACTED_COS_ID"
    const val SECRET_KEY = "REDACTED_COS_KEY"

    private const val OBJECT_KEY = "leaderboard.json"

    /** 桶是否已配置（决定页面显示"未配置"还是正常拉取） */
    val configured: Boolean get() = BUCKET_URL.isNotBlank()

    data class Entry(
        val name: String,
        val game: String,
        val score: Long,
        val updatedAt: String
    )

    data class Snapshot(val updatedAt: String, val entries: List<Entry>)

    private fun objectUrl() = "${BUCKET_URL.trimEnd('/')}/$OBJECT_KEY"

    private fun nowIso(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.CHINA).format(Date())

    /** 拉取榜单（匿名，桶需公有读） */
    suspend fun fetch(): Result<Snapshot> = withContext(Dispatchers.IO) {
        runCatching {
            require(configured) { "联网榜未配置：填好 LeaderboardStore 的 BUCKET_URL / SECRET_ID / SECRET_KEY（腾讯云 COS）即生效" }
            val code: Int
            val body: String
            (URL(objectUrl()).openConnection() as HttpURLConnection).let { c ->
                c.connectTimeout = 8000
                c.readTimeout = 8000
                c.setRequestProperty("User-Agent", "DDmusic")
                code = c.responseCode
                body = ((c.inputStream.takeIf { code in 200..299 } ?: c.errorStream))
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                c.disconnect()
            }
            if (code == 404) error("榜单文件还不存在（首次上传成绩时自动创建）")
            if (code == 403) error("桶不是公有读（把权限改为「公有读私有写」）")
            if (code !in 200..299) error("拉取失败（HTTP $code）")
            parse(body)
        }
    }

    /** 上传/更新我的最好成绩（需要 CAM 密钥；合并=同 名字+游戏 保留高分） */
    suspend fun upload(name: String, game: String, score: Long): Result<Snapshot> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(configured) { "联网榜未配置：先填 BUCKET_URL" }
                require(SECRET_ID.isNotBlank() && SECRET_KEY.isNotBlank()) { "未配置腾讯云密钥（SECRET_ID / SECRET_KEY）" }

                // 1) 拉最新全量（404 视为空榜）
                val current = fetch().getOrElse { Snapshot("", emptyList()) }
                val merged = current.entries.toMutableList()
                val idx = merged.indexOfFirst { it.name == name && it.game == game }
                if (idx >= 0) {
                    val old = merged[idx]
                    if (score > old.score) merged[idx] = old.copy(score = score, updatedAt = nowIso())
                } else {
                    merged.add(Entry(name, game, score, nowIso()))
                }

                // 2) 整包写回（PUT Object + CAM 签名）
                val fileJson = JSONObject()
                    .put("updated_at", nowIso())
                    .put(
                        "entries",
                        JSONArray().apply {
                            merged.forEach {
                                put(
                                    JSONObject()
                                        .put("name", it.name)
                                        .put("game", it.game)
                                        .put("score", it.score)
                                        .put("updated_at", it.updatedAt)
                                )
                            }
                        }
                    )
                val code: Int
                val errMsg: String
                (URL(objectUrl()).openConnection() as HttpURLConnection).let { c ->
                    c.requestMethod = "PUT"
                    c.doOutput = true
                    c.connectTimeout = 8000
                    c.readTimeout = 8000
                    c.setRequestProperty("Authorization", cosAuthorization("put", "/$OBJECT_KEY"))
                    c.setRequestProperty("Content-Type", "application/json")
                    c.setRequestProperty("User-Agent", "DDmusic")
                    c.outputStream.use { it.write(fileJson.toString().toByteArray()) }
                    code = c.responseCode
                    errMsg = ((c.errorStream ?: c.inputStream))?.bufferedReader()?.use { it.readText() }.orEmpty().take(300)
                    c.disconnect()
                }
                if (code !in 200..299) {
                    error(
                        when (code) {
                            401, 403 -> "签名/权限被拒（检查 SECRET_ID/KEY 是否为该桶读写授权）HTTP $code"
                            else -> "写入失败（HTTP $code）$errMsg"
                        }
                    )
                }

                // 3) 回读确认
                fetch().getOrElse { Snapshot(nowIso(), merged.sortedWith(compareByDescending<Entry> { it.score }.thenBy { it.name })) }
            }
        }

    // ── COS CAM 签名（XML API，HMAC-SHA1，纯标准库） ──

    private fun cosAuthorization(method: String, pathname: String): String {
        val now = System.currentTimeMillis() / 1000
        val keyTime = "$now;${now + 600}"
        val signKey = hmacSha1Hex(SECRET_KEY, keyTime)
        // HttpString = 小写方法\nURI路径\nhttp参数(空)\nhttp头部(空)\n
        val httpString = "$method\n$pathname\n\n\n"
        val stringToSign = "sha1\n$keyTime\n${sha1Hex(httpString)}\n"
        val signature = hmacSha1Hex(signKey, stringToSign)
        return "q-sign-algorithm=sha1&q-ak=$SECRET_ID&q-sign-time=$keyTime&q-key-time=$keyTime" +
                "&q-header-list=&q-url-param-list=&q-signature=$signature"
    }

    private fun hmacSha1Hex(key: String, data: String): String =
        Mac.getInstance("HmacSHA1").run {
            init(SecretKeySpec(key.toByteArray(), "HmacSHA1"))
            doFinal(data.toByteArray()).joinToString("") { "%02x".format(it) }
        }

    private fun sha1Hex(data: String): String =
        MessageDigest.getInstance("SHA-1").digest(data.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun parse(body: String): Snapshot {
        val root = JSONObject(body)
        val arr = root.optJSONArray("entries") ?: JSONArray()
        val entries = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Entry(
                name = o.optString("name"),
                game = o.optString("game"),
                score = o.optLong("score"),
                updatedAt = o.optString("updated_at")
            )
        }.sortedWith(compareByDescending<Entry> { it.score }.thenBy { it.name })
        return Snapshot(updatedAt = root.optString("updated_at"), entries = entries)
    }
}
