package com.laodao.signage

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * 端侧配置：这台盒子该去拉哪份清单。
 *
 * 放在**素材根目录**下（通常是 /sdcard/signage/signage-remote.json），
 * 用 U 盘、电视自带的文件管理器、或者 adb push 都能改，**改完不用重装 APK**。
 * 具体落在哪个路径由 Storage 实测决定，手机打开 /status 能看到实际位置。
 * 10 台盒子部署同一个 APK，各自改一下 device 就行。
 *
 * 文件不存在 = 纯本地模式（第 1 步的行为）。
 */
data class RemoteConfig(
    val playlistUrl: String,
    val assetBaseUrlOverride: String?,
    val device: String,
    val refreshIntervalSec: Int,
    val prune: Boolean,
    /**
     * 状态回传地址。**空 = 按清单地址自动推**（同一个服务器，绝大多数场景这样就够了）。
     * 填 `off` 关掉回传；填完整 URL 可以指到别处。
     * 正常不用管它，手改 JSON 的兜底开关。
     */
    val reportUrl: String? = null
) {

    /** 把 URL 里的 {device} 占位替换掉 */
    val resolvedPlaylistUrl: String
        get() = resolveUrl(playlistUrl, device)

    companion object {

        /**
         * URL **路径段**的百分号编码。
         *
         * 这里**不能**用 `URLEncoder.encode`：它是表单编码，空格会变成 `+`，
         * 而在路径里 `+` 是字面的加号。文件名带空格（"秋季 促销.jpg"）或者设备名带空格时，
         * 端侧会拿着 `秋季+促销.jpg` 去请求一个不存在的地址 —— 表现只是"这张图不更新"，极难查。
         */
        fun encode(s: String): String {
            val safe = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.~"
            val sb = StringBuilder(s.length * 3)
            for (b in s.toByteArray(Charsets.UTF_8)) {
                val v = b.toInt() and 0xFF
                val c = v.toChar()
                if (safe.indexOf(c) >= 0) sb.append(c)
                else sb.append('%').append(String.format("%02X", v))
            }
            return sb.toString()
        }

        /** 把 URL 里的 {device} 占位替换掉。device 为空就原样返回 */
        fun resolveUrl(playlistUrl: String, device: String): String {
            val url = playlistUrl.trim()
            return if (device.isEmpty()) url else url.replace("{device}", encode(device))
        }

        /**
         * 清单地址的格式校验。返回错误说明，null = 通过。
         *
         * 手机上填错地址的代价是「十块屏一起不更新」，所以宁可在这里啰嗦一点。
         * 只挡明显错的，不替用户判断网络能不能通 —— 那是「测试连接」按钮的活。
         */
        fun checkPlaylistUrl(raw: String): String? {
            val url = raw.trim()
            if (url.isEmpty()) return "请填清单地址"
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                return "地址要以 http:// 或 https:// 开头"
            }
            if (url.any { it.isWhitespace() }) return "地址里不能有空格或换行"
            // 占位符只认 {device}。写错了（{devcie}、漏个花括号）不会报错，只会安静地
            // 请求一个不存在的地址 —— 十块屏一起不更新，且看不出原因，所以在这里拦掉。
            if (url.contains('{') || url.contains('}')) {
                if (url.count { it == '{' } != url.count { it == '}' }) {
                    return "地址里的 {device} 占位没写完整"
                }
                val names = Regex("\\{([^}]*)\\}").findAll(url).map { it.groupValues[1] }.toList()
                val wrong = names.firstOrNull { it != "device" }
                if (wrong != null) return "地址里只认 {device} 这个占位符，现在写的是 {$wrong}"
            }
            return null
        }

        /**
         * 设备名字段。它会替换进 URL 的 {device} 占位，所以不能带会破坏 URL 的字符。
         * 空是允许的（不用占位符的场景）。
         */
        fun checkDevice(raw: String): String? {
            val d = raw.trim()
            if (d.isEmpty()) return null
            if (d.length > 64) return "设备名太长了（最多 64 个字）"
            if (ILLEGAL_IN_NAME.containsMatchIn(d)) {
                return "设备名不能含 / \\ : * ? \" < > | 这类字符"
            }
            return null
        }

        /** 组装要写进 signage-remote.json 的内容。缩进两格，方便 U 盘改了直接看 */
        fun jsonText(
            playlistUrl: String,
            device: String,
            refreshIntervalSec: Int,
            prune: Boolean,
            assetBaseUrl: String?,
            /**
             * 手机上改地址时**必须把原有的 reportUrl 原样带回去**。
             * 漏了它的话，用户在页面上一保存，手改过的 `reportUrl: off` 就没了 ——
             * 表现为"关掉回传之后过几天自己又报上了"，查起来莫名其妙。
             */
            reportUrl: String? = null
        ): String {
            val o = JSONObject()
            o.put("playlistUrl", playlistUrl.trim())
            if (!assetBaseUrl.isNullOrBlank()) o.put("assetBaseUrl", assetBaseUrl.trim())
            if (device.isNotBlank()) o.put("device", device.trim())
            o.put("refreshIntervalSec", refreshIntervalSec.coerceIn(MIN_REFRESH_SEC, MAX_REFRESH_SEC))
            o.put("prune", prune)
            if (!reportUrl.isNullOrBlank()) o.put("reportUrl", reportUrl.trim())
            return o.toString(2)
        }

        /** 刷新间隔的下限/上限（秒）。太短会把电视和服务器都拖垮，太长改了地址半天不生效 */
        const val MIN_REFRESH_SEC = 30
        const val MAX_REFRESH_SEC = 24 * 3600

        /**
         * 素材基址的推断规则。清单里没写 assetBaseUrl 就用清单自己的目录。
         *
         * 端侧同步和手机上那个「测试连接」按钮共用这一份 —— 各写一套的话会出现
         * 「测试说能取到、同步却取不到」，那比没有测试还糟。
         */
        fun assetBaseFor(override: String?, playlistBase: String?, resolvedPlaylistUrl: String): String =
            override?.ifBlank { null }
                ?: playlistBase?.ifBlank { null }
                ?: resolvedPlaylistUrl.substringBeforeLast('/', "")

        /**
         * 素材地址 = 基址 + 相对路径。**每一段单独编码**，文件名里有中文、空格也不会散架。
         * 规则和端侧同步用的完全一致（AssetSyncer 直接调这里）。
         */
        fun assetUrl(base: String, rel: String): String {
            val b = if (base.endsWith("/")) base else "$base/"
            return b + rel.split("/").joinToString("/") { encode(it) }
        }

        private val ILLEGAL_IN_NAME = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]")

        fun configFile(context: Context): File =
            Storage.file(context, Config.REMOTE_CONFIG_FILE)

        /** 读取配置；文件不存在或格式不对返回 null（调用方走本地模式） */
        fun load(context: Context): RemoteConfig? {
            val f = configFile(context)
            if (!f.isFile) return null

            val text = try {
                f.readText()
            } catch (e: Exception) {
                Log.w(Config.TAG, "读端侧配置失败", e)
                return null
            }

            val o = try {
                JSONObject(text)
            } catch (e: Exception) {
                Log.w(Config.TAG, "端侧配置不是合法 JSON", e)
                return null
            }

            val url = o.optString("playlistUrl", "").trim()
            if (url.isEmpty()) {
                Log.w(Config.TAG, "端侧配置里没有 playlistUrl，按本地模式跑")
                return null
            }

            return RemoteConfig(
                playlistUrl = url,
                assetBaseUrlOverride = o.optString("assetBaseUrl", "").trim().ifEmpty { null },
                device = o.optString("device", "").trim(),
                refreshIntervalSec = o.optInt("refreshIntervalSec", Config.DEFAULT_REFRESH_SEC)
                    .coerceIn(MIN_REFRESH_SEC, MAX_REFRESH_SEC),
                prune = o.optBoolean("prune", true),
                reportUrl = o.optString("reportUrl", "").trim().ifEmpty { null }
            )
        }
    }
}
