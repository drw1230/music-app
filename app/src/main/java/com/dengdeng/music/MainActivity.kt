package com.dengdeng.music

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.dengdeng.music.ui.MainScreen
import com.dengdeng.music.ui.MusicViewModel
import com.dengdeng.music.ui.theme.MusicAppTheme
import com.dengdeng.music.ui.theme.parseHexColor
import com.dengdeng.music.ui.theme.themeDataStore
import kotlinx.coroutines.launch

// themeDataStore 已移到 ui/theme/ThemePrefs.kt（与桌面小组件共享同一实例）

class MainActivity : ComponentActivity() {

    private val viewModel: MusicViewModel by viewModels()
    private var hasPermission by mutableStateOf(false)

    /** Android 13+ 用 READ_MEDIA_AUDIO，旧版本用 READ_EXTERNAL_STORAGE */
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (granted) viewModel.scanMusic()
    }

    /** Android 13+ 通知权限（通知栏媒体控制需要，拒绝不影响播放） */
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 拒绝则通知栏控制不可见，但不阻塞主流程 */ }

    /** 删除本地音频文件（Android 11+ 系统弹确认框，用户确认后删除） */
    private val deleteSongsLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        // 用户确认删除后，重新扫描曲库
        if (result.resultCode == RESULT_OK) {
            viewModel.scanMusic()
        }
    }

    /** 发起删除请求：传入要删除的音频 Uri 列表 */
    fun requestDeleteSongs(uris: List<Uri>) {
        val pendingIntent = android.provider.MediaStore.createDeleteRequest(contentResolver, uris)
        deleteSongsLauncher.launch(
            androidx.activity.result.IntentSenderRequest.Builder(pendingIntent.intentSender).build()
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // SplashScreen API：让系统启动屏在首帧渲染后立即消失（用户几乎看不到圆角图标的"旧加载界面"）
        val splashScreen = installSplashScreen()
        splashScreen.setKeepOnScreenCondition { false }   // 首帧即关闭
        super.onCreate(savedInstanceState)
        // 仅读取媒体权限状态（不弹框）；弹框延后到主界面显示后，避免启动时多个界面叠加
        hasPermission = checkMediaPermission()
        setContent {
            // 主题模式：0=跟随系统 1=亮色 2=暗色（DataStore 持久化）
            var themeMode by remember { mutableStateOf(0) }
            // 主题色："" = 官方浅紫；非空 = 6 位 HEX（如 1E88E5）
            var themeColor by remember { mutableStateOf("") }
            // 主题色历史（新尝试的在前，不含官方浅紫；初次为 6 个常用色）
            var colorHistory by remember { mutableStateOf(listOf<String>()) }
            val scope = rememberCoroutineScope()
            LaunchedEffect(Unit) {
                themeDataStore.data.collect { prefs ->
                    themeMode = prefs[intPreferencesKey("theme_mode")] ?: 0
                    themeColor = prefs[stringPreferencesKey("theme_color")] ?: ""
                    colorHistory = (prefs[stringPreferencesKey("color_history")] ?: "")
                        .split(',').map { it.trim() }.filter { it.isNotEmpty() }
                }
            }
            // 主界面显示后：已授权 → 自动扫描曲库（恢复"打开即有歌"）；未授权 → 弹权限（授权后自动扫描）
            LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(500)
                requestMediaPermissionIfNeeded()
                kotlinx.coroutines.delay(500)
                requestNotificationPermissionIfNeeded()
            }
            // ===== 主题色彩：点击即生效（无预览/无确定/无回滚） =====
            // 任何选择（色点/历史色/随机色球/合法HEX输入/模式切换）立即落盘并把颜色顶到历史最前
            // 模式：0=跟随系统 1=亮色 2=暗色
            fun applyTheme(mode: Int, colorHex: String) {
                themeMode = mode
                themeColor = colorHex
                if (colorHex.isNotEmpty() && colorHistory.firstOrNull() != colorHex) {
                    colorHistory = (listOf(colorHex) + colorHistory.filter { it != colorHex }).take(24)
                }
                scope.launch {
                    themeDataStore.edit { prefs ->
                        prefs[intPreferencesKey("theme_mode")] = mode
                        prefs[stringPreferencesKey("theme_color")] = colorHex
                        prefs[stringPreferencesKey("color_history")] = colorHistory.joinToString(",")
                    }
                }
            }

            val darkTheme = when (themeMode) {
                1 -> false
                2 -> true
                else -> isSystemInDarkTheme()
            }
            MusicAppTheme(
                darkTheme = darkTheme,
                primaryColor = parseHexColor(themeColor)
            ) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    MainScreen(
                        viewModel = viewModel,
                        hasPermission = hasPermission,
                        onDeleteSongs = { requestDeleteSongs(it) },
                        themeMode = themeMode,
                        themeColorHex = themeColor,
                        colorHistory = colorHistory,
                        onApplyTheme = { mode, color -> applyTheme(mode, color) }
                    )
                }
            }
        }
    }

    /** 检查媒体读取权限（不弹框），返回是否已授权 */
    private fun checkMediaPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(this, permission) ==
                PackageManager.PERMISSION_GRANTED
    }

    /** 已授权则自动扫描曲库；未授权则请求权限（授权后回调自动扫描） */
    private fun requestMediaPermissionIfNeeded() {
        if (checkMediaPermission()) {
            viewModel.scanMusic()
            return
        }
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        permissionLauncher.launch(permission)
    }

    /** Android 13+ 请求通知权限 */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
