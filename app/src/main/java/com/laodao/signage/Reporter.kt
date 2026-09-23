package com.laodao.signage

import android.util.Log
import java.net.URI

/**
 * 状态回传：让网页后台知道每块屏还活着、在播哪一版。
 *
 * 为什么要它：十块屏装的是同一个 APK，哪台在跑、哪台卡住、哪台还在播上个月的清单，
 * 现在只能一台台去点二维码、开 /status 看。屏挂在店里，人不在店里。
 *
 * 刻意做成**零配置**：回传地址从清单地址**同源推出来**（清单和后台本来就在同一个服务器上）。
 * 用户只要填过清单地址，回传自动就有，不用再多填一个框。
 *
 * 时间戳只作参考：老电视的系统时钟经常是错的（有的差好几年）。**判断在线与否一律用
 * 服务器收到的时刻**，端侧报上来的时间只用来对照。
 */
object Reporter {

    /** 后台接这个路径，跟网页后台的 /api/report 对应 */
    const val PATH = "/api/report"

    /** reportUrl 填这些值 = 关掉回传 */
    private val OFF_VALUES = setOf("off", "none", "no", "false", "0", "-")

    /**
     * 对象存储的域名后缀。**不能往这些域名上推 /api/report**。
     *
     * 对象存储模式下（桶设成公有读），端侧填的是桶的域名，那边根本没有 /api/report，
     * 只会回 404 或 403。不排除的话每台屏每 5 分钟白敲一次，日志里全是没用的失败。
     */
    private val OBJECT_STORAGE_HOSTS = listOf(
        "myqcloud.com", "aliyuncs.com", "amazonaws.com", "googleapis.com",
        "qiniu.com", "qiniucdn.com", "bcebos.com", "jdcloud-oss.com", "cloudfront.net",
    )

    data class Result(val ok: Boolean, val message: String, val atMs: Long)

    /** 最近一次上报结果。给 /status 和手机上传页看 */
    @Volatile
    var lastResult: Result? = null
        private set

    /** 一共报了几次（含失败），排错时有用 */
    @Volatile
    var attempts: Int = 0
        private set

    /** 连续失败了几次 */
    @Volatile
    var failStreak: Int = 0
        private set

    /** shouldAttempt 的调用次数，配合 failStreak 做退让 */
    @Volatile
    private var tick: Int = 0

    /**
     * 算出该往哪报。返回 null = 这台屏不报（地址推不出来，或者被显式关掉了）。
     *
     * 推导规则：清单地址的 **协议 + 主机 + 端口**，路径固定换成 /api/report。
     * 故意不保留清单的目录前缀 —— 后台的接口一律挂在 /api/ 根下，
     * 而清单名是可以带子目录的（`signage/playlist.json`），跟着走反而会推错。
     */
    fun endpoint(playlistUrl: String, override: String? = null): String? {
        val o = override?.trim().orEmpty()
        if (o.isNotEmpty()) {
            // 显式配了就以它为准：能关掉，也能指到别处
            return if (o.lowercase() in OFF_VALUES) null else o
        }

        val url = playlistUrl.trim()
        if (url.isEmpty()) return null

        val uri = try {
            URI(url)
        } catch (e: Exception) {
            // 地址里有中文、空格之类会导致解析失败。返回 null 而不是抛异常：
            // 回传是附带功能，它出问题绝不能影响正常播放
            return null
        }

        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.lowercase() ?: return null
        if (host.isEmpty()) return null
        if (OBJECT_STORAGE_HOSTS.any { host == it || host.endsWith(".$it") }) return null

        val port = if (uri.port > 0) ":" + uri.port else ""
        return "$scheme://$host$port$PATH"
    }

    /**
     * 实际的上报间隔：跟着清单刷新间隔走，但夹在 [Config.REPORT_MIN_SEC] ~
     * [Config.REPORT_MAX_SEC] 之间。报上去的同时告诉服务器这个值 ——
     * 服务器要靠它判断"多久没心跳算离线"，不然只能拍一个死数。
     */
    fun intervalSec(refreshSec: Int): Int =
        refreshSec.coerceIn(Config.REPORT_MIN_SEC, Config.REPORT_MAX_SEC)

    /**
     * 这次要不要真的发。
     *
     * 连续失败到 [Config.REPORT_BACKOFF_AFTER] 次之后，改成「每 N 次机会只试一次」：
     * 地址填错（比如填成对象存储域名）不能每 5 分钟硬敲，白耗电视的网卡。
     * 一旦成功，计数清零，立刻恢复正常频率。
     */
    fun shouldAttempt(failStreak: Int, tick: Int): Boolean =
        failStreak < Config.REPORT_BACKOFF_AFTER || tick % Config.REPORT_BACKOFF_AFTER == 0

    /** 带状态的版本，调用方不用自己管计数器 */
    fun nextDue(): Boolean {
        tick++
        return shouldAttempt(failStreak, tick)
    }

    /**
     * 报一次。**必须在后台线程调用**（会走网络）。
     * 失败只记在 lastResult 里，不抛异常、不打扰播放 —— 回传是顺便做的事。
     */
    fun report(endpoint: String, body: String): Result {
        attempts++
        val r = try {
            val reply = Http.postJson(endpoint, body, Config.REPORT_TIMEOUT_MS)
            Result(true, reply.trim().ifEmpty { "HTTP 200" }.take(300), System.currentTimeMillis())
        } catch (e: Exception) {
            Result(false, (e.message ?: e.javaClass.simpleName).take(300), System.currentTimeMillis())
        }

        if (r.ok) {
            // 报通了就把退让清掉，别让几天前的一次失败影响到现在
            failStreak = 0
        } else {
            failStreak++
            Log.w(Config.TAG, "状态回传失败（连续 ${failStreak} 次）：$endpoint ${r.message}")
        }
        lastResult = r
        return r
    }

    /** 测试用：把记着的状态清干净 */
    fun resetState() {
        lastResult = null
        attempts = 0
        failStreak = 0
        tick = 0
    }
}
