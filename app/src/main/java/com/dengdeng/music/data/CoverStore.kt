package com.dengdeng.music.data

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 封面选择持久化存储：
 * 记录「歌名|艺术家」→ 封面 URL 的映射（用户手动选择或首次联网确定后写入），
 * 保证已确定的封面在后续展示时不再触发联网搜索/变化。
 */
object CoverStore {

    private const val FILE_NAME = "cover_selection.json"

    // 内存缓存（避免每次读文件）
    @Volatile
    private var memoryCache: MutableMap<String, String>? = null

    private fun keyOf(title: String, artist: String) = "$title|$artist".trim().lowercase()

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    private fun load(context: Context): Map<String, String> {
        memoryCache?.let { return it }
        val map = try {
            val text = file(context).readText(Charsets.UTF_8)
            val json = JSONObject(text)
            val keys = json.keys()
            val m = mutableMapOf<String, String>()
            while (keys.hasNext()) {
                val k = keys.next()
                m[k] = json.optString(k, "")
            }
            m
        } catch (e: Exception) {
            mutableMapOf()
        }
        memoryCache = map
        return map
    }

    private fun persist(context: Context, map: Map<String, String>) {
        try {
            val json = JSONObject()
            map.forEach { (k, v) -> json.put(k, v) }
            file(context).parentFile?.mkdirs()
            file(context).writeText(json.toString(), Charsets.UTF_8)
        } catch (e: Exception) {
            // 忽略持久化失败（内存缓存仍生效）
        }
    }

    /** 读取已确定的封面 URL（无则 null） */
    fun get(context: Context, title: String, artist: String): String? {
        return load(context)[keyOf(title, artist)]?.takeIf { it.isNotBlank() }
    }

    /** 保存用户确定的封面 */
    fun save(context: Context, title: String, artist: String, url: String) {
        val map = load(context).toMutableMap()
        map[keyOf(title, artist)] = url
        memoryCache = map
        persist(context, map)
    }
}
