package com.dengdeng.music

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.dengdeng.music.ui.MainScreen
import com.dengdeng.music.ui.MusicViewModel
import com.dengdeng.music.ui.theme.MusicAppTheme

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkAndRequestPermission()
        requestNotificationPermissionIfNeeded()
        setContent {
            MusicAppTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    MainScreen(viewModel = viewModel, hasPermission = hasPermission)
                }
            }
        }
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

    /** 检查并申请媒体读取权限 */
    private fun checkAndRequestPermission() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        val granted = ContextCompat.checkSelfPermission(this, permission) ==
                PackageManager.PERMISSION_GRANTED
        hasPermission = granted
        if (!granted) {
            permissionLauncher.launch(permission)
        } else {
            viewModel.scanMusic()
        }
    }
}
