package com.dengdeng.music.data

import android.content.Context
import androidx.datastore.preferences.core.edit
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
