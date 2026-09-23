package com.laodao.signage

/**
 * 全部可调参数集中在这里，改完重新打包即可。
 */
object Config {

    /** 版本号，只用于日志和 User-Agent，跟 build.gradle 的 versionName 手动保持一致 */
    const val VERSION_NAME = "0.3.8"

    // ===== 播放 =====

    /** 每张图片停留多久（毫秒）。8000 = 8 秒。远端清单里写了 durationSec 的以清单为准 */
    const val IMAGE_DURATION_MS = 8_000L

    /** 目录为空时，多久重新扫一次目录（毫秒） */
    const val EMPTY_RETRY_MS = 10_000L

    /**
     * 本地模式下每隔多久重扫一次素材目录。
     * 有两个用处：往目录里新拷的文件不用重启、不用等一轮播完就能生效；
     * 万一播放卡住（比如某台老电视的视频解码器卡死），这里也是兜底重启的地方。
     */
    const val LOCAL_RESCAN_MS = 15_000L

    /**
     * 播放看门狗：一条内容起播后超过这么久还没结束，就强行跳下一条。
     * 正常图片 8 秒、视频最长也就一两分钟，15 分钟一定是卡住了。
     */
    const val PLAY_WATCHDOG_MS = 15 * 60 * 1000L

    /** 是否静音。门店屏幕一般静音，需要声音改成 false */
    const val MUTED = true

    /** 图片显示方式：true = 等比缩放留黑边（不裁切内容）；false = 铺满屏幕（会裁切） */
    const val IMAGE_FIT_CENTER = true

    /** 图片内存缓存上限 = 可用内存 / 该值 */
    const val IMAGE_CACHE_DIVISOR = 8

    /** 日志 TAG */
    const val TAG = "SignageTV"

    // ===== 第 2 步：远端清单 =====

    /**
     * 端侧配置文件，放在 /sdcard/Android/data/com.laodao.signage/files/ 下。
     * 文件不存在 = 纯本地模式，走第 1 步那套「扫本地目录」。
     */
    const val REMOTE_CONFIG_FILE = "signage-remote.json"

    /** 默认刷新间隔（秒）。清单里没配就用这个 */
    const val DEFAULT_REFRESH_SEC = 300

    /** 拉清单的超时（毫秒） */
    const val HTTP_TIMEOUT_MS = 15_000

    /** 下载单个素材的超时（毫秒） */
    const val DOWNLOAD_TIMEOUT_MS = 120_000

    /** 远端素材目录名（相对于 files/），跟本地目录 media/ 分开，互不覆盖 */
    const val REMOTE_DIR = "remote"

    // ===== 第 3 步：手机扫码上传 =====

    /**
     * 手机扫码上传总开关。
     * 打开后电视会在局域网里监听一个端口，屏幕上显示二维码；
     * 手机连同一个 WiFi 扫码，就能直接把图片/视频传进来。关掉则完全不监听端口。
     */
    const val UPLOAD_ENABLED = true

    /** 上传服务起始端口，被别的程序占用就自动往后试 */
    const val UPLOAD_PORT = 8080

    /** 端口往后最多试几个 */
    const val UPLOAD_PORT_TRIES = 12

    /** 单个文件大小上限（字节）。默认 512 MB */
    const val UPLOAD_MAX_BYTES = 512L * 1024 * 1024

    /** 手机上传素材的目录名（相对于 files/），跟 media/、remote/ 物理隔离 */
    const val MANUAL_DIR = "manual"

    /**
     * 手机上传页的访问码文件（放在素材根目录下，跟 signage-remote.json 并列）。
     * **文件不存在 = 不开启访问码**，局域网里谁连上都能用，跟老版本一样。
     * 在手机页面上设过一次之后，所有接口都要先登录。
     * 忘了码就去这个文件：插 U 盘删掉它，或者用电视自带的文件管理器删，就恢复原状。
     */
    const val UPLOAD_CODE_FILE = "signage-upload.json"

    /** 二维码浮层多久自动隐藏（毫秒）。触屏上要掏手机、连 WiFi，留宽一点 */
    const val QR_AUTO_HIDE_MS = 120_000L

    // ===== 触摸屏 =====

    /**
     * 触摸屏电视：点屏幕任意位置呼出/收起二维码，不用遥控器。
     * 关掉的话就只认遥控器的「确定」「菜单」键。两种方式可以同时用，不冲突。
     */
    const val TOUCH_TAP_ENABLED = true

    /**
     * 二维码边长 = 屏幕短边 × 这个比例。
     * 电视的屏幕密度普遍偏低（很多是 160dpi），按 dp 写死会在屏上显小、手机不好扫，
     * 所以按屏幕实际像素算。1000px × 1000px 已经是 Version 3 以内的码，够清晰了。
     */
    const val QR_SIZE_RATIO = 0.34

    /** 二维码边长下限（像素），小屏也不能小于这个 */
    const val QR_SIZE_MIN_PX = 320

    /** 二维码边长上限（像素） */
    const val QR_SIZE_MAX_PX = 1000

    /**
     * 按屏幕短边算二维码边长。写成纯函数，不碰 Android API，方便单独验证边界。
     */
    fun qrSizePx(shortSidePx: Int): Int =
        (shortSidePx * QR_SIZE_RATIO).toInt().coerceIn(QR_SIZE_MIN_PX, QR_SIZE_MAX_PX)

    // ===== 第 5 步：状态回传 =====

    /**
     * 上报间隔跟着**清单刷新间隔**走（同一趟里报一次，不额外起定时器），
     * 但夹在这个区间里：太短纯属浪费（老电视的 CPU 和网卡都紧张），
     * 太长页面上的"在线"就不准了 —— 屏已经黑了两个小时页面还显示在线，那比不显示更坏。
     */
    const val REPORT_MIN_SEC = 60
    const val REPORT_MAX_SEC = 1800

    /**
     * 上报超时（毫秒）。**比拉清单短**：上报是顺带的活儿，它卡住不能拖累同步 ——
     * 同步慢意味着素材更新慢，那是正事。
     */
    const val REPORT_TIMEOUT_MS = 8_000

    /**
     * 连续失败几次之后开始"每 N 次只试一次"。
     * 地址填错（比如填的是对象存储桶的域名，那边根本没有 /api/report）时，
     * 不能每 5 分钟硬敲一次敲到天荒地老。
     */
    const val REPORT_BACKOFF_AFTER = 3
}
