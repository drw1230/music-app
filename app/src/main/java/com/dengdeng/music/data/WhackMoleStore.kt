package com.dengdeng.music.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import org.json.JSONObject

/**
 * 打地鼠战绩存储（复用 PlayerProfile 的 player_prefs 单实例 store，禁止重复声明）
 *
 * 口径（用户 2026-09-13 拍板）：
 * - 听歌模式：只存本机，不接联网榜（各歌节奏不可比）
 *   · 按曲目维度：best（该曲单次最高分）/ maxCombo / plays
 *   · 全局：totalScore（所有局累加总分）
 * - 标准模式：上线后接联网排行榜（LeaderboardStore），本地只记各关最高分
 * - latencyMs：延迟校准偏移（无感校准不可靠 → 放游戏"!"说明里手动微调）
 */
object WhackMoleStore {
    const val GAME_LISTEN = "whack_mole_listen"
    const val GAME_STANDARD = "whack_mole_standard"

    data class SongRecord(
        val songKey: String,
        val title: String,
        val artist: String,
        val best: Long,
        val maxCombo: Int,
        val plays: Int,
        val lastPlayedMs: Long = 0L
    )

    private val KEY_SONGS = stringPreferencesKey("wm_listen_songs")   // { songKey: {title, artist, best, maxCombo, plays} }
    private val KEY_STD_BEST = longPreferencesKey("wm_std_best")      // 标准模式（预留）
    private val KEY_TOTAL = longPreferencesKey("wm_total_score")
    private val KEY_LATENCY = longPreferencesKey("wm_latency_ms")
    private val KEY_SND = intPreferencesKey("wm_snd_style")           // 按键音效风格 id
    private val KEY_HAPTIC = booleanPreferencesKey("wm_haptics_on")   // 触觉震动开关
    private val KEY_SND_RECENT = stringPreferencesKey("wm_snd_recent") // 最近用过的手感 id（逗号分隔，新的在前）

    /** 常用位数量（菜单里直显的手感个数） */
    const val SND_RECENT_MAX = 3

    private const val SND_RECENT_DEFAULT = "0,1,2"

    fun songKeyOf(song: Song) = "${song.id}_${song.durationMs}"

    /**
     * 结算：累加总分 + 更新该曲 best/maxCombo/plays
     * @return (该曲最高分, 是否刷新纪录)
     */
    suspend fun submit(ctx: Context, song: Song, score: Long, maxCombo: Int): Pair<Long, Boolean> {
        var best = score
        var isNew = true
        ctx.playerDataStore.edit { p ->
            p[KEY_TOTAL] = (p[KEY_TOTAL] ?: 0L) + score
            val obj = JSONObject(p[KEY_SONGS].orEmpty().ifBlank { "{}" })
            val key = songKeyOf(song)
            val rec = obj.optJSONObject(key)
            if (rec != null) {
                best = rec.optLong("best")
                isNew = score > best
                if (isNew) best = score
                rec.put("best", best)
                rec.put("maxCombo", maxOf(rec.optInt("maxCombo"), maxCombo))
                rec.put("plays", rec.optInt("plays") + 1)
                rec.put("lastPlayed", System.currentTimeMillis())
            } else {
                best = score
                isNew = true
                obj.put(
                    key, JSONObject()
                        .put("title", song.title)
                        .put("artist", song.artist)
                        .put("best", score)
                        .put("maxCombo", maxCombo)
                        .put("plays", 1)
                        .put("lastPlayed", System.currentTimeMillis())
                )
            }
            p[KEY_SONGS] = obj.toString()
        }
        return best to isNew
    }

    suspend fun songRecords(ctx: Context): List<SongRecord> = runCatching {
        val obj = JSONObject(ctx.playerDataStore.data.first()[KEY_SONGS].orEmpty().ifBlank { "{}" })
        obj.keys().asSequence().map { k ->
            val o = obj.getJSONObject(k)
            SongRecord(
                songKey = k,
                title = o.optString("title"),
                artist = o.optString("artist"),
                best = o.optLong("best"),
                maxCombo = o.optInt("maxCombo"),
                plays = o.optInt("plays"),
                lastPlayedMs = o.optLong("lastPlayed")
            )
        }.sortedByDescending { it.best }.toList()
    }.getOrDefault(emptyList())

    /** 选歌页置顶排序用：songKey → 最后游玩时间（只含玩过的） */
    suspend fun lastPlayedMap(ctx: Context): Map<String, Long> = runCatching {
        songRecords(ctx).filter { it.lastPlayedMs > 0 }.associate { it.songKey to it.lastPlayedMs }
    }.getOrDefault(emptyMap())

    suspend fun totalScore(ctx: Context): Long = ctx.playerDataStore.data.first()[KEY_TOTAL] ?: 0L

    suspend fun latencyMs(ctx: Context): Long = ctx.playerDataStore.data.first()[KEY_LATENCY] ?: 120L

    suspend fun setLatencyMs(ctx: Context, v: Long) {
        ctx.playerDataStore.edit { it[KEY_LATENCY] = v }
    }

    private val KEY_BG = intPreferencesKey("wm_bg_kind")   // -1=按时段自动，其余为 MoleArt.BG_*

    /** 场景背景选择（-1 = 按当前时段自动，见 WhackMoleScreen.pickBgByTime） */
    suspend fun bgKind(ctx: Context): Int = ctx.playerDataStore.data.first()[KEY_BG] ?: -1

    suspend fun setBgKind(ctx: Context, v: Int) {
        ctx.playerDataStore.edit { it[KEY_BG] = v }
    }

    /** 按键音效风格（0=像素方波，见 WhackMoleScreen.MOLE_SOUND_STYLES） */
    suspend fun sndStyle(ctx: Context): Int = ctx.playerDataStore.data.first()[KEY_SND] ?: 0

    suspend fun setSndStyle(ctx: Context, v: Int) {
        ctx.playerDataStore.edit { it[KEY_SND] = v }
    }

    /**
     * 最近用过的手感 id（新的在前，最多 [SND_RECENT_MAX] 个）——菜单里就直显这几个。
     * 不足时用默认 0,1,2 补齐，保证常用位始终占满。
     */
    suspend fun sndRecent(ctx: Context): List<Int> = runCatching {
        val raw = ctx.playerDataStore.data.first()[KEY_SND_RECENT].orEmpty().ifBlank { SND_RECENT_DEFAULT }
        val ids = raw.split(',').mapNotNull { it.trim().toIntOrNull() }
            .filter { it >= 0 }.distinct().take(SND_RECENT_MAX)
        if (ids.size >= SND_RECENT_MAX) ids
        else (ids + listOf(0, 1, 2)).distinct().take(SND_RECENT_MAX)
    }.getOrDefault(listOf(0, 1, 2))

    /**
     * 把 [id] 置为最近使用：移到最前，最久没选的被挤出常用位。
     * 用户 2026-09-13 要求「三个都能被顶掉」→ 三个常用位都参与轮换。
     */
    suspend fun touchSndRecent(ctx: Context, id: Int): List<Int> {
        val next = (listOf(id) + sndRecent(ctx).filter { it != id }).take(SND_RECENT_MAX)
        ctx.playerDataStore.edit { it[KEY_SND_RECENT] = next.joinToString(",") }
        return next
    }

    /** 触觉震动开关（默认开） */
    suspend fun hapticsOn(ctx: Context): Boolean = ctx.playerDataStore.data.first()[KEY_HAPTIC] ?: true

    suspend fun setHapticsOn(ctx: Context, v: Boolean) {
        ctx.playerDataStore.edit { it[KEY_HAPTIC] = v }
    }
}
