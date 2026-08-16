package com.dengdeng.music.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

/**
 * 用户数据仓库 —— 收藏 + 歌单持久化
 * 用 DataStore 存 JSON，轻量可靠（歌曲详情仍来自 MediaStore，这里只存 ID）
 */
object UserLibraryStore {

    // 扩展属性：DataStore 实例
    private val Context.dataStore by preferencesDataStore(name = "user_library")

    private val KEY_FAVORITES = stringPreferencesKey("favorites_json")     // 收藏的歌曲 ID 集合
    private val KEY_PLAYLISTS = stringPreferencesKey("playlists_json")     // 歌单列表 JSON
    private val KEY_METADATA = stringPreferencesKey("metadata_overrides_json") // 元数据修正映射（songId → title|artist）
    private val KEY_SORT_MODE = intPreferencesKey("sort_mode")              // 排序方式记忆
    private val KEY_SEARCH_HISTORY = stringPreferencesKey("search_history_json") // 搜索历史
    private val KEY_PLAY_HISTORY = stringPreferencesKey("play_history_json") // 播放历史（songId → 次数）

    // ==================== 收藏 ====================

    /** 收藏的歌曲 ID 集合（Flow） */
    fun favoriteIdsFlow(context: Context): Flow<Set<Long>> =
        context.dataStore.data.map { prefs ->
            parseLongSet(prefs[KEY_FAVORITES] ?: "[]")
        }

    /** 切换收藏状态，返回是否已收藏 */
    suspend fun toggleFavorite(context: Context, songId: Long): Boolean {
        val current = favoriteIdsFlow(context).first()
        val newSet = if (songId in current) current - songId else current + songId
        context.dataStore.edit { prefs -> prefs[KEY_FAVORITES] = longSetToJson(newSet) }
        return songId in newSet
    }

    // ==================== 歌单 ====================

    /** 歌单列表（Flow） */
    fun playlistsFlow(context: Context): Flow<List<Playlist>> =
        context.dataStore.data.map { prefs ->
            parsePlaylists(prefs[KEY_PLAYLISTS] ?: "[]")
        }

    /** 创建歌单，返回新歌单 ID */
    suspend fun createPlaylist(context: Context, name: String): Long {
        val lists = playlistsFlow(context).first().toMutableList()
        val newId = (lists.maxOfOrNull { it.id } ?: 0) + 1
        lists.add(Playlist(id = newId, name = name, songIds = emptyList()))
        savePlaylists(context, lists)
        return newId
    }

    /** 删除歌单 */
    suspend fun deletePlaylist(context: Context, playlistId: Long) {
        val lists = playlistsFlow(context).first().filterNot { it.id == playlistId }
        savePlaylists(context, lists)
    }

    /** 往歌单添加歌曲 */
    suspend fun addSongToPlaylist(context: Context, playlistId: Long, songId: Long) {
        val lists = playlistsFlow(context).first().toMutableList()
        val idx = lists.indexOfFirst { it.id == playlistId }
        if (idx >= 0) {
            val p = lists[idx]
            if (songId !in p.songIds) {
                lists[idx] = p.copy(songIds = p.songIds + songId)
                savePlaylists(context, lists)
            }
        }
    }

    /** 从歌单移除歌曲 */
    suspend fun removeSongFromPlaylist(context: Context, playlistId: Long, songId: Long) {
        val lists = playlistsFlow(context).first().toMutableList()
        val idx = lists.indexOfFirst { it.id == playlistId }
        if (idx >= 0) {
            val p = lists[idx]
            lists[idx] = p.copy(songIds = p.songIds - songId)
            savePlaylists(context, lists)
        }
    }

    /** 重命名歌单 */
    suspend fun renamePlaylist(context: Context, playlistId: Long, newName: String) {
        val lists = playlistsFlow(context).first().toMutableList()
        val idx = lists.indexOfFirst { it.id == playlistId }
        if (idx >= 0) {
            lists[idx] = lists[idx].copy(name = newName)
            savePlaylists(context, lists)
        }
    }

    // ==================== 元数据修正（歌手/歌名整理） ====================

    /** 全部元数据修正映射（songId → (title, artist)） */
    fun metadataOverridesFlow(context: Context): Flow<Map<Long, Pair<String, String>>> =
        context.dataStore.data.map { prefs -> parseMetadata(prefs[KEY_METADATA] ?: "{}") }

    /** 保存一条元数据修正 */
    suspend fun saveMetadataOverride(context: Context, songId: Long, title: String, artist: String) {
        val map = metadataOverridesFlow(context).first().toMutableMap()
        map[songId] = title to artist
        saveMetadata(context, map)
    }

    private suspend fun saveMetadata(context: Context, map: Map<Long, Pair<String, String>>) {
        val obj = JSONObject()
        map.forEach { (id, pair) ->
            val item = JSONObject().put("title", pair.first).put("artist", pair.second)
            obj.put(id.toString(), item)
        }
        context.dataStore.edit { prefs -> prefs[KEY_METADATA] = obj.toString() }
    }

    private fun parseMetadata(raw: String): Map<Long, Pair<String, String>> {
        return try {
            val obj = JSONObject(raw)
            val result = mutableMapOf<Long, Pair<String, String>>()
            obj.keys().forEach { key ->
                val item = obj.optJSONObject(key) ?: return@forEach
                result[key.toLongOrNull() ?: return@forEach] =
                    (item.optString("title", "") to item.optString("artist", ""))
            }
            result
        } catch (e: Exception) {
            emptyMap()
        }
    }

    // ==================== 排序方式记忆 ====================

    /** 上次使用的排序方式（默认 0=按歌名） */
    suspend fun getSortMode(context: Context): Int =
        context.dataStore.data.map { prefs -> prefs[KEY_SORT_MODE] ?: 0 }.first()

    suspend fun saveSortMode(context: Context, mode: Int) {
        context.dataStore.edit { prefs -> prefs[KEY_SORT_MODE] = mode }
    }

    // ==================== 搜索历史 ====================

    /** 搜索历史（最新在前，最多 10 条） */
    fun searchHistoryFlow(context: Context): Flow<List<String>> =
        context.dataStore.data.map { prefs -> parseStringList(prefs[KEY_SEARCH_HISTORY] ?: "[]") }

    /** 新增一条搜索历史 */
    suspend fun addSearchHistory(context: Context, query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        val list = searchHistoryFlow(context).first().toMutableList()
        list.remove(q)              // 去重
        list.add(0, q)              // 最新在前
        if (list.size > 10) list.subList(10, list.size).clear()
        context.dataStore.edit { prefs -> prefs[KEY_SEARCH_HISTORY] = stringListToJson(list) }
    }

    /** 清空搜索历史 */
    suspend fun clearSearchHistory(context: Context) {
        context.dataStore.edit { prefs -> prefs[KEY_SEARCH_HISTORY] = "[]" }
    }

    // ==================== 播放历史 ====================

    /** 播放历史（songId → 播放次数，Flow） */
    fun playHistoryFlow(context: Context): Flow<Map<Long, Int>> =
        context.dataStore.data.map { prefs -> parsePlayHistory(prefs[KEY_PLAY_HISTORY] ?: "{}") }

    /** 记录一次播放 */
    suspend fun addPlayRecord(context: Context, songId: Long) {
        val map = playHistoryFlow(context).first().toMutableMap()
        map[songId] = (map[songId] ?: 0) + 1
        context.dataStore.edit { prefs -> prefs[KEY_PLAY_HISTORY] = playHistoryToJson(map) }
    }

    private fun parsePlayHistory(raw: String): Map<Long, Int> {
        return try {
            val obj = JSONObject(raw)
            val result = mutableMapOf<Long, Int>()
            obj.keys().forEach { key ->
                result[key.toLongOrNull() ?: return@forEach] = obj.optInt(key, 0)
            }
            result
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun playHistoryToJson(map: Map<Long, Int>): String {
        val obj = JSONObject()
        map.forEach { (id, count) -> obj.put(id.toString(), count) }
        return obj.toString()
    }

    // ==================== 序列化 ====================

    private suspend fun savePlaylists(context: Context, lists: List<Playlist>) {
        val arr = JSONArray()
        lists.forEach { p ->
            val obj = JSONObject()
            obj.put("id", p.id)
            obj.put("name", p.name)
            obj.put("songIds", JSONArray(p.songIds))
            arr.put(obj)
        }
        context.dataStore.edit { prefs -> prefs[KEY_PLAYLISTS] = arr.toString() }
    }

    private fun parsePlaylists(raw: String): List<Playlist> {
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.getJSONObject(i)
                val songIdsArr = obj.optJSONArray("songIds")
                val ids = mutableListOf<Long>()
                if (songIdsArr != null) {
                    for (j in 0 until songIdsArr.length()) {
                        ids.add(songIdsArr.getLong(j))
                    }
                }
                Playlist(
                    id = obj.getLong("id"),
                    name = obj.getString("name"),
                    songIds = ids
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseLongSet(raw: String): Set<Long> {
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getLong(it) }.toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    private fun parseStringList(raw: String): List<String> {
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun stringListToJson(list: List<String>): String {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        return arr.toString()
    }

    private fun longSetToJson(ids: Set<Long>): String {
        val arr = JSONArray()
        ids.forEach { arr.put(it) }
        return arr.toString()
    }
}

/** 歌单数据类 */
data class Playlist(
    val id: Long,
    val name: String,
    val songIds: List<Long>
)
