package com.dengdeng.music.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    // 内存缓存：搜索 key（title|artist）→ 匹配结果，避免每次切歌都重复请求
    private val matchCache = mutableMapOf<String, SongMatch>()
    // 封面缓存：key（title|artist）→ 封面 URL
    private val artworkCache = mutableMapOf<String, String>()

    private fun cacheKey(title: String, artist: String) = "$title|$artist".trim().lowercase()

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
}
