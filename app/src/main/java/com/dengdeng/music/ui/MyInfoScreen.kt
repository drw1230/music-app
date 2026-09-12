package com.dengdeng.music.ui

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Leaderboard
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dengdeng.music.data.LeaderboardStore
import com.dengdeng.music.data.PlayerProfile
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 我的信息（玩家档案样本页）
 * 1. 名字：未设置自动弹取名框（有则跳过），改名随时可改；游戏成绩都绑定这个名字
 * 2. 本地战绩：各游戏历史最高分（游戏内容打磨中，当前为空态展示）
 * 3. 联网排行榜（GitHub 演示）：进入自动拉取榜单；「上传我的成绩」演示完整写链路
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyInfoScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf<String?>(null) }   // null = 载入中
    var records by remember { mutableStateOf<List<PlayerProfile.GameRecord>>(emptyList()) }
    var showNameDialog by remember { mutableStateOf(false) }

    // 联网榜状态：null=加载中 / Error / Snapshot
    var lbError by remember { mutableStateOf<String?>(null) }
    var lb by remember { mutableStateOf<LeaderboardStore.Snapshot?>(null) }
    var uploading by remember { mutableStateOf(false) }

    fun refreshBoard() {
        scope.launch {
            lbError = null
            LeaderboardStore.fetch().fold(
                onSuccess = { lb = it },
                onFailure = { lbError = it.message ?: "拉取失败" }
            )
        }
    }

    // 进入：读本地档案 + 拉联网榜；未取名则先弹取名
    LaunchedEffect(Unit) {
        val n = PlayerProfile.getName(ctx)
        name = n
        records = PlayerProfile.getRecords(ctx)
        if (n.isBlank()) showNameDialog = true
        refreshBoard()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("我的信息") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            // ── 玩家卡片 ──
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.AccountCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(52.dp)
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = if (name.isNullOrBlank()) "未设置名字" else name!!,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "取好名字后，游戏成绩与排行榜都绑定它",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = { showNameDialog = true }) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("改名")
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // ── 本地战绩卡片 ──
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("本地战绩", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    if (records.isEmpty()) {
                        Text(
                            "还没有游戏记录 · 游戏内容打磨中，敬请期待",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        records.forEach { r ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    PlayerProfile.GAME_NAMES[r.gameId] ?: r.gameId,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    "最高 ${r.best} · ${r.plays} 次",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // ── 联网排行榜卡片（GitHub 演示） ──
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Leaderboard,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("联网排行榜（腾讯云）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        "存储：腾讯云 COS 对象存储 · leaderboard.json",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))

                    when {
                        // 加载中
                        lb == null && lbError == null -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(10.dp))
                                Text("正在拉取榜单…", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        // 失败
                        lbError != null -> {
                            Text(
                                "$lbError",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        // 成功
                        else -> {
                            val entries = lb?.entries.orEmpty()
                            if (entries.isEmpty()) {
                                Text("榜上还没有人，上传第一条成绩吧", style = MaterialTheme.typography.bodyMedium)
                            } else {
                                entries.take(20).forEachIndexed { i, e ->
                                    Row(
                                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            "${i + 1}",
                                            style = MaterialTheme.typography.titleSmall,
                                            color = if (i < 3) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.width(26.dp)
                                        )
                                        Column(Modifier.weight(1f)) {
                                            Text(e.name, style = MaterialTheme.typography.bodyMedium)
                                            Text(
                                                "${PlayerProfile.GAME_NAMES[e.game] ?: e.game} · ${fmtTime(e.updatedAt)}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Text(
                                            "${e.score}",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            val n = name
                            val best = records.maxOfOrNull { it.best }
                            when {
                                n.isNullOrBlank() -> Toast.makeText(ctx, "先给自己取个名字", Toast.LENGTH_SHORT).show()
                                best == null -> Toast.makeText(ctx, "还没有游戏成绩可以上传", Toast.LENGTH_SHORT).show()
                                else -> {
                                    uploading = true
                                    scope.launch {
                                        LeaderboardStore.upload(n, records.maxByOrNull { it.best }!!.gameId, best).fold(
                                            onSuccess = {
                                                lb = it; lbError = null
                                                Toast.makeText(ctx, "已上传：$n · $best 分", Toast.LENGTH_SHORT).show()
                                            },
                                            onFailure = {
                                                Toast.makeText(ctx, it.message ?: "上传失败", Toast.LENGTH_LONG).show()
                                            }
                                        )
                                        uploading = false
                                    }
                                }
                            }
                        },
                        enabled = !uploading,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        if (uploading) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("上传我的成绩")
                    }
                    Text(
                        when {
                            !LeaderboardStore.configured ->
                                "未配置：创建腾讯云 COS 桶（公有读私有写）后，把桶访问域名与 CAM 子用户密钥填入 LeaderboardStore 即生效"
                            LeaderboardStore.SECRET_KEY.isBlank() ->
                                "桶已配置（只读演示）：填入 CAM 子用户 SECRET_ID / SECRET_KEY 后即可上传成绩"
                            else ->
                                "已配置：上传即把「名字+游戏+最高分」合并进 COS 榜单文件"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    // ── 取名 / 改名弹窗 ──
    if (showNameDialog && name != null) {
        var input by remember(showNameDialog) { mutableStateOf(name.orEmpty()) }
        AlertDialog(
            onDismissRequest = { showNameDialog = false },
            title = { Text(if (name.isNullOrBlank()) "给自己取个名字" else "改个名字") },
            text = {
                Column {
                    Text("名字会绑定你的游戏成绩，并显示在排行榜上")
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        label = { Text("玩家名字") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = input.isNotBlank(),
                    onClick = {
                        scope.launch {
                            PlayerProfile.saveName(ctx, input)
                            name = input.trim()
                        }
                        showNameDialog = false
                        Toast.makeText(ctx, "已保存：${input.trim()}", Toast.LENGTH_SHORT).show()
                    }
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showNameDialog = false }) { Text("取消") }
            }
        )
    }
}

private fun fmtTime(iso: String): String = runCatching {
    val d = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.CHINA).parse(iso)
    if (d != null) SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(d) else iso
}.getOrDefault(iso)
