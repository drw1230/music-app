package com.dengdeng.music.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dengdeng.music.data.GitHubUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * GitHub 最新版本弹窗（API 直连，不打开网页）
 *
 * 进来自动查 `api.github.com` 的最新 Release → 显示版本号/日期/APK 大小 → 点下载走系统下载器。
 * 之所以不做成网页：手机上 `github.com` 主站被阻断（实测 3/3 超时），但 API 域名通畅。
 * 详见 [GitHubUpdater] 顶部说明。
 */
@Composable
fun GitHubUpdateDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    // null = 查询中；非 null = 已有结果
    var result by remember { mutableStateOf<GitHubUpdater.CheckResult?>(null) }
    var retryKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(retryKey) {
        result = null
        result = withContext(Dispatchers.IO) { GitHubUpdater.fetchLatest() }
    }

    val r = result

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("GitHub 最新版本") },
        text = {
            when {
                r == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("正在查询…", style = MaterialTheme.typography.bodyMedium)
                }

                r is GitHubUpdater.CheckResult.Failed -> Text(
                    text = r.reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )

                r is GitHubUpdater.CheckResult.Found -> {
                    val rel = r.release
                    Column {
                        Text(
                            text = rel.tag.ifBlank { "未知版本" },
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (rel.publishedDate.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "发布于 ${rel.publishedDate}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        val apk = rel.apk
                        if (apk == null) {
                            Text(
                                text = "该版本没有上传 APK 附件",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                        } else {
                            Text(
                                text = apk.name,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "大小 ${formatApkSize(apk.size)} · 下载后到通知栏点开安装",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 分享下载链接给朋友（网页直链；App 内自己下载走 API 端点，不经过这条链接）
                if (r is GitHubUpdater.CheckResult.Found && r.release.apk != null) {
                    TextButton(onClick = { shareDownloadLink(context, r.release) }) { Text("分享") }
                }
                when {
                    r is GitHubUpdater.CheckResult.Failed ->
                        TextButton(onClick = { retryKey++ }) { Text("重试") }

                    r is GitHubUpdater.CheckResult.Found && r.release.apk != null ->
                        TextButton(onClick = {
                            if (GitHubUpdater.download(context, r.release)) onDismiss()
                        }) { Text("下载") }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(if (r is GitHubUpdater.CheckResult.Failed) "关闭" else "取消")
            }
        }
    )
}

/** 字节数 → 人读大小（MB / KB） */
private fun formatApkSize(bytes: Long): String = when {
    bytes <= 0L -> "未知"
    bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    else -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
}

/**
 * 用系统分享面板把 GitHub 下载直链发给朋友
 * 注意：直链走 github.com 中转，国内未科学上网的接收方可能打不开
 * （这是用户要的"分享下载链接"行为；国内推广更适合分享蓝奏云地址）
 */
private fun shareDownloadLink(context: Context, release: GitHubUpdater.ReleaseInfo) {
    val apk = release.apk ?: return
    try {
        val text = "DDmusic ${release.tag} 安卓版下载：${apk.browserUrl}"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "DDmusic 下载链接")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, "分享 DDmusic 下载链接"))
    } catch (e: Exception) {
        Toast.makeText(context, "分享失败：${e.message}", Toast.LENGTH_SHORT).show()
    }
}
