package com.dengdeng.music.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 每日电台生成器 —— UI（每日电台界面）与桌面小组件共用的唯一入口
 *
 * 目标：小组件的"电台键"播放的内容与 App 内"每日电台（探索版）"**完全同源**：
 * 多榜单混合 + 常听歌手相似 + 随机歌单，并发解析 URL 只留可播，并记住本次推荐
 * （下次生成过滤 → 大半新歌）。
 *
 * 抽成独立 object 的原因：电台歌单生成原先写死在 OnlineRadioScreen/MusicViewModel 里，
 * 离开 UI 就无法运行；小组件需要在 App 未打开时后台生成。
 */
object DailyRadioFetcher {

    /** 探索版候选榜单组合（网易云歌单 id, QQ topid）：每次随机抽 3 组混合 */
    val hotVariants = listOf(
        3778678L to 4,       // 云音乐热歌榜
        19723756L to 52,     // 云音乐飙升榜
        3779629L to 5,       // 云音乐新歌榜
        2884035L to 26,      // 云音乐原创榜
        3777929L to 0,       // 抖音热歌榜
        71385702L to 0,      // 云音乐古风榜
        991319582L to 0,     // 云音乐说唱榜
        745956260L to 0      // 欧美金曲榜
    )
    val hotVariantNames = listOf("热歌榜", "飙升榜", "新歌榜", "原创榜", "抖音热歌", "古风榜", "说唱榜", "欧美金曲")

    /**
     * 第一步：生成电台候选（OnlineSong，未解析 URL）
     * 探索版混合方案：A 常听歌手相似 + B 随机 3 榜单 + C 随机歌单，按歌名|歌手去重
     */
    suspend fun fetchCandidates(
        topArtists: List<String>,
        listenedKeys: Set<String>
    ): List<OnlineMetadataFetcher.OnlineSong> = withContext(Dispatchers.IO) {
        val familiar = runCatching {
            OnlineMetadataFetcher.fetchFamiliarSongs(topArtists, listenedKeys, perArtist = 5, limit = 6)
        }.getOrDefault(emptyList())
        val bList = runCatching {
            val picks = hotVariants.shuffled().take(3)
            picks.flatMap { OnlineMetadataFetcher.fetchHotSongs(7, it.first, it.second) }
        }.getOrDefault(emptyList())
        val dList = runCatching {
            OnlineMetadataFetcher.fetchRandomSongs(8)
        }.getOrDefault(emptyList())
        (familiar + bList + dList).distinctBy { "${it.title}|${it.artist}".lowercase() }
    }

    /**
     * 第二步：并发解析 URL（8 并发，只留解析成功的）→ 转成可入队的 Song（负 id 在线歌）
     * 并记住本次推荐 key（下次生成时由 fetchers 过滤 → 大半新歌）
     */
    suspend fun resolveToQueue(
        context: Context,
        candidates: List<OnlineMetadataFetcher.OnlineSong>,
        rememberRecommended: Boolean = true
    ): List<Song> = coroutineScope {
        val gate = Semaphore(8)
        val resolved = candidates.map { s -> async {
            val url = runCatching {
                gate.withPermit { OnlineMetadataFetcher.resolveOnlineUrl(s) }
            }.getOrNull()
            if (url != null) Song(
                id = -1L,
                title = s.title,
                artist = s.artist,
                album = "每日电台",
                durationMs = s.durationMs,
                uri = android.net.Uri.parse(url),
                albumArtUri = s.artUrl?.let { android.net.Uri.parse(it) }
            ) else null
        } }.awaitAll().filterNotNull()

        if (rememberRecommended && resolved.isNotEmpty()) {
            runCatching {
                UserLibraryStore.saveRecentRadioKeys(
                    context,
                    resolved.map { "${it.title}|${it.artist}".lowercase() }.toSet()
                )
            }
        }
        resolved
    }

    // ===== 供小组件使用的无 UI 数据准备 =====

    /** 常听歌手 Top N（来自播放历史 + 本地曲库），与 MusicViewModel.topArtists 同逻辑 */
    fun topArtistsFromHistory(context: Context, songs: List<Song>, limit: Int = 3): List<String> {
        val history = runCatching {
            kotlinx.coroutines.runBlocking { UserLibraryStore.playHistoryFlow(context).first() }
        }.getOrDefault(emptyMap())
        if (history.isEmpty()) return emptyList()
        val counts = HashMap<String, Int>()
        for ((id, times) in history) {
            val artist = songs.find { it.id == id }?.artist ?: continue
            if (artist.isBlank() || artist == "<unknown>") continue
            counts[artist] = (counts[artist] ?: 0) + times
        }
        return counts.entries.sortedByDescending { it.value }.take(limit).map { it.key }
    }

    /** 已听 key 集合（本地曲库全部），与 MusicViewModel.listenedSongKeys 的本地部分同逻辑 */
    fun listenedKeysFromSongs(songs: List<Song>): Set<String> =
        songs.map { "${it.title}|${it.artist}".lowercase() }.toSet()
}
