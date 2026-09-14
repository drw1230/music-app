package com.dengdeng.music.ui

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.dengdeng.music.data.MusicRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 单次录音秒数（≥5 秒识别率明显更好；环境嘈杂时更长）
 *  注：此常量与 MusicRecognizer.record() 的 seconds 参数一致 */
private const val RECORD_SECONDS = 10

/** 诊断日志 TAG（与 MusicRecognizer 一致，adb logcat -s DDmusicRec 可单抓识别链路） */
private const val TAG = "DDmusicRec"

/** 识别流程状态 */
private enum class RecState { IDLE, RECORDING, UPLOADING, ERROR }

/**
 * 听歌识曲界面
 *
 * 进入即自动开始：请求麦克风权限 → 原生录音（44.1kHz WAV）→ 上传 AHA/ACRCloud 识别
 * → **识别成功直接 [onPickQuery] 带搜索词跳进联网搜索**（不再落地结果列表，少一次点击）
 */
@Composable
fun RecognizeScreen(
    onBack: () -> Unit,
    onPickQuery: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 麦克风权限状态
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }
    var permissionDenied by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.i(TAG, "麦克风权限回调: granted=$granted")
        hasPermission = granted
        permissionDenied = !granted
    }

    var state by remember { mutableStateOf(RecState.IDLE) }
    // 本次录音已进行秒数（驱动进度条）
    var elapsed by remember { mutableIntStateOf(0) }
    // 是否已识别过至少一次（用于区分"未识别到"和"还没开始"）
    var triedOnce by remember { mutableStateOf(false) }

    /** 开始一次识别：录音 → 上传 → 展示结果 */
    fun startRecognize() {
        Log.i(TAG, "startRecognize: state=$state hasPermission=$hasPermission")
        if (state == RecState.RECORDING || state == RecState.UPLOADING) return
        if (!hasPermission) {
            Log.w(TAG, "无麦克风权限，发起权限申请")
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        scope.launch {
            triedOnce = true
            state = RecState.RECORDING
            elapsed = 0
            // 独立协程推进秒数，仅用于进度显示
            val ticker = launch {
                while (elapsed < RECORD_SECONDS) {
                    delay(1000)
                    elapsed++
                }
            }
            Log.i(TAG, "开始录音…")
            val wav = withContext(Dispatchers.IO) { MusicRecognizer.record(RECORD_SECONDS) }
            ticker.cancel()
            if (wav == null) {
                Log.e(TAG, "录音失败（返回 null），状态置为 ERROR")
                state = RecState.ERROR
                return@launch
            }
            state = RecState.UPLOADING
            Log.i(TAG, "录音完成，开始上传识别…")
            val res = withContext(Dispatchers.IO) { MusicRecognizer.recognize(wav) }
            state = RecState.IDLE
            Log.i(TAG, "识别流程结束: result=${res?.title ?: "null"}")
            if (res != null) {
                // 识别成功 → 直接带着搜索词跳进联网搜索（由 MainScreen 落到搜索框）
                val q = res.query.ifBlank { res.title }
                Log.i(TAG, "识别成功（来源 ${res.source}）→ 跳搜索: 「$q」")
                onPickQuery(q)
            }
        }
    }

    // 进入界面：无权限则先申请；有权限则自动开始识别
    LaunchedEffect(Unit) {
        Log.i(TAG, "进入听歌识曲界面: hasPermission=$hasPermission")
        if (hasPermission) startRecognize() else {
            Log.w(TAG, "首次进入无权限，弹权限申请")
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Column(Modifier.fillMaxSize()) {
        // 顶部栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Column(Modifier.weight(1f)) {
                Text(
                    "听歌识曲",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = when (state) {
                        RecState.RECORDING -> "正在聆听… 请把手机靠近音源"
                        RecState.UPLOADING -> "识别中…"
                        RecState.ERROR -> "录音失败，请重试"
                        RecState.IDLE -> if (triedOnce) "未识别到，可再试一次" else "让音乐在附近播放，识别到会自动搜歌"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 中央：麦克风 + 录音进度
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 28.dp, bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(104.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Mic,
                    contentDescription = null,
                    modifier = Modifier.size(46.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(Modifier.height(20.dp))
            Text(
                text = when (state) {
                    RecState.RECORDING -> "${RECORD_SECONDS - elapsed} 秒"
                    RecState.UPLOADING -> "识别中"
                    else -> ""
                },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(10.dp))
            // 进度条：录音阶段按秒填充；识别中填满（表示等待）
            val fraction = when (state) {
                RecState.RECORDING -> elapsed.toFloat() / RECORD_SECONDS
                RecState.UPLOADING -> 1f
                else -> 0f
            }
            Box(
                Modifier
                    .fillMaxWidth(0.62f)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }

        // 权限缺失提示
        if (!hasPermission) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (permissionDenied) "需要麦克风权限才能听歌识曲，请在系统设置里允许" else "需要麦克风权限",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }) {
                    Text("授予麦克风权限")
                }
            }
        }

        // 识别成功会直接跳进搜索，所以这里不需要结果列表，只要把底部按钮压到底
        Spacer(Modifier.weight(1f))

        // 底部操作
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Button(
                onClick = { startRecognize() },
                modifier = Modifier.fillMaxWidth(),
                enabled = hasPermission && state == RecState.IDLE
            ) {
                Text("再识别一次")
            }
        }
    }
}
