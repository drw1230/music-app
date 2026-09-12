# DDmusic 音乐 App — 项目状态与开发档案（v1.1.0）

> 给新会话/新窗口的快速上手文档。完整排障史见旧工作区 `...\obsidian\agent\work buddy\2026-08-14-21-06-06\`（MEMORY.md v2-v5 + 每日日志 + 决策文档）。
> 当日工作日志见当前工作区 `.workbuddy/memory/2026-08-16.md`。

## 一、项目总览

- **应用**：DDmusic —— 本地音乐 + 在线试听/下载的安卓音乐播放器（**v1.1.0**）
- **代码**：`D:\dev\music-app`（Git 仓库，master 分支，v1.0.0 = `86d6a5b`）
- **远程**：`https://github.com/drw1230/music-app`（Private 仓库，SSH 免密 push）
- **包名**：`com.dengdeng.music`（minSdk 26 / targetSdk 35 / compileSdk 35，debug 签名 keystore 入仓库）
- **版本**：versionCode 7 / versionName 1.1.0
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
| **v1.0.4** | **830a44b** | 播放页小歌词（白色细瘦字体、紧贴封面下沿）；移除私自添加的深色半透明底板（用户否决，已删除） |
| **v1.1.0** | 本次提交 | **音源可播性修复（朋友反馈"搜索结果有些无法播放"）**：①网易云请求加匿名 cookie（`os=pc; appver=2.10.6; MUSIC_A=随机`）→ VIP/付费歌从 `code=-110` 变为可解析（实测 6/6）；②新增跨平台兜底 `kugouFallbackUrl()`——主源解析失败时按「歌名+歌手」去酷狗搜索取流（覆盖 QQ 源歌曲）；③`fetchAudioSources()` 酷狗一路改为复用 `searchKugouByTitle()`（带缓存）；④**搜索页新增「修复音源」小按钮**（顶部栏右侧）——清空在线搜索/音源聚合/URL 缓存后重新搜索，修复"曾解析失败被缓存住"的歌。⑤**播放页控制栏新增「＋」按钮**——弹出"添加到歌单"（选已有歌单直接添加 / 「新建歌单」内联输入后创建并顺手加入；在线歌暂提示不支持）。⑥**曲库页 ⋮ 菜单新增「软件升级」**——App 内 WebView 打开蓝奏云分享页手动下载升级包（下载走系统 DownloadManager，通知栏点开安装；`APK_DOWNLOAD_URL` 常量待填）。实测底数：酷狗 8/8、网易云 3/8、QQ 0/8（QQ 需真实登录态，搁置）。⑦**升级下载文件名修复**——蓝奏云 CDN 直链返回 32 位 hash + `.bin` 文件名，改为从网页 DOM 抓真实文件名 + 四级兜底（DOM > Content-Disposition > URL > 回退）+ 强制 `.apk` 后缀。⑧**曲库页 ⋮ 菜单新增「听歌识曲」**——进入即自动录音（麦克风权限内聚申请）→ 44.1kHz WAV → AudD 免注册匿名接口识别 → 结果以列表排列，**点击某条结果带歌名联动进入联网搜索界面**（`data/MusicRecognizer.kt` + `ui/RecognizeScreen.kt`）。⑨**听歌识曲引擎升级为「AHA/ACRCloud 免账号识曲接口」**（原先 AudD 匿名只能拿到英文元数据 → 中文歌搜不到）：原生录音（44.1kHz WAV，**关键是没有 WebView 那套 WebRTC 回声消除**）→ `POST https://aha-music.com/identify`（从网页前端逆向得到：body = JSON `{"audio": base64(音频字节 + 8个空格盐), "mimeType":"audio/wav"}`，**必须带 `Accept: application/json`**，否则返回网页 HTML；用 multipart 会 400 Missing audio data）→ 解析 `data.title / artists / duration_ms / external_metadata.deezer.artists[].langs`（**中文名唯一来源**）→ **拼「中文歌手名 + 标题」作搜索词** → 识别成功**直接跳联网搜索**（不再落地结果列表）。实测：手机放陈楚生 → 自动跳搜索 → 第 1 条即《有没有人告诉你》(5:44)。⑩**移除网页版「在线识曲」入口并彻底删除实现**（WebView 录音被 WebRTC 回声消除把音乐消掉 → ACRCloud `2004 Can't generate fingerprint`，不可靠）：2026-09-12 已删除 `ui/OnlineRecognizeScreen.kt`，同时移除只为它加的 `MODIFY_AUDIO_SETTINGS` 权限（`RECORD_AUDIO` 保留给原生识曲）。⑪**「软件升级」改为二级菜单选下载源**：⋮ → 软件升级 → AlertDialog 二选一（`UpgradeSourceRow` 行样式：名称 + 说明 + 右箭头）——①**蓝奏云网盘**（`UPGRADE_URL_LANZOU`，密码 1234，走 App 内 WebView `UpgradeScreen(url)`）②**GitHub Releases**（**改为 API 直连，不打开网页**）。⑫**GitHub 升级改走 API 直连下载**（新增 `data/GitHubUpdater.kt` + `ui/GitHubUpdateDialog.kt`）：点 GitHub → 弹窗自动查 `GET https://api.github.com/repos/drw1230/music-app/releases/latest` → 显示版本号/发布日期/APK 名与大小 → 点「下载」用系统 DownloadManager 下载到「下载」目录（关键：资产用 **API 资产端点** `releases/assets/{id}` + 请求头 **`Accept: application/octet-stream`**，否则只拿到 JSON 元数据）→ 通知栏点开安装。**为什么不用网页**（2026-09-12 手机端实测，中国移动 5G · 重庆）：`github.com` 主站 **3/3 次超时（10s）**、网页与 `browser_download_url` 均 0 字节；而 `api.github.com` 200/0.57s、`objects.githubusercontent.com` 可达、`codeload`/`raw.githubusercontent.com` 均可达——**只有 github.com 一个域名被阻断**。同一份 15.3MB 资产：API 端点 **7.3s（2.09 MB/s）**，网页直链 30s 超时 0 字节。⑬**CI 构建成功后自动发 GitHub Release**（`.github/workflows/build.yml`）：加 `permissions: contents: write` + 从 `app/build.gradle.kts` 读 `versionName` 生成 tag（`v1.1.0`）+ `softprops/action-gh-release@v2` 发布，附件 `DDmusic-vX.Y.Z.apk`，`make_latest: true`（同版本重复构建 = 覆盖同一 Release） |

## 四、构建环境（重要！WSL2 容器构建）

- **本机 Gradle 构建永久放弃**（native-platform.dll.lock 拒绝访问，不可逆）→ **一律 WSL2 容器构建**
- **一键命令（Windows Git Bash）**：`bash build.bat`（仅构建）或 `bash build.bat install`（构建+adb 装手机）
  - 原理：WSL2 Ubuntu-2404 内构建（/opt/music-app + /root/.gradle 在 ext4），rsync 同步源码，APK 拷回 `app\build\outputs\apk\debug\`
  - 性能：**增量构建 3-8 秒**；工具链 JDK21/SDK/Gradle 8.11.1 全在 WSL 内
- **云端 CI 保留为兜底**：`git push`（SSH）→ Actions → 下载 artifact → adb 安装
- **git remote（SSH 免密）**：`git@github.com:drw1230/music-app.git`（~/.ssh/id_ed25519_github + config 走 ssh.github.com:443）
- 手机 adb：`R5CWC08ZBET`；Windows 侧 adb 路径 `C:\dev\android\sdkroot\platform-tools\adb.exe`（v37.0.1，2026-09-12 重新下载安装——`C:\dev\android\` 下 JDK/SDK 其余部分已不存在，仅 platform-tools 恢复）

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

1. **v1.1.0 已发版**（2026-09-12）：仓库已转 **Public** → 提交并 push 到 GitHub → CI 自动构建并自动发 Release `v1.1.0`（附件 `DDmusic-v1.1.0.apk`）。**待用户手机验证**：①音源可播性 ②听歌识曲 ③⋮→软件升级二级菜单（蓝奏云 / GitHub 直连下载）
2. **已提交状态**：本轮所有改动已随 v1.1.0 一起 commit + push（`OnlineMetadataFetcher.kt` 音源修复、`PlayerScreen.kt` 小歌词底板移除 + 控制栏＋、`MainScreen.kt` 修复音源/＋歌单/软件升级二级菜单/听歌识曲入口（在线识曲入口已移除）、`OnlineSearchScreen.kt` 修复音源按钮、`MusicViewModel.kt` clearSourceCaches/createPlaylistWithSong、`MusicRecognizer.kt` AHA/ACRCloud 主引擎、`RecognizeScreen.kt` 跳搜索、`build.gradle.kts` versionCode 7 / 1.1.0、`AndroidManifest.xml` REQUEST_INSTALL_PACKAGES + RECORD_AUDIO（已移除 MODIFY_AUDIO_SETTINGS）、`.github/workflows/build.yml` 自动发 Release、新增 `ui/UpgradeScreen.kt` + `data/GitHubUpdater.kt` + `ui/GitHubUpdateDialog.kt`；已删除 `ui/OnlineRecognizeScreen.kt`）
3. **需求清单**：① ~~播放页菜单"加入歌单"~~ ✅ 已完成；② ~~软件升级功能~~ ✅ 已完成（v1.1.0，二级菜单双源）；③ ~~听歌识曲~~ ✅ 已完成并**升级引擎**（v1.1.0：AHA/ACRCloud 免账号，识别后自动跳搜索）；④ ~~在线识曲（网页嵌入）~~ ❌ **已废弃并移除入口**（WebView 录音被回声消除削废，见本清单第 7 条）；⑤ **本地导出/导入备份文件**（收藏+歌单，已定案不含云端）⏳ 待开发；⑥ **调查"副本分身"现象** ⏳ 现象待用户确认
4. **听歌识曲已知限制**：AudD 匿名接口**每天 10 次**且**单次只返回 1 首**结果（UI 用列表累积呈现）；如需提额到 300 次/天，注册 dashboard.audd.io 拿 token 填进 `MusicRecognizer.AUDD_TOKEN` 即可，无需改其它代码
5. **软件升级现状（2026-09-12 定型：二级菜单双源，已可正常使用）**：①蓝奏云（WebView 网页，密码 1234）②GitHub（api.github.com 直连查版本 + 下载）。**前置条件已完成**：(a) 仓库已改 **Public**（2026-09-12 用户操作，实测未登录访问返回 200）；(b) Release 由 CI 自动发布（首次 push 后产生 `v1.1.0`）。经验：私有仓库匿名调 API 会 404，App 内已把该情形映射为中文提示"私有仓库无法匿名访问，请先把仓库改为 Public"
6. **网络实测结论（2026-09-12，手机端中国移动 5G，重要资产）**：**国内手机不能访问 github.com 主站，但 GitHub 的其它域名全部可用**——`github.com` 3/3 次超时；`api.github.com` 200/0.57s；`objects.githubusercontent.com` 可达；`codeload.github.com` 301/0.73s；`raw.githubusercontent.com` 301/0.57s。→ **任何面向国内手机的 GitHub 功能都不要碰 github.com 网页/直链，改走 API**（查版本用 `/releases/latest`，下资产用 `/releases/assets/{id}` + `Accept: application/octet-stream`）。第三方镜像 `gh-proxy.com` 可用但仅 168KB/s（比直连 API 慢 12 倍），`ghfast.top` 完全不通
7. **识曲功能现状（2026-09-12 定型）**：✅ 走**原生录音 + AHA/ACRCloud 免账号接口**，识别成功自动跳搜索，实测命中。**录音只在内存里（`MusicRecognizer.record()` 返回 ByteArray，全程不落盘、上传后即丢弃），所以没有临时录音文件需要清理**。⚠️ 已废弃的网页版路径**代码与权限均已删除**，以下排障经验仅作历史备查：①WebView 网页录音**必须同时声明 `RECORD_AUDIO` + `MODIFY_AUDIO_SETTINGS`**，缺后者 Chromium 会 `W cr_media: Requires MODIFY_AUDIO_SETTINGS and RECORD_AUDIO. No audio device will be available for recording` → 网页 `getUserMedia` 抛 `NotReadableError` → 网页弹"未知错误"。②**WebView 录音路径本质不可靠**：音频走 WebRTC 语音链路（带回声消除 AEC/AGC），会主动消掉手机扬声器正在播的音乐，导致 ACRCloud `2004 Can't generate fingerprint`；而同一场景改用原生 `AudioRecord` 则一次识别成功 → **凡是"录手机自己扬声器里放的声音"，一律用原生录音，不要用 WebView**。③网页内文字不暴露给无障碍树（`uiautomator dump` 只能看到原生 UI），抓网页内部状态只能靠 `WebChromeClient.onConsoleMessage` + 注入 JS 探针。④`getBoundingClientRect()` 判断"在视口内"是假阳性（被遮挡/被祖先裁剪都照样返回可见），必须叠加 `elementFromPoint` 命中测试 + 祖祖先可见性诊断
8. **GitHub token（ghp_0eC5...）曾在会话暴露** → 建议撤销重建（SSH push 不受影响）。**仓库改 Public 需要 PAT**：本机 git remote 走 SSH，GCM 里没有存 github.com 的 HTTPS 凭据（已实测 `git credential fill` 为空），所以 AI 目前无法调 API 改可见性 → 需用户手动改，或提供一枚**最小权限** fine-grained PAT（仅该仓库 + Administration 读写）。公开前已做安全预检：仓库内无 token/密钥字面量，git 历史也无；仅有标准 Android 调试签名（`app/debug.keystore` + `storePassword="android"`，非敏感）
9. 后续可选优化：音频焦点/耳机线控；桌面小组件；App 内「有新版本」主动提示（对比本机 versionCode 与 Release tag）
10. 用户偏好记录（长期）：电台/冷门探索**每次打开要不同**（大池子+随机化+30天记忆过期已实现）；熟悉版**不要本地歌**只要相似曲目；**不做 AI 自动化真机验收**（用户自己验）
