// 顶层构建文件
plugins {
    // AGP 8.9.1 官方支持 compileSdk 36（Android 16），修复旧版强压编译导致的闪退
    id("com.android.application") version "8.9.1" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
