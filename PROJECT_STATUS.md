# DDmusic 音乐 App — 项目状态汇总（2026-08-16 15:15）

> 给新会话/新窗口的快速上手文档。完整经验见 `.workbuddy/memory/MEMORY.md`（自动注入）。

## 一、项目总览

- **应用**：DDmusic —— 本地音乐 + 在线试听/下载的安卓音乐播放器
- **代码**：`D:\dev\music-app`（Git 仓库，master 分支，当前 `fda59d5`）
- **远程**：`https://github.com/drw1230/music-app`（公开仓库，GitHub Actions 云端构建）
- **包名**：`com.dengdeng.music`（minSdk 26 / targetSdk 35 / compileSdk 35，debug 签名）
- **核心功能**：
  - 本地曲库扫描（MediaStore）+ 播放（Media3 ExoPlayer + MediaSessionService 后台播放）
  - 播放页：旋转封面/歌词滚动跟随+微调/上下滑切歌/均衡器（Equalizer+BassBoost+Virtualizer）
  - 在线能力：搜索（网易云+QQ+酷狗 3 源聚合音源）、试听（曲库底部迷你条统一控制）、下载入库、每日电台（双榜 40 首 + 负反馈过滤 + 播完自动刷新）、歌词/封面多源联网获取
  - 记忆：收藏（喜欢）/歌单/搜索历史/播放历史/循环乱序/上次播放（重启恢复但保持暂停）/跳过歌 全部持久化
- **技术栈**：Kotlin + Jetpack Compose + Media3 + DataStore + Coil + AGP 8.9.1 + Gradle 8.11.1 + JDK 21（本机）/ 17（CI）

## 二、开发历史（git 主要里程碑）

| 版本 | 提交 | 内容 |
|---|---|---|
| v0.1.0 | 44f63a7 | 骨架：本地扫描 + 播放 |
| v0.1.x | 816ff4a→b772268 | 后台播放/全屏页/队列/深色主题/封面闪退修复/美化 |
| v0.2.0 | 3164f6f | 改名 DDmusic + 红黑主题图标 + 乱序播放 + 曲库头部卡片 |
| v0.3.0 | 42d3a5c | 收藏持久化 + 我的歌单 + 迷你条拖进度 + 本地搜索 |
| v0.3.1 | c933891/c0eef6c | 喜欢/长按菜单/从列表移除/删除（createDeleteRequest） |
| v0.3.2 | 6941313 | 更多菜单 + 工具链升级 |
| v3-1 | 105666c | 在线试听/批量下载/歌词微调/编辑标签 |
| v3-2 | 2f1983d | 在线试听改迷你条 + 移除批量下载 + 均衡器 |
| v3-3 | 0fbfe7e | 每日电台（双榜+负反馈+在线队列） |
| v3-3b | c97c09e | 电台入口/自动播放/刷新；电台点歌进全屏；播放页在线歌支持 + 下载 |
| v3-3c | ac782ad | 搜索音源行"试听"按钮；OnlineSourceSheet 重构；**修复播放页下载卡死** |
| v3-3d | 19bbf45 | **①音源查询并行化+缓存5分钟+超时6s（下载弹窗不再卡）②ExoPlayer 加 UA+跨协议重定向（在线流不再403缓冲）③电台播完自动刷新继续播（radioMode/radioQueueEnded）④重启恢复歌曲+进度但保持暂停 ⑤搜索历史默认一排+'历史 N'展开** |
| v3-3e | 638c676 | 在线试听统一迷你条（不进全屏）+ 搜索历史点击搜索框才显示 |
| v3-3f | 2110072 | 迷你条点击（本地/在线）均可进全屏播放界面 |
| CI | 071007d→fda59d5 | GitHub Actions 云端构建 + 签名统一（keystore 入仓库） |

## 三、当前进度（2026-08-16）

- ✅ **功能全部完成**（最近一轮 2110072：在线试听统一迷你条 + 点迷你条进全屏）
- ✅ **CI 云端构建已跑通**（仓库内 `app/debug.keystore` 保证签名与手机一致，可覆盖安装保留数据）
- ⏳ **最后一轮构建**（`be36c3a`，含 keystore 入仓库签名方案）等待完成 → 下载最新 `music-app-debug-apk` → adb 覆盖安装 → 验证
- ⚠️ **手机现状**：装的是 12:15 本机构建的 638c676（试听统一迷你条 + 搜索历史聚焦，但**在线歌点迷你条不进全屏**）

## 四、构建环境（重要！本机已改云端）

- **本机 Gradle 构建 12:06 起彻底异常**：`Failed to load native library 'native-platform.dll'`（Gradle 写 .lock 被系统"拒绝访问"），重启×4/坚果云全停/Defender 关闭均无效 → **改用 GitHub Actions 云端构建**
- **以后构建流程**：改代码 → git push → Actions 自动构建（约 6-10 分钟）→ 下载 artifact → adb 安装
- 本机 E:\gradle-fresh 保留（依赖完整 2.3G），gradle.properties 已加 `org.gradle.vfs.watch=false`；若本机恢复可继续用（详见 MEMORY v5）
- 手机 adb 连接正常：`R5CWC08ZBET`

## 五、关键路径

- 项目：`D:\dev\music-app`
- 构建产物（本机旧）：`D:\dev\music-app\app\build\outputs\apk\debug\app-debug.apk`
- CI 产物下载：`https://github.com/drw1230/music-app/actions` → Artifacts
- 工作区日志：`.workbuddy/memory/2026-08-16.md`
- 排障工具：`E:\tmp\NPInit6.java`（Native.init 复现）、`E:\tmp\LockTest3.java`（Java rw 测试）

## 六、待办 / 提醒

1. 等待 CI 最后一轮构建 → 下载 → 覆盖安装 → 用户验证（迷你条点在线歌进全屏等）
2. GitHub token 已在聊天暴露，建议用户撤销重建（`repo` + `workflow` 两个 scope）
3. 仓库当前**公开**——若不想公开源码，改 Private
4. 用户反馈后如有新需求 → 直接改代码 push → CI 出包
