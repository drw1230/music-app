package com.dengdeng.music.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
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
    private val KEY_LAST_PLAY = stringPreferencesKey("last_play_json")      // 上次播放（歌曲+进度+状态）
    private val KEY_REPEAT_MODE = intPreferencesKey("repeat_mode")          // 循环模式记忆
    private val KEY_SHUFFLE = booleanPreferencesKey("shuffle_mode")         // 乱序播放记忆
    private val KEY_LYRIC_OFFSET = stringPreferencesKey("lyric_offset_json") // 歌词微调偏移（songId → ms）
    private val KEY_SKIP_LIST = stringPreferencesKey("skip_list_json")     // 电台负反馈：10秒内切走的歌（title|artist）

    /** 上次播放信息 */
    data class LastPlay(val songId: Long, val positionMs: Long, val isPlaying: Boolean)

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

    // ==================== 最后播放时间（智能歌单"最近播放"倒序） ====================

    private val KEY_LAST_PLAYED_MS = stringPreferencesKey("last_played_ms_json")

    /** 最后播放时间（songId → 毫秒时间戳，Flow） */
    fun lastPlayedMsFlow(context: Context): Flow<Map<Long, Long>> =
        context.dataStore.data.map { prefs -> parseLongMap(prefs[KEY_LAST_PLAYED_MS] ?: "{}") }

    /** 保存最后播放时间整表 */
    suspend fun saveLastPlayedMs(context: Context, map: Map<Long, Long>) {
        context.dataStore.edit { prefs -> prefs[KEY_LAST_PLAYED_MS] = longMapToJson(map) }
    }

    private fun parseLongMap(raw: String): Map<Long, Long> {
        return try {
            val obj = JSONObject(raw)
            val result = mutableMapOf<Long, Long>()
            obj.keys().forEach { key ->
                result[key.toLongOrNull() ?: return@forEach] = obj.optLong(key, 0L)
            }
            result
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun longMapToJson(map: Map<Long, Long>): String {
        val obj = JSONObject()
        map.forEach { (id, ts) -> obj.put(id.toString(), ts) }
        return obj.toString()
    }

    // ==================== 在线收藏（"喜欢"列表显示在线歌） ====================

    /** 在线收藏的歌（含歌曲信息与播放 URL，供"喜欢"列表显示与播放） */
    data class OnlineFavorite(
        val title: String,
        val artist: String,
        val album: String,
        val artUrl: String?,
        val durationMs: Long,
        val url: String?
    )

    private val KEY_ONLINE_FAVORITES = stringPreferencesKey("online_favorites_json")

    /** 在线收藏列表 Flow */
    fun onlineFavoritesFlow(context: Context): Flow<List<OnlineFavorite>> =
        context.dataStore.data.map { prefs -> parseOnlineFavorites(prefs[KEY_ONLINE_FAVORITES] ?: "[]") }

    /** 保存在线收藏列表 */
    suspend fun saveOnlineFavorites(context: Context, list: List<OnlineFavorite>) {
        val arr = JSONArray()
        list.forEach { f ->
            arr.put(
                JSONObject()
                    .put("title", f.title)
                    .put("artist", f.artist)
                    .put("album", f.album)
                    .put("artUrl", f.artUrl ?: "")
                    .put("durationMs", f.durationMs)
                    .put("url", f.url ?: "")
            )
        }
        context.dataStore.edit { prefs -> prefs[KEY_ONLINE_FAVORITES] = arr.toString() }
    }

    private fun parseOnlineFavorites(raw: String): List<OnlineFavorite> {
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                OnlineFavorite(
                    title = o.optString("title", ""),
                    artist = o.optString("artist", ""),
                    album = o.optString("album", ""),
                    artUrl = o.optString("artUrl", "").takeIf { it.isNotBlank() },
                    durationMs = o.optLong("durationMs", 0L),
                    url = o.optString("url", "").takeIf { it.isNotBlank() }
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    // ==================== 在线播放记录（智能歌单统计） ====================

    /** 在线播放记录（key = "title|artist".lowercase()；存 URL 供智能歌单点击重播） */
    data class OnlineRecord(
        val title: String,
        val artist: String,
        val url: String,
        val artUrl: String?,
        val durationMs: Long,
        val times: Int,
        val lastPlayedMs: Long
    )

    private val KEY_ONLINE_RECORDS = stringPreferencesKey("online_records_json")

    /** 在线播放记录 Flow */
    fun onlineRecordsFlow(context: Context): Flow<Map<String, OnlineRecord>> =
        context.dataStore.data.map { prefs -> parseOnlineRecords(prefs[KEY_ONLINE_RECORDS] ?: "{}") }

    /** 保存整表在线记录（ViewModel 内存为唯一增量来源，这里只持久化） */
    suspend fun saveOnlineRecords(context: Context, map: Map<String, OnlineRecord>) {
        context.dataStore.edit { prefs -> prefs[KEY_ONLINE_RECORDS] = onlineRecordsToJson(map) }
    }

    private fun parseOnlineRecords(raw: String): Map<String, OnlineRecord> {
        return try {
            val obj = JSONObject(raw)
            val result = mutableMapOf<String, OnlineRecord>()
            obj.keys().forEach { key ->
                val item = obj.optJSONObject(key) ?: return@forEach
                result[key] = OnlineRecord(
                    title = item.optString("title"),
                    artist = item.optString("artist"),
                    url = item.optString("url"),
                    artUrl = item.optString("artUrl").takeIf { it.isNotBlank() },
                    durationMs = item.optLong("durationMs", 0L),
                    times = item.optInt("times", 1),
                    lastPlayedMs = item.optLong("lastPlayedMs", 0L)
                )
            }
            result
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun onlineRecordsToJson(map: Map<String, OnlineRecord>): String {
        val obj = JSONObject()
        map.forEach { (key, r) ->
            obj.put(
                key,
                JSONObject()
                    .put("title", r.title)
                    .put("artist", r.artist)
                    .put("url", r.url)
                    .put("artUrl", r.artUrl ?: "")
                    .put("durationMs", r.durationMs)
                    .put("times", r.times)
                    .put("lastPlayedMs", r.lastPlayedMs)
            )
        }
        return obj.toString()
    }

    // ==================== 曲库缓存（启动秒显，避免每次重扫转圈） ====================

    /** 缓存的歌曲（含 MediaStore id，播放历史/收藏可对齐） */
    data class CachedSong(
        val id: Long,
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long,
        val uri: String,
        val albumArtUri: String?
    )

    private val KEY_SONGS_CACHE = stringPreferencesKey("songs_cache_json")

    /** 曲库缓存 Flow（无缓存返回空列表） */
    fun songsCacheFlow(context: Context): Flow<List<CachedSong>> =
        context.dataStore.data.map { prefs -> parseSongsCache(prefs[KEY_SONGS_CACHE] ?: "[]") }

    /** 保存曲库缓存（扫描完成后调用） */
    suspend fun saveSongsCache(context: Context, songs: List<CachedSong>) {
        context.dataStore.edit { prefs -> prefs[KEY_SONGS_CACHE] = songsCacheToJson(songs) }
    }

    private fun parseSongsCache(raw: String): List<CachedSong> {
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                CachedSong(
                    id = o.optLong("id", -1L),
                    title = o.optString("title", ""),
                    artist = o.optString("artist", ""),
                    album = o.optString("album", ""),
                    durationMs = o.optLong("durationMs", 0L),
                    uri = o.optString("uri", ""),
                    albumArtUri = o.optString("albumArtUri", "").takeIf { it.isNotBlank() }
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun songsCacheToJson(songs: List<CachedSong>): String {
        val arr = JSONArray()
        songs.forEach { s ->
            arr.put(
                JSONObject()
                    .put("id", s.id)
                    .put("title", s.title)
                    .put("artist", s.artist)
                    .put("album", s.album)
                    .put("durationMs", s.durationMs)
                    .put("uri", s.uri)
                    .put("albumArtUri", s.albumArtUri ?: "")
            )
        }
        return arr.toString()
    }

    // ==================== 上次播放 + 播放模式记忆 ====================

    /** 上次播放信息（歌曲 ID + 进度 + 播放状态），无记录返回 null */
    suspend fun getLastPlay(context: Context): LastPlay? =
        context.dataStore.data.map { prefs ->
            val raw = prefs[KEY_LAST_PLAY] ?: return@map null
            try {
                val obj = JSONObject(raw)
                LastPlay(
                    songId = obj.getLong("songId"),
                    positionMs = obj.optLong("positionMs", 0L),
                    isPlaying = obj.optBoolean("isPlaying", false)
                )
            } catch (e: Exception) {
                null
            }
        }.first()

    /** 保存上次播放信息 */
    suspend fun saveLastPlay(context: Context, songId: Long, positionMs: Long, isPlaying: Boolean) {
        val obj = JSONObject()
            .put("songId", songId)
            .put("positionMs", positionMs)
            .put("isPlaying", isPlaying)
        context.dataStore.edit { prefs -> prefs[KEY_LAST_PLAY] = obj.toString() }
    }

    /** 上次循环模式（默认全部循环 off → 0） */
    suspend fun getRepeatMode(context: Context): Int =
        context.dataStore.data.map { prefs -> prefs[KEY_REPEAT_MODE] ?: 0 }.first()

    suspend fun saveRepeatMode(context: Context, mode: Int) {
        context.dataStore.edit { prefs -> prefs[KEY_REPEAT_MODE] = mode }
    }

    /** 上次乱序播放开关 */
    suspend fun getShuffle(context: Context): Boolean =
        context.dataStore.data.map { prefs -> prefs[KEY_SHUFFLE] ?: false }.first()

    suspend fun saveShuffle(context: Context, enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_SHUFFLE] = enabled }
    }

    // ==================== 歌词微调偏移 ====================

    /** 全部歌词偏移（songId → 偏移毫秒，正=歌词提前/负=歌词延后） */
    fun lyricOffsetsFlow(context: Context): Flow<Map<Long, Long>> =
        context.dataStore.data.map { prefs ->
            val raw = prefs[KEY_LYRIC_OFFSET] ?: "{}"
            try {
                val obj = JSONObject(raw)
                val map = mutableMapOf<Long, Long>()
                obj.keys().forEach { key ->
                    map[key.toLongOrNull() ?: return@forEach] = obj.optLong(key, 0L)
                }
                map
            } catch (e: Exception) {
                emptyMap()
            }
        }

    /** 设置某首歌的歌词偏移 */
    suspend fun saveLyricOffset(context: Context, songId: Long, offsetMs: Long) {
        val map = lyricOffsetsFlow(context).first().toMutableMap()
        if (offsetMs == 0L) map.remove(songId) else map[songId] = offsetMs
        val obj = JSONObject()
        map.forEach { (id, off) -> obj.put(id.toString(), off) }
        context.dataStore.edit { prefs -> prefs[KEY_LYRIC_OFFSET] = obj.toString() }
    }

    // ==================== 电台负反馈（跳过歌） ====================

    /** 跳过歌集合（"歌名|歌手"，10秒内切走即记录，电台推荐时过滤） */
    // ==================== 冷门探索最近推荐（每次进入刷新大半；30 天过期 → 旧歌重新可推） ====================

    private val KEY_RECENT_COLD = stringPreferencesKey("recent_cold_keys_json")
    private val KEY_RECENT_COLD_TS = longPreferencesKey("recent_cold_ts_ms")

    /** 上次推荐的冷门探索歌曲 key 集合（超过 30 天自动过期返回空 → 旧歌重新可推） */
    fun recentColdKeysFlow(context: Context): Flow<Set<String>> =
        context.dataStore.data.map { prefs ->
            val ts = prefs[KEY_RECENT_COLD_TS] ?: 0L
            if (ts > 0 && System.currentTimeMillis() - ts > RECENT_EXPIRE_MS) {
                emptySet()
            } else {
                try {
                    val arr = JSONArray(prefs[KEY_RECENT_COLD] ?: "[]")
                    (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotBlank() } }.toSet()
                } catch (e: Exception) { emptySet() }
            }
        }

    suspend fun saveRecentColdKeys(context: Context, keys: Set<String>) {
        val arr = JSONArray()
        keys.forEach { arr.put(it) }
        context.dataStore.edit { prefs ->
            prefs[KEY_RECENT_COLD] = arr.toString()
            prefs[KEY_RECENT_COLD_TS] = System.currentTimeMillis()
        }
    }

    // ==================== 电台最近推荐（每天刷新大半更新；30 天过期 → 旧歌重新可推） ====================

    private val KEY_RECENT_RADIO = stringPreferencesKey("recent_radio_keys_json")
    private val KEY_RECENT_RADIO_TS = longPreferencesKey("recent_radio_ts_ms")
    private val RECENT_EXPIRE_MS = 30L * 24 * 3600 * 1000   // 一个月

    /** 最近推荐的电台歌曲 key 集合（"title|artist".lowercase()；超过 30 天自动过期返回空 → 旧歌重新可推） */
    fun recentRadioKeysFlow(context: Context): Flow<Set<String>> =
        context.dataStore.data.map { prefs ->
            val ts = prefs[KEY_RECENT_RADIO_TS] ?: 0L
            if (ts > 0 && System.currentTimeMillis() - ts > RECENT_EXPIRE_MS) {
                emptySet()   // 记忆过期：不再过滤，一轮新的候选重新开始
            } else {
                try {
                    val arr = JSONArray(prefs[KEY_RECENT_RADIO] ?: "[]")
                    (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotBlank() } }.toSet()
                } catch (e: Exception) { emptySet() }
            }
        }

    /** 保存最近推荐的电台歌曲 key 集合（同时记录时间戳） */
    suspend fun saveRecentRadioKeys(context: Context, keys: Set<String>) {
        val arr = JSONArray()
        keys.forEach { arr.put(it) }
        context.dataStore.edit { prefs ->
            prefs[KEY_RECENT_RADIO] = arr.toString()
            prefs[KEY_RECENT_RADIO_TS] = System.currentTimeMillis()
        }
    }

    fun skipListFlow(context: Context): Flow<Set<String>> =
        context.dataStore.data.map { prefs ->
            val raw = prefs[KEY_SKIP_LIST] ?: "[]"
            try {
                val arr = JSONArray(raw)
                (0 until arr.length()).map { arr.getString(it) }.toSet()
            } catch (e: Exception) {
                emptySet()
            }
        }

    /** 追加跳过歌 */
    suspend fun addSkipSong(context: Context, key: String) {
        val set = skipListFlow(context).first().toMutableSet()
        if (set.add(key)) {
            val arr = JSONArray()
            set.forEach { arr.put(it) }
            context.dataStore.edit { prefs -> prefs[KEY_SKIP_LIST] = arr.toString() }
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
