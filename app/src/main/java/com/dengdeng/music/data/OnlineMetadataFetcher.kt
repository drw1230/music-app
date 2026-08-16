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

    // 内存缓存：搜索 key（title|artist）→ 匹配结果，避免每次切歌都重复请求
    private val matchCache = mutableMapOf<String, SongMatch>()

    private fun cacheKey(title: String, artist: String) = "$title|$artist".trim().lowercase()

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
            val artistName = s.optJSONArray("artists")
                ?.takeIf { it.length() > 0 }
                ?.let { it.optJSONObject(0)?.optString("name", "") } ?: ""
            val artUrl = s.optJSONObject("album")?.optString("picUrl", null)?.takeIf { it.isNotBlank() }
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
            JSONObject(sb.toString())
        } catch (e: Exception) {
            null
        }
    }
}
