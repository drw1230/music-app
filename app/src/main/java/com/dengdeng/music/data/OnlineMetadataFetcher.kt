package com.dengdeng.music.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 在线元数据获取（网易云音乐公开接口）：
 * - 搜索歌曲：按「歌名 + 艺术家」匹配，返回网易云 songId 与专辑封面 URL
 * - 获取歌词：按 songId 拉取 LRC 文本
 * - 内存缓存搜索结果，避免重复请求
 */
object OnlineMetadataFetcher {

    data class SongMatch(
        val songId: Long,
        val title: String,
        val artist: String,
        val albumArtUrl: String?
    )

    /** QQ 音乐匹配结果 */
    data class QQMatch(
        val songmid: String,
        val albummid: String?,
        val title: String,
        val artist: String,
        val durationMs: Long
    )

    /** 联网搜索结果（多源合并展示用） */
    data class OnlineSong(
        val platform: String,      // 来源平台："网易云" / "QQ音乐" / "酷狗"
        val id: String,            // 网易云数字 id / QQ songmid / 酷狗普通 FileHash
        val title: String,
        val artist: String,
        val album: String,
        val artUrl: String?,
        val durationMs: Long,
        val extra: String = ""     // 平台附加信息（酷狗：各音质 hash/大小 JSON）
    )

    /** 单曲音源条目（不同平台/品质/格式） */
    data class AudioSource(
        val platform: String,      // 来源平台
        val quality: String,       // 品质：标准 / 高品 / 无损
        val format: String,        // 格式：MP3 / FLAC / M4A
        val bitrate: Int,          // 码率（bps），0=未知
        val url: String?,          // 播放地址（null = VIP 受限不可用）
        val sizeBytes: Long,       // 文件大小（0=未知，用码率估算）
        val vip: Boolean           // 是否需 VIP
    )

    // 内存缓存：搜索 key（title|artist）→ 匹配结果，避免每次切歌都重复请求
    private val matchCache = mutableMapOf<String, SongMatch>()
    // 封面缓存：key（title|artist）→ 封面 URL
    private val artworkCache = mutableMapOf<String, String>()
    // 联网搜索结果缓存：关键词 → 结果列表
    private val onlineSearchCache = mutableMapOf<String, List<OnlineSong>>()
    // 音源聚合结果缓存：key=平台|id → (时间戳, 音源列表)，5 分钟内不重复查询
    private val sourceCache = mutableMapOf<String, Pair<Long, List<AudioSource>>>()
    // 酷狗兜底搜索缓存：key（title|artist）→ 酷狗 hash 信息（含 null 结果，避免反复搜索）
    private val kugouFallbackCache = mutableMapOf<String, KugouInfo?>()

    /**
     * 网易云匿名 cookie：模拟官方 PC 客户端（os=pc + appver）
     * 实测（2026-09-12）：不带 cookie 时 VIP/付费歌曲（fee=1）返回 code=-110、url=null；
     * 带上后 VIP 歌 6/6 全部解析成功且音频流可达。无需真实账号登录。
     */
    private val neteaseAnonCookie: String by lazy {
        val rnd = SecureRandom()
        val token = (1..32).joinToString("") { "0123456789abcdef"[rnd.nextInt(16)].toString() }
        "os=pc; appver=2.10.6; MUSIC_A=$token"
    }

    private fun cacheKey(title: String, artist: String) = "$title|$artist".trim().lowercase()

    /**
     * 清空在线搜索/音源相关缓存（"修复音源"用）
     * 场景：某首歌曾解析失败（VIP 受限/平台挂掉）被写进缓存 → 之后一直"播不了"；
     * 清空后下次解析重新走网络，配合 cookie / 跨平台兜底即可恢复。
     * 注意：不清 matchCache（歌曲匹配缓存），避免影响本地歌曲封面/歌词的匹配速度。
     */
    fun clearOnlineCaches() {
        onlineSearchCache.clear()
        sourceCache.clear()
        kugouFallbackCache.clear()
    }

    /**
     * 爬取多个候选封面 URL（多源汇总，供用户手动选择）：
     * iTunes 前 3 个（时长核对排序）→ 网易云专辑/歌手 → QQ 音乐 albummid
     */
    suspend fun searchArtworkCandidates(title: String, artist: String, durationMs: Long? = null): List<String> = withContext(Dispatchers.IO) {
        if (title.isBlank()) return@withContext emptyList()
        val out = mutableListOf<String>()
        val seen = mutableSetOf<String>()

        fun add(u: String?) {
            if (!u.isNullOrBlank() && seen.add(u)) out.add(u)
        }

        // iTunes（最多 3 个，按时长排序）
        runCatching {
            val query = URLEncoder.encode("$title $artist".trim(), "UTF-8")
            val json = httpGet(URL("https://itunes.apple.com/search?term=$query&entity=song&limit=5"))
            val results = json?.optJSONArray("results") ?: return@runCatching
            val items = mutableListOf<Pair<Long, String>>()
            for (i in 0 until results.length()) {
                val r = results.optJSONObject(i) ?: continue
                val art = r.optString("artworkUrl100", "").takeIf { it.isNotBlank() }
                    ?.replace("100x100", "600x600") ?: continue
                val dur = r.optLong("trackTimeMillis", 0L)
                items.add(dur to art)
            }
            if (durationMs != null) {
                items.sortBy { kotlin.math.abs(it.first - durationMs) }
            }
            items.take(3).forEach { add(it.second) }
        }

        // 网易云（专辑封面 + 歌手头像）
        runCatching {
            val query = URLEncoder.encode("$title $artist".trim(), "UTF-8")
            val json = httpGet(URL("https://music.163.com/api/search/get?s=$query&type=1&limit=3"))
            val songs = json?.optJSONObject("result")?.optJSONArray("songs") ?: return@runCatching
            for (i in 0 until songs.length()) {
                val s = songs.optJSONObject(i) ?: continue
                add(s.optJSONObject("album")?.optString("picUrl", null))
                val artists = s.optJSONArray("artists")
                if (artists != null && artists.length() > 0) {
                    add(artists.optJSONObject(0)?.optString("img1v1Url", null))
                }
            }
        }

        // QQ 音乐（albummid 封面）
        runCatching {
            val qq = searchSongQQ(title, artist, durationMs)
            add(qq?.albummid?.takeIf { it.isNotBlank() }?.let { qqAlbumArtUrl(it) })
        }

        out
    }

    /**
     * 搜索封面（多源自动核对）：
     * 1. iTunes 主源：返回高清封面 + 时长，按时长接近度自动核对选最佳
     * 2. 网易云兜底：iTunes 无结果时用网易云专辑/歌手图
     */
    suspend fun searchArtwork(title: String, artist: String, durationMs: Long? = null): String? = withContext(Dispatchers.IO) {
        if (title.isBlank()) return@withContext null
        val key = "art|${cacheKey(title, artist)}"
        artworkCache[key]?.let { return@withContext it }

        // —— iTunes（封面主源，时长自动核对）——
        val query = URLEncoder.encode("$title $artist".trim(), "UTF-8")
        val itunesUrl = URL("https://itunes.apple.com/search?term=$query&entity=song&limit=5")
        val itunesJson = httpGet(itunesUrl)
        val results = itunesJson?.optJSONArray("results")
        if (results != null && results.length() > 0) {
            var bestUrl: String? = null
            var bestDiff = Long.MAX_VALUE
            for (i in 0 until results.length()) {
                val r = results.optJSONObject(i) ?: continue
                val art = r.optString("artworkUrl100", "").takeIf { it.isNotBlank() }
                    ?.replace("100x100", "600x600")   // 升级为高清大图
                if (art == null) continue
                val dur = r.optLong("trackTimeMillis", 0L)
                if (durationMs != null && dur > 0) {
                    // 时长自动核对：选与本地歌曲时长最接近的
                    val diff = kotlin.math.abs(dur - durationMs)
                    if (diff < bestDiff) {
                        bestDiff = diff
                        bestUrl = art
                    }
                } else {
                    bestUrl = art
                    break
                }
            }
            if (bestUrl != null) {
                artworkCache[key] = bestUrl
                return@withContext bestUrl
            }
        }

        // —— 网易云兜底（专辑封面或歌手头像）——
        val match = searchSong(title, artist, durationMs)
        val neteaseArt = match?.albumArtUrl
        if (neteaseArt != null) {
            artworkCache[key] = neteaseArt
            return@withContext neteaseArt
        }

        // —— QQ 音乐兜底（albummid → 封面 URL）——
        val qq = searchSongQQ(title, artist, durationMs)
        val qqArt = qq?.albummid?.takeIf { it.isNotBlank() }?.let { qqAlbumArtUrl(it) }
        if (qqArt != null) artworkCache[key] = qqArt
        return@withContext qqArt
    }

    /** 搜索歌曲，返回最佳匹配（按时长接近度，无时长信息则取第一个） */
    suspend fun searchSong(title: String, artist: String, durationMs: Long? = null): SongMatch? = withContext(Dispatchers.IO) {
        if (title.isBlank()) return@withContext null
        val key = cacheKey(title, artist)
        matchCache[key]?.let { return@withContext it }

        val query = URLEncoder.encode("$title $artist".trim(), "UTF-8")
        val url = URL("https://music.163.com/api/search/get?s=$query&type=1&limit=3")
        val json = httpGet(url) ?: return@withContext null

        val songs = json.optJSONObject("result")?.optJSONArray("songs") ?: return@withContext null
        var best: SongMatch? = null
        var bestDiff = Long.MAX_VALUE

        for (i in 0 until songs.length()) {
            val s = songs.optJSONObject(i) ?: continue
            val id = s.optLong("id", 0L)
            if (id <= 0L) continue
            val name = s.optString("name", title)
            val artistsArr = s.optJSONArray("artists")
            val artistName = artistsArr
                ?.takeIf { it.length() > 0 }
                ?.let { it.optJSONObject(0)?.optString("name", "") } ?: ""
            // 封面优先专辑 picUrl；缺失时用歌手头像 img1v1Url 兜底
            var artUrl = s.optJSONObject("album")?.optString("picUrl", null)?.takeIf { it.isNotBlank() }
            if (artUrl == null && artistsArr != null && artistsArr.length() > 0) {
                artUrl = artistsArr.optJSONObject(0)?.optString("img1v1Url", null)?.takeIf { it.isNotBlank() }
            }
            val match = SongMatch(id, name, artistName, artUrl)

            // 时长匹配度（秒级误差越小越好）
            val durMs = s.optLong("duration", 0L)
            if (durationMs != null && durMs > 0) {
                val diff = kotlin.math.abs(durMs - durationMs)
                if (diff < bestDiff) {
                    bestDiff = diff
                    best = match
                }
            } else {
                best = match
                break
            }
        }

        if (best != null) matchCache[key] = best
        return@withContext best
    }

    /** 按歌名+歌手联网取歌词（供下载保存 .lrc；先搜歌拿 id 再取歌词） */
    suspend fun fetchLyricForSong(title: String, artist: String): String? = withContext(Dispatchers.IO) {
        val match = searchSong(title, artist) ?: return@withContext null
        fetchLyric(match.songId)
    }

    /** 按 songId 获取 LRC 歌词文本 */
    suspend fun fetchLyric(songId: Long): String? = withContext(Dispatchers.IO) {
        if (songId <= 0L) return@withContext null
        val url = URL("https://music.163.com/api/song/lyric?id=$songId&lv=1&kv=1&tv=-1")
        val json = httpGet(url) ?: return@withContext null
        val lyric = json.optJSONObject("lrc")?.optString("lyric", "")?.takeIf { it.isNotBlank() }
        return@withContext lyric
    }

    // ==================== QQ 音乐备选源 ====================

    /**
     * QQ 音乐搜索（备选源）：w 参数公开接口
     * 返回最佳匹配（按时长校准），含 songmid（歌词）与 albummid（封面）
     */
    suspend fun searchSongQQ(title: String, artist: String, durationMs: Long? = null): QQMatch? = withContext(Dispatchers.IO) {
        if (title.isBlank()) return@withContext null
        val query = URLEncoder.encode("$title $artist".trim(), "UTF-8")
        val url = URL("https://c.y.qq.com/soso/fcgi-bin/client_search_cp?w=$query&format=json&n=5&p=1")
        val json = httpGetWithHeaders(url) ?: return@withContext null

        val list = json.optJSONObject("data")?.optJSONObject("song")?.optJSONArray("list")
            ?: return@withContext null

        var best: QQMatch? = null
        var bestDiff = Long.MAX_VALUE
        for (i in 0 until list.length()) {
            val s = list.optJSONObject(i) ?: continue
            val songmid = s.optString("songmid", "").takeIf { it.isNotBlank() } ?: continue
            val name = s.optString("songname", title)
            val singerName = s.optJSONArray("singer")
                ?.takeIf { it.length() > 0 }
                ?.let { it.optJSONObject(0)?.optString("name", "") } ?: ""
            val albummid = s.optString("albummid", "").takeIf { it.isNotBlank() }
            val durMs = s.optLong("interval", 0L) * 1000L   // interval 单位是秒
            val match = QQMatch(songmid, albummid, name, singerName, durMs)

            if (durationMs != null && durMs > 0) {
                val diff = kotlin.math.abs(durMs - durationMs)
                if (diff < bestDiff) {
                    bestDiff = diff
                    best = match
                }
            } else {
                best = match
                break
            }
        }
        return@withContext best
    }

    /** 按 QQ songmid 获取 LRC 歌词 */
    suspend fun fetchLyricQQ(songmid: String): String? = withContext(Dispatchers.IO) {
        if (songmid.isBlank()) return@withContext null
        val url = URL("https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=$songmid&format=json&nobase64=1")
        val json = httpGetWithHeaders(url) ?: return@withContext null
        val lyric = json.optString("lyric", "").takeIf { it.isNotBlank() }
        return@withContext lyric
    }

    /** 由 QQ albummid 拼出封面 URL（500x500） */
    fun qqAlbumArtUrl(albummid: String): String =
        "https://y.gtimg.cn/music/photo_new/T002R500x500M000$albummid.jpg"

    /** GET 请求并解析 JSON（UTF-8），失败返回 null */
    // ==================== 网易云 weapi 加密（相似歌曲 simiSong 接口需要，老 API 已废弃） ====================

    private val WEAPI_PRESET_KEY = "0CoJUm6Qyw8W8jud".toByteArray(Charsets.UTF_8)
    private val WEAPI_IV = "0102030405060708".toByteArray(Charsets.UTF_8)
    private val WEAPI_MODULUS_HEX =
        "e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725152b3ab17a876aea8a5aa76d2e417629ec4ee341f56135fccf695280104e0312ecbda92557c93870114af6c9d05c4f7f0c3685b7a46bee255932575cce10b424d813cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7"
    private val WEAPI_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

    /** AES-CBC 加密（PKCS5 padding，与网易云一致） */
    private fun aesCbcEncrypt(data: ByteArray, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(WEAPI_IV))
        return cipher.doFinal(data)
    }

    /** 教科书 RSA 无 padding：secKey 反转 → m^e mod n → 256 位 hex */
    private fun rsaNoPaddingEncrypt(data: ByteArray): String {
        val n = BigInteger(WEAPI_MODULUS_HEX, 16)
        val m = BigInteger(1, data.reversedArray())
        val c = m.modPow(BigInteger.valueOf(65537L), n)
        return c.toString(16).padStart(256, '0')
    }

    /** 生成 weapi 请求体（params + encSecKey，表单编码） */
    private fun weapiBody(payload: JSONObject): String {
        val secKey = (0 until 16)
            .map { WEAPI_CHARS[SecureRandom().nextInt(WEAPI_CHARS.length)] }
            .joinToString("")
        // 第一层 AES（presetKey）→ base64 → 第二层 AES（随机 secKey）
        val first = aesCbcEncrypt(payload.toString().toByteArray(Charsets.UTF_8), WEAPI_PRESET_KEY)
        val b64 = Base64.getEncoder().encodeToString(first)
        val params = aesCbcEncrypt(b64.toByteArray(Charsets.UTF_8), secKey.toByteArray(Charsets.UTF_8))
        val encSecKey = rsaNoPaddingEncrypt(secKey.toByteArray(Charsets.UTF_8))
        return "params=" + URLEncoder.encode(Base64.getEncoder().encodeToString(params), "UTF-8") +
                "&encSecKey=" + encSecKey
    }

    /** POST weapi 接口并解析 JSON */
    private fun weapiPost(path: String, payload: JSONObject): JSONObject? {
        return try {
            val conn = (URL("https://music.163.com" + path).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 6000
                readTimeout = 6000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel) Chrome/120 Mobile")
                setRequestProperty("Referer", "https://music.163.com")
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                doOutput = true
            }
            conn.outputStream.use { it.write(weapiBody(payload).toByteArray(Charsets.UTF_8)) }
            readJson(conn)
        } catch (e: Exception) {
            null
        }
    }

    private fun httpGet(url: URL): JSONObject? {
        return try {
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6000
                readTimeout = 6000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                setRequestProperty("Accept", "application/json")
                // 网易云接口：带 Referer + 匿名 cookie，否则 VIP/付费歌曲解析不出播放地址（code=-110）
                if (url.host.contains("163.com")) {
                    setRequestProperty("Referer", "https://music.163.com")
                    setRequestProperty("Cookie", neteaseAnonCookie)
                }
            }
            readJson(conn)
        } catch (e: Exception) {
            null
        }
    }

    /** GET 请求（带 Referer，QQ 音乐接口需要）并解析 JSON */
    private fun httpGetWithHeaders(url: URL): JSONObject? {
        return try {
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6000
                readTimeout = 6000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                setRequestProperty("Referer", "https://y.qq.com")
                setRequestProperty("Accept", "application/json")
            }
            readJson(conn)
        } catch (e: Exception) {
            null
        }
    }

    private fun readJson(conn: HttpURLConnection): JSONObject? {
        if (conn.responseCode != 200) {
            conn.disconnect()
            return null
        }
        val reader = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8"))
        val sb = StringBuilder()
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            sb.append(line)
        }
        reader.close()
        conn.disconnect()
        return JSONObject(sb.toString())
    }

    // ==================== 联网搜索 + 音源查询 ====================

    /**
     * 联网搜索歌曲（网易云 + QQ 音乐多源合并）
     * 返回按来源分组的匹配歌曲列表，供"网络搜索"界面展示
     */
    suspend fun searchSongsOnline(query: String, limit: Int = 15): List<OnlineSong> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext emptyList()
        val key = q.lowercase()
        onlineSearchCache[key]?.let { return@withContext it }

        val results = mutableListOf<OnlineSong>()

        // —— 网易云 ——
        try {
            val nUrl = URL("https://music.163.com/api/search/get?s=" + URLEncoder.encode(q, "UTF-8") + "&type=1&limit=$limit")
            val nJson = httpGet(nUrl)
            val nSongs = nJson?.optJSONObject("result")?.optJSONArray("songs")
            if (nSongs != null) {
                for (i in 0 until nSongs.length()) {
                    val s = nSongs.optJSONObject(i) ?: continue
                    val id = s.optLong("id", 0L)
                    if (id <= 0L) continue
                    val title = s.optString("name", "")
                    val artistsArr = s.optJSONArray("artists")
                    val artist = artistsArr
                        ?.takeIf { it.length() > 0 }
                        ?.let { it.optJSONObject(0)?.optString("name", "") } ?: ""
                    var artUrl = s.optJSONObject("album")?.optString("picUrl", null)?.takeIf { it.isNotBlank() }
                    if (artUrl == null && artistsArr != null && artistsArr.length() > 0) {
                        artUrl = artistsArr.optJSONObject(0)?.optString("img1v1Url", null)?.takeIf { it.isNotBlank() }
                    }
                    results.add(
                        OnlineSong(
                            platform = "网易云",
                            id = id.toString(),
                            title = title,
                            artist = artist,
                            album = s.optJSONObject("album")?.optString("name", "") ?: "",
                            artUrl = artUrl,
                            durationMs = s.optLong("duration", 0L)
                        )
                    )
                }
            }
        } catch (e: Exception) { }

        // —— QQ 音乐 ——
        try {
            val qUrl = URL("https://c.y.qq.com/soso/fcgi-bin/client_search_cp?w=" + URLEncoder.encode(q, "UTF-8") + "&format=json&n=$limit&p=1")
            val qJson = httpGetWithHeaders(qUrl)
            val qSongs = qJson?.optJSONObject("data")?.optJSONObject("song")?.optJSONArray("list")
            if (qSongs != null) {
                for (i in 0 until qSongs.length()) {
                    val s = qSongs.optJSONObject(i) ?: continue
                    val songmid = s.optString("songmid", "")
                    if (songmid.isBlank()) continue
                    val singers = s.optJSONArray("singer")
                    val artist = singers
                        ?.takeIf { it.length() > 0 }
                        ?.let { it.optJSONObject(0)?.optString("name", "") } ?: ""
                    results.add(
                        OnlineSong(
                            platform = "QQ音乐",
                            id = songmid,
                            title = s.optString("songname", ""),
                            artist = artist,
                            album = s.optString("albumname", ""),
                            artUrl = s.optString("albummid", "").takeIf { it.isNotBlank() }?.let { qqAlbumArtUrl(it) },
                            durationMs = (s.optLong("interval", 0L) * 1000L)
                        )
                    )
                }
            }
        } catch (e: Exception) { }

        // —— 酷狗 ——
        try {
            val kUrl = URL("https://songsearch.kugou.com/song_search_v2?keyword=" + URLEncoder.encode(q, "UTF-8") + "&page=1&pagesize=$limit")
            val kJson = httpGet(kUrl)
            val kSongs = kJson?.optJSONObject("data")?.optJSONArray("lists")
            if (kSongs != null) {
                for (i in 0 until kSongs.length()) {
                    val s = kSongs.optJSONObject(i) ?: continue
                    val hash = s.optString("FileHash", "")
                    if (hash.isBlank()) continue
                    // 打包各音质 hash/大小信息供音源界面使用
                    val extra = JSONObject()
                        .put("hash", hash)
                        .put("hqHash", s.optString("HQFileHash", ""))
                        .put("sqHash", s.optString("SQFileHash", ""))
                        .put("size", s.optLong("FileSize", 0L))
                        .put("hqSize", s.optLong("HQFileSize", 0L))
                        .put("sqSize", s.optLong("SQFileSize", 0L))
                        .put("hqBit", s.optInt("HQBitrate", 0))
                        .put("sqBit", s.optInt("SQBitrate", 0))
                        .put("pay", s.optInt("PayType", 0))
                        .toString()
                    results.add(
                        OnlineSong(
                            platform = "酷狗",
                            id = hash,
                            title = s.optString("SongName", ""),
                            artist = s.optString("SingerName", ""),
                            album = s.optString("AlbumName", ""),
                            artUrl = s.optString("AlbumImage", null)?.takeIf { it.isNotBlank() },
                            durationMs = (s.optInt("Duration", 0) * 1000L),
                            extra = extra
                        )
                    )
                }
            }
        } catch (e: Exception) { }

        // 同一首歌多平台命中时去重（按 歌名|歌手）
        val dedup = LinkedHashMap<String, OnlineSong>()
        for (s in results) {
            val k = "${s.title}|${s.artist}".lowercase()
            if (!dedup.containsKey(k)) dedup[k] = s
        }
        val final = dedup.values.toList()
        if (final.isNotEmpty()) onlineSearchCache[key] = final
        return@withContext final
    }

    /**
     * 查询单曲的所有可用音源（跨平台聚合：网易云 + QQ音乐 + 酷狗 × 各品质档）
     * 不局限于来源平台——点任何一首歌都能看到全部渠道的音源
     * - 网易云：128k 标准 / 320k 高品 / 无损（VIP 返回 null url）
     * - QQ 音乐：M500 mp3 128k / M800 mp3 320k / F000 flac 无损（VIP 返回空 purl）
     * - 酷狗：普通 / 320k 高品 / 无损 flac（免费歌曲多，Privilege=0 可直接下载）
     * 三平台 + 各品质档并行请求，结果内存缓存 5 分钟（避免重复查询卡弹窗）
     */
    suspend fun fetchAudioSources(song: OnlineSong): List<AudioSource> = withContext(Dispatchers.IO) {
        val cacheKey = "${song.platform}|${song.id}"
        sourceCache[cacheKey]?.let { (t, v) ->
            if (System.currentTimeMillis() - t < 300_000L) return@withContext v
            sourceCache.remove(cacheKey)
        }
        val durSec = (song.durationMs / 1000L).coerceAtLeast(1L)
        val title = song.title
        val artist = song.artist

        val sources = coroutineScope {
            // ===== 1. 网易云 =====
            val netease = async {
                val nid = if (song.platform == "网易云") song.id.toLongOrNull() else null
                val id = nid ?: searchSong(title, artist, song.durationMs)?.songId
                if (id == null || id <= 0L) emptyList()
                else listOf(
                    Triple(128000, "标准", "MP3"),
                    Triple(320000, "高品", "MP3"),
                    Triple(999000, "无损", "FLAC")
                ).map { (br, quality, format) -> async { neteaseAudioSource(id, br, quality, format, durSec) } }
                    .awaitAll().filterNotNull()
            }

            // ===== 2. QQ 音乐 =====
            val qq = async {
                val mid = if (song.platform == "QQ音乐") song.id else null
                val songmid = mid ?: searchSongQQ(title, artist, song.durationMs)?.songmid
                if (songmid.isNullOrBlank()) emptyList()
                else listOf(
                    Triple("M500", "标准", "MP3"),
                    Triple("M800", "高品", "MP3"),
                    Triple("F000", "无损", "FLAC")
                ).map { (prefix, quality, format) -> async { qqAudioSource(songmid, prefix, quality, format, durSec) } }
                    .awaitAll().filterNotNull()
            }

            // ===== 3. 酷狗（本平台 extra 优先；其它平台的歌 → 按歌名+歌手搜索兜底）=====
            val kugou = async {
                val kg = parseKugouExtra(song) ?: searchKugouByTitle(title, artist)
                if (kg != null) {
                    val out = mutableListOf<AudioSource>()
                    kugouAudioSources(kg.hash, kg.hqHash, kg.sqHash, kg.size, kg.hqSize, kg.sqSize, kg.hqBit, kg.sqBit, durSec, out)
                    out.toList()
                } else emptyList()
            }

            listOf(netease, qq, kugou).awaitAll().flatten()
        }

        sourceCache[cacheKey] = System.currentTimeMillis() to sources
        sources
    }

    /** 酷狗 extra JSON 解析 */
    private data class KugouInfo(
        val hash: String, val hqHash: String, val sqHash: String,
        val size: Long, val hqSize: Long, val sqSize: Long,
        val hqBit: Int, val sqBit: Int
    )

    private fun parseKugouExtra(song: OnlineSong): KugouInfo? {
        if (song.platform != "酷狗" || song.extra.isBlank()) return null
        return try {
            val j = JSONObject(song.extra)
            KugouInfo(
                hash = j.optString("hash", song.id),
                hqHash = j.optString("hqHash", ""),
                sqHash = j.optString("sqHash", ""),
                size = j.optLong("size", 0L),
                hqSize = j.optLong("hqSize", 0L),
                sqSize = j.optLong("sqSize", 0L),
                hqBit = j.optInt("hqBit", 0),
                sqBit = j.optInt("sqBit", 0)
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 按「歌名 + 歌手」在酷狗搜索（跨平台兜底用）
     * 结果缓存（含 null），避免同一首歌反复搜索
     */
    private suspend fun searchKugouByTitle(title: String, artist: String): KugouInfo? {
        if (title.isBlank()) return null
        val key = cacheKey(title, artist)
        if (kugouFallbackCache.containsKey(key)) return kugouFallbackCache[key]
        val info = try {
            val kw = URLEncoder.encode("$title $artist".trim(), "UTF-8")
            val url = URL("https://songsearch.kugou.com/song_search_v2?keyword=$kw&page=1&pagesize=1")
            val s = httpGet(url)?.optJSONObject("data")?.optJSONArray("lists")?.optJSONObject(0)
            s?.let {
                KugouInfo(
                    hash = it.optString("FileHash", ""),
                    hqHash = it.optString("HQFileHash", ""),
                    sqHash = it.optString("SQFileHash", ""),
                    size = it.optLong("FileSize", 0L),
                    hqSize = it.optLong("HQFileSize", 0L),
                    sqSize = it.optLong("SQFileSize", 0L),
                    hqBit = it.optInt("HQBitrate", 0),
                    sqBit = it.optInt("SQBitrate", 0)
                )
            }?.takeIf { it.hash.isNotBlank() }
        } catch (e: Exception) {
            null
        }
        kugouFallbackCache[key] = info
        return info
    }

    /**
     * 跨平台兜底取流：本平台音源解析失败（VIP 限制 / 未登录 / 版权）时，
     * 按「歌名 + 歌手」去酷狗搜索取流（酷狗免费源多、无需登录，实测 8/8 稳定）
     */
    private suspend fun kugouFallbackUrl(title: String, artist: String): String? {
        val info = searchKugouByTitle(title, artist) ?: return null
        if (info.hqHash.isNotBlank()) {
            kugouAudioSource(info.hqHash, "高品", "MP3", if (info.hqBit > 0) info.hqBit else 320000, info.hqSize, 1L)
                ?.url?.let { return it }
        }
        if (info.hash.isNotBlank()) {
            kugouAudioSource(info.hash, "标准", "MP3", 128000, info.size, 1L)?.url?.let { return it }
        }
        return null
    }

    /** 酷狗多档音源（普通 / 高品 / 无损，各调一次 getSongInfo 拿播放地址，并行请求） */
    private suspend fun kugouAudioSources(
        hash: String, hqHash: String, sqHash: String,
        size: Long, hqSize: Long, sqSize: Long,
        hqBit: Int, sqBit: Int, durSec: Long,
        sources: MutableList<AudioSource>
    ) {
        val items = coroutineScope {
            listOfNotNull(
                hash.takeIf { it.isNotBlank() }?.let { async { kugouAudioSource(it, "标准", "MP3", 128000, size, durSec) } },
                hqHash.takeIf { it.isNotBlank() }?.let { async { kugouAudioSource(it, "高品", "MP3", if (hqBit > 0) hqBit else 320000, hqSize, durSec) } },
                sqHash.takeIf { it.isNotBlank() }?.let { async { kugouAudioSource(it, "无损", "FLAC", if (sqBit > 0) sqBit else 0, sqSize, durSec) } }
            ).awaitAll().filterNotNull()
        }
        sources.addAll(items)
    }

    /** 酷狗单档音源（getSongInfo 拿播放 URL） */
    private suspend fun kugouAudioSource(hash: String, quality: String, format: String, bitrate: Int, sizeBytes: Long, durSec: Long): AudioSource? {
        return try {
            val url = URL("https://m.kugou.com/app/i/getSongInfo.php?cmd=playInfo&hash=$hash")
            val json = httpGet(url)
            val audioUrl = json?.optString("url", null)?.takeIf { it.isNotBlank() }
            if (audioUrl == null) {
                AudioSource("酷狗", quality, format, bitrate, null, sizeBytes, true)
            } else {
                AudioSource("酷狗", quality, format, bitrate, audioUrl, sizeBytes, false)
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 网易云单码率音源 */
    private suspend fun neteaseAudioSource(id: Long, br: Int, quality: String, format: String, durSec: Long): AudioSource? {
        return try {
            val url = URL("https://music.163.com/api/song/enhance/player/url?id=$id&ids=%5B$id%5D&br=$br")
            val json = httpGet(url)
            val data = json?.optJSONArray("data")?.optJSONObject(0)
            val audioUrl = data?.optString("url", null)?.takeIf { it.isNotBlank() }
            val size = data?.optLong("size", 0L) ?: 0L
            if (audioUrl == null) {
                // VIP 受限：仅占位条目（url=null，vip=true）
                AudioSource("网易云", quality, format, br, null, 0L, true)
            } else {
                AudioSource("网易云", quality, format, br, audioUrl, size, false)
            }
        } catch (e: Exception) {
            null
        }
    }

    /** QQ 音乐单品质音源 */
    private suspend fun qqAudioSource(songmid: String, prefix: String, quality: String, format: String, durSec: Long): AudioSource? {
        return try {
            val filename = "${prefix}${songmid}.${if (prefix == "F000") "flac" else "mp3"}"
            val param = JSONObject()
                .put("req_0", JSONObject()
                    .put("module", "vkey.GetVkeyServer")
                    .put("method", "CgiGetVkey")
                    .put("param", JSONObject()
                        .put("guid", "1234567890")
                        .put("songmid", JSONArray().put(songmid))
                        .put("songtype", JSONArray().put(0))
                        .put("uin", "0")
                        .put("loginflag", 1)
                        .put("platform", "20")
                        .put("filename", JSONArray().put(filename))))
            val url = URL("https://u.y.qq.com/cgi-bin/musicu.fcg?format=json&data=" + URLEncoder.encode(param.toString(), "UTF-8"))
            val json = httpGetWithHeaders(url)
            val info = json?.optJSONObject("req_0")?.optJSONObject("data")?.optJSONArray("midurlinfo")?.optJSONObject(0)
            val purl = info?.optString("purl", "") ?: ""
            if (purl.isBlank()) {
                AudioSource("QQ音乐", quality, format, if (prefix == "F000") 0 else if (prefix == "M800") 320000 else 128000, null, 0L, true)
            } else {
                val sip = json?.optJSONObject("req_0")?.optJSONObject("data")?.optJSONArray("sip")?.optString(0) ?: ""
                val full = if (sip.endsWith("/")) "$sip$purl" else "$sip/$purl"
                val bitrate = if (prefix == "F000") 0 else if (prefix == "M800") 320000 else 128000
                val size = if (prefix == "F000") 0L else bitrate / 8L * durSec
                AudioSource("QQ音乐", quality, format, bitrate, full, size, false)
            }
        } catch (e: Exception) {
            null
        }
    }

    // ==================== 热歌榜 / 电台 ====================

    /**
     * 获取热门歌曲（网易云歌单 + QQ 榜单 双源合并）
     * @param limit 每榜取多少首（合并去重后约 2×limit）
     * @param neteaseId 网易云歌单 id（默认 3778678 热歌榜；可传飙升榜 19723756 / 新歌榜 3779629 / 流行指数 2884035 等）
     * @param qqTopId QQ 榜单 topid（默认 4 热歌榜；可传 52 抖音热歌 / 5 新歌榜 / 26 欧美金曲 等）
     */
    suspend fun fetchHotSongs(
        limit: Int = 20,
        neteaseId: Long = 3778678L,
        qqTopId: Int = 4
    ): List<OnlineSong> = withContext(Dispatchers.IO) {
        val results = mutableListOf<OnlineSong>()

        // —— 网易云热歌榜（歌单 id 参数化）——
        try {
            val url = URL("https://music.163.com/api/playlist/detail?id=$neteaseId&updateTime=-1")
            val json = httpGet(url)
            val tracks = json?.optJSONObject("result")?.optJSONArray("tracks")
            if (tracks != null) {
                var count = 0
                for (i in 0 until tracks.length()) {
                    if (count >= limit) break
                    val s = tracks.optJSONObject(i) ?: continue
                    val id = s.optLong("id", 0L)
                    if (id <= 0L) continue
                    val artistsArr = s.optJSONArray("artists")
                    val artist = artistsArr
                        ?.takeIf { it.length() > 0 }
                        ?.let { it.optJSONObject(0)?.optString("name", "") } ?: ""
                    var artUrl = s.optJSONObject("album")?.optString("picUrl", null)?.takeIf { it.isNotBlank() }
                    if (artUrl == null && artistsArr != null && artistsArr.length() > 0) {
                        artUrl = artistsArr.optJSONObject(0)?.optString("img1v1Url", null)?.takeIf { it.isNotBlank() }
                    }
                    results.add(
                        OnlineSong(
                            platform = "网易云",
                            id = id.toString(),
                            title = s.optString("name", ""),
                            artist = artist,
                            album = s.optJSONObject("album")?.optString("name", "") ?: "",
                            artUrl = artUrl,
                            durationMs = s.optLong("duration", 0L)
                        )
                    )
                    count++
                }
            }
        } catch (e: Exception) { }

        // —— QQ 热歌榜（topid 参数化）——
        try {
            val url = URL("https://c.y.qq.com/v8/fcg-bin/fcg_v8_toplist_cp.fcg?topid=$qqTopId&format=json&page=detail&type=top&tpl=3")
            val json = httpGetWithHeaders(url)
            val songlist = json?.optJSONArray("songlist")
            if (songlist != null) {
                var count = 0
                for (i in 0 until songlist.length()) {
                    if (count >= limit) break
                    val d = songlist.optJSONObject(i)?.optJSONObject("data") ?: continue
                    val songmid = d.optString("songmid", "")
                    if (songmid.isBlank()) continue
                    val singers = d.optJSONArray("singer")
                    val artist = singers
                        ?.takeIf { it.length() > 0 }
                        ?.let { it.optJSONObject(0)?.optString("name", "") } ?: ""
                    results.add(
                        OnlineSong(
                            platform = "QQ音乐",
                            id = songmid,
                            title = d.optString("songname", ""),
                            artist = artist,
                            album = d.optString("albumname", ""),
                            artUrl = d.optString("albummid", "").takeIf { it.isNotBlank() }?.let { qqAlbumArtUrl(it) },
                            durationMs = d.optLong("interval", 0L) * 1000L
                        )
                    )
                    count++
                }
            }
        } catch (e: Exception) { }

        // 去重（歌名|歌手）
        val dedup = LinkedHashMap<String, OnlineSong>()
        for (s in results) {
            val k = "${s.title}|${s.artist}".lowercase()
            if (!dedup.containsKey(k)) dedup[k] = s
        }
        return@withContext dedup.values.toList()
    }

    /**
     * 按歌手取平台热门歌（电台「熟悉版」平台部分）
     * 输入我最常听的歌手列表，并行搜索每个歌手的热门歌曲（网易云+QQ 双源）
     * 过滤已听过的歌（listenedKeys），并按歌名去重（同名/翻唱只保留第一首，避免刷屏）
     */
    suspend fun fetchFamiliarSongs(
        artists: List<String>,
        listenedKeys: Set<String>,
        perArtist: Int = 8,
        limit: Int = 30
    ): List<OnlineSong> =
        withContext(Dispatchers.IO) {
            if (artists.isEmpty()) return@withContext emptyList()
            val results = coroutineScope {
                artists.map { artist ->
                    async {
                        runCatching { searchSongsOnline(artist, perArtist) }.getOrNull() ?: emptyList()
                    }
                }.awaitAll().flatten()
            }
            val seenTitle = HashSet<String>()
            val out = ArrayList<OnlineSong>()
            for (s in results) {
                val key = "${s.title}|${s.artist}".lowercase()
                if (key in listenedKeys) continue          // 已听过的歌不再推荐
                if (!seenTitle.add(s.title.lowercase())) continue  // 同名只留一首
                out.add(s)
                if (out.size >= limit) break
            }
            out
        }

    /**
     * 相似曲目推荐（电台「熟悉版」数据源 v2：纯平台相似曲目，不含本地、不按歌手）：
     * 种子歌（用户常听/喜欢的 title|artist）→ 网易云搜索拿 songId → simiSong 相似接口拉相似歌。
     * 多首种子歌的相似结果混合 → 风格广泛；过滤已听；每首种子独立失败不影响整体。
     */
    suspend fun fetchSimilarSongs(
        seedSongs: List<Pair<String, String>>,   // title to artist
        listenedKeys: Set<String>,
        perSeed: Int = 15,
        limit: Int = 50
    ): List<OnlineSong> = withContext(Dispatchers.IO) {
        if (seedSongs.isEmpty()) return@withContext emptyList()
        val results = coroutineScope {
            seedSongs.map { (title, artist) ->
                async {
                    runCatching {
                        // 1) 种子歌 → 网易云 songId（title+artist 搜索提高命中）
                        val q = if (artist.isNotBlank() && artist != "未知艺术家" && artist != "未知") {
                            "$title $artist"
                        } else title
                        val seed = searchSongsOnline(q, 1).firstOrNull() ?: return@async emptyList()
                        // 2) weapi simiSong 相似曲目接口（老 api/discovery/simiSong 已废弃返回 400；参数名 songid 小写）
                        val payload = JSONObject()
                            .put("songid", seed.id.toLongOrNull() ?: 0L)
                            .put("limit", perSeed)
                            .put("offset", 0)
                        val json = weapiPost("/weapi/v1/discovery/simiSong", payload)
                        val songs = json?.optJSONArray("songs")
                            ?: json?.optJSONObject("result")?.optJSONArray("songs")
                        if (songs == null) emptyList() else {
                            (0 until songs.length()).mapNotNull { i ->
                                val s = songs.optJSONObject(i) ?: return@mapNotNull null
                                val id = s.optLong("id", 0L)
                                if (id <= 0L) return@mapNotNull null
                                val artistsArr = s.optJSONArray("artists")
                                val art = artistsArr
                                    ?.takeIf { it.length() > 0 }
                                    ?.let { it.optJSONObject(0)?.optString("name", "") } ?: ""
                                OnlineSong(
                                    platform = "网易云",
                                    id = id.toString(),
                                    title = s.optString("name", ""),
                                    artist = art,
                                    album = s.optJSONObject("album")?.optString("name", "") ?: "",
                                    artUrl = s.optJSONObject("album")?.optString("picUrl", null)?.takeIf { it.isNotBlank() },
                                    durationMs = s.optLong("duration", 0L)
                                )
                            }
                        }
                    }.getOrNull() ?: emptyList()
                }
            }.awaitAll().flatten()
        }
        val seenTitle = HashSet<String>()
        val out = ArrayList<OnlineSong>()
        for (s in results) {
            val key = "${s.title}|${s.artist}".lowercase()
            if (key in listenedKeys) continue          // 已听过的歌不再推荐
            if (!seenTitle.add(s.title.lowercase())) continue  // 同名只留一首
            out.add(s)
            if (out.size >= limit) break
        }
        out
    }

    /**
     * 随机歌单拉歌（电台「随机歌单」来源）：随机关键词搜索网易云歌单 → 拉取歌曲
     * 关键词覆盖常见曲风/场景，每次随机 → 歌单多样
     */
    suspend fun fetchRandomSongs(limit: Int = 10): List<OnlineSong> = withContext(Dispatchers.IO) {
        val keywords = listOf(
            "华语经典", "民谣", "轻音乐", "摇滚", "说唱", "欧美", "纯音乐", "经典老歌", "粤语", "国风",
            "独立音乐", "爵士", "电子", "后摇", "古风", "怀旧", "清新", "治愈", "咖啡", "旅行",
            "夜店", "健身", "学习", "开车", "清晨", "深夜",
            "钢琴", "小提琴", "吉他", "萨克斯", "口琴", "手风琴",
            "老歌", "怀旧金曲", "红歌", "影视金曲", "动画", "游戏",
            "雨声", "海浪", "森林", "星空"
        )
        val results = mutableListOf<OnlineSong>()
        val kws = keywords.shuffled().take(3)   // 随机 3 个关键词轮询
        for (kw in kws) {
            if (results.size >= limit) break
            val playlistIds = try {
                val url = URL("https://music.163.com/api/search/get?s=${URLEncoder.encode(kw, "UTF-8")}&type=1000&limit=1")
                val json = httpGet(url)
                val playlists = json?.optJSONObject("result")?.optJSONArray("playlists")
                (0 until (playlists?.length() ?: 0)).mapNotNull { i ->
                    playlists?.optJSONObject(i)?.optLong("id", 0L)?.takeIf { it > 0 }
                }
            } catch (e: Exception) { emptyList() }
            val pid = playlistIds.firstOrNull() ?: continue
            try {
                val url = URL("https://music.163.com/api/playlist/detail?id=$pid&updateTime=-1")
                val json = httpGet(url)
                val tracks = json?.optJSONObject("result")?.optJSONArray("tracks")
                if (tracks != null) {
                    for (i in 0 until tracks.length()) {
                        if (results.size >= limit) break
                        val s = tracks.optJSONObject(i) ?: continue
                        val id = s.optLong("id", 0L)
                        if (id <= 0L) continue
                        val artistsArr = s.optJSONArray("artists")
                        val artist = artistsArr
                            ?.takeIf { it.length() > 0 }
                            ?.let { it.optJSONObject(0)?.optString("name", "") } ?: ""
                        results.add(
                            OnlineSong(
                                platform = "网易云",
                                id = id.toString(),
                                title = s.optString("name", ""),
                                artist = artist,
                                album = s.optJSONObject("album")?.optString("name", "") ?: "",
                                artUrl = s.optJSONObject("album")?.optString("picUrl", null)?.takeIf { it.isNotBlank() },
                                durationMs = s.optLong("duration", 0L)
                            )
                        )
                    }
                }
            } catch (e: Exception) { }
        }
        results.distinctBy { "${it.title}|${it.artist}".lowercase() }.take(limit)
    }

    /**
     * 平台冷门歌曲（智能歌单「冷门探索」数据源）
     * 搜索网易云"冷门/小众"主题歌单 → 拉取歌单歌曲，合并去重
     */
    suspend fun fetchColdSongs(limit: Int = 50): List<OnlineSong> = withContext(Dispatchers.IO) {
        val results = mutableListOf<OnlineSong>()
        // 大关键词池：每次随机选 6 个 + 随机翻页 → 每次拉到不同的冷门歌单，歌单不重复
        val keywords = listOf(
            "冷门宝藏歌曲", "小众好听歌曲", "冷门神曲", "冷门好歌", "宝藏歌曲",
            "私藏歌单", "小众音乐", "遗珠", "被遗忘的好歌", "冷门经典",
            "民谣", "独立音乐", "后摇", "纯音乐", "轻音乐",
            "国风", "古风", "粤语", "欧美小众", "爵士",
            "电子", "说唱", "摇滚", "现场", "翻唱", "清唱",
            "怀旧金曲", "老歌", "经典", "粤语老歌", "闽南语",
            "钢琴曲", "小提琴", "吉他", "萨克斯", "口琴",
            "雨天", "夜晚", "孤独", "温柔", "安静"
        )
        for (kw in keywords.shuffled().take(6)) {
            if (results.size >= limit) break
            val playlistIds = try {
                val page = (0..3).random()   // 随机翻页，避免每次都取前几个歌单
                val url = URL("https://music.163.com/api/search/get?s=${URLEncoder.encode(kw, "UTF-8")}&type=1000&limit=3&offset=$page")
                val json = httpGet(url)
                val playlists = json?.optJSONObject("result")?.optJSONArray("playlists")
                (0 until (playlists?.length() ?: 0)).mapNotNull { i ->
                    playlists?.optJSONObject(i)?.optLong("id", 0L)?.takeIf { it > 0 }
                }
            } catch (e: Exception) {
                emptyList()
            }
            for (pid in playlistIds) {
                try {
                    val url = URL("https://music.163.com/api/playlist/detail?id=$pid&updateTime=-1")
                    val json = httpGet(url)
                    val tracks = json?.optJSONObject("result")?.optJSONArray("tracks")
                    if (tracks != null) {
                        for (i in 0 until tracks.length()) {
                            if (results.size >= limit) break
                            val s = tracks.optJSONObject(i) ?: continue
                            val id = s.optLong("id", 0L)
                            if (id <= 0L) continue
                            val artistsArr = s.optJSONArray("artists")
                            val artist = artistsArr
                                ?.takeIf { it.length() > 0 }
                                ?.let { it.optJSONObject(0)?.optString("name", "") } ?: ""
                            results.add(
                                OnlineSong(
                                    platform = "网易云",
                                    id = id.toString(),
                                    title = s.optString("name", ""),
                                    artist = artist,
                                    album = s.optJSONObject("album")?.optString("name", "") ?: "",
                                    artUrl = s.optJSONObject("album")?.optString("picUrl", null)?.takeIf { it.isNotBlank() },
                                    durationMs = s.optLong("duration", 0L)
                                )
                            )
                        }
                    }
                } catch (e: Exception) { }
            }
        }
        val dedup = LinkedHashMap<String, OnlineSong>()
        for (s in results) {
            val k = "${s.title}|${s.artist}".lowercase()
            if (!dedup.containsKey(k)) dedup[k] = s
        }
        dedup.values.toList().take(limit)
    }

    /**
     * 预检音源 URL 是否可播放（GET + Range: bytes=0-0，短超时）
     * 200/206 = 可用；用于电台/歌单入队前过滤坏音源，避免播放时缓冲卡死
     */
    fun isUrlPlayable(url: String): Boolean {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Range", "bytes=0-0")
            conn.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14; Pixel) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
            )
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            val code = conn.responseCode
            conn.disconnect()
            code == 200 || code == 206
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 流式嗅探：与播放器相同 UA/Referer 发 GET，只读前 2KB 验证是真实音频流。
     * 比 Range 探测可靠（CDN 防盗链拦探测时，播放也会同样失败 → 探测结果≈真实播放）。
     * 返回 true = 该音源能跑得动。
     */
    fun isStreamPlayable(url: String): Boolean {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14; Pixel) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
                )
                // 与平台匹配的 Referer（播放时也带，保持一致）
                setRequestProperty("Referer", platformReferer(url))
                connectTimeout = 6000
                readTimeout = 6000
            }
            if (conn.responseCode !in 200..299) {
                conn.disconnect()
                return false
            }
            // 注意：不能用 readNBytes()（Java 9/Android 13+ API，minSdk 26 设备会 NoSuchMethodError）
            val buf = ByteArray(2048)
            val len = conn.inputStream.use { inp -> inp.read(buf) }
            conn.disconnect()
            if (len <= 0) return false
            looksLikeAudio(buf.copyOfRange(0, len))
        } catch (e: Exception) {
            false
        }
    }

    /** 按 URL 主机返回平台 Referer */
    private fun platformReferer(url: String): String = when {
        url.contains("163.cn") || url.contains("music.163") || url.contains("126.net") -> "https://music.163.com"
        url.contains("y.qq.com") || url.contains("qq.com") -> "https://y.qq.com"
        url.contains("kugou.com") || url.contains("kgimg.com") || url.contains("krc") -> "https://www.kugou.com"
        else -> "https://music.163.com"
    }

    /** 前 2KB 是否像音频流（MP3/FLAC/OGG/WAV/M4A 特征字节） */
    private fun looksLikeAudio(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        fun c(i: Int, ch: Char) = bytes[i] == ch.code.toByte()
        // ID3（MP3 带标签）
        if (c(0, 'I') && c(1, 'D') && c(2, '3')) return true
        // MP3/ADTS 帧同步 0xFF Ex
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        if (b0 == 0xFF && (b1 and 0xE0) == 0xE0) return true
        // fLaC / OggS / RIFF
        if (c(0, 'f') && c(1, 'L') && c(2, 'a') && c(3, 'C')) return true
        if (c(0, 'O') && c(1, 'g') && c(2, 'g') && c(3, 'S')) return true
        if (c(0, 'R') && c(1, 'I') && c(2, 'F') && c(3, 'F')) return true
        // M4A/MP4: ftyp（第 4 字节起）
        if (bytes.size >= 8 && c(4, 'f') && c(5, 't') && c(6, 'y') && c(7, 'p')) return true
        return false
    }

    /**
     * 解析并验证可播性（一次调用合并两件事）：
     * 解析出播放 URL → 流式嗅探确认能出音频流 → 返回 URL；否则 null。
     * 实测：网易云 CDN 在 UA/Referer 下稳定返回 200+ID3，探测可靠。
     */
    suspend fun resolveAndVerify(song: OnlineSong): String? {
        val url = resolveOnlineUrl(song) ?: return null
        return if (isStreamPlayable(url)) url else null
    }

    /**
     * 解析单曲的可用播放 URL（按平台单请求，供电台/试听快速取流）
     * 优先 高品（网易云 320k / QQ M800 / 酷狗 320k），失败降级标准
     * 本平台解析失败时跨平台兜底：按「歌名 + 歌手」去酷狗搜索取流
     */
    suspend fun resolveOnlineUrl(song: OnlineSong): String? = withContext(Dispatchers.IO) {
        val direct = when (song.platform) {
            "网易云" -> {
                val id = song.id.toLongOrNull() ?: 0L
                if (id <= 0L) null
                else neteaseAudioSource(id, 320000, "高品", "MP3", 1L)?.url
                    ?: neteaseAudioSource(id, 128000, "标准", "MP3", 1L)?.url
            }
            "QQ音乐" -> {
                qqAudioSource(song.id, "M800", "高品", "MP3", 1L)?.url
                    ?: qqAudioSource(song.id, "M500", "标准", "MP3", 1L)?.url
            }
            "酷狗" -> {
                val info = parseKugouExtra(song)
                val hq = info?.hqHash ?: ""
                val norm = info?.hash ?: song.id
                if (hq.isNotBlank()) kugouAudioSource(hq, "高品", "MP3", 320000, 0L, 1L)?.url
                    ?: kugouAudioSource(norm, "标准", "MP3", 128000, 0L, 1L)?.url
                else kugouAudioSource(norm, "标准", "MP3", 128000, 0L, 1L)?.url
            }
            else -> null
        }
        // 本平台解析失败（VIP 限制 / 未登录 / 版权下架）→ 酷狗兜底
        direct ?: kugouFallbackUrl(song.title, song.artist)
    }
}
