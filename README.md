# 我的音乐（MusicApp）

个人音乐播放器 —— Kotlin + Jetpack Compose

## 项目定位

本地播放器 + 在线音源下载 + 每日电台（规划中）

## 版本路线

- **v1（当前）**：扫描本地音乐 → 播放列表 → 播放/暂停/切歌 → 通知栏控制
- **v2**：音乐库完整化（艺术家/专辑浏览、标签编辑、在线封面歌词）+ 在线音源下载
- **v3**：每日电台（热歌榜 + 本地统计加权）、智能歌单、均衡器

## 技术栈

| 组件 | 版本 | 说明 |
|------|------|------|
| Kotlin | 2.0.21 | 编程语言 |
| Jetpack Compose | BOM 2024.12.01 | UI 框架 |
| Media3 ExoPlayer | 1.5.1 | 播放引擎 |
| Coil | 2.7.0 | 封面加载 |
| minSdk / targetSdk | 26 / 36 | Android 8.0+ |

## 架构

三层分离：
- **UI 层**（`ui/`）：Compose 界面，只管显示和交互
- **数据层**（`data/`）：MediaStore 扫描音乐
- **播放层**（`player/`）：Media3 播放管理

## 构建环境

国内网络环境，全部走镜像源：

| 组件 | 位置 | 镜像 |
|------|------|------|
| JDK 21 | `C:\dev\android\jdk\jdk-21.0.12+8` | 清华 Adoptium |
| Android SDK | `C:\dev\android\sdkroot` | 腾讯 AndroidSDK |
| Gradle 8.10.2 | `C:\dev\android\gradle\gradle-8.10.2` | 腾讯 gradle |
| Maven 依赖 | 构建时自动 | 腾讯/阿里 Maven |

环境变量：`JAVA_HOME`、`ANDROID_HOME` 已写入用户级。

## 构建命令

```bash
# 构建 Debug APK
./gradlew assembleDebug

# 输出位置
app/build/outputs/apk/debug/app-debug.apk
```

## 注意

- 项目路径必须是纯英文（AGP 要求），所以放在 `C:\dev\music-app` 而不是坚果云目录
