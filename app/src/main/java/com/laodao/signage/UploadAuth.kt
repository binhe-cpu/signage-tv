package com.laodao.signage

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.IOException
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 手机上传页的访问码。
 *
 * 为什么要有：上传页是**局域网里谁都能打开**的 —— 电视连着店里的 WiFi，同一个网段
 * 任何一个浏览器输个 IP 就能看素材、删素材、改服务器地址。这是这套东西里唯一一处
 * 没有门的地方。现在给它加一个码。
 *
 * 设计上的几个取舍：
 *
 * - **码存在素材根目录下的 `signage-upload.json`**（跟 `signage-remote.json` 一个地方）。
 *   选这个位置是为了「忘了码还能救」：插 U 盘、或者用电视自带的文件管理器把这个文件删掉，
 *   就恢复成不需要访问码。放在 App 私有目录里就只能重装 APK。
 * - **文件不存在 = 不开启访问码**，行为跟以前完全一样。老电视、懒得设的场景零影响；
 *   设过之后才拦人。
 * - **登录凭证是访问码的 HMAC**（`sig.exp`），跟网页后台一个套路：不用额外的密钥文件，
 *   改码就把所有已登录的手机一次性踢掉 —— 这正是改码想要的效果。
 *
 * 这个类刻意不碰 Android 的 UI / Context（除了取路径那个函数），
 * 好让 JVM 测试台能原样复制过去跑真实请求。
 */
object UploadAuth {

    /** 访问码文件，放在素材根目录下，跟 signage-remote.json 并列 */
    const val FILE_NAME = "signage-upload.json"

    /** 登录凭证的 Cookie 名 */
    const val COOKIE_NAME = "signage_tv"

    const val MIN_LEN = 4
    const val MAX_LEN = 64

    /** 登录有效期 14 天。手机上多输几次码比三天两头重登更烦人 */
    const val TTL_MS = 14L * 24 * 3600 * 1000

    /**
     * 访问码格式校验。返回给人看的原因，null = 通过。
     *
     * 不做长度上限以外的强度要求 —— 老道要在店里手机上输，短一点好记。
     * 只挡明显会坏掉的（空、太短、带换行）。
     */
    fun checkCode(raw: String): String? {
        val c = raw.trim()
        if (c.isEmpty()) return "请输入访问码"
        if (c.length < MIN_LEN) return "访问码太短了，至少 $MIN_LEN 位"
        if (c.length > MAX_LEN) return "访问码太长了（最多 $MAX_LEN 个字符）"
        if (c.any { it == '\n' || it == '\r' }) return "访问码里不能有换行"
        return null
    }

    /**
     * 读当前访问码。空字符串 = 没设（不拦人）。
     *
     * 每次请求都真读文件，不做缓存：手机上刚改完密码，下一次请求就得按新码拦，
     * 缓存了就会出现"改了码旧手机还能进"。文件只有几十字节，这点 IO 无所谓。
     */
    fun readCode(file: File?): String {
        if (file == null || !file.isFile) return ""
        return try {
            val o = JSONObject(file.readText())
            o.optString("code", "").trim()
        } catch (e: Exception) {
            // 文件坏了当作没设码：总比锁死自己强 —— 页面进不去就没法自救，
            // 而这里唯一的门槛只是"别让店里的人乱传"
            Log.w(Config.TAG, "读访问码失败，先当作没设：${file.absolutePath}", e)
            ""
        }
    }

    /**
     * 写访问码（空串 = 关掉访问码，把文件删掉）。返回错误说明，null = 成功。
     *
     * 先写临时文件再改名：改到一半断电不会留下半截 JSON ——
     * 那种文件读出来是空码，等于门突然开了，比打不开更糟。
     */
    fun writeCode(file: File?, code: String): String? {
        if (file == null) return "拿不到访问码文件的位置"
        val dir = file.parentFile ?: return "拿不到访问码文件的目录"

        if (code.isEmpty()) {
            // 文件不存在就是"没设访问码"，语义只有一种，不用再写个 {"code":""} 出来
            if (!dir.isDirectory) return null
            if (file.isFile && !file.delete()) {
                return "关不掉访问码：文件删不了（${file.absolutePath}）"
            }
            return null
        }

        if (!dir.isDirectory && !dir.mkdirs()) return "目录建不出来：${dir.absolutePath}"
        if (!dir.isDirectory) return "目录建不出来：${dir.absolutePath}"

        val tmp = File(dir, "." + file.name + ".part")
        return try {
            tmp.writeText(text(code))
            if (file.exists() && !file.delete()) throw IOException("旧文件删不掉")
            if (!tmp.renameTo(file)) throw IOException("改名失败")
            null
        } catch (e: Exception) {
            tmp.delete()
            "保存访问码失败：${e.message ?: e.javaClass.simpleName}（路径 ${file.absolutePath}）"
        }
    }

    /** 写进文件的内容。缩进两格，U 盘打开也能看懂 */
    fun text(code: String): String {
        val o = JSONObject()
        o.put("code", code)
        o.put("note", "手机上传页的访问码。删掉这个文件就恢复成不需要访问码")
        return o.toString(2)
    }

    // ==================== 登录凭证 ====================

    /** 签发凭证：`HMAC(码, 过期时间戳).过期时间戳`，跟网页后台同一套 */
    fun makeToken(code: String, nowMs: Long): String {
        if (code.isEmpty()) return ""      // 没设码就没有"凭证"这回事
        val exp = (nowMs / 1000) + (TTL_MS / 1000)
        val expStr = exp.toString()
        return sign(code, expStr) + "." + expStr
    }

    /** 校验凭证。过期的、签名对不上的、格式乱的都返回 false */
    fun verifyToken(code: String, token: String?, nowMs: Long): Boolean {
        if (code.isEmpty() || token.isNullOrBlank()) return false
        val dot = token.lastIndexOf('.')
        if (dot <= 0 || dot == token.length - 1) return false
        val exp = token.substring(dot + 1).toLongOrNull() ?: return false
        if (exp * 1000L < nowMs) return false
        return constantTimeEquals(token.substring(0, dot), sign(code, token.substring(dot + 1)))
    }

    /** 定长比较，别让猜码的人从响应时间里读出比对到第几位 */
    fun constantTimeEquals(a: String, b: String): Boolean {
        val x = a.toByteArray(Charsets.UTF_8)
        val y = b.toByteArray(Charsets.UTF_8)
        if (x.size != y.size) return false
        var diff = 0
        for (i in x.indices) diff = diff or (x[i].toInt() xor y[i].toInt())
        return diff == 0
    }

    /** 从请求头的 Cookie 里挑出某一个值。凭证本身只有 hex 和点，不用再做 URL 解码 */
    fun parseCookie(raw: String?, name: String): String? {
        if (raw.isNullOrBlank()) return null
        for (part in raw.split(';')) {
            val i = part.indexOf('=')
            if (i <= 0) continue
            if (part.substring(0, i).trim() == name) return part.substring(i + 1).trim()
        }
        return null
    }

    fun cookieHeader(token: String): String =
        "$COOKIE_NAME=$token; Path=/; Max-Age=${TTL_MS / 1000}; HttpOnly; SameSite=Lax"

    fun clearCookieHeader(): String = "$COOKIE_NAME=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax"

    private fun sign(code: String, msg: String): String = try {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(code.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        hex(mac.doFinal(msg.toByteArray(Charsets.UTF_8)))
    } catch (e: Exception) {
        Log.w(Config.TAG, "算凭证签名失败", e)
        ""
    }

    private const val HEX = "0123456789abcdef"

    private fun hex(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) {
            val v = x.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    /** 访问码文件的位置，跟着素材根目录走（根目录是运行时实测出来的） */
    fun codeFile(context: Context): File = Storage.file(context, FILE_NAME)
}
