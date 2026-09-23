package com.laodao.signage

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.laodao.signage.databinding.ActivityMainBinding
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar

/**
 * 播放主界面。
 *
 * 素材来源有三条路，最后都归成 List<PlaybackItem>，播放引擎只认它：
 *
 *   1. 手机扫码上传 —— files/manual/，优先级最高，永远排在最前面。
 *      传进来的东西立刻能播，不用电脑、不用 U 盘。
 *   2. 远端清单 —— files/signage-remote.json 里配了 playlistUrl 时生效。
 *      起播先用上次缓存的清单，后台去拉新的，拉到就热替换（不打断当前这条）。
 *   3. 本地目录 —— 没配远端、或远端彻底拿不到东西时的兜底。
 *      扫 files/media/，按文件名升序循环，每 15 秒重扫一次，新拷进去的立刻生效。
 *
 * 「该播第几条」这件事全部交给 Sequencer，这里只负责把它落到播放器和界面上。
 * 这样那套游标逻辑能在 JVM 测试台里跑真实断言 —— View 层测不了，逻辑层必须测到。
 *
 * 呼出二维码有两条路，两条都通：触摸屏点一下屏幕，或者按遥控器「确定」键。
 */
@androidx.annotation.OptIn(UnstableApi::class)
class MainActivity : Activity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var player: ExoPlayer
    private val handler = Handler(Looper.getMainLooper())

    /** 播放游标。所有「该播第几条」的判断都在它里面 */
    private val seq = Sequencer()

    /** 不含手机上传项的基础播放列表。手机上传的内容每次组装时拼在最前面 */
    private var baseItems: List<PlaybackItem> = emptyList()

    /** 当前这条是什么时候起播的（给看门狗用） */
    private var startAtMs = 0L

    private var failStreak = 0
    @Volatile private var lastError = ""

    @Volatile private var remoteConfigured = false
    private var refreshIntervalSec = Config.DEFAULT_REFRESH_SEC
    private var syncing = false
    private var firstSyncDone = false
    @Volatile private var lastMessage = ""

    /** 最近一次同步的结果。状态回传要拿它报「这次连上没、清单几项」 */
    @Volatile private var lastSync: RemoteRepository.SyncResult? = null

    private var uploadServer: UploadServer? = null
    private var qrShownUrl: String? = null

    /** 浮层是不是「因为没素材」自动弹出来的。是的话，一有内容就该收掉 */
    private var qrAutoShown = false

    private val imageCache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / Config.IMAGE_CACHE_DIVISOR).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private val advance = Runnable { next() }

    /** 定时去拉一次远端清单 */
    private val refreshTask = Runnable { refreshAsync() }

    /**
     * 兜底定时器。本地模式下定期重扫素材目录；
     * 同时兼作看门狗：当前这条播放时间离谱地长，就说明卡住了，强行跳下一条。
     */
    private val rescanTask = Runnable { onRescan() }

    /** 收起二维码浮层 */
    private val hideQr = Runnable { hideQrNow() }

    /** 手机上传来新东西后，稍等一下再跳到它（一次传多个文件时避免反复跳） */
    private val jumpToManual = Runnable { applyBase(baseItems, jumpToFront = true) }

    /** 触摸屏：识别「轻点一下」，用它切换二维码浮层 */
    private val tapDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            // onDown 必须返回 true，否则后面的手势回调不会来
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                toggleQrByTouch()
                return true
            }
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 屏幕常亮，不进入息屏
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUi()

        // 版本号：装到电视上以后，呼出二维码就能核对装的是哪一版
        binding.qrVersion.text = versionLabel()

        binding.imageView.scaleType =
            if (Config.IMAGE_FIT_CENTER) ImageView.ScaleType.FIT_CENTER
            else ImageView.ScaleType.CENTER_CROP

        player = ExoPlayer.Builder(this).build().apply {
            repeatMode = Player.REPEAT_MODE_OFF
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) handler.post { next() }
                }

                override fun onPlayerError(error: PlaybackException) {
                    lastError = "视频播放失败：${error.errorCodeName}"
                    Log.w(Config.TAG, "$lastError（跳过）")
                    handler.post { skipAfterFailure() }
                }
            })
        }
        binding.playerView.player = player

        remoteConfigured = RemoteRepository.isConfigured(this)
        refreshIntervalSec = RemoteRepository.refreshIntervalSec(this)

        if (Config.UPLOAD_ENABLED) {
            uploadServer = UploadServer(
                // 目录每次都现算：存储根目录可能在拿到权限后从私有目录切到 /sdcard/signage/
                dirProvider = { MediaLibrary.manualDir(this) },
                deviceName = getString(R.string.app_name),
                startPort = Config.UPLOAD_PORT,
                onLibraryChanged = { kind -> handler.post { onLibraryChanged(kind) } },
                // 手机打开 http://电视IP:端口/status 就能看到下面这些，排错不用连电脑
                statusProvider = { runCatching { statusJson() }.getOrNull() },
                // 手机上要能删的不只是刚传的几条 —— U 盘/文件管理器拷进 media 的也得列出来。
                // 远端目录只是列出来给人看（不让删），但目录本身还是得给。
                mediaDirProvider = { MediaLibrary.dir(this) },
                remoteDirProvider = { RemoteRepository.mediaDir(this) },
                // 服务器地址也能在手机上改：省掉"抱着笔记本去店里 push 一个 json"
                remoteConfigFileProvider = { RemoteConfig.configFile(this) },
                remoteInfoProvider = { runCatching { remoteInfoJson() }.getOrNull() },
                onRemoteConfigChanged = { handler.post { onRemoteConfigChanged() } },
                // 上传页的访问码。文件不存在 = 不拦人；在手机页面上设过之后才要登录
                codeFileProvider = { UploadAuth.codeFile(this) }
            )
        }

        // 6.0 以上要运行时申请存储权限，才写得进 /sdcard/signage/。
        // 5.x 的机器（比如店里那台电视）在安装时就自动授予了，不会弹窗。
        ensureStoragePermission()

        Log.i(Config.TAG, "素材根目录：${Storage.root(this).absolutePath}（${Storage.rootLabel(this)}）")
        Log.i(Config.TAG, "本地素材目录：${MediaLibrary.dir(this).absolutePath}")
        Log.i(Config.TAG, "手机上传目录：${MediaLibrary.manualDir(this).absolutePath}")
        Log.i(Config.TAG, "触摸呼出二维码：${Config.TOUCH_TAP_ENABLED}")
        if (remoteConfigured) {
            Log.i(Config.TAG, "端侧配置：${RemoteConfig.configFile(this).absolutePath}")
        } else {
            Log.i(Config.TAG, "未配置远端，按本地目录模式运行")
        }
    }

    override fun onStart() {
        super.onStart()

        if (Config.UPLOAD_ENABLED) uploadServer?.start()

        if (remoteConfigured) {
            // 先用上次缓存的清单起播，不等网络；随后后台去拉最新的
            val cached = RemoteRepository.readCachedPlaylist(this)
            val cachedItems = cached?.let {
                PlaylistResolver.resolve(
                    it, RemoteRepository.mediaDir(this), Calendar.getInstance()
                )
            } ?: emptyList()

            applyBase(cachedItems)
            refreshAsync()
        } else {
            reloadLocal()
        }
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(advance)
        handler.removeCallbacks(refreshTask)
        handler.removeCallbacks(rescanTask)
        handler.removeCallbacks(jumpToManual)
        hideQrNow()
        uploadServer?.stop()
        // 退出前把游标标成"没在播"，回来时才会重新起播，而不是以为还在播
        seq.stopped()
        player.pause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        uploadServer?.stop()
        uploadServer = null
        player.release()
        super.onDestroy()
    }

    // ==================== 存储权限 ====================

    /**
     * 6.0 ~ 10.0 需要运行时授予 WRITE_EXTERNAL_STORAGE，才写得进 /sdcard/signage/。
     * 再新的版本（11 起）这个权限已经被分区存储架空，弹窗也是白发，索性不打扰 ——
     * 那种机器会自动退到 Android/data 私有目录，照样能播，只是目录藏得深一点。
     * 5.x（店里那台电视）在安装时就授予了，这里直接过。
     */
    private fun ensureStoragePermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            Build.VERSION.SDK_INT > Build.VERSION_CODES.Q
        ) {
            return
        }
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_STORAGE)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_STORAGE) return

        // 授权以后根目录可能整个换地方（私有目录 → /sdcard/signage/），
        // 得重新选一次、重新扫一遍，否则播放列表还指在旧路径上。
        Storage.invalidate()
        Log.i(
            Config.TAG,
            "存储权限结果 ${grantResults.joinToString()}，素材根目录重选为 ${Storage.root(this).absolutePath}"
        )
        if (remoteConfigured) refreshAsync() else reloadLocal()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    /**
     * 触摸屏电视：点屏幕任意位置弹出/收起二维码，不用遥控器。
     *
     * 这里把触摸事件整个吃下、不下发到子 View —— 界面上没有任何需要触摸的控件，
     * 统一在 Activity 层处理，省得 PlayerView / ImageView 各自抢事件。
     * 用 GestureDetector 而不是 setOnClickListener，是为了把「轻点」和「滑动」分开：
     * 有人擦屏幕、手蹭一下，不会误弹二维码。
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (Config.TOUCH_TAP_ENABLED) {
            tapDetector.onTouchEvent(ev)
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    /**
     * 遥控器按键：方向键全部吞掉，防止店员或顾客误触弹出菜单。
     * 「确定」键用来切换二维码浮层，「菜单」键也能呼出。
     * 返回键故意保留，方便调试时退出；第 4 步做 Kiosk 时再彻底封掉。
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_MENU -> {
            showQr()
            true
        }

        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER -> {
            if (binding.qrOverlay.visibility == View.VISIBLE) hideQrNow() else showQr()
            true
        }

        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT -> true

        else -> super.onKeyDown(keyCode, event)
    }

    private fun hideSystemUi() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    @Suppress("DEPRECATION")
    private fun versionLabel(): String = try {
        val info = packageManager.getPackageInfo(packageName, 0)
        "${getString(R.string.app_name)} v${info.versionName} (${PackageInfoCompat.getLongVersionCode(info)})"
    } catch (e: Exception) {
        ""
    }

    // ==================== 手机扫码上传 ====================

    /** 触摸屏点一下：二维码开着就收起，没开就呼出 */
    private fun toggleQrByTouch() {
        if (!Config.UPLOAD_ENABLED) return
        if (binding.qrOverlay.visibility == View.VISIBLE) hideQrNow() else showQr()
    }

    /**
     * 上传服务在子线程回调，这里已经切回主线程。
     *
     * 上传和删除分开对待，这是刻意的：
     *   - 刚传完东西 → 用户正盯着屏幕等反应，防抖 700ms（多张连传只在最后触发一次）
     *     后跳过去播，顺便收掉二维码浮层，不然浮层盖着刚传的内容，看着像"传了没播"；
     *   - 删掉了东西 → 平静地重扫重排，让 Sequencer 决定该播哪条。
     *     要是删除也走"跳过去"，删一张图整个屏就跳回第一条了，看着像应用重启。
     */
    private fun onLibraryChanged(kind: LibraryChange) {
        handler.removeCallbacks(jumpToManual)
        if (kind == LibraryChange.UPLOAD) {
            handler.postDelayed(jumpToManual, 700)
            return
        }
        // 远端模式下 media/ 不参与播放（base 是远端清单），删 manual 只需重拼一次
        if (remoteConfigured) applyBase(baseItems) else reloadLocal()
    }

    /**
     * @param auto true = 因为没素材才自动亮的，一有内容就该收掉
     */
    private fun showQr(auto: Boolean = false) {
        if (!Config.UPLOAD_ENABLED) return

        val url = uploadServer?.url()
        val overlay = binding.qrOverlay

        // 内容没变就别重画，只把自动隐藏往后推
        if (url != null && url == qrShownUrl && overlay.visibility == View.VISIBLE) {
            // 已经由用户主动打开过的话，别被"自动"这一路降级成自动的
            qrAutoShown = qrAutoShown && auto
            restartQrTimer()
            return
        }

        val sizePx = qrSizePx()
        val bitmap = url?.let { QrCode.bitmap(it, sizePx) }

        if (bitmap == null) {
            binding.qrImage.visibility = View.GONE
            binding.qrImage.setImageDrawable(null)
            binding.qrUrl.text = getString(R.string.qr_no_network)
            qrShownUrl = null
        } else {
            // 尺寸按屏幕算，不写死在布局里：电视密度低，280dp 实际只有 280px，太小吃亏
            binding.qrImage.layoutParams = binding.qrImage.layoutParams.apply {
                width = sizePx
                height = sizePx
            }
            binding.qrImage.visibility = View.VISIBLE
            binding.qrImage.setImageBitmap(bitmap)
            binding.qrUrl.text = url
            qrShownUrl = url
        }

        qrAutoShown = auto
        overlay.visibility = View.VISIBLE
        restartQrTimer()
    }

    /** 二维码边长：按屏幕短边取比例，再夹到上下限之间 */
    private fun qrSizePx(): Int {
        val dm = resources.displayMetrics
        return Config.qrSizePx(minOf(dm.widthPixels, dm.heightPixels))
    }

    private fun restartQrTimer() {
        handler.removeCallbacks(hideQr)
        handler.postDelayed(hideQr, Config.QR_AUTO_HIDE_MS)
    }

    private fun hideQrNow() {
        handler.removeCallbacks(hideQr)
        qrShownUrl = null
        qrAutoShown = false
        binding.qrOverlay.visibility = View.GONE
        binding.qrImage.setImageDrawable(null)
    }

    // ==================== 远端同步 ====================

    private fun refreshAsync() {
        if (syncing || !remoteConfigured) return
        syncing = true

        Thread {
            val result = try {
                RemoteRepository.sync(this)
            } catch (e: Exception) {
                Log.w(Config.TAG, "同步异常", e)
                null
            }

            handler.post {
                syncing = false
                firstSyncDone = true
                if (result != null) lastSync = result

                when {
                    result == null -> {
                        // 配置文件被删了，退回本地模式
                        remoteConfigured = false
                        reloadLocal()
                    }

                    result.items.isEmpty() -> {
                        lastMessage = result.message
                        // 远端给不出可播内容时，退回本地目录兜底，屏不能黑
                        applyBase(MediaLibrary.localItems(MediaLibrary.dir(this)))
                        if (seq.items.isEmpty()) showPlaceholder(placeholderText())
                    }

                    else -> {
                        lastMessage = result.message
                        applyBase(result.items)
                    }
                }

                scheduleRefresh()
                // 状态回传放在最后：它报的正好是"这一轮同步之后"的状态，
                // 而且哪怕它整个坏掉，上面该做的都已经做完了
                reportAsync()
            }
        }.start()
    }

    private fun scheduleRefresh() {
        handler.removeCallbacks(refreshTask)
        if (remoteConfigured) {
            handler.postDelayed(refreshTask, refreshIntervalSec * 1000L)
        }
    }

    /**
     * 手机上刚改了服务器地址（或把远端关掉了）。
     *
     * 三个动作缺一不可：重读开关、换刷新间隔、**立刻同步一次**。
     * 只重读不立刻同步的话，用户改完地址盯着电视，最长要等一整个刷新周期才见效 ——
     * 他会以为没生效，然后再改一遍。
     */
    private fun onRemoteConfigChanged() {
        remoteConfigured = RemoteRepository.isConfigured(this)
        refreshIntervalSec = RemoteRepository.refreshIntervalSec(this)
        handler.removeCallbacks(refreshTask)

        if (!remoteConfigured) {
            lastMessage = "已关掉远端，按本地目录模式运行"
            Log.i(Config.TAG, lastMessage)
            reloadLocal()
            return
        }

        Log.i(Config.TAG, "远端配置已更新，立刻同步一次；间隔 ${refreshIntervalSec}s")
        if (syncing) {
            // 正有一个同步在跑（用的还是旧配置）。等它收尾，再补一次，别把两个叠在一起
            handler.postDelayed(refreshTask, 3_000)
        } else {
            refreshAsync()
        }
    }

    /** 给手机页面回显的远端配置摘要 */
    private fun remoteInfoJson(): JSONObject {
        val cfg = RemoteConfig.load(this)
        val o = JSONObject()
        o.put("configured", cfg != null)
        o.put("file", RemoteConfig.configFile(this).absolutePath)
        o.put("message", lastMessage)
        o.put("assetDir", RemoteRepository.mediaDir(this).absolutePath)
        if (cfg != null) {
            o.put("playlistUrl", cfg.playlistUrl)
            o.put("device", cfg.device)
            o.put("resolvedUrl", cfg.resolvedPlaylistUrl)
            o.put("refreshIntervalSec", cfg.refreshIntervalSec)
            o.put("prune", cfg.prune)
            o.put("assetBaseUrl", cfg.assetBaseUrlOverride ?: "")
            // 回传：手机上能看到"这台屏到底往哪报、报上去没有"，
            // 否则「页面上看不到这台设备」就只能靠猜
            o.put("reportUrl", cfg.reportUrl ?: "")
            o.put("reportTarget", reporterTarget())
            o.put("reportIntervalSec", Reporter.intervalSec(cfg.refreshIntervalSec))
            o.put("reportAttempts", Reporter.attempts)
            o.put("reportFailStreak", Reporter.failStreak)
            o.put("reportOk", Reporter.lastResult?.ok ?: false)
            o.put("reportMessage", Reporter.lastResult?.message ?: "还没到上报的时候")
        }
        return o
    }

    // ==================== 状态回传 ====================

    /** 这台屏该往哪报状态。空字符串 = 不报（没配远端、地址推不出来、或者被显式关掉了） */
    private fun reporterTarget(): String {
        val cfg = runCatching { RemoteConfig.load(this) }.getOrNull() ?: return ""
        return Reporter.endpoint(cfg.resolvedPlaylistUrl, cfg.reportUrl) ?: ""
    }

    /**
     * 报一次状态。**失败完全不打扰**：不起提示、不进 lastError、不影响播放。
     *
     * 上报地址和间隔都从远端配置推出来，所以这里不需要用户配任何东西。
     * 起个短线程是因为 report() 走网络，而这里是主线程 —— 主线程一卡就是画面一顿。
     */
    private fun reportAsync() {
        val cfg = runCatching { RemoteConfig.load(this) }.getOrNull() ?: return
        val target = Reporter.endpoint(cfg.resolvedPlaylistUrl, cfg.reportUrl) ?: return

        // 连续失败之后退让：地址填错（比如填成对象存储域名）不能每 5 分钟白敲一次
        if (!Reporter.nextDue()) return

        val body = runCatching { reportJson(cfg).toString() }.getOrNull() ?: return
        Thread { Reporter.report(target, body) }.start()
    }

    /**
     * 上报的内容。只报**能看出"这块屏现在什么情况"**的那几项，别把 /status 那一大坨
     * （三个目录的完整文件列表、存储候选）全塞进去 —— 十块屏每 5 分钟各报一份，
     * 服务器要存下来，写得太胖纯属给自己找麻烦。
     */
    private fun reportJson(cfg: RemoteConfig): JSONObject {
        val o = JSONObject()
        o.put("device", cfg.device)
        o.put("version", versionLabel())
        o.put("mode", if (remoteConfigured) "remote" else "local")
        o.put("playlistUrl", cfg.resolvedPlaylistUrl)
        o.put("reportIntervalSec", Reporter.intervalSec(refreshIntervalSec))

        val sync = lastSync
        o.put("online", sync?.online ?: false)
        o.put("revision", runCatching { RemoteRepository.readRevision(this) }.getOrDefault(""))
        o.put("playlistTotal", sync?.playlist?.entries?.size ?: 0)
        o.put("playableNow", seq.items.size)
        // 三个来源各几个文件：素材"下下来了没"一眼就能看出来
        o.put("manualCount", runCatching { MediaLibrary.scan(MediaLibrary.manualDir(this)).size }.getOrDefault(0))
        o.put("localCount", runCatching { MediaLibrary.scan(MediaLibrary.dir(this)).size }.getOrDefault(0))
        o.put("remoteCount", runCatching { MediaLibrary.scan(RemoteRepository.mediaDir(this)).size }.getOrDefault(0))

        o.put("playingIndex", seq.index)
        o.put("playingFile", seq.currentName() ?: "")

        o.put("message", lastMessage.take(300))
        o.put("storageLabel", Storage.rootLabel(this))
        o.put("storageRoot", Storage.root(this).absolutePath)
        o.put("storageWritable", Storage.isWritable(this))
        o.put("screen", "${resources.displayMetrics.widthPixels}x${resources.displayMetrics.heightPixels}")
        o.put("runningSec",
            if (startAtMs <= 0L) -1L else (SystemClock.elapsedRealtime() - startAtMs) / 1000)
        o.put("lastError", lastError.take(300))
        // 端侧时钟只作参考。老电视的时钟经常是错的，**在线与否一律看服务器收到的时刻**
        o.put("reportedAtMs", System.currentTimeMillis())
        return o
    }

    // ==================== 播放列表组装 ====================

    private fun manualItems(): List<PlaybackItem> =
        if (Config.UPLOAD_ENABLED) MediaLibrary.manualItems(this) else emptyList()

    /**
     * 换成一批新的基础素材，手机上传的内容每次重新拼在最前面。
     * jumpToFront = true 时不管当前在播什么，直接跳到手机刚传的那条（用户正盯着屏幕等）。
     */
    private fun applyBase(newBase: List<PlaybackItem>, jumpToFront: Boolean = false) {
        baseItems = newBase
        val combined = manualItems() + newBase

        if (jumpToFront && combined.isNotEmpty()) {
            // 刚在手机上点完上传：先收起二维码浮层，不然它盖着刚传进来的东西，
            // 一眼看上去就像"传了但没播"。
            hideQrNow()
            seq.update(combined)
            // 播 0 而不是"最后传的那条"：manual 段按上传时间升序，从 0 开始正好把
            // 这一批刚传的从头挨个过一遍，用户能一次看全。别改成播最后一条。
            play(0)
            return
        }

        // 关键：update() 返回 >= 0 就必须去起播。
        // 以前这里只挪游标不起播，一轮播完就冻住不动了 —— 就是这个 bug 让新素材永远轮不上。
        val target = seq.update(combined)
        if (target >= 0) play(target)
        else if (seq.items.isEmpty()) showPlaceholder(placeholderText())
    }

    /** 本地目录模式：重新扫一遍，新拷进去的素材立刻生效 */
    private fun reloadLocal() {
        handler.removeCallbacks(rescanTask)
        applyBase(MediaLibrary.localItems(MediaLibrary.dir(this)))
        handler.postDelayed(rescanTask, Config.LOCAL_RESCAN_MS)
    }

    /** 兜底定时器到期：卡住了就跳下一条，否则重扫目录 */
    private fun onRescan() {
        if (remoteConfigured) {
            refreshAsync()
            return
        }
        if (isStuck()) {
            lastError = "上一条超过 ${Config.PLAY_WATCHDOG_MS / 1000} 秒没播完，已强制跳下一条"
            Log.w(Config.TAG, lastError)
            next()
            return
        }
        reloadLocal()
    }

    private fun isStuck(): Boolean =
        seq.playing && startAtMs > 0L &&
            SystemClock.elapsedRealtime() - startAtMs > Config.PLAY_WATCHDOG_MS

    private fun play(index: Int) {
        handler.removeCallbacks(advance)
        if (seq.items.isEmpty()) return

        val item = seq.items[index]
        seq.started(index)
        startAtMs = SystemClock.elapsedRealtime()

        // 没素材时自动亮起的二维码，一有内容就收掉，别挡着画面
        if (qrAutoShown) hideQrNow()
        binding.placeholder.visibility = View.GONE

        if (item.isVideo) {
            switchToVideo()
            player.volume = if (item.muted) 0f else item.volume.coerceIn(0f, 1f)
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(item.file)))
            player.prepare()
            player.play()
            failStreak = 0
        } else {
            player.stop()
            player.clearMediaItems()

            val bitmap = imageCache.get(item.file.absolutePath)
                ?: decodeSampled(item.file)?.also { imageCache.put(item.file.absolutePath, it) }

            if (bitmap == null) {
                lastError = "图片解码失败：${item.file.name}"
                Log.w(Config.TAG, "$lastError（跳过）")
                skipAfterFailure()
                return
            }

            switchToImage()
            binding.imageView.setImageBitmap(bitmap)
            failStreak = 0
            handler.postDelayed(advance, item.durationMs)
        }

        prefetch(index + 1)
    }

    private fun next() {
        handler.removeCallbacks(advance)

        val idx = seq.advance()
        if (idx < 0) {
            // 列表空了：重新扫目录 / 重新同步
            if (remoteConfigured) refreshAsync() else reloadLocal()
            return
        }

        // 本地模式：一轮播完顺便重扫目录，新拷进去的素材立刻接上
        if (idx == 0 && !remoteConfigured) {
            reloadLocal()
            return
        }

        play(idx)
    }

    /** 连续播放失败时不要死循环，退回提示页并等待重试 */
    private fun skipAfterFailure() {
        failStreak++
        if (failStreak > seq.items.size) {
            failStreak = 0
            showPlaceholder(
                "素材都无法播放，请检查文件格式\n\n${MediaLibrary.dir(this).absolutePath}"
            )
            handler.postDelayed(rescanTask, Config.EMPTY_RETRY_MS)
            return
        }
        handler.post { next() }
    }

    private fun placeholderText(): String {
        val base = when {
            remoteConfigured && !firstSyncDone ->
                "正在同步远端素材…\n\n${RemoteConfig.configFile(this).absolutePath}"

            remoteConfigured ->
                "当前时段没有可播内容\n\n$lastMessage"

            else ->
                "还没有素材\n\n把图片或视频放进这个目录：\n${MediaLibrary.dir(this).absolutePath}"
        }

        return if (Config.UPLOAD_ENABLED) {
            val how = if (Config.TOUCH_TAP_ENABLED) "点一下屏幕" else "按遥控器「确定」键"
            "$base\n\n$how，就能用手机扫码直接传素材。"
        } else {
            base
        }
    }

    private fun switchToVideo() {
        binding.imageView.visibility = View.GONE
        binding.playerView.visibility = View.VISIBLE
    }

    private fun switchToImage() {
        binding.playerView.visibility = View.GONE
        binding.imageView.visibility = View.VISIBLE
    }

    private fun showPlaceholder(text: String) {
        seq.stopped()
        player.stop()
        player.clearMediaItems()
        binding.imageView.visibility = View.GONE
        binding.playerView.visibility = View.GONE
        binding.placeholder.text = text
        binding.placeholder.visibility = View.VISIBLE
        // 没东西可播的时候，屏幕空着也是空着，直接把二维码亮出来
        showQr(auto = true)
    }

    /** 提前解码下一张图片，轮到它时就不会卡一下 */
    private fun prefetch(index: Int) {
        if (seq.items.isEmpty()) return
        val item = seq.items[index % seq.items.size]
        if (item.isVideo || imageCache.get(item.file.absolutePath) != null) return

        Thread {
            val bitmap = decodeSampled(item.file) ?: return@Thread
            handler.post { imageCache.put(item.file.absolutePath, bitmap) }
        }.start()
    }

    /** 按屏幕尺寸降采样解码，避免大图把内存吃光 */
    private fun decodeSampled(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val reqWidth = resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val reqHeight = resources.displayMetrics.heightPixels.coerceAtLeast(1)

        var sampleSize = 1
        while (bounds.outWidth / (sampleSize * 2) >= reqWidth &&
            bounds.outHeight / (sampleSize * 2) >= reqHeight
        ) {
            sampleSize *= 2
        }

        return try {
            BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sampleSize }
            )
        } catch (e: OutOfMemoryError) {
            lastError = "图片解码内存不足：${file.name}"
            Log.w(Config.TAG, lastError, e)
            null
        }
    }

    // ==================== 诊断 ====================

    /**
     * 电视上没法看 logcat，所以把状态挂到上传服务的 /status 上：
     * 手机浏览器打开 http://电视IP:端口/status 就能看到目录里有几个文件、正在播哪一条。
     */
    private fun statusJson(): JSONObject {
        val root = JSONObject()
        root.put("version", versionLabel())
        root.put("mode", if (remoteConfigured) "远端清单" else "本地目录")
        root.put("remoteMessage", lastMessage)
        // 改了地址没生效时最先要看的两行：到底配的哪份清单、配置文件在哪
        root.put("remoteConfigured", remoteConfigured)
        root.put("remotePlaylistUrl", runCatching {
            RemoteConfig.load(this)?.resolvedPlaylistUrl ?: ""
        }.getOrDefault(""))
        root.put("remoteConfigFile", RemoteConfig.configFile(this).absolutePath)
        root.put("uploadUrl", uploadServer?.url() ?: "")
        root.put("touchTap", Config.TOUCH_TAP_ENABLED)
        // 上传页的访问码：手机说"打开页面进不去"时，先看这两行
        root.put("uploadCodeFile", UploadAuth.codeFile(this).absolutePath)
        root.put("uploadCodeSet", runCatching {
            UploadAuth.readCode(UploadAuth.codeFile(this)).isNotEmpty()
        }.getOrDefault(false))

        // 状态回传：电脑上那个后台看不到这块屏时，先看这一段 ——
        // 是"没往那儿报"，还是"报了但被拒了"，这两件事的修法完全不一样
        val rp = JSONObject()
        rp.put("target", reporterTarget())
        rp.put("deviceName", runCatching { RemoteConfig.load(this)?.device ?: "" }.getOrDefault(""))
        rp.put("intervalSec", Reporter.intervalSec(refreshIntervalSec))
        rp.put("attempts", Reporter.attempts)
        rp.put("failStreak", Reporter.failStreak)
        rp.put("ok", Reporter.lastResult?.ok ?: false)
        rp.put("message", Reporter.lastResult?.message ?: "")
        rp.put("atMs", Reporter.lastResult?.atMs ?: 0L)
        root.put("report", rp)

        // 当前生效的清单版本号。跟电脑上发布的比一下，就知道这块屏更新到哪一版了
        root.put("revision", runCatching { RemoteRepository.readRevision(this) }.getOrDefault(""))

        // 存储选址：用了哪个根目录、实测能不能写、其它候选为什么没被选上。
        // 「传了不播」「电视上找不到目录」看这一段基本就有答案了。
        root.put("storageRoot", Storage.root(this).absolutePath)
        root.put("storageLabel", Storage.rootLabel(this))
        root.put("storageWritable", Storage.isWritable(this))
        val candidates = JSONArray()
        for (slot in Storage.diagnose(this)) {
            candidates.put(
                JSONObject()
                    .put("label", slot.label)
                    .put("path", slot.path)
                    .put("writable", slot.writable)
                    .put("note", slot.note)
            )
        }
        root.put("storageCandidates", candidates)

        root.put("mediaDir", MediaLibrary.dir(this).absolutePath)
        root.put("mediaFiles", fileList(MediaLibrary.dir(this)))
        root.put("manualDir", MediaLibrary.manualDir(this).absolutePath)
        root.put("manualFiles", fileList(MediaLibrary.manualDir(this)))
        root.put("remoteDir", RemoteRepository.mediaDir(this).absolutePath)
        root.put("remoteFiles", fileList(RemoteRepository.mediaDir(this)))

        val pb = JSONObject()
        pb.put("total", seq.items.size)
        pb.put("index", seq.index)
        pb.put("playing", seq.playing)
        pb.put("file", seq.currentName() ?: "")
        pb.put("video", seq.items.getOrNull(seq.index)?.isVideo ?: false)
        pb.put("runningSec", if (startAtMs <= 0L) -1L else (SystemClock.elapsedRealtime() - startAtMs) / 1000)
        pb.put("failStreak", failStreak)
        root.put("playback", pb)
        root.put("lastError", lastError)
        return root
    }

    private fun fileList(dir: File): JSONArray {
        val arr = JSONArray()
        val files = dir.listFiles() ?: return arr
        for (f in files.sortedBy { it.name.lowercase() }) {
            if (!f.isFile) continue
            val o = JSONObject()
            o.put("name", f.name)
            o.put("size", f.length())
            arr.put(o)
        }
        return arr
    }

    private companion object {
        /** 申请存储权限的请求码 */
        const val REQ_STORAGE = 1001
    }
}
