package com.dengdeng.music.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * 歌曲元数据自动匹配：
 * 1. 解析歌名中杂乱的「歌手 - 歌名」格式（文件名常见的混排）
 * 2. 无歌手名的歌曲联网按歌名匹配补全歌手
 * 3. 已修正的结果持久化（UserLibraryStore）——匹配上的不再重复处理
 */
object MetadataEnhancer {

    private val SPLIT_REGEX = Regex("^\\s*(.+?)\\s*[-–—|/]\\s*(.+?)\\s*$")
    private const val UNKNOWN = "未知艺术家"

    // 内存缓存：songId → 是否已处理（避免并发重复）
    private val processedCache = HashSet<Long>()

    /**
     * 批量增强曲库歌曲，返回修正后的歌曲列表（未修改的保持原对象）
     * 已持久化覆盖的直接应用；新歌才走解析/联网流程
     */
    suspend fun enhanceAll(context: Context, songs: List<Song>): List<Song> = withContext(Dispatchers.IO) {
        if (songs.isEmpty()) return@withContext songs

        val overrides = UserLibraryStore.metadataOverridesFlow(context).first()
        var changed = false
        val result = songs.map { song ->
            // 1. 已有持久化覆盖 → 直接应用（匹配过的不动）
            val saved = overrides[song.id]
            if (saved != null) {
                if (saved.first != song.title || saved.second != song.artist) {
                    changed = true
                    song.copy(title = saved.first, artist = saved.second)
                } else {
                    song
                }
            } else {
                val enhanced = enhanceOne(context, song)
                if (enhanced != null) {
                    changed = true
                    enhanced
                } else {
                    song
                }
            }
        }
        return@withContext if (changed) result else songs
    }

    /** 单首歌增强：需要修正返回新 Song，否则返回 null（供"刷新歌词/歌手"触发） */
    suspend fun enhanceOne(context: Context, song: Song): Song? {
        if (song.id in processedCache) return null

        val title = song.title.trim()
        val artist = song.artist.trim()

        // —— 情形 A：歌手未知 ——
        if (artist.isBlank() || artist == UNKNOWN) {
            // A1: 歌名里含「歌手 - 歌名」→ 拆分
            val split = SPLIT_REGEX.find(title)
            if (split != null) {
                val newArtist = split.groupValues[1].trim()
                val newTitle = split.groupValues[2].trim()
                if (newArtist.isNotBlank() && newTitle.isNotBlank()) {
                    val fixed = song.copy(title = newTitle, artist = newArtist)
                    persist(context, song.id, newTitle, newArtist)
                    return fixed
                }
            }
            // A2: 无分隔符 → 联网按歌名搜歌手
            val online = try {
                OnlineMetadataFetcher.searchSong(title, "", song.durationMs)?.artist
            } catch (e: Exception) {
                null
            }
            if (!online.isNullOrBlank() && online != UNKNOWN) {
                val fixed = song.copy(artist = online)
                persist(context, song.id, title, online)
                return fixed
            }
            // 都失败：标记已处理避免重复联网
            processedCache.add(song.id)
            return null
        }

        // —— 情形 B：歌手已知但歌名里带了「歌手-」前缀（清理 title） ——
        val prefix = "$artist -"
        if (title.startsWith(prefix, ignoreCase = true) && title.length > prefix.length) {
            val newTitle = title.substring(prefix.length).trim()
            if (newTitle.isNotBlank()) {
                val fixed = song.copy(title = newTitle)
                persist(context, song.id, newTitle, artist)
                return fixed
            }
        }
        // 分隔符前部分与歌手相同 → 也清理（如 "歌手-歌名" 无空格）
        val split = SPLIT_REGEX.find(title)
        if (split != null) {
            val head = split.groupValues[1].trim()
            val tail = split.groupValues[2].trim()
            if (head.equals(artist, ignoreCase = true) && tail.isNotBlank()) {
                val fixed = song.copy(title = tail)
                persist(context, song.id, tail, artist)
                return fixed
            }
        }

        processedCache.add(song.id)
        return null
    }

    /** 清理内存缓存（曲库刷新后可重新评估新歌） */
    fun clearCache() {
        processedCache.clear()
    }

    private suspend fun persist(context: Context, songId: Long, title: String, artist: String) {
        runCatching {
            UserLibraryStore.saveMetadataOverride(context, songId, title, artist)
        }
    }
}
