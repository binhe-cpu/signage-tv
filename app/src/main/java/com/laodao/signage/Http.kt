package com.laodao.signage

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 极简 HTTP，只用 JDK 自带的 HttpURLConnection。
 *
 * 为什么不引 OkHttp：这里一共就两个动作（GET 一段 JSON、GET 一个文件），
 * 引一个 800KB 的库换不来什么，还多一份要跟着升级的依赖。
 */
object Http {

    private const val UA = "SignageTV/" + Config.VERSION_NAME

    fun getText(url: String, timeoutMs: Int = Config.HTTP_TIMEOUT_MS): String {
        val conn = open(url, timeoutMs)
        return try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /**
     * 只看一眼这个地址通不通，不下载内容。
     *
     * 手机上「测试连接」用它戳第一条素材：清单能拉到、素材路径却是错的，是这套配置里最隐蔽的坑
     * —— 端侧的表现只是「素材不更新」，很难往地址上想。
     */
    fun probe(url: String, timeoutMs: Int = Config.HTTP_TIMEOUT_MS): String {
        val conn = open(url, timeoutMs)
        return try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            val len = conn.getHeaderField("Content-Length")
            if (len.isNullOrBlank()) "HTTP $code" else "HTTP $code，$len 字节"
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /**
     * POST 一段 JSON，返回响应体。状态回传用它。
     *
     * 两个地方容易写错：
     *  1. **先写请求体再读响应码**。反过来的话某些实现会先把请求头发出去（没有 Content-Length），
     *     服务端在那儿等请求体，双方对着卡到超时。
     *  2. **出错时必须读 errorStream**。4xx/5xx 的响应体挂在 errorStream 上，不读它连接就不释放；
     *     而且服务端写的「为什么拒绝」全在那一小段文本里，扔掉它就只能看到一句干巴巴的 HTTP 400。
     */
    fun postJson(url: String, body: String, timeoutMs: Int = Config.HTTP_TIMEOUT_MS): String {
        val bytes = body.toByteArray(Charsets.UTF_8)

        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Accept", "application/json")
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }

            val code = conn.responseCode
            val text = readBody(conn, code)
            if (code !in 200..299) {
                val why = text.trim().take(200)
                throw IOException("HTTP $code" + if (why.isEmpty()) "" else "：$why")
            }
            text
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /** 成功读 inputStream、失败读 errorStream，两边都不留没读完的流 */
    private fun readBody(conn: HttpURLConnection, code: Int): String {
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        return stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
    }

    /**
     * 下载到 dest。先写 .part 临时文件，成功后再改名，
     * 这样中途断网不会留下一个看起来完整、实际半截的素材被播出去。
     */
    fun download(url: String, dest: File, timeoutMs: Int = Config.DOWNLOAD_TIMEOUT_MS): Long {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".part")

        val conn = open(url, timeoutMs)
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")

            conn.inputStream.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE * 16) }
            }
        } catch (e: Exception) {
            tmp.delete()
            throw e
        } finally {
            runCatching { conn.disconnect() }
        }

        if (dest.exists() && !dest.delete()) {
            tmp.delete()
            throw IOException("无法覆盖旧文件：${dest.name}")
        }
        if (!tmp.renameTo(dest)) {
            tmp.delete()
            throw IOException("重命名失败：${dest.name}")
        }
        return dest.length()
    }

    private fun open(url: String, timeoutMs: Int): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.requestMethod = "GET"
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Accept", "*/*")
        conn.setRequestProperty("Cache-Control", "no-cache")
        return conn
    }
}
