import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.dengdeng.music"
    // AGP 8.7.3 官方支持的最大 compileSdk 是 35（36 需 AGP 8.9+，之前用 suppress 强压会导致部分设备闪退）
    compileSdk = 35
    // 显式指定本地已安装的 build-tools 版本，避免 AGP 联网下载默认版本
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.dengdeng.music"
        minSdk = 26
        targetSdk = 35
        versionCode = 12
        versionName = "1.3.0"

        // 腾讯云 COS 密钥（联网排行榜）：构建时注入，不进仓库（GitHub Push Protection 要求）。
        // 本机：local.properties 或 secrets.properties（两者都被 .gitignore 排除；后者不
        // 在 build.sh 的 rsync 排除清单里，WSL 构建也能拿到）。CI：GitHub Actions secrets。
        val secrets = Properties().apply {
            for (f in listOf(rootProject.file("local.properties"), rootProject.file("secrets.properties"))) {
                if (f.exists()) f.inputStream().use { load(it) }
            }
        }
        buildConfigField("String", "COS_SECRET_ID", "\"${secrets.getProperty("COS_SECRET_ID") ?: ""}\"")
        buildConfigField("String", "COS_SECRET_KEY", "\"${secrets.getProperty("COS_SECRET_KEY") ?: ""}\"")
    }

    buildTypes {
        release {
            // R8 混淆 + 资源裁剪：release 包更小、冷启动更快（debug 不受影响）
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // debug 使用仓库内置 keystore（本机与 CI 签名一致，可覆盖安装不丢数据）
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    signingConfigs {
        // AGP 内置已有名为 "debug" 的 SigningConfig，直接配置其属性（create 会重名冲突导致 CI 构建失败）
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }


    buildFeatures {
        compose = true
        buildConfig = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Compose BOM 统一管理版本
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    // SplashScreen API：让系统启动屏在首帧后立即消失，避免用户看到圆角图标的"旧加载界面"
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Media3 播放器（谷歌官方，本地播放核心）
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")

    // Coil 图片加载（显示专辑封面）
    implementation("io.coil-kt:coil-compose:2.7.0")

    // DataStore 轻量持久化（收藏、歌单）
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // 生命周期
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    // 测试
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
