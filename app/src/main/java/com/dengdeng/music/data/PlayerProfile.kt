package com.dengdeng.music.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

/**
 * 玩家档案本地存储（DataStore 单实例：游戏入口 / 我的信息页共用，禁止在别处重复声明同名 store）
 *
 * 存什么（全部只存本机，不上传）：
 * - player_name  玩家名字（首次进游戏/我的信息时取，有则跳过）
 * - game_records 各游戏战绩 JSON：[{gameId, best(历史最高分), plays(游玩次数), lastPlayed(最后游玩时间戳)}]
 *
 * 联网只发生一件事：玩家在「我的信息」主动点「上传成绩」→ 仅上传 名字+游戏id+最高分+时间 四个字段
 * 到 GitHub 榜单文件（见 LeaderboardStore），其余任何数据不出设备。
 */
val Context.playerDataStore by preferencesDataStore(name = "player_prefs")

object PlayerProfile {

    /** 游戏 id 常量（游戏内容打磨中，先占位一个） */
    const val GAME_GUESS_SONG = "guess_song"
    val GAME_NAMES = mapOf(GAME_GUESS_SONG to "猜歌挑战")

    data class GameRecord(
        val gameId: String,
        val best: Long,
        val plays: Int,
        val lastPlayedMs: Long
    )

    private val KEY_NAME = stringPreferencesKey("player_name")
    private val KEY_RECORDS = stringPreferencesKey("game_records")

    suspend fun getName(ctx: Context): String =
        ctx.playerDataStore.data.first()[KEY_NAME].orEmpty().trim()

    suspend fun hasName(ctx: Context): Boolean = getName(ctx).isNotBlank()

    suspend fun saveName(ctx: Context, name: String) {
        ctx.playerDataStore.edit { it[KEY_NAME] = name.trim() }
    }

    suspend fun getRecords(ctx: Context): List<GameRecord> = runCatching {
        val arr = JSONArray(ctx.playerDataStore.data.first()[KEY_RECORDS].orEmpty().ifBlank { "[]" })
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            GameRecord(
                gameId = o.optString("gameId"),
                best = o.optLong("best"),
                plays = o.optInt("plays"),
                lastPlayedMs = o.optLong("lastPlayed")
            )
        }
    }.getOrDefault(emptyList())

    /** 提交一次成绩：只有超过历史最高分才刷新 best；plays/lastPlayed 每次都更新 */
    suspend fun submitScore(ctx: Context, gameId: String, score: Long) {
        ctx.playerDataStore.edit { prefs ->
            val arr = JSONArray(prefs[KEY_RECORDS].orEmpty().ifBlank { "[]" })
            var found = false
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.optString("gameId") == gameId) {
                    found = true
                    if (score > o.optLong("best")) o.put("best", score)
                    o.put("plays", o.optInt("plays") + 1)
                    o.put("lastPlayed", System.currentTimeMillis())
                }
            }
            if (!found) {
                arr.put(
                    JSONObject()
                        .put("gameId", gameId)
                        .put("best", score)
                        .put("plays", 1)
                        .put("lastPlayed", System.currentTimeMillis())
                )
            }
            prefs[KEY_RECORDS] = arr.toString()
        }
    }
}
