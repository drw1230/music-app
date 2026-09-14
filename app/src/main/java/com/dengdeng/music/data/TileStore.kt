package com.dengdeng.music.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.coroutines.flow.first

/**
 * 别踩白块战绩存储（复用 PlayerProfile 的 player_prefs 单实例 store，禁止重复声明）
 *
 * 口径（用户 2026-09-14 拍板）：
 * - 分模式各记一套，量纲不同不混算：
 *   · 经典：最短用时（ms，越小越好，0 = 未通关过）
 *   · 街机：最高分
 *   · 接力：最长连续通过段数（每段 50 块零失误 = 接上）
 * - 记录只存本机（联网分模式榜 = Batch 2）
 * - 下落速度档：0 慢 / 1 中 / 2 快（影响方块可见时间，不改判定容差）
 */
object TileStore {

    private val KEY_CLASSIC_MS = longPreferencesKey("tl_classic_ms")
    private val KEY_ARCADE_BEST = longPreferencesKey("tl_arcade_best")
    private val KEY_RELAY_STREAK = intPreferencesKey("tl_relay_streak")
    private val KEY_SPEED = intPreferencesKey("tl_speed")
    // 统计（跨模式累计，仅供参考不参与排名）
    private val KEY_TOTAL_TILES = longPreferencesKey("tl_total_tiles")

    /** 下落速度档：0 慢 / 1 中 / 2 快 */
    suspend fun speed(ctx: Context): Int = ctx.playerDataStore.data.first()[KEY_SPEED] ?: 1

    suspend fun setSpeed(ctx: Context, v: Int) {
        ctx.playerDataStore.edit { it[KEY_SPEED] = v.coerceIn(0, 2) }
    }

    suspend fun classicBestMs(ctx: Context): Long = ctx.playerDataStore.data.first()[KEY_CLASSIC_MS] ?: 0L

    suspend fun arcadeBest(ctx: Context): Long = ctx.playerDataStore.data.first()[KEY_ARCADE_BEST] ?: 0L

    suspend fun relayBestStreak(ctx: Context): Int = ctx.playerDataStore.data.first()[KEY_RELAY_STREAK] ?: 0

    suspend fun totalTiles(ctx: Context): Long = ctx.playerDataStore.data.first()[KEY_TOTAL_TILES] ?: 0L

    /** 经典结算：用时更短即刷新纪录。@return (最佳用时, 是否新纪录) */
    suspend fun submitClassic(ctx: Context, usedMs: Long): Pair<Long, Boolean> {
        // 防御：无效用时（<=0，例如残局/未打满就失败）一律不写盘，
        // 否则会把历史最佳冲成 0（2026-09-14 真机验证踩到的坑）
        if (usedMs <= 0L) return classicBestMs(ctx) to false
        var best = usedMs
        var isNew = true
        ctx.playerDataStore.edit { p ->
            val old = p[KEY_CLASSIC_MS] ?: 0L
            if (old in 1 until usedMs) {
                best = old; isNew = false
            } else {
                p[KEY_CLASSIC_MS] = usedMs
            }
        }
        return best to isNew
    }

    /** 街机结算：分数更高即刷新纪录。@return (最高分, 是否新纪录) */
    suspend fun submitArcade(ctx: Context, score: Long): Pair<Long, Boolean> {
        if (score <= 0L) return arcadeBest(ctx) to false
        var best = score
        var isNew = true
        ctx.playerDataStore.edit { p ->
            val old = p[KEY_ARCADE_BEST] ?: 0L
            if (old >= score) {
                best = old; isNew = false
            } else {
                p[KEY_ARCADE_BEST] = score
            }
        }
        return best to isNew
    }

    /** 接力结算：连续段数更高即刷新纪录。@return (最长段数, 是否新纪录) */
    suspend fun submitRelay(ctx: Context, streak: Int): Pair<Int, Boolean> {
        if (streak <= 0) return relayBestStreak(ctx) to false
        var best = streak
        var isNew = true
        ctx.playerDataStore.edit { p ->
            val old = p[KEY_RELAY_STREAK] ?: 0
            if (old >= streak) {
                best = old; isNew = false
            } else {
                p[KEY_RELAY_STREAK] = streak
            }
        }
        return best to isNew
    }

    /** 累计打过的块数（本机统计口） */
    suspend fun addTiles(ctx: Context, n: Int) {
        if (n <= 0) return
        ctx.playerDataStore.edit { p ->
            p[KEY_TOTAL_TILES] = (p[KEY_TOTAL_TILES] ?: 0L) + n
        }
    }
}
