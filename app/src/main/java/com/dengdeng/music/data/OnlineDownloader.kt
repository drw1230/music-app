package com.dengdeng.music.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 在线音源下载（网易云 + QQ 音乐，多源搜索取可下载音频）
 * 自用功能：搜索歌曲 → 获取音频 URL → 下载到系统音乐库（Music/DDmusic/）
 */
object OnlineDownloader {

    data class Candidate(
        val source: String,     // "网易云" / "QQ音乐"
        val title: String,
        val artist: String,
        val audioUrl: String?   // 可下载的音频 URL（可能为 null = 该源无权限/无音源）
    )

    /** 搜索候选（网易云 + QQ，各取时长最匹配的一首），并尝试获取其音频 URL */
    suspend fun searchCandidates(title: String, artist: String, durationMs: Long? = null): List<Candidate> = withContext(Dispatchers.IO) {
        val out = mutableListOf<Candidate>()

        // 网易云
        runCatching {
            val match = OnlineMetadataFetcher.searchSong(title, artist, durationMs)
            if (match != null) {
                val url = neteaseAudioUrl(match.songId)
                out.add(Candidate("网易云", match.title, match.artist, url))
            }
        }

        // QQ 音乐
        runCatching {
            val qq = OnlineMetadataFetcher.searchSongQQ(title, artist, durationMs)
            if (qq != null) {
                val url = qqAudioUrl(qq.songmid)
                out.add(Candidate("QQ音乐", qq.title, qq.artist, url))
            }
        }

        out
    }

    /** 网易云音频 URL（enhance/player/url 接口） */
    private suspend fun neteaseAudioUrl(songId: Long): String? = withContext(Dispatchers.IO) {
        if (songId <= 0L) return@withContext null
        val url = URL("https://music.163.com/api/song/enhance/player/url?id=$songId&ids=[$songId]&br=320000")
        val json = getJson(url) ?: return@withContext null
        val arr = json.optJSONArray("data") ?: return@withContext null
        if (arr.length() == 0) return@withContext null
        arr.optJSONObject(0)?.optString("url", null)?.takeIf { it.isNotBlank() && it != "http://music.163.com/404" }
    }

    /** QQ 音乐音频 URL（musicu.fcg 接口，sip + filename/vkey 拼接） */
    private suspend fun qqAudioUrl(songmid: String): String? = withContext(Dispatchers.IO) {
        if (songmid.isBlank()) return@withContext null
        val data = JSONObject().put(
            "req_0", JSONObject().put("module", "vkey.GetVkeyServer")
                .put("method", "CgiGetVkey")
                .put("param", JSONObject()
                    .put("guid", "1234567890")
                    .put("songmid", JSONArrayOf(songmid))
                    .put("songtype", JSONArrayOf(0))
                    .put("uin", "0")
                    .put("loginflag", 1)
                    .put("platform", "20"))
        )
        val encoded = URLEncoder.encode(data.toString(), "UTF-8")
        val url = URL("https://u.y.qq.com/cgi-bin/musicu.fcg?format=json&data=$encoded")
        val json = getJson(url) ?: return@withContext null
        val res = json.optJSONObject("req_0")?.optJSONObject("data") ?: return@withContext null
        val sip = res.optJSONArray("sip")?.takeIf { it.length() > 0 }?.optString(0, "") ?: return@withContext null

        // 优先 testfile2g（试听，含完整 query）；其次 filename + vkey 拼接
        val tf = res.optString("testfile2g", "").takeIf { it.isNotBlank() }
        if (tf != null) return@withContext sip + tf

        val filename = res.optString("filename", "").takeIf { it.isNotBlank() }
        val vkey = res.optString("vkey", "").takeIf { it.isNotBlank() }
        if (filename != null && vkey != null) {
            return@withContext sip + "$filename?guid=1234567890&vkey=$vkey&uin=0&fromtag=8"
        }
        null
    }

    private fun JSONArrayOf(vararg values: Any): org.json.JSONArray {
        val arr = org.json.JSONArray()
        values.forEach { arr.put(it) }
        return arr
    }

    /** 下载音频到系统音乐库（Music/DDmusic/），成功后尽力保存歌词(.lrc)与封面图片；返回是否成功 */
    suspend fun downloadToMusicLibrary(
        context: Context,
        url: String,
        title: String,
        artist: String,
        format: String = "mp3",
        artUrl: String? = null
    ): Boolean =
        withContext(Dispatchers.IO) {
            if (url.isBlank()) return@withContext false
            try {
                val isFlac = format.equals("FLAC", true)
                val ext = if (isFlac) "flac" else "mp3"
                val mime = if (isFlac) "audio/flac" else "audio/mpeg"
                val displayName = "${sanitize(title)}-${sanitize(artist)}.$ext"
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Audio.Media.MIME_TYPE, mime)
                    put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/DDmusic")
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return@withContext false

                val success = try {
                    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 15000
                        readTimeout = 30000
                        setRequestProperty("User-Agent", "Mozilla/5.0")
                    }
                    if (conn.responseCode !in 200..299) {
                        conn.disconnect()
                        false
                    } else {
                        resolver.openOutputStream(uri)?.use { out ->
                            conn.inputStream.use { inp ->
                                inp.copyTo(out, 64 * 1024)
                            }
                        } != null
                    }
                } catch (e: Exception) {
                    false
                }

                if (success) {
                    values.clear()
                    values.put(MediaStore.Audio.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    // 尽力保存歌词 + 封面（失败不影响下载成功；下次扫描进曲库即有歌词与封面）
                    runCatching { saveLyricFile(context, title, artist) }
                    runCatching { artUrl?.let { saveCoverFile(context, title, artist, it) } }
                } else {
                    resolver.delete(uri, null, null)
                }
                success
            } catch (e: Exception) {
                false
            }
        }

    /** 保存歌词到 Music/DDmusic/<同名>.lrc（LyricParser 本地命中；联网取歌词失败则跳过） */
    private suspend fun saveLyricFile(context: Context, title: String, artist: String) {
        val lyric = OnlineMetadataFetcher.fetchLyricForSong(title, artist) ?: return
        if (lyric.isBlank()) return
        val safeTitle = sanitize(title)
        val safeArtist = sanitize(artist)
        val displayName = "$safeTitle-$safeArtist.lrc"
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Files.FileColumns.DISPLAY_NAME, displayName)
            put(MediaStore.Files.FileColumns.MIME_TYPE, "text/plain")
            put(MediaStore.Files.FileColumns.RELATIVE_PATH, "Music/DDmusic")
            put(MediaStore.Files.FileColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Files.getContentUri("external"), values) ?: return
        val ok = try {
            resolver.openOutputStream(uri)?.use { out ->
                out.write(lyric.toByteArray(Charsets.UTF_8))
            } != null
        } catch (e: Exception) {
            false
        }
        if (ok) {
            values.clear()
            values.put(MediaStore.Files.FileColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } else {
            resolver.delete(uri, null, null)
        }
    }

    /** 保存封面图片到应用私有目录（filesDir/covers/）+ 记录 CoverStore（URL 兜底） */
    private suspend fun saveCoverFile(context: Context, title: String, artist: String, artUrl: String) {
        val target = LocalCover.targetFile(context, title, artist)
        if (target.exists()) {
            // 已有本地封面，仅补记 CoverStore URL
            runCatching { CoverStore.save(context, title, artist, artUrl) }
            return
        }
        val ok = try {
            val conn = (URL(artUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 15000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
            }
            if (conn.responseCode != 200) {
                conn.disconnect()
                false
            } else {
                target.outputStream().use { out -> conn.inputStream.use { it.copyTo(out) } }.let { true }
            }
        } catch (e: Exception) {
            false
        }
        if (ok) {
            runCatching { CoverStore.save(context, title, artist, artUrl) }
        }
    }

    private fun sanitize(s: String): String =
        s.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(40)

    private fun getJson(url: URL): JSONObject? {
        return try {
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                setRequestProperty("Referer", "https://y.qq.com")
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode != 200) {
                conn.disconnect()
                return null
            }
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            conn.disconnect()
            JSONObject(text)
        } catch (e: Exception) {
            null
        }
    }
}
