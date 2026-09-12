package com.dengdeng.music.ui

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import java.net.URLDecoder
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * 下载源：蓝奏云网盘分享页（App 内 WebView 打开）—— 换分享地址只需要改这一行
 * 当前：蓝奏云「DDmusic 更新」文件夹分享（访问密码 1234，页面内手动输入）
 * 注意：蓝奏云有 JS 反爬挑战，必须由浏览器/WebView 执行 JS 才能打开，请勿改成 HttpClient 抓取
 *
 * （另一个下载源「GitHub Releases」不在这里：github.com 主站在国内手机网络被阻断，
 *   实测 3/3 超时，改为走 api.github.com 直连，见 [com.dengdeng.music.data.GitHubUpdater]
 *   与 [GitHubUpdateDialog]）
 */
const val UPGRADE_URL_LANZOU = "https://wwbpy.lanzouq.com/b01eurncrg"

/** 手机版 Chrome UA：去掉 WebView 的 wv 标记，确保网盘返回正常的手机页面 */
private const val MOBILE_CHROME_UA =
    "Mozilla/5.0 (Linux; Android 14; SM-S9180) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

/**
 * 软件升级界面（网页通道）：在 App 内 WebView 打开指定下载源页面，手动下载最新 APK 升级包
 * - 下载源由调用方通过 [url] 传入（当前只有蓝奏云，见本文件顶部常量）
 * - 页面里的下载按钮 → 交给系统下载器（DownloadManager）下载到「下载」目录
 * - 下载完成后从通知栏点开即可安装（需允许安装未知应用）
 * - 返回键：优先回网页上一页，到顶后退出本界面
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpgradeScreen(url: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    BackHandler {
        val wv = webViewRef
        if (wv != null && wv.canGoBack()) wv.goBack() else onBack()
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("软件升级") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
            actions = {
                // 复制当前下载页地址，方便转发给朋友（蓝奏云分享链接可直接在国内打开）
                IconButton(onClick = {
                    clipboard.setText(AnnotatedString(url))
                    Toast.makeText(context, "链接已复制，可发给朋友", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "复制链接")
                }
            }
        )

        if (url.isBlank()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "下载地址未配置",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            AndroidView(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        // 网盘对 WebView 有反爬：伪装成手机 Chrome，让 JS 挑战和页面都正常
                        settings.userAgentString = MOBILE_CHROME_UA
                        // 通过 JS 挑战 / 密码验证都靠 cookie，必须放开
                        android.webkit.CookieManager.getInstance().setAcceptCookie(true)
                        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        // 页面加载完注入样式：蓝奏云的密码输入框是白底无边框，在白色页面里完全隐形
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String) {
                                view.evaluateJavascript(VISIBLE_INPUT_JS, null)
                            }
                        }
                        webChromeClient = WebChromeClient()
                        // 页面里的下载（APK / 压缩包）交给系统下载器
                        // 网盘直链的文件名是 32 位 hash + .bin（CDN 命名），先问页面 DOM 要真实文件名
                        setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                            evaluateJavascript(APK_NAME_JS) { raw ->
                                enqueueDownload(
                                    ctx, url, userAgent, contentDisposition, mimeType,
                                    parseJsString(raw)
                                )
                            }
                        }
                        loadUrl(url)
                        webViewRef = this
                    }
                }
            )
        }
    }
}

/**
 * 给页面里的密码/文本输入框强制加可见样式（2026-09-12 真机截图定位）：
 * 蓝奏云密码页的输入框白底、无边框，在白色页面里完全隐形 —— 用户只能看到
 * "文件受密码保护"的提示和"确认"按钮，看不到在哪里输密码。
 * 处理：白底黑字 + 蓝色边框 + 居中 + 加大高度（44px+，好点好输）。
 * 只处理可见的输入框和密码框（跳过 hidden/submit/button，避免误伤页面布局）。
 */
private const val VISIBLE_INPUT_JS = """
(function(){
  var list=document.querySelectorAll('input,textarea');
  for(var i=0;i<list.length;i++){
    var el=list[i];
    var t=(el.getAttribute('type')||'').toLowerCase();
    if(t==='hidden'||t==='submit'||t==='button'||t==='image'||t==='checkbox'||t==='radio')continue;
    if(el.offsetParent===null&&t!=='password')continue;
    el.style.display='block';
    el.style.width='72%';
    el.style.margin='14px auto';
    el.style.height='46px';
    el.style.padding='0 14px';
    el.style.boxSizing='border-box';
    el.style.fontSize='18px';
    el.style.textAlign='center';
    el.style.border='2px solid #2b7de9';
    el.style.borderRadius='10px';
    el.style.background='#ffffff';
    el.style.color='#111111';
    el.style.outline='none';
    if(!el.getAttribute('placeholder')){
      el.setAttribute('placeholder', t==='password'?'password':'');
    }
  }
})()
"""

/**
 * 从当前页面 DOM 里提取真实文件名（.apk）
 * 原因：网盘给的下载直链文件名是 32 位 hash + .bin，只有页面上的文件列表才有真名
 */
private const val APK_NAME_JS = """
(function(){
  var pick='';
  var list=document.querySelectorAll('a,.filename,.file-name,.name,.tit,span,div,p,li,strong,h1,h2,h3');
  for(var i=0;i<list.length;i++){
    var t=((list[i].textContent||'')+'').trim();
    var m=t.match(/([^\s\/\\]{2,90}\.apk)/i);
    if(m){ pick=m[1]; break; }
  }
  return pick;
})()
"""

/** 解析 evaluateJavascript 的回调值（带引号的 JSON 字符串 → 纯文本；null → null） */
private fun parseJsString(raw: String?): String? {
    val s = raw?.trim()?.removeSurrounding("\"")?.trim()
    return s?.takeIf { it.isNotBlank() && it != "null" }
}

/**
 * 决定下载保存的文件名：
 * 1. 优先页面 DOM 抓到的真实名（网盘场景最可靠）
 * 2. 其次 Content-Disposition 的 filename（含 RFC5987 filename*，做 URL 解码）
 * 3. 再次 URL 最后一段
 * 4. 判定无效（32 位 hash / 带编码残留 / 无扩展名）→ 回退「DDmusic-升级包-月日时分.apk」
 * 最后统一强制 .apk 后缀（网盘常返回 .bin）
 */
private fun decideFileName(url: String, disposition: String?, mime: String?, pageName: String?): String {
    var name = pageName?.trim().orEmpty()

    if (name.isBlank() && !disposition.isNullOrBlank()) {
        val star = Regex("filename\\*\\s*=\\s*(?:UTF-8|utf-8)?''([^;]+)").find(disposition)?.groupValues?.get(1)
        val plain = Regex("filename\\s*=\\s*\"?([^\";]+)").find(disposition)?.groupValues?.get(1)
        name = (star ?: plain ?: "").trim()
    }
    name = runCatching { URLDecoder.decode(name, "UTF-8") }.getOrDefault(name).trim()

    if (name.isBlank()) {
        name = url.substringBefore('?').substringAfterLast('/')
        name = runCatching { URLDecoder.decode(name, "UTF-8") }.getOrDefault(name).trim()
    }

    // 32 位（或更长）纯 hex + 可选短后缀 = 网盘 hash 名，不是真文件名
    val looksLikeHash = Regex("^[0-9a-fA-F]{16,}(\\.[a-zA-Z0-9]{1,5})?$").matches(name)
    val usable = name.isNotBlank() && !looksLikeHash && !name.contains('%') &&
            name.length in 5..120 && name.contains('.')

    if (!usable) {
        val ts = java.text.SimpleDateFormat("MMdd-HHmm", java.util.Locale.US).format(java.util.Date())
        return "DDmusic-升级包-$ts.apk"
    }

    // 统一 .apk 后缀（网盘原样给 .bin / 无后缀）
    val base = name.substringBeforeLast('.', name)
    return "$base.apk"
}

/** 用系统下载器下载升级包（带 Referer，规避网盘防盗链），完成后通知栏点击安装 */
private fun enqueueDownload(
    context: Context,
    url: String,
    userAgent: String?,
    contentDisposition: String?,
    mimeType: String?,
    pageName: String?
) {
    try {
        val fileName = decideFileName(url, contentDisposition, mimeType, pageName)
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            if (!mimeType.isNullOrBlank()) setMimeType(mimeType)
            if (!userAgent.isNullOrBlank()) addRequestHeader("User-Agent", userAgent)
            // 网盘下载链接常校验 Referer，带上更稳
            addRequestHeader("Referer", url)
            setTitle(fileName)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
        }
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.enqueue(request)
        Toast.makeText(context, "开始下载：$fileName", Toast.LENGTH_LONG).show()
    } catch (e: Exception) {
        Toast.makeText(context, "下载失败：${e.message}", Toast.LENGTH_SHORT).show()
    }
}
