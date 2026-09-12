package com.dengdeng.music.data

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * GitHub Releases 更新检查 + APK 下载
 *
 * 【为什么必须走 api.github.com，不能用网页/网页直链】
 * 2026-09-12 用手机（中国移动 5G · 重庆）直连实测：
 * ```
 * github.com                    → 3/3 次超时（10s）；网页与 browser_download_url 均 0 字节
 * api.github.com                → 200 / 0.57s                      ✅
 * objects.githubusercontent.com → 可达（Release 资产的真实存储域名） ✅
 * codeload.github.com           → 301 / 0.73s                      ✅
 * raw.githubusercontent.com     → 301 / 0.57s                      ✅
 * ```
 * 结论：**只有 github.com 这一个域名被阻断**，GitHub 其它域名都通。因此：
 *   ① 查最新版本 → `GET https://api.github.com/repos/{repo}/releases/latest`（匿名可调）
 *   ② 下载资产   → `GET https://api.github.com/repos/{repo}/releases/assets/{id}`
 *                  必须带请求头 `Accept: application/octet-stream`，否则只返回 JSON 元数据；
 *                  该请求会 302 跳到 objects.githubusercontent.com（可达）取真实字节。
 * 同一份 15.3MB 资产实测：API 端点 7.3s（2.09 MB/s）；网页直链 30s 超时 0 字节。
 *
 * ⚠️ 前提：仓库必须是 **Public**。私有仓库匿名调 API 会返回 404（无法把 token 塞进 App）。
 *
 * 日志 TAG：DDmusicUpd（adb logcat -s DDmusicUpd 可单抓升级链路）
 */
object GitHubUpdater {

    private const val TAG = "DDmusicUpd"

    /** 仓库全名（owner/repo）—— 换仓库只改这一行 */
    const val REPO = "drw1230/music-app"

    private const val API_LATEST = "https://api.github.com/repos/$REPO/releases/latest"
    private const val UA = "DDmusic-Android"

    /** Release 里的一个 .apk 资产 */
    data class ApkAsset(
        val name: String,
        val size: Long,
        /** GitHub API 资产端点（下载时需带 Accept: application/octet-stream） */
        val apiUrl: String
    )

    /** 最新 Release 信息 */
    data class ReleaseInfo(
        /** tag 名，如 v1.0.5 */
        val tag: String,
        /** 发布日期 yyyy-MM-dd（解析失败为空串） */
        val publishedDate: String,
        /** 该 Release 里的 apk 资产（没有 apk 时为 null） */
        val apk: ApkAsset?
    )

    /** 查询结果：成功 / 失败（失败带人话原因，直接给界面用） */
    sealed interface CheckResult {
        data class Found(val release: ReleaseInfo) : CheckResult
        data class Failed(val reason: String) : CheckResult
    }

    /**
     * 查询最新 Release（**IO 线程调用**）
     * 匿名调用限额 60 次/小时，正常使用（用户手点）远远够用
     */
    fun fetchLatest(): CheckResult {
        val t0 = System.currentTimeMillis()
        return try {
            val conn = (URL(API_LATEST).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                setRequestProperty("User-Agent", UA)
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            Log.i(TAG, "查询最新版本 code=$code 耗时=${System.currentTimeMillis() - t0}ms body=${body.take(200)}")
            runCatching { conn.disconnect() }

            when {
                code == 404 -> CheckResult.Failed(
                    "仓库不可访问或还没有发布 Release。\n（私有仓库无法匿名访问，请先把仓库改为 Public）"
                )
                code == 403 -> CheckResult.Failed("GitHub 接口调用过于频繁，请稍后再试")
                code !in 200..299 -> CheckResult.Failed("查询失败（HTTP $code）")
                else -> parseRelease(body)?.let { CheckResult.Found(it) }
                    ?: CheckResult.Failed("返回内容解析失败")
            }
        } catch (e: Exception) {
            Log.e(TAG, "查询最新版本失败（${System.currentTimeMillis() - t0}ms）", e)
            CheckResult.Failed("网络请求失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** 解析 `releases/latest` 响应 */
    private fun parseRelease(body: String): ReleaseInfo? {
        return try {
            val json = JSONObject(body)
            val tag = json.optString("tag_name", "").trim()
            if (tag.isBlank()) return null
            val date = json.optString("published_at", "").trim().substringBefore('T')
            // 只认 .apk 资产（Release 里可能还有源码包）
            var apk: ApkAsset? = null
            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.optJSONObject(i) ?: continue
                    val name = a.optString("name", "")
                    if (!name.endsWith(".apk", ignoreCase = true)) continue
                    apk = ApkAsset(name, a.optLong("size", 0L), a.optString("url", ""))
                    break
                }
            }
            ReleaseInfo(tag, date, apk)
        } catch (e: Exception) {
            Log.e(TAG, "解析 Release 失败: ${body.take(200)}", e)
            null
        }
    }

    /**
     * 用系统下载器下载该 Release 的 APK（下载到公共「下载」目录，完成后通知栏点开安装）
     * 关键点：走 **API 资产端点**并带 `Accept: application/octet-stream`，否则拿不到字节流。
     * @return 是否成功入队
     */
    fun download(context: Context, release: ReleaseInfo): Boolean {
        val apk = release.apk
        if (apk == null) {
            Toast.makeText(context, "该版本没有 APK 附件", Toast.LENGTH_SHORT).show()
            return false
        }
        return try {
            val request = DownloadManager.Request(Uri.parse(apk.apiUrl)).apply {
                // 缺这一行只会下到 JSON 元数据，不是 APK
                addRequestHeader("Accept", "application/octet-stream")
                addRequestHeader("User-Agent", UA)
                setMimeType("application/vnd.android.package-archive")
                setTitle(apk.name)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, apk.name)
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
            }
            (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            Log.i(TAG, "已入队下载: ${apk.name} (${apk.size}B) ← ${apk.apiUrl}")
            Toast.makeText(context, "开始下载：${apk.name}", Toast.LENGTH_LONG).show()
            true
        } catch (e: Exception) {
            Log.e(TAG, "下载入队失败", e)
            Toast.makeText(context, "下载失败：${e.message}", Toast.LENGTH_SHORT).show()
            false
        }
    }
}
