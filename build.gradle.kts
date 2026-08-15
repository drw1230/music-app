// 顶层构建文件
plugins {
    // AGP 9.0 官方支持 compileSdk 36（Android 16），配 Gradle 9.5（native-platform 新版修复本机 file-events DLL 崩溃）
    // 注意：AGP 9 内置 Kotlin 支持，不再需要单独应用 kotlin-android 插件
    id("com.android.application") version "9.0.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20" apply false
}
