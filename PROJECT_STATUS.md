# DDmusic 音乐 App — 项目状态与开发档案（v1.0.3）

> 给新会话/新窗口的快速上手文档。完整排障史见旧工作区 `...\obsidian\agent\work buddy\2026-08-14-21-06-06\`（MEMORY.md v2-v5 + 每日日志 + 决策文档）。
> 当日工作日志见当前工作区 `.workbuddy/memory/2026-08-16.md`。

## 一、项目总览

- **应用**：DDmusic —— 本地音乐 + 在线试听/下载的安卓音乐播放器（**v1.0.3**）
- **代码**：`D:\dev\music-app`（Git 仓库，master 分支，v1.0.0 = `86d6a5b`）
- **远程**：`https://github.com/drw1230/music-app`（Private 仓库，SSH 免密 push）
- **包名**：`com.dengdeng.music`（minSdk 26 / targetSdk 35 / compileSdk 35，debug 签名 keystore 入仓库）
- **版本**：versionCode 4 / versionName 1.0.3
- **技术栈**：Kotlin + Jetpack Compose + Media3 + DataStore + Coil + AGP 8.9.1 + Gradle 8.11.1 + JDK 21

## 二、功能清单（v1.0.0）

| 模块 | 功能 |
|---|---|
| **本地曲库** | MediaStore 智能扫描；封面/歌词自动获取（CoverStore/LyricParser 持久化）；曲库缓存秒显（启动不重扫转圈） |
| **曲库 tab** | 全部 / 喜欢（本地+在线收藏合并）/ 最近播放（本地+在线按时间倒序）/ 歌单 |
| **播放页（Fly 风格）** | 大封面（点击进大歌词页，系统返回键回播放页）；歌词滚动跟随+点击/拖动控制进度；控制栏（🔁模式/⬇下载/▶≡队列/⋮菜单）；大播放按钮；上下滑切歌/左右滑关闭 |
| **迷你播放条** | 全局共用（曲库/电台/冷门探索）：封面/歌名/🔁循环/⏮/⏯/⏭；点击进全屏；在线歌同样显示上下曲 |
| **在线搜索** | 网易云+QQ+酷狗 3 源聚合；音源弹窗（试听 + 标准/高品/无损下载）；下载自动保存封面+歌词到本地（.lrc 同目录） |
| **每日电台** | 探索版（随机 2→3 榜单 + 常听歌手 + 随机歌单混合）、熟悉版（纯相似曲目：weapi simiSong，不要本地/歌手维度）；进入默认不播放；音源解析过滤（只推荐能解析出 URL 的歌）；播放时 onPlayerError 立即跳 + 5 秒缓冲守卫；电台列表点击 = 整单队列播放（支持上下曲） |
| **智能歌单** | 最常听（本地+在线合并次数统计）、冷门探索（平台冷门歌，非本地/没听过，刷新换一批）；在线歌可收藏进"喜欢" |
| **记忆持久化** | 收藏/歌单/播放历史/在线播放记录/搜索历史/循环模式/上次播放位置/主题/歌词偏移/跳过歌/排序方式（默认最近添加）/电台最近推荐（30 天过期）全部 DataStore 跨重启保留 |
| **其他** | 深色主题、睡眠定时（曲库⋮菜单）、自定义封面、标签编辑、歌词微调、均衡器、R8 优化（release 22MB→2.9MB）、SplashScreen API（系统启动屏瞬间消失 + 品牌图） |

## 三、开发历史（git 主要里程碑）

| 版本 | 提交 | 内容 |
|---|---|---|
| v0.1.0→v0.3.2 | 44f63a7→6941313 | 骨架/后台播放/全屏/队列/主题/收藏/歌单/迷你条/更多菜单 |
| v3-x | 105666c→2110072 | 在线试听/均衡器/每日电台/音源并行化/UA 数据源修复/迷你条进全屏 |
| CI | 071007d→fda59d5 | GitHub Actions 云端构建 + keystore 签名统一 |
| v0.9.x | ff11578 | 曲库缓存秒显 + 启动体验优化（R8/权限延后/SplashScreen API/图标品牌图） |
| **v1.0.0** | **86d6a5b** | **正式版**：智能歌单（最常听/冷门探索）、在线收藏进喜欢、tab 专辑→最近、电台探索版 B+C+D 混合/熟悉版相似曲目（weapi simiSong）、三模块数据源随机化 + 30 天记忆过期、Fly 风格播放页、下载保存封面歌词、定时器移除、关于弹窗可滑动功能说明 |
| **v1.0.1** | **8c87a4b** | **无音乐也能用在线功能**：无权限/空曲库显示 OnlineOnlyView（每日电台/智能歌单/在线搜索三入口），删死代码；小米11U 兼容性验证通过 |
| **v1.0.2** | **e3a486a→7a9c149** | **移除曲库菜单"播放历史与排行"**（功能由"最近"tab 替代）；**排序默认值改为"最近添加"**（getSortMode 默认 3） |
| **v1.0.3** | **482436a→2ca957c** | **修复编辑标签无法保存**（MediaStore 尽力写+内存/队列更新+replaceMediaItem+override 持久化）；**返回键层级**（非全部 tab 先回全部再退出）；APK 在桌面 DDmusic-v1.0.3.apk |

## 四、构建环境（重要！WSL2 容器构建）

- **本机 Gradle 构建永久放弃**（native-platform.dll.lock 拒绝访问，不可逆）→ **一律 WSL2 容器构建**
- **一键命令（Windows Git Bash）**：`bash build.bat`（仅构建）或 `bash build.bat install`（构建+adb 装手机）
  - 原理：WSL2 Ubuntu-2404 内构建（/opt/music-app + /root/.gradle 在 ext4），rsync 同步源码，APK 拷回 `app\build\outputs\apk\debug\`
  - 性能：**增量构建 3-8 秒**；工具链 JDK21/SDK/Gradle 8.11.1 全在 WSL 内
- **云端 CI 保留为兜底**：`git push`（SSH）→ Actions → 下载 artifact → adb 安装
- **git remote（SSH 免密）**：`git@github.com:drw1230/music-app.git`（~/.ssh/id_ed25519_github + config 走 ssh.github.com:443）
- 手机 adb：`R5CWC08ZBET`

## 五、开发流程（下次开发照此执行）

1. **改代码**（`D:\dev\music-app`，Kotlin + Compose）
2. **自查编译性**（引用/import/签名一致性）
3. `bash build.bat install` → **构建+装手机（3-8 秒）** → **交付用户验证**（不做自动化真机验收，除非根因不明）
4. 用户反馈 → 修 → 再装
5. 稳定后 `git add -A && git commit && git push`（SSH 免密）
6. 更新 `.workbuddy/memory/2026-08-16.md` 工作日志 + 本文件（PROJECT_STATUS.md）

## 六、关键路径

- 项目：`D:\dev\music-app`；APK：`app\build\outputs\apk\debug\app-debug.apk`
- 旧工作区（完整排障史）：`...\obsidian\agent\work buddy\2026-08-14-21-06-06\`
- 当前工作区日志：`.workbuddy/memory/2026-08-16.md`；长期约定：`.workbuddy/memory/MEMORY.md`

## 七、待办 / 提醒

1. **v1.0.3 已发布**：APK 桌面 DDmusic-v1.0.3.apk；已装三星
2. GitHub token（ghp_0eC5...）曾在会话暴露 → 建议撤销重建（SSH push 不受影响）
3. 后续可选优化：构建成功自动发 GitHub Release；音频焦点/耳机线控；桌面小组件
4. 用户偏好记录（长期）：电台/冷门探索**每次打开要不同**（大池子+随机化+30天记忆过期已实现）；熟悉版**不要本地歌**只要相似曲目；**不做 AI 自动化真机验收**（用户自己验）
