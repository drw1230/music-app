package com.dengdeng.music.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

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
        val platform: String,      // 来源平台："网易云" / "QQ音乐"
        val id: String,            // 网易云数字 id 或 QQ songmid
        val title: String,
        val artist: String,
        val album: String,
        val artUrl: String?,
        val durationMs: Long
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

    private fun cacheKey(title: String, artist: String) = "$title|$artist".trim().lowercase()

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
    private fun httpGet(url: URL): JSONObject? {
        return try {
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                setRequestProperty("Accept", "application/json")
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
                connectTimeout = 8000
                readTimeout = 8000
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
     * 查询单曲的所有可用音源（多平台 × 多品质）
     * - 网易云：128k 标准 / 320k 高品 / 无损（VIP 返回 null url）
     * - QQ 音乐：M500 mp3 128k / M800 mp3 320k / F000 flac 无损（VIP 返回空 purl）
     */
    suspend fun fetchAudioSources(song: OnlineSong): List<AudioSource> = withContext(Dispatchers.IO) {
        val sources = mutableListOf<AudioSource>()
        val durSec = (song.durationMs / 1000L).coerceAtLeast(1L)

        when (song.platform) {
            "网易云" -> {
                val id = song.id.toLongOrNull() ?: 0L
                if (id > 0L) {
                    // 三个码率档：128k / 320k / 无损
                    val levels = listOf(
                        Triple(128000, "标准", "MP3"),
                        Triple(320000, "高品", "MP3"),
                        Triple(999000, "无损", "FLAC")
                    )
                    for ((br, quality, format) in levels) {
                        val item = neteaseAudioSource(id, br, quality, format, durSec)
                        if (item != null) sources.add(item)
                    }
                }
            }
            "QQ音乐" -> {
                val mid = song.id
                if (mid.isNotBlank()) {
                    // M500=128k mp3 / M800=320k mp3 / F000=flac
                    val levels = listOf(
                        Triple("M500", "标准", "MP3"),
                        Triple("M800", "高品", "MP3"),
                        Triple("F000", "无损", "FLAC")
                    )
                    for ((prefix, quality, format) in levels) {
                        val item = qqAudioSource(mid, prefix, quality, format, durSec)
                        if (item != null) sources.add(item)
                    }
                }
            }
        }
        return@withContext sources
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
}
