package com.dengdeng.music.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * LRC 歌词解析器
 *
 * 支持标准 LRC 格式：
 *   [mm:ss.xx]歌词文本
 *   [mm:ss.xx][mm:ss.yy]重复行（每行都显示）
 *   [ti:标题] [ar:艺术家] [al:专辑] [by:上传者] [offset:+/-毫秒]
 */
object LyricParser {

    /** 单行歌词 */
    data class LyricLine(
        val timeMs: Long,   // 该行开始时间（毫秒，已应用 offset）
        val text: String    // 歌词文本
    )

    /** 解析 LRC 文本，返回按时间排序的歌词行 */
    fun parse(lrcText: String, offsetMs: Long = 0L): List<LyricLine> {
        if (lrcText.isBlank()) return emptyList()

        val lines = mutableListOf<LyricLine>()
        var offset = offsetMs

        // 先解析元数据（offset 标签）
        val offsetRegex = Regex("""\[offset:([+-]?\d+)\]""")
        offsetRegex.find(lrcText)?.let { match ->
            offset = match.groupValues[1].toLongOrNull() ?: 0L
        }

        // 时间戳正则：[mm:ss] 或 [mm:ss.xx] 或 [mm:ss:xx]
        val timeRegex = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

        lrcText.lines().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach

            // 找出所有时间戳
            val times = timeRegex.findAll(line).map { match ->
                val min = match.groupValues[1].toLongOrNull() ?: 0L
                val sec = match.groupValues[2].toLongOrNull() ?: 0L
                val frac = match.groupValues[3].ifEmpty { "0" }
                val ms = min * 60_000 + sec * 1000 + frac.toFractionMs()
                ms + offset
            }.toList()

            if (times.isEmpty()) return@forEach // 纯元数据行或无时间戳

            // 去掉所有时间戳后剩歌词文本
            val text = timeRegex.replace(line, "").trim()
            if (text.isEmpty()) return@forEach

            times.forEach { t ->
                lines.add(LyricLine(timeMs = t, text = text))
            }
        }

        return lines.sortedBy { it.timeMs }
    }

    /** 分数秒（1-3 位）转毫秒 */
    private fun String.toFractionMs(): Long {
        return when (length) {
            1 -> toLongOrNull()?.times(100) ?: 0L       // .1 = 100ms
            2 -> toLongOrNull()?.times(10) ?: 0L        // .12 = 120ms
            3 -> toLongOrNull() ?: 0L                   // .123 = 123ms
            else -> 0L
        }
    }

    /**
     * 从歌曲读取歌词
     * 策略：① 本地同目录同名 .lrc → ② 联网获取（网易云，按歌名+艺术家搜索）并缓存到 cacheDir
     * @param artist 艺术家（联网搜索用）
     */
    suspend fun loadLyrics(context: Context, songUri: Uri, title: String, artist: String = ""): List<LyricLine> =
        withContext(Dispatchers.IO) {
            try {
                // 方法1：本地同路径同名 .lrc
                val lrcPath = findLrcViaMediaStore(context, songUri, title)
                if (lrcPath != null) {
                    val text = java.io.File(lrcPath).readText(Charsets.UTF_8)
                    return@withContext parse(text)
                }

                // 方法2：联网获取（网易云搜索 + 歌词），成功则缓存到 cacheDir
                val cacheFile = lyricCacheFile(context, title, artist)
                val cachedText = if (cacheFile.exists()) cacheFile.readText(Charsets.UTF_8) else null
                if (!cachedText.isNullOrBlank()) return@withContext parse(cachedText)

                val match = OnlineMetadataFetcher.searchSong(title, artist)
                    ?: return@withContext emptyList()
                val lyricText = OnlineMetadataFetcher.fetchLyric(match.songId)
                    ?: return@withContext emptyList()
                if (lyricText.isNotBlank()) {
                    runCatching {
                        cacheFile.parentFile?.mkdirs()
                        cacheFile.writeText(lyricText, Charsets.UTF_8)
                    }
                    return@withContext parse(lyricText)
                }
                emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        }

    /** 歌词磁盘缓存文件（cacheDir/lyrics/歌名-艺术家.lrc） */
    private fun lyricCacheFile(context: Context, title: String, artist: String): java.io.File {
        val safeName = "$title-$artist".replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80)
        return java.io.File(context.cacheDir, "lyrics/$safeName.lrc")
    }

    /**
     * 通过 MediaStore 查找同名 .lrc 文件
     * 歌曲 Uri → 取所在目录（用 DATA 列，若可读）→ 找 "同名.lrc"
     */
    private fun findLrcViaMediaStore(context: Context, songUri: Uri, title: String): String? {
        // 先尝试获取歌曲文件路径
        val songPath = try {
            context.contentResolver.query(
                songUri,
                arrayOf(android.provider.MediaStore.Audio.Media.DATA),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }

        if (songPath != null) {
            val file = java.io.File(songPath)
            val dir = file.parentFile
            val baseName = file.nameWithoutExtension
            if (dir != null) {
                // 直接找同名 .lrc
                val sameName = java.io.File(dir, "$baseName.lrc")
                if (sameName.exists()) return sameName.absolutePath

                // 再尝试歌名.lrc
                val byTitle = java.io.File(dir, "$title.lrc")
                if (byTitle.exists()) return byTitle.absolutePath

                // 最后：扫描目录下所有 .lrc，看谁的内容含歌名（兜底）
                dir.listFiles { f -> f.extension.equals("lrc", true) }
                    ?.firstOrNull { f ->
                        try {
                            val head = f.readText(Charsets.UTF_8).take(2048)
                            head.contains(title, ignoreCase = true)
                        } catch (e: Exception) {
                            false
                        }
                    }
                    ?.let { return it.absolutePath }
            }
        }
        return null
    }
}
