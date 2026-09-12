package com.dengdeng.music.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.dengdeng.music.R
import com.dengdeng.music.data.DailyRadioFetcher
import com.dengdeng.music.data.MusicRepository
import com.dengdeng.music.data.Song
import com.dengdeng.music.data.UserLibraryStore
import com.dengdeng.music.player.PlaybackService
import com.dengdeng.music.player.PlayerControllerProvider
import com.dengdeng.music.ui.theme.themeDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.net.URL
import kotlin.random.Random

/**
 * 桌面小组件状态中枢
 *
 * 职责：
 * 1. 持有一条与 PlaybackService 的 MediaController 长连接，监听播放状态变化
 * 2. 播放中每 2 秒推一次进度（暂停时停止，省电）
 * 3. 构建 RemoteViews（信息/封面/进度/按钮/像素音浪帧动画）
 *
 * 冷启动（App 未运行）：MediaController 连接会拉起 PlaybackService，
 * 播放键读取 LastPlay（上次歌曲+进度）恢复单曲队列开播。
 */
object WidgetUpdater {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var controller: MediaController? = null
    private var connecting = false
    private var progressJob: Job? = null
    private var songsCache: List<Song>? = null
    private val coverCache = HashMap<Long, Bitmap?>()

    /** 音浪动画方案名（点击小音浪循环切换） */
    val WAVE_STYLES = listOf("跳动柱", "像素阶梯", "点阵波浪", "方波")
    private const val FRAMES_PER_STYLE = 5
    private const val FRAME_MS = 380
    private val waveFrameCache = HashMap<String, Bitmap>()

    // ===== 连接与监听 =====

    fun ensureConnected(context: Context) {
        if (controller != null || connecting) return
        connecting = true
        val appCtx = context.applicationContext
        val future = PlayerControllerProvider.createController(appCtx)
        future.addListener({
            connecting = false
            controller = runCatching { future.get() }.getOrNull()
            controller?.addListener(object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = pushUpdate(appCtx)
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    startProgressTicker(appCtx)
                    pushUpdate(appCtx)
                }
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = pushUpdate(appCtx)
                override fun onPlaybackStateChanged(playbackState: Int) = pushUpdate(appCtx)
                override fun onRepeatModeChanged(repeatMode: Int) = pushUpdate(appCtx)
                override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = pushUpdate(appCtx)
            })
            startProgressTicker(appCtx)
            pushUpdate(appCtx)
        }) { it.run() }
    }

    private fun startProgressTicker(context: Context) {
        val appCtx = context.applicationContext
        val playing = controller?.isPlaying == true
        if (!playing && progressJob?.isActive == true) return  // 暂停：让现有 ticker 自然停
        if (progressJob?.isActive == true) return
        progressJob = scope.launch {
            while (isActive) {
                val c = controller ?: break
                if (c.isPlaying) pushUpdate(appCtx)
                else break
                delay(2000)
            }
            progressJob = null
        }
    }

    /** 推送一次小组件刷新（任意线程可调；按每个实例的实际尺寸选择布局） */
    fun pushUpdate(context: Context) {
        val appCtx = context.applicationContext
        scope.launch {
            val snapshot = collectSnapshot(appCtx)
            val mgr = AppWidgetManager.getInstance(appCtx)
            val ids = mgr.getAppWidgetIds(ComponentName(appCtx, WidgetProvider::class.java))
            if (ids != null && ids.isNotEmpty()) {
                for (id in ids) {
                    val rv = buildRemoteViews(appCtx, snapshot, isBigWidget(mgr, id))
                    mgr.updateAppWidget(id, rv)
                }
            }
            // 封面异步加载：加载完再推一次
            val song = snapshot.song
            if (song != null && !coverCache.containsKey(song.id)) {
                val bmp = withContext(Dispatchers.IO) { loadCover(appCtx, song) }
                coverCache[song.id] = bmp
                if (currentSongId() == song.id) {
                    for (id in ids ?: IntArray(0)) {
                        val rv2 = buildRemoteViews(appCtx, collectSnapshot(appCtx), isBigWidget(mgr, id))
                        mgr.updateAppWidget(id, rv2)
                    }
                }
            }
        }
    }

    /** 实例宽度 ≥320dp 视为"拉伸大尺寸"，用加大版布局 */
    private fun isBigWidget(mgr: AppWidgetManager, id: Int): Boolean {
        val w = runCatching {
            mgr.getAppWidgetOptions(id)?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) ?: 0
        }.getOrDefault(0)
        return w >= 320
    }

    private fun currentSongId(): Long? = controller?.currentMediaItem?.mediaId?.toLongOrNull()

    // ===== 状态快照 =====

    private data class Snapshot(
        val song: Song?,
        val title: String,
        val artist: String,
        val positionMs: Long,
        val durationMs: Long,
        val isPlaying: Boolean,
        val shuffle: Boolean,
        val repeatMode: Int,
        val favorite: Boolean,
        val themeColor: Int,
        val waveStyle: Int
    )

    private suspend fun collectSnapshot(ctx: Context): Snapshot {
        val c = controller
        return if (c != null && c.currentMediaItem != null) {
            val id = c.currentMediaItem?.mediaId?.toLongOrNull()
            val song = id?.let { getSongs(ctx).find { s -> s.id == it } }
            Snapshot(
                song = song,
                title = c.mediaMetadata.title?.toString() ?: "未知歌曲",
                artist = c.mediaMetadata.artist?.toString() ?: "",
                positionMs = c.currentPosition.coerceAtLeast(0),
                durationMs = c.duration.coerceAtLeast(0),
                isPlaying = c.isPlaying,
                shuffle = c.shuffleModeEnabled,
                repeatMode = c.repeatMode,
                favorite = id != null && runCatching {
                    UserLibraryStore.favoriteIdsFlow(ctx).first().contains(id)
                }.getOrDefault(false),
                themeColor = readThemeColor(ctx),
                waveStyle = readWaveStyle(ctx)
            )
        } else {
            // 冷启动：显示上次播放的歌曲（暂停态）
            val last = runCatching { UserLibraryStore.getLastPlay(ctx) }.getOrNull()
            val song = last?.songId?.let { sid -> getSongs(ctx).find { it.id == sid } }
            Snapshot(
                song = song,
                title = song?.title ?: "DDmusic",
                artist = song?.artist ?: "点 ▶ 立刻开播",
                positionMs = last?.positionMs ?: 0L,
                durationMs = song?.durationMs ?: 0L,
                isPlaying = false,
                shuffle = runCatching { UserLibraryStore.getShuffle(ctx) }.getOrDefault(false),
                repeatMode = runCatching { UserLibraryStore.getRepeatMode(ctx) }.getOrDefault(Player.REPEAT_MODE_OFF),
                favorite = song != null && runCatching {
                    UserLibraryStore.favoriteIdsFlow(ctx).first().contains(song.id)
                }.getOrDefault(false),
                themeColor = readThemeColor(ctx),
                waveStyle = readWaveStyle(ctx)
            )
        }
    }

    private suspend fun readThemeColor(ctx: Context): Int {
        return try {
            val hex = ctx.themeDataStore.data.first()[stringPreferencesKey("theme_color")] ?: ""
            if (hex.isBlank()) 0xFF6750A4.toInt()
            else Color.parseColor("#$hex")
        } catch (_: Exception) {
            0xFF6750A4.toInt()
        }
    }

    private fun readWaveStyle(ctx: Context): Int =
        ctx.getSharedPreferences("widget_wave", Context.MODE_PRIVATE)
            .getInt("style", 0).coerceIn(0, WAVE_STYLES.size - 1)

    private fun getSongs(ctx: Context): List<Song> {
        songsCache?.let { return it }
        return runBlocking(Dispatchers.IO) {
            runCatching { MusicRepository.scanSongs(ctx) }.getOrDefault(emptyList())
        }.also { songsCache = it }
    }

    // ===== RemoteViews 构建 =====

    private fun buildRemoteViews(ctx: Context, s: Snapshot, big: Boolean): RemoteViews {
        val rv = RemoteViews(
            ctx.packageName,
            if (big) R.layout.widget_4x2_big else R.layout.widget_4x2
        )
        val accent = s.themeColor

        rv.setTextViewText(R.id.title, s.title)
        rv.setTextViewText(
            R.id.artist,
            s.artist.ifBlank { if (s.isPlaying || s.song != null) "" else "点 ▶ 立刻开播" }
        )

        // 封面（点击进播放页）
        val cover = s.song?.let { coverCache[it.id] }
        if (cover != null) {
            rv.setImageViewBitmap(R.id.cover, cover)
        } else {
            rv.setImageViewResource(R.id.cover, R.drawable.w_play)
            rv.setInt(R.id.cover, "setColorFilter", accent)
        }
        rv.setOnClickPendingIntent(R.id.cover_frame, WidgetProvider.openAppPending(ctx))

        // 进度（点哪跳哪：10 段）
        val dur = s.durationMs.coerceAtLeast(1)
        val pos = s.positionMs.coerceIn(0, dur)
        rv.setInt(R.id.progress, "setMax", 1000)
        rv.setInt(R.id.progress, "setProgress", ((pos * 1000f) / dur).toInt())
        rv.setTextViewText(R.id.t_cur, formatMs(pos))
        rv.setTextViewText(R.id.t_max, formatMs(dur))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            rv.setColorStateList(
                R.id.progress, "setProgressTintList",
                android.content.res.ColorStateList.valueOf(accent)
            )
        }
        for (i in 0 until 10) {
            val fraction = (i + 0.5f) / 10f
            rv.setOnClickPendingIntent(
                ctx.seekViewId(i),
                WidgetProvider.commandPending(ctx, WidgetProvider.CMD_SEEK, "fraction" to fraction)
            )
        }

        // 播放/暂停
        rv.setImageViewResource(R.id.btn_play, if (s.isPlaying) R.drawable.w_pause else R.drawable.w_play)
        rv.setOnClickPendingIntent(R.id.btn_play, WidgetProvider.commandPending(ctx, WidgetProvider.CMD_PLAYPAUSE))
        rv.setOnClickPendingIntent(R.id.btn_prev, WidgetProvider.commandPending(ctx, WidgetProvider.CMD_PREV))
        rv.setOnClickPendingIntent(R.id.btn_next, WidgetProvider.commandPending(ctx, WidgetProvider.CMD_NEXT))
        rv.setOnClickPendingIntent(R.id.btn_mode, WidgetProvider.commandPending(ctx, WidgetProvider.CMD_MODE))
        rv.setOnClickPendingIntent(R.id.btn_radio, WidgetProvider.commandPending(ctx, WidgetProvider.CMD_RADIO))

        // 模式图标
        val modeIcon = when {
            s.shuffle -> R.drawable.w_mode_shuffle
            s.repeatMode == Player.REPEAT_MODE_ONE -> R.drawable.w_mode_one
            else -> R.drawable.w_mode_seq
        }
        rv.setImageViewResource(R.id.btn_mode, modeIcon)

        // 图标着色（跟随主题色）
        listOf(R.id.btn_prev, R.id.btn_play, R.id.btn_next, R.id.btn_mode, R.id.btn_radio).forEach {
            rv.setInt(it, "setColorFilter", accent)
        }

        // 喜欢
        rv.setImageViewResource(R.id.btn_like, if (s.favorite) R.drawable.w_heart else R.drawable.w_heart_off)
        rv.setInt(
            R.id.btn_like, "setColorFilter",
            if (s.favorite) 0xFFEFB8C8.toInt() else Color.WHITE
        )
        rv.setOnClickPendingIntent(R.id.btn_like, WidgetProvider.commandPending(ctx, WidgetProvider.CMD_LIKE))

        // 小音浪：帧动画（暂停时静止第一帧）；大版位图基准更大
        buildWave(ctx, rv, s.waveStyle, accent, s.isPlaying, big)
        rv.setOnClickPendingIntent(R.id.wave_flipper, WidgetProvider.commandPending(ctx, WidgetProvider.CMD_WAVE))

        return rv
    }

    /** 生成 N 帧位图塞进 ViewFlipper */
    private fun buildWave(ctx: Context, rv: RemoteViews, style: Int, accent: Int, playing: Boolean, big: Boolean) {
        rv.removeAllViews(R.id.wave_flipper)
        val wPx = if (big) 400 else 220   // 位图基准宽（fitStart 缩放）
        val hPx = if (big) 60 else 44
        for (f in 0 until FRAMES_PER_STYLE) {
            val bmp = waveFrame(style, f, accent, wPx, hPx)
            val frame = RemoteViews(ctx.packageName, R.layout.widget_wave_frame)
            frame.setImageViewBitmap(R.id.wave_frame_img, bmp)
            rv.addView(R.id.wave_flipper, frame)
        }
        rv.setBoolean(R.id.wave_flipper, "setAutoStart", playing)
        rv.setInt(R.id.wave_flipper, "setDisplayedChild", 0)
    }

    /** 像素音浪帧：4 种方案 × 5 帧，方块块拼出"跳动感" */
    private fun waveFrame(style: Int, frame: Int, color: Int, wPx: Int, hPx: Int): Bitmap {
        val key = "$style-$frame-$color-$wPx-$hPx"
        waveFrameCache[key]?.let { return it }
        val bmp = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        val cell = hPx / 4f
        val cols = (wPx / (cell * 1.5f)).toInt().coerceIn(6, 30)
        val rnd = Random(frame * 131 + style * 977)

        when (style) {
            0 -> { // 跳动柱：随机高度的方块柱（每帧高度不同）
                for (c in 0 until cols) {
                    val h = (1 + rnd.nextInt(4)) * cell
                    val x = c * cell * 1.5f
                    canvas.drawRect(x, hPx - h, x + cell, hPx.toFloat(), paint)
                }
            }
            1 -> { // 像素阶梯：高度随列递增，帧偏移产生流动感
                for (c in 0 until cols) {
                    val h = ((c + frame * 2) % 4 + 1) * cell
                    val x = c * cell * 1.5f
                    canvas.drawRect(x, hPx - h, x + cell, hPx.toFloat(), paint)
                }
            }
            2 -> { // 点阵波浪：正弦波上的单像素点
                for (c in 0 until cols) {
                    val phase = (c * 0.9 + frame * 0.8).toFloat()
                    val y = (hPx / 2f + Math.sin(phase.toDouble()).toFloat() * hPx * 0.32f) - cell / 2
                    val x = c * cell * 1.5f
                    canvas.drawRect(x, y, x + cell, y + cell, paint)
                }
            }
            else -> { // 方波：高低电平方块，帧间相位翻转
                for (c in 0 until cols) {
                    val on = ((c + frame) % 2 == 0)
                    val h = if (on) cell * 3f else cell * 1f
                    val x = c * cell * 1.5f
                    canvas.drawRect(x, hPx - h, x + cell, hPx.toFloat(), paint)
                }
            }
        }
        waveFrameCache[key] = bmp
        return bmp
    }

    private fun formatMs(ms: Long): String {
        val total = ms / 1000
        return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
    }

    // ===== 封面加载 =====

    private fun loadCover(ctx: Context, song: Song): Bitmap? = runCatching {
        val uri = song.albumArtUri ?: return@runCatching null
        val bytes = if (uri.scheme == "http" || uri.scheme == "https") {
            URL(uri.toString()).openConnection().apply {
                connectTimeout = 6000
                readTimeout = 6000
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }.getInputStream().use { it.readBytes() }
        } else {
            ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } ?: return@runCatching null
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        val sample = maxOf(1, minOf(opts.outWidth, opts.outHeight) / 256)
        BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample }
        )
    }.getOrNull()

    // ===== 命令执行（WidgetProvider 调用） =====

    fun handleCommand(context: Context, cmd: String, fraction: Float) {
        val appCtx = context.applicationContext
        scope.launch {
            val c = controller
                ?: run {
                    ensureConnected(appCtx)
                    delay(1200)  // 等连接建立（一般 <500ms）
                    controller
                }
            if (c == null) {
                // 连不上播放服务 → 直接打开软件兜底
                if (cmd == WidgetProvider.CMD_PLAYPAUSE) openApp(appCtx)
                return@launch
            }
            withContext(Dispatchers.Main) {
                when (cmd) {
                    WidgetProvider.CMD_PLAYPAUSE -> {
                        if (c.currentMediaItem != null) {
                            if (c.isPlaying) c.pause() else c.play()
                        } else {
                            // 冷启动（服务刚起、队列空）→ 直接打开软件，由 App 自动恢复上次播放
                            openApp(appCtx)
                        }
                    }
                    WidgetProvider.CMD_NEXT -> if (c.hasNextMediaItem()) c.seekToNextMediaItem()
                    WidgetProvider.CMD_PREV -> if (c.hasPreviousMediaItem()) c.seekToPreviousMediaItem()
                    WidgetProvider.CMD_MODE -> cycleMode(appCtx, c)
                    WidgetProvider.CMD_RADIO -> startRadio(appCtx, c)
                    WidgetProvider.CMD_LIKE -> toggleLike(appCtx, c)
                    WidgetProvider.CMD_WAVE -> cycleWave(appCtx)
                    WidgetProvider.CMD_SEEK -> {
                        val dur = c.duration
                        if (dur > 0) c.seekTo((dur * fraction).toLong().coerceIn(0, dur))
                    }
                }
            }
            delay(150)
            pushUpdate(appCtx)
        }
    }

    /** 后台打开 App（App 内会自动恢复上次播放状态） */
    private fun openApp(ctx: Context) {
        val intent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        } ?: return
        runCatching { ctx.startActivity(intent) }
    }

    /**
     * 后台每日电台：与 App 内"每日电台（探索版）"完全同源
     * （data/DailyRadioFetcher：多榜单混合 + 常听歌手相似 + 随机歌单，并发解析只留可播）
     */
    private suspend fun startRadio(ctx: Context, c: MediaController) {
        val pool = withContext(Dispatchers.IO) { getSongs(ctx) }
        val candidates = DailyRadioFetcher.fetchCandidates(
            DailyRadioFetcher.topArtistsFromHistory(ctx, pool, 3),
            DailyRadioFetcher.listenedKeysFromSongs(pool)
        )
        val queue = withContext(Dispatchers.IO) {
            DailyRadioFetcher.resolveToQueue(ctx, candidates)
        }
        if (queue.isEmpty()) {
            android.widget.Toast.makeText(ctx, "电台生成失败，请检查网络后重试", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        android.widget.Toast.makeText(ctx, "每日电台 · ${queue.size} 首，后台开播", android.widget.Toast.LENGTH_SHORT).show()
        c.setMediaItems(PlaybackService.buildMediaItems(queue.map { it.toSongInfo() }))
        c.prepare()
        c.play()
    }

    /** 顺序 → 单曲 → 乱序 → 顺序（同步写回记忆） */
    private suspend fun cycleMode(ctx: Context, c: MediaController) {
        val state = when {
            c.shuffleModeEnabled -> 2
            c.repeatMode == Player.REPEAT_MODE_ONE -> 1
            else -> 0
        }
        when ((state + 1) % 3) {
            0 -> { c.shuffleModeEnabled = false; c.repeatMode = Player.REPEAT_MODE_OFF }
            1 -> { c.shuffleModeEnabled = false; c.repeatMode = Player.REPEAT_MODE_ONE }
            else -> { c.shuffleModeEnabled = true; c.repeatMode = Player.REPEAT_MODE_OFF }
        }
        runCatching {
            UserLibraryStore.saveRepeatMode(ctx, c.repeatMode)
            UserLibraryStore.saveShuffle(ctx, c.shuffleModeEnabled)
        }
    }

    private suspend fun toggleLike(ctx: Context, c: MediaController) {
        val id = c.currentMediaItem?.mediaId?.toLongOrNull() ?: return
        val liked = runCatching { UserLibraryStore.toggleFavorite(ctx, id) }.getOrDefault(false)
        android.widget.Toast.makeText(
            ctx, if (liked) "已喜欢 ♡" else "已取消喜欢", android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    private fun cycleWave(ctx: Context) {
        val prefs = ctx.getSharedPreferences("widget_wave", Context.MODE_PRIVATE)
        val next = (prefs.getInt("style", 0) + 1) % WAVE_STYLES.size
        prefs.edit().putInt("style", next).apply()
        android.widget.Toast.makeText(
            ctx, "音浪：${WAVE_STYLES[next]}", android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    private fun Song.toSongInfo() = PlaybackService.SongInfo(
        id = id, title = title, artist = artist, album = album,
        uri = uri.toString(), albumArtUri = albumArtUri?.toString(), durationMs = durationMs
    )
}

/** seek 分段 View 的 id（见 widget_4x2.xml） */
fun Context.seekViewId(i: Int): Int {
    val name = "seek$i"
    return resources.getIdentifier(name, "id", packageName).takeIf { it != 0 }
        ?: R.id.seek0
}
