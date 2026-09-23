package com.laodao.signage

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 素材库发生了什么变化。
 *
 * 上传和删除对播放器的含义不一样：手机上刚传完东西，用户正盯着屏幕等，得跳过去播给他看；
 * 删掉一条素材则应该平静地重排，**不能**把整屏拉回第一条 —— 那看着像应用重启了。
 */
enum class LibraryChange { UPLOAD, DELETE }

/**
 * 手机扫码上传服务。
 *
 * 电视在局域网里监听一个端口，屏幕上显示二维码（内容就是这个地址）。
 * 手机连同一个 WiFi 扫码 → 浏览器打开上传页 → 选图片/视频 → 直接传进电视。
 *
 * 全程不经过任何外部服务，店里的网断了也不影响（只要手机和电视还在同一个局域网）。
 *
 * 接口：
 *   GET  /                            手机页面（上传 + 素材管理 + 预览 + 远端设置 + 访问码设置）
 *   GET  /auth                        当前有没有设访问码（给页面决定要不要弹登录层）
 *   POST /login                       用访问码换登录凭证（Cookie）
 *   POST /password                    改访问码（传空 = 关掉访问码）
 *   GET  /list                        电视上全部素材（JSON），带来源和相对路径
 *   GET  /status                      电视当前状态（JSON）：目录路径、文件列表、正在播哪一条
 *   GET  /preview?src=&path=          看素材内容，支持 Range（视频要拖进度条）
 *   GET  /remote                      当前的远端清单配置
 *   POST /upload?name=xx              上传单个文件，请求体是原始二进制
 *   POST /delete?src=&name=相对路径    删除某个素材
 *   POST /clear?src=                  清空某个来源的素材
 *   POST /remote                      保存远端配置（JSON 体），保存后立刻同步一次
 *   POST /remote/test?url=&device=    只测这个地址能不能拉到合法清单，不保存
 *   POST /remote/off                  关掉远端，退回本地目录模式
 *
 * 除了 `GET /`（页面本身）、`GET /favicon.ico`、`GET /auth` 和 `POST /login`，
 * 其余接口在**设了访问码之后**都要带登录凭证 —— 见 UploadAuth。
 *
 * 用「一个文件一个请求 + 原始二进制体」而不是 multipart：服务端只需边读边写盘，
 * 大视频也不会把内存撑爆，解析逻辑也简单到不会出错。
 */
class UploadServer(
    /**
     * 素材目录用 provider 而不是固定 File：存储位置可能在服务起来之后才定下来
     * （比如用户刚把存储权限点成同意，根目录会从私有目录切到 /sdcard/signage/）。
     */
    private val dirProvider: () -> File,
    private val deviceName: String,
    private val startPort: Int,
    private val onLibraryChanged: (LibraryChange) -> Unit,
    /**
     * 播放状态由 MainActivity 提供。电视上没法看 logcat，
     * 手机上打开 /status 就能看到「目录里有几个文件、正在播哪一条、上次报什么错」。
     */
    private val statusProvider: (() -> JSONObject?)? = null,
    /**
     * 另外两个素材目录。要在手机上删素材，光知道上传目录不够 ——
     * 老道往电视里放东西有两条路：U 盘/文件管理器拷进 media/，或者手机上扫二维码传进 manual/。
     * 这两处都得能列出、能删。远端目录只读，理由见 deleteByPath。
     */
    private val mediaDirProvider: (() -> File)? = null,
    private val remoteDirProvider: (() -> File)? = null,
    /**
     * 端侧配置文件（signage-remote.json）的位置。手机上直接改服务器地址，
     * 就是为了免掉「抱着笔记本去店里 adb push 一下」这一步。
     */
    private val remoteConfigFileProvider: (() -> File)? = null,
    /** 当前远端配置的摘要，给页面回显用（只有 MainActivity 拿得到 Context，所以由它提供） */
    private val remoteInfoProvider: (() -> JSONObject?)? = null,
    /** 配置改完了。MainActivity 重新初始化远端并立刻拉一次清单，不用等下一个刷新周期 */
    private val onRemoteConfigChanged: (() -> Unit)? = null,
    /**
     * 访问码文件（素材根目录下的 signage-upload.json）。
     * 文件不存在 = 不开启访问码；设过之后，除了页面本身和登录接口，全部要凭证。
     * 手机上「设置/修改访问码」写的就是它 —— 位置跟 signage-remote.json 并列，
     * 所以忘掉码还能插 U 盘删掉来救。
     */
    private val codeFileProvider: (() -> File)? = null
) {

    private val dir: File get() = dirProvider()

    private var serverSocket: ServerSocket? = null
    private var port = 0
    private val running = AtomicBoolean(false)
    /** 每个连接一个线程。做成守护线程，退出时不会被它挂住 */
    private val pool = Executors.newFixedThreadPool(4) { r ->
        Thread(r, "signage-upload-worker").apply { isDaemon = true }
    }
    private var acceptThread: Thread? = null

    fun start(): Boolean {
        if (running.get()) return true
        // 目录建不出来也照样把服务起起来：手机还得能打开 /status，
        // 不然连问题是出在目录上都不知道。
        if (!dir.isDirectory) dir.mkdirs()

        var bound: ServerSocket? = null
        var boundPort = 0
        for (i in 0 until Config.UPLOAD_PORT_TRIES) {
            val candidate = startPort + i
            try {
                val s = ServerSocket()
                s.reuseAddress = true
                s.bind(InetSocketAddress(candidate))
                bound = s
                boundPort = candidate
                break
            } catch (e: IOException) {
                // 端口被占用，试下一个
            }
        }

        if (bound == null) {
            Log.w(
                Config.TAG,
                "上传服务启动失败：端口 ${startPort}~${startPort + Config.UPLOAD_PORT_TRIES - 1} 全被占用"
            )
            return false
        }

        serverSocket = bound
        port = boundPort
        running.set(true)
        acceptThread = Thread({ acceptLoop() }, "signage-upload-accept").apply {
            isDaemon = true
            start()
        }
        Log.i(Config.TAG, "手机上传服务已启动：${url() ?: "（没检测到局域网 IP）"}")
        return true
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            // 关的时候出错无所谓
        }
        serverSocket = null
        Log.i(Config.TAG, "手机上传服务已停止")
    }

    /** 形如 http://192.168.1.5:8080/；服务没起来或者没连局域网时返回 null */
    fun url(): String? {
        if (!running.get()) return null
        val ip = localIp() ?: return null
        return "http://$ip:$port/"
    }

    // ==================== 接收连接 ====================

    private fun acceptLoop() {
        while (running.get()) {
            val socket = try {
                serverSocket?.accept() ?: break
            } catch (e: Exception) {
                if (running.get()) Log.w(Config.TAG, "accept 异常", e)
                break
            }

            try {
                pool.execute { handleQuietly(socket) }
            } catch (e: Exception) {
                try {
                    socket.close()
                } catch (ignored: Exception) {
                }
            }
        }
    }

    private fun handleQuietly(socket: Socket) {
        try {
            handle(socket)
        } catch (e: Exception) {
            Log.w(Config.TAG, "处理手机请求失败", e)
        } finally {
            try {
                socket.close()
            } catch (ignored: Exception) {
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = 120_000

        val input = BufferedInputStream(socket.getInputStream(), 64 * 1024)
        val output = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)

        val requestLine = readLine(input) ?: return
        val parts = requestLine.split(" ")
        if (parts.size < 2) {
            sendText(output, 400, "bad request")
            return
        }
        val method = parts[0].uppercase()
        val target = parts[1]

        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) {
                headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
            }
        }

        val q = target.indexOf('?')
        val path = if (q >= 0) target.substring(0, q) else target
        val query = if (q >= 0) parseQuery(target.substring(q + 1)) else emptyMap()

        // 设了访问码就先过闸门。页面本身、favicon、登录接口是开放的 ——
        // 手机得先能打开页面，才有地方输码。
        // 拒绝之前**必须**先把请求体丢掉：不丢的话手机还在往外发数据、这边已经把连接关了，
        // 手机上报的是「网络中断」，把「该输访问码了」伪装成网络故障。
        if (!isOpenPath(method, path)) {
            val code = currentCode()
            if (code.isNotEmpty() && !isAuthed(headers, code)) {
                drain(input, headers["content-length"]?.toLongOrNull() ?: -1L)
                sendBytes(
                    output, 401, "application/json; charset=utf-8",
                    authRequiredJson().toByteArray(Charsets.UTF_8)
                )
                return
            }
        }

        when {
            method == "GET" && (path == "/" || path == "/index.html") ->
                sendHtml(output, page())

            // 页面一打开就来问一句"这台电视设了访问码没有"，据此决定要不要弹登录层。
            // 只回答"有没有"，不泄露码本身，所以不需要登录
            method == "GET" && path == "/auth" ->
                sendJson(output, authInfoJson())

            method == "POST" && path == "/login" ->
                doLogin(input, output, headers)

            method == "POST" && path == "/password" ->
                doPassword(input, output, headers)

            method == "GET" && path == "/list" ->
                sendJson(output, listJson())

            method == "GET" && (path == "/status" || path == "/diag") ->
                sendJson(output, statusJson())

            method == "GET" && path == "/preview" ->
                servePreview(query, headers, output, headOnly = false)

            method == "HEAD" && path == "/preview" ->
                servePreview(query, headers, output, headOnly = true)

            method == "GET" && path == "/remote" ->
                sendJson(output, remoteInfoJson())

            method == "POST" && path == "/remote" ->
                saveRemoteConfig(input, output, headers)

            method == "POST" && path == "/remote/test" ->
                sendJson(output, testRemoteUrl(query))

            method == "POST" && path == "/remote/off" ->
                sendJson(output, clearRemoteConfig())

            method == "POST" && path == "/upload" ->
                handleUpload(input, output, headers, query)

            method == "POST" && path == "/delete" ->
                sendJson(output, deleteByPath(query["src"], query["name"]))

            method == "POST" && path == "/clear" ->
                sendJson(output, clearSource(query["src"]))

            method == "GET" && path == "/favicon.ico" ->
                sendBytes(output, 204, "text/plain", ByteArray(0))

            else ->
                sendText(output, 404, "not found")
        }
    }

    // ==================== 各接口实现 ====================

    private fun handleUpload(
        input: InputStream,
        output: OutputStream,
        headers: Map<String, String>,
        query: Map<String, String>
    ) {
        val length = headers["content-length"]?.toLongOrNull() ?: -1L

        /*
         * 提前拒绝的时候，必须先把请求体丢掉再回响应。
         * 不丢的话手机还在往外发数据、服务端已经把连接关了 —— 手机那边报的是「网络中断」，
         * 把「这个格式不支持」这种小事误报成网络故障，现场根本没法查。
         */
        fun reject(message: String) {
            if (length > 0L) drain(input, length)
            sendJson(output, err(message))
        }

        val name = sanitize(query["name"].orEmpty())
        if (name == null) {
            reject("文件名不合法")
            return
        }
        if (!MediaLibrary.isMedia(name)) {
            reject("只接受图片或视频：$name")
            return
        }

        // 落盘前先确认真有目录。以前这里不查，目录建不出来时每个文件都静默失败，
        // 页面上只看到「完成：0 / 6」，根本看不出原因。
        val target = dir
        if (!target.isDirectory) target.mkdirs()
        if (!target.isDirectory) {
            Log.w(Config.TAG, "存不了：目录建不出来 ${target.absolutePath}")
            reject("电视上这个目录建不出来，文件没地方放：${target.absolutePath}")
            return
        }

        if (length <= 0L) {
            sendJson(output, err("缺少 Content-Length"))
            return
        }
        if (length > Config.UPLOAD_MAX_BYTES) {
            drain(input, length)
            sendJson(output, err("文件太大，上限 ${Config.UPLOAD_MAX_BYTES / 1024 / 1024} MB"))
            return
        }

        val tmp = File(target, ".$name.part")
        var written = 0L
        var failure: String? = null
        try {
            FileOutputStream(tmp).use { fos ->
                val buf = ByteArray(128 * 1024)
                var remain = length
                while (remain > 0L) {
                    val want = minOf(buf.size.toLong(), remain).toInt()
                    val n = input.read(buf, 0, want)
                    if (n < 0) break
                    fos.write(buf, 0, n)
                    written += n
                    remain -= n
                }
                fos.flush()
            }
        } catch (e: Exception) {
            failure = e.message ?: e.javaClass.simpleName
        }

        if (failure != null || written != length) {
            tmp.delete()
            val why = failure ?: "只收到 $written / $length 字节"
            Log.w(Config.TAG, "上传失败：$name —— $why（目录 ${target.absolutePath}）")
            sendJson(output, err("写入失败：$why"))
            return
        }

        val dest = File(target, name)
        if (dest.exists() && !dest.delete()) {
            tmp.delete()
            sendJson(output, err("无法覆盖同名文件，可能正在播放"))
            return
        }
        if (!tmp.renameTo(dest)) {
            tmp.delete()
            val why = "改名没成功（${tmp.absolutePath} → ${dest.absolutePath}）"
            Log.w(Config.TAG, "上传失败：$name —— $why")
            sendJson(output, err(why))
            return
        }

        Log.i(Config.TAG, "手机上传完成：$name（${length / 1024} KB）")
        notifyChanged(LibraryChange.UPLOAD)
        sendJson(output, okJson(name, length))
    }

    /**
     * 电视上全部素材，三个来源合在一起。
     *
     * 每条都带 `src`（来源）和 `path`（相对所属目录的路径）—— 删除时就靠这两个定位。
     * 光给文件名不行：media/ 是递归扫的，可能躺在子目录里（有些电视的文件管理器
     * 拷文件时会顺手多建一层），重名也可能撞上。
     */
    private fun listJson(): String {
        val arr = JSONArray()
        for ((src, root) in sourceDirs()) {
            if (root == null || !root.isDirectory) continue
            for (f in MediaLibrary.scan(root)) {
                val o = JSONObject()
                o.put("src", src)
                o.put("path", relativePath(root, f))
                o.put("name", f.name)
                o.put("size", f.length())
                o.put("mtime", f.lastModified())
                // 远端那几条在页面上只展示不给删，这里直接告诉前端，省得它自己猜
                if (src == SRC_REMOTE) o.put("readonly", true)
                arr.put(o)
            }
        }
        val root = JSONObject()
        root.put("ok", true)
        root.put("dir", dir.absolutePath)
        root.put("items", arr)
        return root.toString()
    }

    /** 三个来源和各自的目录。拿不到的（比如调用方没传）就跳过 */
    private fun sourceDirs(): List<Pair<String, File?>> = listOf(
        SRC_MANUAL to dir,
        SRC_MEDIA to runCatching { mediaDirProvider?.invoke() }.getOrNull(),
        SRC_REMOTE to runCatching { remoteDirProvider?.invoke() }.getOrNull()
    )

    private fun relativePath(root: File, f: File): String =
        f.absolutePath.removePrefix(root.absolutePath).trimStart('/', '\\').replace('\\', '/')

    /**
     * 电视当前状态。手机浏览器打开 http://电视IP:端口/status 就能看到。
     *
     * 为什么要有这个：电视系统一般没有 adb，屏幕上也看不到日志，
     * 一旦"传了不播"，光靠猜没法定位。这里把目录路径、文件列表、
     * 正在播第几条、上一次报什么错全都摊开，手机上瞄一眼就知道卡在哪一步。
     */
    private fun statusJson(): String {
        val root = try {
            statusProvider?.invoke() ?: JSONObject()
        } catch (e: Exception) {
            JSONObject().put("statusProviderError", e.message ?: e.javaClass.simpleName)
        }
        root.put("ok", true)
        root.put("device", deviceName)
        root.put("uploadRunning", running.get())
        root.put("uploadPort", port)
        root.put("uploadDir", dir.absolutePath)
        return root.toString()
    }

    /**
     * 删一个素材。src 缺省当 manual（老版本页面就是这么调的）。
     *
     * **远端目录（remote/）故意不给删。** 那里的文件是电脑端清单同步下来的，
     * 删掉之后下一次同步（默认 5 分钟）又会重新下回来 —— 用户看到的是"删了又自己回来了"，
     * 比不给删更让人糊涂。要下架远端素材，正确做法是改电脑端的清单，
     * 端侧清单里没有的条目本来就不会播（配了 prune 的还会顺手清掉本地文件）。
     */
    private fun deleteByPath(rawSrc: String?, rawPath: String?): String {
        val src = (rawSrc ?: SRC_MANUAL).lowercase()
        if (src == SRC_REMOTE) {
            return err("这条来自电脑端的清单，手机上删不掉。要下架请改电脑端的清单（改完电视几次同步内就不播了）")
        }
        val root = dirOf(src) ?: return err("不认识的来源：$src")
        if (!root.isDirectory) return err("目录不存在：${root.absolutePath}")

        val rel = rawPath?.trim().orEmpty()
        val target = resolveIn(root, rel) ?: return err("文件名不合法：$rel")
        if (!target.isFile) return err("文件不存在：${target.absolutePath}")

        if (!target.delete()) {
            // 删不掉大多是只读文件系统或文件正被占用。把完整路径带回去，别让人对着屏幕猜
            return err("删除失败：${target.absolutePath}（可能正被播放，或这块盘是只读的）")
        }
        Log.i(Config.TAG, "手机删除素材：$src/$rel")
        pruneEmptyDirs(root, target.parentFile)
        notifyChanged(LibraryChange.DELETE)
        return okJson(rel, 0L)
    }

    /** 按来源清空。同样不给清远端 —— 清完下次同步全回来，白忙一场 */
    private fun clearSource(rawSrc: String?): String {
        val src = (rawSrc ?: SRC_MANUAL).lowercase()
        if (src == SRC_REMOTE) {
            return err("远端素材由电脑端的清单控制，这里清不了。要清空请改清单")
        }
        val root = dirOf(src) ?: return err("不认识的来源：$src")
        if (!root.isDirectory) return err("目录不存在：${root.absolutePath}")

        var n = 0
        for (f in MediaLibrary.scan(root)) {
            if (f.delete()) n++
        }
        if (n > 0) {
            pruneEmptyDirs(root, root)
            notifyChanged(LibraryChange.DELETE)
        }
        Log.i(Config.TAG, "手机清空素材（$src），共 $n 个文件")
        val out = JSONObject()
        out.put("ok", true)
        out.put("removed", n)
        return out.toString()
    }

    // ==================== 素材内容预览 ====================

    /**
     * 把素材内容发给手机浏览器。
     *
     * 为什么要有：页面上原来只有文件名，没法确认「这条到底是不是我要播的那张」。
     * 传错一张、或者某张图其实是坏的，等跑到店里对着电视才发现就晚了。
     *
     * **必须支持 Range**：视频拖进度条全靠它，不支持的话浏览器只能从头播，有的干脆报错。
     * 三种写法都认（bytes=a-b / bytes=a- / bytes=-n），越界回 416 并把真实长度带回去，
     * 播放器拿到 `bytes * /总长` 会自己重发完整请求。
     */
    private fun servePreview(
        query: Map<String, String>,
        headers: Map<String, String>,
        output: OutputStream,
        headOnly: Boolean
    ) {
        val src = (query["src"] ?: SRC_MEDIA).lowercase()
        val root = dirOf(src)
        if (root == null || !root.isDirectory) {
            sendJson(output, err("不认识的来源：$src"))
            return
        }
        val rel = query["path"].orEmpty()
        val f = resolveIn(root, rel)
        if (f == null) {
            sendJson(output, err("路径不合法：$rel"))
            return
        }
        if (!f.isFile) {
            sendJson(output, err("文件不存在：$rel"))
            return
        }

        val size = f.length()
        val type = contentTypeOf(f.name)
        val range = parseRange(headers["range"], size)
        when {
            range === BAD_RANGE -> sendRangeError(output, size)
            range == null -> sendFile(output, 200, type, size, null, f, 0L, headOnly)
            else -> sendFile(
                output, 206, type, range.length,
                "bytes ${range.start}-${range.end}/$size", f, range.start, headOnly
            )
        }
    }

    /**
     * 解析 Range 头。
     *   null       = 客户端没要分段，整份发
     *   BAD_RANGE  = 要了但看不懂 / 越界，回 416
     */
    private fun parseRange(raw: String?, size: Long): ByteRange? {
        val h = raw?.trim().orEmpty()
        if (h.isEmpty()) return null
        if (!h.startsWith("bytes=")) return BAD_RANGE
        if (size <= 0L) return BAD_RANGE

        // 多段请求（bytes=0-9,20-29）只取第一段。为了这个极简服务去生成
        // multipart/byteranges 不值得，而浏览器/播放器实际发的基本都是单个区间。
        val first = h.removePrefix("bytes=").substringBefore(',').trim()
        val dash = first.indexOf('-')
        if (dash < 0) return BAD_RANGE
        val left = first.substring(0, dash).trim()
        val right = first.substring(dash + 1).trim()

        return try {
            if (left.isEmpty()) {
                // bytes=-n：最后 n 个字节
                val n = right.toLong()
                if (n <= 0L) BAD_RANGE
                else {
                    val len = minOf(n, size)
                    ByteRange(size - len, size - 1)
                }
            } else {
                val start = left.toLong()
                if (start < 0L || start >= size) {
                    BAD_RANGE
                } else {
                    val end = if (right.isEmpty()) size - 1 else minOf(right.toLong(), size - 1)
                    if (end < start) BAD_RANGE else ByteRange(start, end)
                }
            }
        } catch (e: NumberFormatException) {
            BAD_RANGE
        }
    }

    private fun sendFile(
        output: OutputStream,
        code: Int,
        contentType: String,
        contentLength: Long,
        contentRange: String?,
        file: File,
        from: Long,
        headOnly: Boolean
    ) {
        val head = StringBuilder(320)
            .append("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n")
            .append("Content-Type: ").append(contentType).append("\r\n")
            .append("Content-Length: ").append(contentLength).append("\r\n")
            .append("Accept-Ranges: bytes\r\n")
            .append("Cache-Control: no-store\r\n")
            .append("Connection: close\r\n")
        if (contentRange != null) head.append("Content-Range: ").append(contentRange).append("\r\n")
        head.append("\r\n")

        output.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        if (headOnly || contentLength <= 0L) {
            runCatching { output.flush() }
            return
        }

        try {
            // 用 RandomAccessFile 定位，不用 FileInputStream.skip()：后者在部分文件系统上
            // 会少跳，而视频拖进度条正好卡在"从第 N 字节开始发"这件事上，一错就是花屏。
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(from)
                val buf = ByteArray(64 * 1024)
                var remain = contentLength
                while (remain > 0L) {
                    val n = raf.read(buf, 0, minOf(buf.size.toLong(), remain).toInt())
                    if (n < 0) break
                    output.write(buf, 0, n)
                    remain -= n
                }
            }
        } catch (e: Exception) {
            // 拖进度条、退出页面都会让客户端中途断开，这属于正常现象，不当错误
            Log.i(Config.TAG, "预览中断：${file.name}（${e.javaClass.simpleName}）")
        }
        runCatching { output.flush() }
    }

    /** 416：把真实长度一并说清楚，播放器能自己纠正 */
    private fun sendRangeError(output: OutputStream, size: Long) {
        val body = err("Range 越界或不认识：这个文件只有 $size 字节").toByteArray(Charsets.UTF_8)
        val head = StringBuilder(256)
            .append("HTTP/1.1 416 ").append(reason(416)).append("\r\n")
            .append("Content-Type: application/json; charset=utf-8\r\n")
            .append("Content-Length: ").append(body.size).append("\r\n")
            .append("Accept-Ranges: bytes\r\n")
            .append("Content-Range: bytes */").append(size).append("\r\n")
            .append("Connection: close\r\n\r\n")
        output.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        output.write(body)
        output.flush()
    }

    private fun contentTypeOf(name: String): String =
        when (name.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            "gif" -> "image/gif"
            "mp4", "m4v" -> "video/mp4"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "mov" -> "video/quicktime"
            "3gp" -> "video/3gpp"
            "avi" -> "video/x-msvideo"
            "ts" -> "video/mp2t"
            else -> "application/octet-stream"
        }

    // ==================== 访问码（手机页的登录） ====================

    private fun codeFile(): File? = try {
        codeFileProvider?.invoke()
    } catch (e: Exception) {
        null
    }

    /**
     * 当前访问码。每次请求都真读一遍文件，不做缓存 ——
     * 缓存了就会出现"刚在手机上改完密码，旧手机照样能进"。
     */
    private fun currentCode(): String = UploadAuth.readCode(codeFile())

    /**
     * 不用登录就能访问的路径。
     * 加进来之前想清楚：这几个是"输码之前必须能用"的东西，多一个就多一个口子。
     */
    private fun isOpenPath(method: String, path: String): Boolean = when (method) {
        "GET" -> path == "/" || path == "/index.html" ||
                path == "/favicon.ico" || path == "/auth"
        "POST" -> path == "/login"
        else -> false
    }

    private fun isAuthed(headers: Map<String, String>, code: String): Boolean =
        UploadAuth.verifyToken(
            code,
            UploadAuth.parseCookie(headers["cookie"], UploadAuth.COOKIE_NAME),
            System.currentTimeMillis()
        )

    /** 401 的响应体。needLogin 是给页面看的：该弹登录层，而不是报"读不到列表" */
    private fun authRequiredJson(): String = JSONObject()
        .put("ok", false)
        .put("needLogin", true)
        .put("error", "需要访问码")
        .toString()

    /** 页面一打开就问这个：设没设码、码文件在哪。**不返回码本身** */
    private fun authInfoJson(): String {
        val file = codeFile()
        val o = JSONObject()
        o.put("ok", true)
        o.put("enabled", UploadAuth.readCode(file).isNotEmpty())
        o.put("minLen", UploadAuth.MIN_LEN)
        o.put("maxLen", UploadAuth.MAX_LEN)
        o.put("codeFile", file?.absolutePath ?: "")
        o.put("writable", try {
            file?.parentFile?.let { it.isDirectory && it.canWrite() } == true
        } catch (e: Exception) {
            false
        })
        return o.toString()
    }

    /**
     * 用访问码换一个 Cookie。
     *
     * 失败时故意睡 600ms：局域网里穷举一个 4 位码本来是几秒钟的事，
     * 拖慢之后就不划算了。手机多等半秒无所谓。
     */
    private fun doLogin(input: InputStream, output: OutputStream, headers: Map<String, String>) {
        val body = readJsonBody(input, headers)
        if (body == null) {
            sendJson(output, err("请求体不是合法的 JSON"))
            return
        }

        val code = currentCode()
        if (code.isEmpty()) {
            // 没设访问码，直接放行。可能是在另一台手机上刚把码关掉了，页面还没刷新
            sendJsonAt(
                output, 200,
                JSONObject().put("ok", true).put("enabled", false).toString()
            )
            return
        }

        val given = body.optString("code", "").trim()
        if (given.isEmpty()) {
            sendJsonAt(output, 400, err("请输入访问码"))
            return
        }
        if (!UploadAuth.constantTimeEquals(given, code)) {
            sleepQuietly(600)
            Log.i(Config.TAG, "手机输入访问码错误")
            sendJsonAt(output, 401, err("访问码不对"))
            return
        }

        val token = UploadAuth.makeToken(code, System.currentTimeMillis())
        Log.i(Config.TAG, "手机登录成功")
        sendBytes(
            output, 200, "application/json; charset=utf-8",
            JSONObject().put("ok", true).put("enabled", true).toString().toByteArray(Charsets.UTF_8),
            listOf("Set-Cookie" to UploadAuth.cookieHeader(token))
        )
    }

    /**
     * 设置 / 修改 / 关掉访问码。`next` 传空 = 关掉（回到"谁连着 WiFi 谁能用"）。
     *
     * 已经有码的时候，这条路由本身就在闸门后面，但仍然要求把当前码再输一遍：
     * 手机放在店里、或者浏览器还开着的时候，改码这么大的事不能只凭一个 cookie 就放行。
     */
    private fun doPassword(input: InputStream, output: OutputStream, headers: Map<String, String>) {
        val file = codeFile()
        if (file == null) {
            sendJson(output, err("这台电视没开访问码功能"))
            return
        }

        val body = readJsonBody(input, headers)
        if (body == null) {
            sendJson(output, err("请求体不是合法的 JSON"))
            return
        }

        val cur = body.optString("current", "").trim()
        val next = body.optString("next", "").trim()
        val code = UploadAuth.readCode(file)

        if (code.isNotEmpty() && !UploadAuth.constantTimeEquals(cur, code)) {
            sleepQuietly(600)
            sendJsonAt(output, 401, err("当前访问码不对"))
            return
        }

        if (next.isNotEmpty()) {
            UploadAuth.checkCode(next)?.let {
                sendJson(output, err(it))
                return
            }
            if (UploadAuth.constantTimeEquals(next, code)) {
                sendJson(output, err("新访问码和现在的一样，不用改"))
                return
            }
        }

        UploadAuth.writeCode(file, next)?.let {
            sendJson(output, err(it))
            return
        }

        // 日志里只记长度，别把码原文写到日志里 —— 这行日志会跟着 bug 报告一起被人看到
        Log.i(
            Config.TAG,
            if (next.isEmpty()) "手机关掉了访问码（局域网内谁都能打开上传页了）"
            else "手机改了访问码（${next.length} 个字符）"
        )

        val o = JSONObject()
        o.put("ok", true)
        o.put("enabled", next.isNotEmpty())
        o.put("codeFile", file.absolutePath)
        // 当前这台手机直接换上对应的凭证：刚改完就被自己踢出去最招人烦，
        // 而别的设备的旧凭证因为签名对不上，会立刻失效 —— 那正是改码想要的效果
        val cookie = if (next.isEmpty()) UploadAuth.clearCookieHeader()
        else UploadAuth.cookieHeader(UploadAuth.makeToken(next, System.currentTimeMillis()))
        sendBytes(
            output, 200, "application/json; charset=utf-8",
            o.toString().toByteArray(Charsets.UTF_8),
            listOf("Set-Cookie" to cookie)
        )
    }

    private fun sleepQuietly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun sendJsonAt(output: OutputStream, status: Int, body: String) =
        sendBytes(output, status, "application/json; charset=utf-8", body.toByteArray(Charsets.UTF_8))

    // ==================== 远端配置（服务器地址） ====================

    /**
     * 在手机上改这台电视该去拉哪份清单。
     *
     * 为什么值得做：10 台电视分散在几个店里，配错地址、换服务器、加一台新屏，
     * 原来都得抱着电脑上门 adb push 一个 json。现在扫个码就能改，改完立刻同步一次。
     * 配置文件本身还在素材根目录里（U 盘照样能改），这里只是多开了一条路。
     */
    private fun saveRemoteConfig(
        input: InputStream,
        output: OutputStream,
        headers: Map<String, String>
    ) {
        val fileOf = remoteConfigFileProvider
        if (fileOf == null) {
            sendJson(output, err("这台电视没开远端配置功能"))
            return
        }

        val body = readJsonBody(input, headers)
        if (body == null) {
            sendJson(output, err("请求体不是合法的 JSON 配置"))
            return
        }

        val url = body.optString("playlistUrl", "")
        RemoteConfig.checkPlaylistUrl(url)?.let {
            sendJson(output, err(it))
            return
        }
        val device = body.optString("device", "")
        RemoteConfig.checkDevice(device)?.let {
            sendJson(output, err(it))
            return
        }

        val interval = body.optInt("refreshIntervalSec", Config.DEFAULT_REFRESH_SEC)
            .coerceIn(RemoteConfig.MIN_REFRESH_SEC, RemoteConfig.MAX_REFRESH_SEC)
        val prune = body.optBoolean("prune", true)
        val assetBase = body.optString("assetBaseUrl", "").trim()

        val file = runCatching { fileOf() }.getOrNull()
        val dir = file?.parentFile
        if (file == null || dir == null) {
            sendJson(output, err("拿不到配置文件路径"))
            return
        }
        if (!dir.isDirectory) dir.mkdirs()
        if (!dir.isDirectory) {
            sendJson(output, err("配置目录建不出来，没地方写配置：${dir.absolutePath}"))
            return
        }

        // 先写临时文件再改名：写到一半被打断（拔电、进程被杀）不会留下一份半截配置 ——
        // 那样电视重启后连本地模式都进不去，屏幕直接空着。
        val tmp = File(dir, "." + file.name + ".part")
        // 原样带回去原有的 reportUrl。页面上没有这个输入框，漏掉它的话，
        // 手改过的 reportUrl（比如 off）会在保存地址之后悄悄消失 ——
        // 表现是「明明关掉了回传，过几天又报上了」，这种查起来最费劲
        val keepReportUrl = runCatching {
            if (file.isFile) {
                JSONObject(file.readText()).optString("reportUrl", "").trim().ifEmpty { null }
            } else {
                null
            }
        }.getOrNull()
        try {
            tmp.writeText(RemoteConfig.jsonText(url, device, interval, prune, assetBase, keepReportUrl))
            if (file.exists() && !file.delete()) throw IOException("旧配置删不掉")
            if (!tmp.renameTo(file)) throw IOException("改名失败")
        } catch (e: Exception) {
            tmp.delete()
            sendJson(
                output,
                err("保存配置失败：${e.message ?: e.javaClass.simpleName}（路径 ${file.absolutePath}）")
            )
            return
        }

        Log.i(Config.TAG, "手机改了远端配置：$url（device=$device，间隔 ${interval}s，prune=$prune）")
        notifyRemoteChanged()

        val o = JSONObject()
        o.put("ok", true)
        o.put("configFile", file.absolutePath)
        o.put("resolvedUrl", RemoteConfig.resolveUrl(url, device))
        o.put("refreshIntervalSec", interval)
        o.put("prune", prune)
        // 顺手告诉手机"改完地址之后状态会往哪报"，省得它以为没生效
        o.put(
            "reportTarget",
            Reporter.endpoint(RemoteConfig.resolveUrl(url, device), keepReportUrl) ?: ""
        )
        sendJson(output, o.toString())
    }

    /**
     * 只测这个地址能不能拉到合法清单，**不保存**。
     *
     * 先测再存：地址填错的话，十块屏会一起不更新，而端侧的表现只是"素材停在旧的那一版"，
     * 很难往地址上想。多按一下按钮，省一趟跑店。
     */
    private fun testRemoteUrl(query: Map<String, String>): String {
        val raw = query["url"].orEmpty()
        RemoteConfig.checkPlaylistUrl(raw)?.let { return err(it) }
        val device = query["device"].orEmpty()
        RemoteConfig.checkDevice(device)?.let { return err(it) }

        val url = RemoteConfig.resolveUrl(raw, device)
        val text = try {
            Http.getText(url)
        } catch (e: Exception) {
            return err("拉不到清单：${e.message ?: e.javaClass.simpleName}\n地址：$url")
        }

        val playlist = PlaylistParser.parse(text)
            ?: return err(
                "拉到了内容，但它不是端侧认得的清单。\n" +
                    "常见原因：地址指向的其实是错误页 / 目录列表；或者清单的 schemaVersion 比这台电视新。\n地址：$url"
            )

        val base = RemoteConfig.assetBaseFor(
            query["assetBaseUrl"]?.trim(), playlist.assetBaseUrl, url
        )

        val o = JSONObject()
        o.put("ok", true)
        o.put("resolvedUrl", url)
        o.put("revision", playlist.revision)
        o.put("count", playlist.entries.size)
        o.put("assetBase", base)

        val first = playlist.entries.firstOrNull()
        if (first == null) {
            o.put("note", "清单是空的 —— 相当于远端内容全部下架，电视会退回播本地目录 media/")
        } else {
            // 顺手戳一下第一条素材：清单拉得到、素材路径却是错的，是这套配置里最隐蔽的坑
            val assetUrl = RemoteConfig.assetUrl(base, first.file)
            o.put("firstAsset", first.file)
            o.put("assetNote", try {
                "第一条素材能取到（${Http.probe(assetUrl)}）"
            } catch (e: Exception) {
                "清单没问题，但第一条素材取不到：${e.message ?: e.javaClass.simpleName}（试的是 $assetUrl）"
            })
        }
        return o.toString()
    }

    /** 关掉远端：删掉配置文件，退回纯本地目录模式（第 1 步那套） */
    private fun clearRemoteConfig(): String {
        val fileOf = remoteConfigFileProvider ?: return err("这台电视没开远端配置功能")
        val file = runCatching { fileOf() }.getOrNull() ?: return err("拿不到配置文件路径")

        val existed = file.isFile
        if (existed && !file.delete()) {
            return err("删不掉配置文件：${file.absolutePath}（这块盘可能是只读的）")
        }

        Log.i(Config.TAG, "手机关掉了远端，退回本地目录模式")
        notifyRemoteChanged()

        val o = JSONObject()
        o.put("ok", true)
        o.put("removed", existed)
        o.put("configFile", file.absolutePath)
        return o.toString()
    }

    private fun remoteInfoJson(): String {
        if (remoteConfigFileProvider == null) return err("这台电视没开远端配置功能")

        val o = try {
            remoteInfoProvider?.invoke() ?: JSONObject()
        } catch (e: Exception) {
            JSONObject().put("providerError", e.message ?: e.javaClass.simpleName)
        }
        val file = runCatching { remoteConfigFileProvider?.invoke() }.getOrNull()
        o.put("ok", true)
        o.put("configFile", file?.absolutePath ?: "")
        o.put("writable", try {
            file?.parentFile?.let { it.isDirectory && it.canWrite() } == true
        } catch (e: Exception) {
            false
        })
        if (!o.has("configured")) o.put("configured", false)
        return o.toString()
    }

    /** 读满 Content-Length 个字节并按 JSON 解析。读不到/不是 JSON 都返回 null，由调用方给出明确错误 */
    private fun readJsonBody(input: InputStream, headers: Map<String, String>): JSONObject? {
        val length = headers["content-length"]?.toLongOrNull() ?: return null
        if (length <= 0L || length > 64 * 1024) return null

        val buf = ByteArray(length.toInt())
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) break
            off += n
        }
        if (off != buf.size) return null

        return try {
            JSONObject(String(buf, Charsets.UTF_8))
        } catch (e: Exception) {
            null
        }
    }

    private fun notifyRemoteChanged() {
        try {
            onRemoteConfigChanged?.invoke()
        } catch (e: Exception) {
            Log.w(Config.TAG, "通知远端配置变更失败", e)
        }
    }

    /**
     * 来源 → 目录。**远端也在里面** —— 预览要看，但删除和清空在各自的入口就
     * 对 `remote` 直接返回了，不会走到这里（远端删了下次同步又回来，见 deleteByPath）。
     */
    private fun dirOf(src: String): File? = when (src) {
        SRC_MANUAL -> dir
        SRC_MEDIA -> runCatching { mediaDirProvider?.invoke() }.getOrNull()
        SRC_REMOTE -> runCatching { remoteDirProvider?.invoke() }.getOrNull()
        else -> null
    }

    /**
     * 把「相对路径」安全地落到 root 底下。
     *
     * 拒绝空段、`.`、`..`、点开头的段（临时文件）和绝对路径，最后再用 canonicalPath
     * 复核一遍 —— 防的是软链接之类的花招把删除操作引到素材目录外面去。
     */
    private fun resolveIn(root: File, rel: String): File? {
        val clean = rel.replace('\\', '/').trim().trimStart('/')
        if (clean.isEmpty() || clean.length > 200) return null
        val segs = clean.split('/')
        if (segs.any { it.isEmpty() || it == "." || it == ".." || it.startsWith(".") }) return null
        if (!MediaLibrary.isMedia(segs.last())) return null
        return try {
            val f = File(root, clean)
            if (f.canonicalPath.startsWith(root.canonicalPath + File.separator)) f else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 删完文件顺手把空掉的子目录收掉，免得电视的文件管理器里留一堆空壳文件夹。
     * 从里往外删，碰到第一个非空目录就停。
     */
    private fun pruneEmptyDirs(root: File, start: File?) {
        var d: File? = start
        while (d != null) {
            if (d.absolutePath == root.absolutePath || !d.isDirectory) return
            if ((d.listFiles()?.size ?: 0) > 0) return
            if (!d.delete()) return
            d = d.parentFile
        }
    }

    private fun notifyChanged(kind: LibraryChange) {
        try {
            onLibraryChanged(kind)
        } catch (e: Exception) {
            Log.w(Config.TAG, "通知播放器刷新失败", e)
        }
    }

    // ==================== HTTP 基础 ====================

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder(128)
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
            if (sb.length > 8192) return null
        }
        return sb.toString()
    }

    private fun parseQuery(raw: String): Map<String, String> {
        val out = HashMap<String, String>()
        if (raw.isEmpty()) return out
        for (pair in raw.split("&")) {
            if (pair.isEmpty()) continue
            val i = pair.indexOf('=')
            val k = if (i >= 0) pair.substring(0, i) else pair
            val v = if (i >= 0) pair.substring(i + 1) else ""
            out[decode(k)] = decode(v)
        }
        return out
    }

    private fun decode(s: String): String = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (e: Exception) {
        s
    }

    /** 去掉路径、非法字符，防止有人往目录外写文件 */
    private fun sanitize(raw: String): String? {
        var n = raw.substringAfterLast('/').substringAfterLast('\\').trim()
        if (n.isEmpty() || n.length > 120) return null
        n = n.replace(ILLEGAL_CHARS, "_")
        if (n.startsWith(".") || n.contains("..")) return null
        if (File(n).name != n) return null
        return n.ifEmpty { null }
    }

    /** 丢掉请求体。最多丢 8 MB —— 真超限的巨型文件没必要陪着读完，回个错误就够了 */
    private fun drain(input: InputStream, length: Long) {
        var remain = minOf(length, DRAIN_MAX_BYTES)
        val buf = ByteArray(64 * 1024)
        try {
            while (remain > 0L) {
                val want = minOf(buf.size.toLong(), remain).toInt()
                val n = input.read(buf, 0, want)
                if (n < 0) break
                remain -= n
            }
        } catch (e: Exception) {
            // 丢弃即可
        }
    }

    private fun sendJson(output: OutputStream, body: String) =
        sendBytes(output, 200, "application/json; charset=utf-8", body.toByteArray(Charsets.UTF_8))

    private fun sendHtml(output: OutputStream, body: String) =
        sendBytes(output, 200, "text/html; charset=utf-8", body.toByteArray(Charsets.UTF_8))

    private fun sendText(output: OutputStream, code: Int, body: String) =
        sendBytes(output, code, "text/plain; charset=utf-8", body.toByteArray(Charsets.UTF_8))

    private fun sendBytes(
        output: OutputStream,
        code: Int,
        contentType: String,
        body: ByteArray,
        extraHeaders: List<Pair<String, String>> = emptyList()
    ) {
        val head = StringBuilder(256)
            .append("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n")
            .append("Content-Type: ").append(contentType).append("\r\n")
            .append("Content-Length: ").append(body.size).append("\r\n")
            .append("Cache-Control: no-store\r\n")
        // Set-Cookie 之类的附加头。登录成功时就是靠这里把凭证发回手机的
        for ((k, v) in extraHeaders) {
            head.append(k).append(": ").append(v).append("\r\n")
        }
        head.append("Connection: close\r\n").append("\r\n")

        output.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        if (body.isNotEmpty()) output.write(body)
        output.flush()
    }

    private fun reason(code: Int): String = when (code) {
        200 -> "OK"
        204 -> "No Content"
        206 -> "Partial Content"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        413 -> "Payload Too Large"
        416 -> "Range Not Satisfiable"
        500 -> "Internal Server Error"
        else -> "OK"
    }

    private fun okJson(name: String, size: Long): String {
        val o = JSONObject()
        o.put("ok", true)
        o.put("name", name)
        o.put("size", size)
        return o.toString()
    }

    private fun err(message: String): String {
        val o = JSONObject()
        o.put("ok", false)
        o.put("error", message)
        return o.toString()
    }

    /** 挑一个局域网 IPv4：优先无线/有线网卡，其次任何非内网保留地址 */
    private fun localIp(): String? {
        return try {
            var fallback: String? = null
            for (nif in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp || nif.isLoopback) continue
                val ifName = nif.name.lowercase()
                for (addr in Collections.list(nif.inetAddresses)) {
                    if (addr !is Inet4Address || addr.isLoopbackAddress) continue
                    val ip = addr.hostAddress ?: continue
                    if (ip.startsWith("169.254.")) continue
                    if (ifName.startsWith("wlan") || ifName.startsWith("eth") || ifName.startsWith("ap")) {
                        return ip
                    }
                    if (fallback == null) fallback = ip
                }
            }
            fallback
        } catch (e: Exception) {
            Log.w(Config.TAG, "获取局域网 IP 失败", e)
            null
        }
    }

    private fun page(): String = PAGE_TEMPLATE.replace("__DEVICE__", deviceName)

    private companion object {
        val ILLEGAL_CHARS = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]")

        /** 拒绝请求时最多丢掉多少请求体（字节） */
        const val DRAIN_MAX_BYTES = 8L * 1024 * 1024

        /** 三个素材来源。页面上传的就是这三个字符串，服务端按白名单认，绝不拿来拼路径 */
        const val SRC_MANUAL = "manual"
        const val SRC_MEDIA = "media"
        const val SRC_REMOTE = "remote"

        /** 客户端要的区间看不懂/越界。用同一个实例做标记，靠引用比较认出来 */
        val BAD_RANGE = ByteRange(-1, -1)
    }
}

/**
 * 解析出来的字节区间，start/end 都是闭区间下标。
 * 单独抽出来是为了让 Range 的解析逻辑不碰网络、不碰文件，能单独验。
 */
private class ByteRange(val start: Long, val end: Long) {
    val length: Long get() = end - start + 1
}

/** 手机扫码后看到的页面。整体是内嵌的，不依赖任何外部资源，店里断网也能用。 */
private val PAGE_TEMPLATE = """<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="theme-color" content="#111111">
<title>门店屏 · 手机上传</title>
<style>
  *{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
  body{margin:0;padding:16px 16px 40px;background:#f5f5f7;color:#111;
       font:16px/1.5 -apple-system,BlinkMacSystemFont,"PingFang SC","Microsoft YaHei",sans-serif}
  h1{font-size:19px;margin:8px 0 4px}
  .sub{color:#666;font-size:13px;margin-bottom:16px;word-break:break-all}
  .card{background:#fff;border-radius:14px;padding:16px;margin-bottom:14px;
        box-shadow:0 1px 3px rgba(0,0,0,.08)}
  .ttl{font-weight:600;margin-bottom:8px}
  .pick{display:block;padding:20px;border:2px dashed #c8c8cd;border-radius:12px;
        text-align:center;color:#0071e3;font-size:17px;font-weight:600;background:#fbfbfd}
  input[type=file]{display:none}
  button{font:inherit;border:0;border-radius:10px;padding:13px 18px;background:#0071e3;
         color:#fff;font-weight:600;width:100%;margin-top:12px}
  button:disabled{background:#c8c8cd}
  button.ghost{background:#efeff3;color:#333}
  ul{list-style:none;padding:0;margin:0}
  li{display:flex;align-items:center;gap:10px;padding:10px 0;border-bottom:1px solid #f0f0f2;font-size:14px}
  li:last-child{border-bottom:0}
  .nm{flex:1;word-break:break-all}
  .sz{color:#999;font-size:12px;white-space:nowrap}
  .del{color:#d02b2b;background:none;border:0;padding:6px 4px;width:auto;margin:0;font-size:14px}
  .tag{font-size:11px;line-height:1.6;padding:1px 6px;border-radius:6px;white-space:nowrap;flex:0 0 auto;
       background:#eef1f6;color:#5a6472}
  .tag.manual{background:#e3f0ff;color:#0b62c4}
  .tag.remote{background:#e8f5ec;color:#1d7a45}
  .lock{color:#9aa0a8;font-size:12px;white-space:nowrap;flex:0 0 auto}
  .bar{height:6px;background:#e8e8ed;border-radius:3px;overflow:hidden;margin-top:12px}
  .bar>i{display:block;height:100%;width:0;background:#0071e3;transition:width .15s}
  .status{font-size:13px;color:#666;margin-top:8px;min-height:18px;word-break:break-all;
          white-space:pre-wrap}
  .status.bad{color:#d02b2b}
  .warn{font-size:13px;line-height:1.5;background:#fff3cd;color:#7a4b00;border-radius:10px;
        padding:10px 12px;margin-bottom:14px;word-break:break-all;white-space:pre-wrap}
  .warn.info{background:#e8f0fe;color:#1a3f7a}
  .empty{color:#999;font-size:14px;padding:6px 0}
  /* 素材缩略图：一行一个，点开看大图。图片用原图缩，服务端不装图像库所以不做压缩 */
  .th{width:46px;height:46px;flex:0 0 auto;border-radius:8px;background:#eef0f4;
      object-fit:cover;display:block}
  .th.vid{display:flex;align-items:center;justify-content:center;color:#5a6472;font-size:17px;
      background:#e7ebf2}
  .nm{cursor:pointer}
  /* 预览层 */
  .viewer{position:fixed;top:0;left:0;right:0;bottom:0;background:rgba(0,0,0,.93);z-index:50;
      display:flex;flex-direction:column}
  .viewer.hide{display:none}
  .vbar{display:flex;align-items:center;gap:8px;padding:12px 14px;color:#fff;font-size:13px}
  .vbar .who{flex:1;word-break:break-all}
  .vbar .btns{flex:0 0 auto;white-space:nowrap}
  .vbar button{width:auto;margin:0 4px 0 0;padding:8px 11px;font-size:13px;background:#2c2c2e;color:#fff;
      border-radius:8px}
  .vbar button:disabled{background:#1c1c1e;color:#666}
  .vstage{flex:1;display:flex;align-items:center;justify-content:center;padding:0 10px 16px;min-height:0}
  .vstage img,.vstage video{max-width:100%;max-height:100%;border-radius:8px}
  .vnote{color:#aaa;font-size:13px;text-align:center;padding:24px;line-height:1.6}
  /* 服务器设置表单 */
  .fld{display:block;font-size:13px;color:#555;margin:12px 0 0}
  .fld input{display:block;width:100%;margin-top:6px;font:inherit;font-size:15px;padding:11px 12px;
      border:1px solid #d5d5da;border-radius:10px;background:#fff;color:#111}
  .fld input:focus{outline:2px solid #0071e3;outline-offset:-1px;border-color:#0071e3}
  .chk{display:flex;gap:9px;align-items:flex-start;font-size:13px;color:#555;margin:14px 0 0;
      line-height:1.45}
  .chk input{width:auto;margin:2px 0 0;flex:0 0 auto}
  details.adv{margin-top:12px}
  details.adv summary{font-size:13px;color:#0071e3;cursor:pointer;padding:4px 0}
  .tip{color:#999;font-size:12px;margin-top:10px;text-align:center}
  /* 访问码：登录遮罩。设了码之后进页面先看到这个 */
  .gate{position:fixed;top:0;left:0;right:0;bottom:0;background:#f5f5f7;z-index:60;
      display:flex;align-items:center;justify-content:center;padding:20px}
  .gate.hide{display:none}
  .gatebox{width:100%;max-width:380px;background:#fff;border-radius:16px;padding:20px;
      box-shadow:0 8px 26px rgba(0,0,0,.14)}
  .gatebox h2{font-size:18px;margin:0 0 6px}
  .gatebox .who2{color:#666;font-size:13px;margin-bottom:6px;word-break:break-all}
  .hintbox{font-size:13px;color:#666;background:#f7f7f9;border-radius:10px;padding:11px 12px;
      margin-top:14px;line-height:1.55;word-break:break-all}
</style>
</head>
<body>
  <h1>门店屏 · 手机上传</h1>
  <div class="sub" id="who">__DEVICE__</div>
  <div class="warn" id="warn" style="display:none"></div>

  <div class="gate hide" id="gate">
    <div class="gatebox">
      <h2>需要访问码</h2>
      <div class="who2" id="gateWho">__DEVICE__</div>
      <label class="fld">访问码
        <input id="gateCode" type="password" autocomplete="current-password">
      </label>
      <button id="gateBtn">进入</button>
      <div class="status" id="gateSt"></div>
      <div class="hintbox">忘了访问码：把 U 盘插到电视上，或者用电视自带的文件管理器，
        删掉 signage 目录里的 <b>signage-upload.json</b>，这个页面就恢复成不用访问码。</div>
    </div>
  </div>

  <div class="card">
    <div class="ttl">选择要上传的内容</div>
    <label class="pick" for="f">点击选图片或视频</label>
    <input id="f" type="file" accept="video/*,image/*" multiple>
    <ul id="queue"></ul>
    <button id="go" disabled>开始上传</button>
    <div class="bar"><i id="pb"></i></div>
    <div class="status" id="st"></div>
    <div class="tip">电视会自动插播刚上传的内容，传完不用做别的</div>
  </div>

  <div class="card">
    <div class="ttl">电视上的素材</div>
    <div class="sub" style="margin:0 0 4px">点缩略图或文件名看内容。删掉一条十几秒内就生效，电视会自动重排，不用重启。</div>
    <ul id="lst"></ul>
    <button class="ghost" id="clr" style="display:none">清空手机上传的内容</button>
    <div class="status" id="lstSt"></div>
  </div>

  <div class="card">
    <div class="ttl">电脑端清单（服务器地址）</div>
    <div class="sub" style="margin:0 0 4px" id="remNow">读取中…</div>
    <div class="sub" style="margin:0 0 10px" id="remRep"></div>
    <label class="fld">清单地址
      <input id="remUrl" type="url" inputmode="url" autocapitalize="off" autocorrect="off"
             placeholder="https://桶名.cos.ap-shanghai.myqcloud.com/playlist.json">
    </label>
    <label class="fld">设备名（清单地址里可以写 {device} 占位）
      <input id="remDev" type="text" autocapitalize="off" autocorrect="off" placeholder="A店">
    </label>
    <label class="fld">多久拉一次清单（分钟）
      <input id="remMin" type="number" inputmode="numeric" min="1" max="1440" value="5">
    </label>
    <label class="chk"><input id="remPrune" type="checkbox" checked>
      <span>清单里没有的素材，顺手把电视上的本地文件清掉（推荐开着，免得旧素材一直占地方）</span></label>
    <details class="adv">
      <summary>高级：素材地址前缀（一般不用填）</summary>
      <label class="fld">素材地址前缀
        <input id="remBase" type="url" inputmode="url" autocapitalize="off" autocorrect="off"
               placeholder="留空 = 自动用清单所在目录">
      </label>
      <div class="tip" style="text-align:left">只有当素材和清单不在同一个目录时才需要填。</div>
    </details>
    <button id="remSave">保存并立即同步</button>
    <button class="ghost" id="remTest">先测一下能不能连上（不保存）</button>
    <button class="ghost" id="remOff">关掉远端，只用本地目录</button>
    <div class="status" id="remSt"></div>
    <div class="tip">改完不用碰电视。同一份 APK 装到十台屏上，各自在这里改一下设备名就行。</div>
  </div>

  <div class="card">
    <div class="ttl">访问码（保护这个上传页）</div>
    <div class="sub" style="margin:0 0 10px" id="authNow">读取中…</div>
    <label class="fld">当前访问码
      <input id="curCode" type="password" autocomplete="current-password" placeholder="还没设过就留空">
    </label>
    <label class="fld">新访问码
      <input id="newCode" type="password" autocomplete="new-password" placeholder="至少 4 位，中文也行">
    </label>
    <label class="fld">新访问码再输一遍
      <input id="newCode2" type="password" autocomplete="new-password">
    </label>
    <button id="saveCode">保存访问码</button>
    <button class="ghost" id="offCode">关掉访问码（局域网内谁都能用）</button>
    <div class="status" id="authSt"></div>
    <div class="tip">没设访问码时，和电视连同一个 WiFi 的任何人打开这个页面，都能传素材、删素材。
      设过之后所有操作都要先输一次码，手机上会记住 14 天。<br>
      改完其他手机上的登录会立刻失效，得重新输新码。</div>
  </div>

  <div class="card">
    <div class="ttl">电视状态（传了不播时点这里看）</div>
    <button class="ghost" id="diagBtn">查看播放状态</button>
    <pre id="diag" style="display:none;white-space:pre-wrap;word-break:break-all;font-size:12px;line-height:1.6;color:#444;background:#f7f7f9;border-radius:8px;padding:10px;margin:12px 0 0"></pre>
  </div>

  <div class="viewer hide" id="viewer">
    <div class="vbar">
      <span class="who" id="vWho"></span>
      <span class="btns">
        <button id="vPrev">上一条</button>
        <button id="vNext">下一条</button>
        <button id="vClose">关闭</button>
      </span>
    </div>
    <div class="vstage" id="vStage"></div>
  </div>

<script>
var picked = [];
var fEl = document.getElementById('f');
var goEl = document.getElementById('go');
var stEl = document.getElementById('st');
var pbEl = document.getElementById('pb');
var qEl = document.getElementById('queue');
var lEl = document.getElementById('lst');
var lstStEl = document.getElementById('lstSt');
var clrEl = document.getElementById('clr');

var SRC_NAME = { manual: '手机', media: '本地', remote: '远端' };

var viewerEl = document.getElementById('viewer');
var vStageEl = document.getElementById('vStage');
var vWhoEl = document.getElementById('vWho');
var vPrevEl = document.getElementById('vPrev');
var vNextEl = document.getElementById('vNext');
var vIndex = -1;
var viewList = [];
var lastItems = [];        // 最近一次列表，预览层靠它翻"上一条/下一条"
var remNowEl = document.getElementById('remNow');
var remStEl = document.getElementById('remSt');

document.getElementById('who').textContent = '__DEVICE__  ·  ' + location.host;

function fmt(n){
  if (n < 1024) return n + ' B';
  if (n < 1048576) return (n / 1024).toFixed(0) + ' KB';
  return (n / 1048576).toFixed(1) + ' MB';
}

function renderQueue(){
  qEl.innerHTML = '';
  if (!picked.length){ goEl.disabled = true; return; }
  goEl.disabled = false;
  for (var i = 0; i < picked.length; i++){
    var li = document.createElement('li');
    var a = document.createElement('span'); a.className = 'nm'; a.textContent = picked[i].name;
    var b = document.createElement('span'); b.className = 'sz'; b.textContent = fmt(picked[i].size);
    li.appendChild(a); li.appendChild(b);
    qEl.appendChild(li);
  }
}

fEl.addEventListener('change', function(){
  for (var i = 0; i < fEl.files.length; i++) picked.push(fEl.files[i]);
  renderQueue();
});

goEl.addEventListener('click', function(){
  if (!picked.length) return;
  goEl.disabled = true;
  var list = picked.slice();
  picked = [];
  renderQueue();
  uploadNext(list, 0, 0, []);
});

function say(text, bad){
  stEl.textContent = text;
  stEl.className = bad ? 'status bad' : 'status';
}

function uploadNext(list, idx, okCount, fails){
  var total = list.length;
  if (idx >= total){
    pbEl.style.width = '100%';
    if (fails.length){
      // 失败原因攒着一起说。以前这里被"完成 0 / 6"盖掉，等于什么都没说
      say('完成：' + okCount + ' / ' + total + ' 个成功。失败原因：\n' + fails.join('\n'), true);
    } else {
      say('完成：' + okCount + ' / ' + total + ' 个文件已传到电视', false);
    }
    fEl.value = '';
    loadList();
    return;
  }
  var f = list[idx];
  pbEl.style.width = Math.round(idx / total * 100) + '%';
  say('正在传 ' + (idx + 1) + '/' + total + '：' + f.name, false);

  var xhr = new XMLHttpRequest();
  xhr.open('POST', '/upload?name=' + encodeURIComponent(f.name));
  xhr.setRequestHeader('Content-Type', 'application/octet-stream');
  xhr.upload.onprogress = function(e){
    if (!e.lengthComputable) return;
    var pct = (idx + e.loaded / e.total) / total * 100;
    pbEl.style.width = Math.round(pct) + '%';
  };
  xhr.onload = function(){
    var ok = false, msg = '';
    try {
      var r = JSON.parse(xhr.responseText);
      ok = r.ok === true;
      msg = r.error || '';
    } catch (err){}
    if (ok){
      okCount++;
    } else {
      var why = msg || ('HTTP ' + xhr.status);
      fails.push('· ' + f.name + ' → ' + why);
      say('传 ' + f.name + ' 失败：' + why, true);
    }
    uploadNext(list, idx + 1, okCount, fails);
  };
  xhr.onerror = function(){
    say('网络中断。确认手机和电视连的是同一个 WiFi', true);
    goEl.disabled = false;
  };
  xhr.send(f);
}

function sayList(text, bad){
  lstStEl.textContent = text || '';
  lstStEl.className = bad ? 'status bad' : 'status';
}

function loadList(){
  var xhr = new XMLHttpRequest();
  xhr.open('GET', '/list');
  xhr.onload = function(){
    var data = { items: [] };
    try { data = JSON.parse(xhr.responseText); } catch (err){}
    renderList(data.items || []);
  };
  xhr.onerror = function(){ sayList('读不到素材列表，确认手机和电视连的是同一个 WiFi', true); };
  xhr.send();
}

function renderList(items){
  lastItems = items || [];
  lEl.innerHTML = '';
  if (!lastItems.length){
    var d = document.createElement('div');
    d.className = 'empty';
    d.textContent = '电视上还没有素材。上面传几个，或者把文件拷进电视的 media 目录。';
    lEl.appendChild(d);
    clrEl.style.display = 'none';
    return;
  }
  var manualCount = 0;
  for (var i = 0; i < lastItems.length; i++){
    var it = lastItems[i];
    if (it.src === 'manual') manualCount++;

    var li = document.createElement('li');
    li.appendChild(thumbNode(it));

    var t = document.createElement('span');
    t.className = 'tag ' + (it.src || '');
    t.textContent = SRC_NAME[it.src] || it.src || '?';

    var a = document.createElement('span'); a.className = 'nm'; a.textContent = it.path || it.name;
    a.onclick = (function(src, path){
      return function(){ openViewer(src, path); };
    })(it.src, it.path);

    var b = document.createElement('span'); b.className = 'sz'; b.textContent = fmt(it.size);

    li.appendChild(t); li.appendChild(a); li.appendChild(b);

    if (it.readonly){
      // 远端那几条是电脑端清单同步下来的，删了下次同步自己会回来，所以只说明、不给删
      var lk = document.createElement('span');
      lk.className = 'lock';
      lk.textContent = '改清单下架';
      li.appendChild(lk);
    } else {
      var c = document.createElement('button');
      c.className = 'del'; c.textContent = '删除';
      c.onclick = (function(src, path){
        return function(){ del(src, path); };
      })(it.src, it.path);
      li.appendChild(c);
    }
    lEl.appendChild(li);
  }
  clrEl.style.display = manualCount ? 'block' : 'none';
}

// ==================== 素材内容预览 ====================

function pvUrl(src, path){
  return '/preview?src=' + encodeURIComponent(src || 'manual') +
         '&path=' + encodeURIComponent(path || '');
}

function isVideoName(n){
  return /\.(mp4|m4v|mkv|webm|mov|3gp|avi|ts)$/i.test(n || '');
}

function thumbNode(it){
  var video = isVideoName(it.path || it.name);
  var node;
  if (video){
    node = document.createElement('span');
    node.className = 'th vid';
    node.textContent = '▶';
  } else {
    node = document.createElement('img');
    node.className = 'th';
    node.alt = '';
    node.loading = 'lazy';     // 滚到才拉，免得一屏三十张大图全下来
    // 原图直接缩小显示。服务端不装图像库，所以不做压缩 —— 大图多的首屏会慢几秒
    node.src = pvUrl(it.src, it.path);
  }
  node.onclick = function(){ openViewer(it.src, it.path); };
  return node;
}

function openViewer(src, path){
  viewList = lastItems.slice();
  vIndex = -1;
  for (var i = 0; i < viewList.length; i++){
    if (viewList[i].src === src && viewList[i].path === path){ vIndex = i; break; }
  }
  if (vIndex < 0) return;
  viewerEl.className = 'viewer';
  document.body.style.overflow = 'hidden';
  renderViewer();
}

function closeViewer(){
  viewerEl.className = 'viewer hide';
  document.body.style.overflow = '';
  vStageEl.innerHTML = '';     // 停掉正在播的视频，别在后台接着响
  vIndex = -1;
}

function showViewerNote(text){
  vStageEl.innerHTML = '';
  var d = document.createElement('div');
  d.className = 'vnote';
  d.textContent = text;
  vStageEl.appendChild(d);
}

/**
 * 素材打不开时，别只留一片黑。回读一次接口，把服务端给的原因原样说出来
 * （文件不存在 / 路径不合法 / 手机和电视不在同一个 WiFi）。
 */
function explainPreviewFailure(it, path){
  var xhr = new XMLHttpRequest();
  xhr.open('GET', pvUrl(it.src, path));
  xhr.onload = function(){
    var msg = '';
    try { msg = (JSON.parse(xhr.responseText) || {}).error || ''; } catch (e){}
    showViewerNote(msg ? ('看不了这条：' + msg)
                       : ('看不了这条：' + path + '（HTTP ' + xhr.status + '）'));
  };
  xhr.onerror = function(){
    showViewerNote('看不了这条：手机和电视可能不在同一个 WiFi');
  };
  xhr.send();
}

function renderViewer(){
  var it = viewList[vIndex];
  if (!it) return;
  var path = it.path || it.name;

  vWhoEl.textContent = path + '（' + (SRC_NAME[it.src] || it.src) + ' · ' + fmt(it.size) +
                       '）　' + (vIndex + 1) + '/' + viewList.length;
  vPrevEl.disabled = vIndex <= 0;
  vNextEl.disabled = vIndex >= viewList.length - 1;

  vStageEl.innerHTML = '';
  if (isVideoName(path)){
    var v = document.createElement('video');
    v.controls = true;
    v.autoplay = true;
    v.playsInline = true;
    v.preload = 'metadata';
    v.src = pvUrl(it.src, path);
    v.onerror = function(){ explainPreviewFailure(it, path); };
    vStageEl.appendChild(v);
  } else {
    var img = document.createElement('img');
    img.alt = path;
    img.src = pvUrl(it.src, path);
    img.onerror = function(){ explainPreviewFailure(it, path); };
    vStageEl.appendChild(img);
  }
}

vPrevEl.onclick = function(){ if (vIndex > 0){ vIndex--; renderViewer(); } };
vNextEl.onclick = function(){ if (vIndex < viewList.length - 1){ vIndex++; renderViewer(); } };
document.getElementById('vClose').onclick = closeViewer;

document.addEventListener('keydown', function(e){
  if (viewerEl.className.indexOf('hide') >= 0) return;
  if (e.key === 'Escape'){ closeViewer(); }
  else if (e.key === 'ArrowLeft' && vIndex > 0){ vIndex--; renderViewer(); }
  else if (e.key === 'ArrowRight' && vIndex < viewList.length - 1){ vIndex++; renderViewer(); }
});

function del(src, path){
  if (!confirm('删除 ' + path + ' ？电视十几秒内就会重排。')) return;
  sayList('正在删除 ' + path + ' …', false);
  var xhr = new XMLHttpRequest();
  xhr.open('POST', '/delete?src=' + encodeURIComponent(src) + '&name=' + encodeURIComponent(path));
  xhr.onload = function(){
    var r = {};
    try { r = JSON.parse(xhr.responseText); } catch (e){}
    if (r.ok === true) sayList('已删除：' + path, false);
    else sayList('删除失败：' + (r.error || ('HTTP ' + xhr.status)), true);
    loadList();
  };
  xhr.onerror = function(){ sayList('网络中断，' + path + ' 没删掉', true); };
  xhr.send();
}

clrEl.addEventListener('click', function(){
  if (!confirm('清空手机上传的内容？不会动本地目录和电脑端清单同步下来的素材。')) return;
  sayList('正在清空…', false);
  var xhr = new XMLHttpRequest();
  xhr.open('POST', '/clear?src=manual');
  xhr.onload = function(){
    var r = {};
    try { r = JSON.parse(xhr.responseText); } catch (e){}
    if (r.ok !== true) sayList('清空失败：' + (r.error || ('HTTP ' + xhr.status)), true);
    else sayList('已清空 ' + (r.removed || 0) + ' 个文件', false);
    loadList();
  };
  xhr.onerror = function(){ sayList('网络中断，没清掉', true); };
  xhr.send();
});

var diagBtn = document.getElementById('diagBtn');
var diagEl = document.getElementById('diag');

diagBtn.addEventListener('click', function(){
  diagBtn.disabled = true;
  diagEl.style.display = 'block';
  diagEl.textContent = '读取中…';
  var xhr = new XMLHttpRequest();
  xhr.open('GET', '/status');
  xhr.onload = function(){
    diagBtn.disabled = false;
    var d = {};
    try { d = JSON.parse(xhr.responseText); }
    catch (e){ diagEl.textContent = '解析失败：' + xhr.responseText; return; }
    if (!d.ok){ diagEl.textContent = '电视返回异常：' + xhr.responseText; return; }
    diagEl.textContent = renderStatus(d);
  };
  xhr.onerror = function(){
    diagBtn.disabled = false;
    diagEl.textContent = '读不到状态，检查手机和电视是不是同一个 WiFi';
  };
  xhr.send();
});

function filesOf(arr){
  arr = arr || [];
  if (!arr.length) return '  （空）';
  var out = [];
  for (var i = 0; i < arr.length; i++){
    out.push('  ' + (i + 1) + '. ' + arr[i].name + '  ' + fmt(arr[i].size));
  }
  return out.join('\n');
}

function renderStatus(d){
  var pb = d.playback || {};
  var L = [];
  L.push('版本：' + (d.version || '?'));
  L.push('模式：' + (d.mode || '?') + '　触屏点击：' + (d.touchTap ? '开' : '关'));
  if (d.remoteConfigured){
    L.push('清单地址：' + (d.remotePlaylistUrl || '?'));
    L.push('配置文件：' + (d.remoteConfigFile || '?'));
  }
  L.push('上传地址：' + (d.uploadUrl || '（没检测到局域网地址）'));
  L.push('');
  L.push('【播放】共 ' + (pb.total || 0) + ' 条，当前第 ' + ((pb.index || 0) + 1) + ' 条：' + (pb.file || '（空）'));
  L.push('　播放中：' + (pb.playing ? '是' : '否') +
         (pb.file ? (pb.video ? '（视频）' : '（图片）') : '') +
         '　已播 ' + (pb.runningSec >= 0 ? pb.runningSec + ' 秒' : '-'));
  L.push('　连续失败：' + (pb.failStreak || 0));
  if (d.lastError) L.push('　上次错误：' + d.lastError);
  L.push('');
  L.push('【存储】' + (d.storageRoot || '?') + '　' + (d.storageLabel || '') +
         (d.storageWritable === false ? '（实测不可写！）' : '（实测可写）'));
  var cs = d.storageCandidates || [];
  for (var i = 0; i < cs.length; i++){
    L.push('　· ' + (cs[i].writable ? '可写　' : '不可写') + ' ' + cs[i].path);
  }
  L.push('');
  L.push('【本地目录 media】' + (d.mediaDir || ''));
  L.push(filesOf(d.mediaFiles));
  L.push('');
  L.push('【手机上传 manual】' + (d.manualDir || ''));
  L.push(filesOf(d.manualFiles));
  L.push('');
  L.push('【远端 remote】' + (d.remoteDir || ''));
  L.push(filesOf(d.remoteFiles));
  if (d.remoteMessage){ L.push(''); L.push('远端同步：' + d.remoteMessage); }
  return L.join('\n');
}

/**
 * 打开页面时顺手看一眼电视的目录到底能不能写。
 * 不能写就直接在顶上报警 —— 省得又出现「传了好几个、成功 0 个」还看不出原因。
 */
function checkStorage(){
  var warnEl = document.getElementById('warn');
  var xhr = new XMLHttpRequest();
  xhr.open('GET', '/status');
  xhr.onload = function(){
    var d = {};
    try { d = JSON.parse(xhr.responseText); } catch (e){ return; }
    if (d.storageWritable === false){
      warnEl.className = 'warn';
      warnEl.textContent = '电视上没找到能写的目录，上传会失败。\n当前：' + (d.storageRoot || '?') +
        '\n请检查电视的存储权限，或者插一张 SD 卡再试。';
    } else if (d.storageRoot){
      warnEl.className = 'warn info';
      warnEl.textContent = '素材目录：' + d.storageRoot + '（' + (d.storageLabel || '') +
        '）。\n把图片、视频拷进这个目录下的 media 文件夹，电视也会自动播。';
    }
    warnEl.style.display = 'block';
  };
  xhr.send();
}

// ==================== 服务器地址（远端清单） ====================

function remSay(text, bad){
  remStEl.textContent = text || '';
  remStEl.className = bad ? 'status bad' : 'status';
}

function loadRemote(){
  var xhr = new XMLHttpRequest();
  xhr.open('GET', '/remote');
  xhr.onload = function(){
    var d = {};
    try { d = JSON.parse(xhr.responseText); } catch (e){}
    if (d.ok !== true){
      remNowEl.textContent = '这台电视没开远端配置功能' + (d.error ? '：' + d.error : '');
      document.getElementById('remSave').disabled = true;
      document.getElementById('remTest').disabled = true;
      document.getElementById('remOff').disabled = true;
      return;
    }
    if (d.configured){
      remNowEl.textContent = '当前：' + (d.resolvedUrl || '') +
        '　·　每 ' + Math.round((d.refreshIntervalSec || 300) / 60) + ' 分钟拉一次' +
        (d.prune ? '　·　自动清理旧素材' : '　·　不自动清理');
      document.getElementById('remUrl').value = d.playlistUrl || '';
      document.getElementById('remDev').value = d.device || '';
      document.getElementById('remMin').value =
        Math.max(1, Math.round((d.refreshIntervalSec || 300) / 60));
      document.getElementById('remPrune').checked = d.prune !== false;
      document.getElementById('remBase').value = d.assetBaseUrl || '';
    } else {
      remNowEl.textContent = '当前：没配远端，只播电视本地 media 目录里的东西。';
    }
    showReport(d);
    if (d.message) remSay('最近一次同步：' + d.message, false);
  };
  xhr.onerror = function(){
    remNowEl.textContent = '读不到远端配置，确认手机和电视连的是同一个 WiFi';
  };
  xhr.send();
}

// 状态回传那一行。目的很实在：电脑上那个后台看不到这块屏时，
// 先确认是「根本没往那儿报」还是「报了但被拒了」—— 这两件事的修法完全不一样。
function showReport(d){
  var el = document.getElementById('remRep');
  if (!el) return;
  if (!d.configured){ el.textContent = ''; return; }
  if (!d.reportTarget){
    el.textContent = '状态回传：这台屏不上报' + (d.reportUrl
      ? '（配置里关掉了）'
      : '（清单在对象存储上，那边没有接收接口）');
    return;
  }
  var t = '状态回传：' + d.reportTarget +
    '　·　每 ' + Math.max(1, Math.round((d.reportIntervalSec || 300) / 60)) + ' 分钟一次';
  if (d.reportAttempts > 0){
    t += d.reportOk ? '　·　上次正常' : '　·　上次失败：' + (d.reportMessage || '');
  } else {
    t += '　·　还没报过';
  }
  el.textContent = t;
  el.style.color = (d.reportAttempts > 0 && !d.reportOk) ? '#c9302c' : '';
}

function remForm(){
  return {
    playlistUrl: document.getElementById('remUrl').value,
    device: document.getElementById('remDev').value,
    refreshIntervalSec: Math.max(1, Number(document.getElementById('remMin').value) || 5) * 60,
    prune: document.getElementById('remPrune').checked,
    assetBaseUrl: document.getElementById('remBase').value
  };
}

// 先测再存：地址填错的话十块屏会一起不更新，而端侧的表现只是"素材停在旧的那一版"，
// 很难往地址上想。多按一下按钮，省一趟跑店。
function testRemote(){
  var f = remForm();
  remSay('正在试…', false);
  var xhr = new XMLHttpRequest();
  xhr.open('POST', '/remote/test?url=' + encodeURIComponent(f.playlistUrl) +
                     '&device=' + encodeURIComponent(f.device) +
                     '&assetBaseUrl=' + encodeURIComponent(f.assetBaseUrl));
  xhr.onload = function(){
    var d = {};
    try { d = JSON.parse(xhr.responseText); } catch (e){}
    if (d.ok !== true){ remSay('连不上：' + (d.error || ('HTTP ' + xhr.status)), true); return; }
    var s = '能连上：清单 ' + d.count + ' 项，版本 ' + (d.revision || '?');
    if (d.assetNote) s += '\n' + d.assetNote;
    if (d.note) s += '\n' + d.note;
    remSay(s, false);
  };
  xhr.onerror = function(){ remSay('网络中断，没测成', true); };
  xhr.send();
}

function saveRemote(){
  var f = remForm();
  remSay('正在保存…', false);
  var xhr = new XMLHttpRequest();
  xhr.open('POST', '/remote');
  xhr.setRequestHeader('Content-Type', 'application/json');
  xhr.onload = function(){
    var d = {};
    try { d = JSON.parse(xhr.responseText); } catch (e){}
    if (d.ok !== true){ remSay('保存失败：' + (d.error || ('HTTP ' + xhr.status)), true); return; }
    remSay('已保存，电视正在去拉清单…', false);
    // 真同步要几秒（拉清单 + 下缺的素材）。回读两次：先给个即时反馈，再让结果落定
    setTimeout(loadRemote, 2000);
    setTimeout(loadRemote, 8000);
  };
  xhr.onerror = function(){ remSay('网络中断，配置没保存', true); };
  xhr.send(JSON.stringify(f));
}

function offRemote(){
  if (!confirm('关掉远端？\n电视会退回只播本地 media 目录。\n（电脑端的清单和已经同步下来的素材都还在，随时能再配回来）')) return;
  remSay('正在关闭…', false);
  var xhr = new XMLHttpRequest();
  xhr.open('POST', '/remote/off');
  xhr.onload = function(){
    var d = {};
    try { d = JSON.parse(xhr.responseText); } catch (e){}
    if (d.ok !== true){ remSay('关闭失败：' + (d.error || ('HTTP ' + xhr.status)), true); return; }
    remSay(d.removed ? '已关掉远端，现在只播本地目录' : '本来就没配远端', false);
    setTimeout(loadRemote, 1200);
  };
  xhr.onerror = function(){ remSay('网络中断，没关掉', true); };
  xhr.send();
}

document.getElementById('remTest').onclick = testRemote;
document.getElementById('remSave').onclick = saveRemote;
document.getElementById('remOff').onclick = offRemote;

// ==================== 访问码 ====================

/**
 * 401 统一处理：任何请求只要拿到「需要访问码」，就弹出登录层。
 *
 * 包在这一层是为了不漏。让每个回调各自判断的话，漏掉一个就会把「该输码了」
 * 显示成「网络中断，确认手机和电视连的是同一个 WiFi」—— 那种假象最难查。
 * 只拦 needLogin 的 401：「访问码输错了」这类要原样交给对应回调去说清楚。
 */
(function(){
  var proto = XMLHttpRequest.prototype;
  var origSend = proto.send;
  proto.send = function(){
    var self = this;
    if (typeof this.onload === 'function'){
      var cb = this.onload;
      this.onload = function(){
        if (self.status === 401){
          var need = false;
          try { need = (JSON.parse(self.responseText) || {}).needLogin === true; } catch (e){}
          if (need){ showGate(); return; }
        }
        cb.call(self);
      };
    }
    return origSend.apply(this, arguments);
  };
})();

var gateEl = document.getElementById('gate');
var gateCodeEl = document.getElementById('gateCode');
var gateStEl = document.getElementById('gateSt');
var gateBtnEl = document.getElementById('gateBtn');
var authNowEl = document.getElementById('authNow');
var authStEl = document.getElementById('authSt');
var gateShown = false;

document.getElementById('gateWho').textContent = '__DEVICE__  ·  ' + location.host;

function showGate(){
  if (gateShown) return;
  gateShown = true;
  gateEl.className = 'gate';
  gateStEl.textContent = '';
  gateStEl.className = 'status';
  gateCodeEl.value = '';
  try { gateCodeEl.focus(); } catch (e){}
}

function hideGate(){
  gateShown = false;
  gateEl.className = 'gate hide';
}

function gateSay(text, bad){
  gateStEl.textContent = text || '';
  gateStEl.className = bad ? 'status bad' : 'status';
}

function authSay(text, bad){
  authStEl.textContent = text || '';
  authStEl.className = bad ? 'status bad' : 'status';
}

function doLogin(){
  var code = gateCodeEl.value;
  if (!code){ gateSay('请输入访问码', true); return; }
  gateBtnEl.disabled = true;
  gateSay('正在验证…', false);
  var xhr = new XMLHttpRequest();
  xhr.open('POST', '/login');
  xhr.setRequestHeader('Content-Type', 'application/json');
  xhr.onload = function(){
    gateBtnEl.disabled = false;
    var d = {};
    try { d = JSON.parse(xhr.responseText); } catch (e){}
    if (d.ok === true){
      hideGate();
      loadList(); loadAuth(); loadRemote(); checkStorage();
      return;
    }
    gateSay(d.error || ('进不去（HTTP ' + xhr.status + '）'), true);
  };
  xhr.onerror = function(){
    gateBtnEl.disabled = false;
    gateSay('网络中断。确认手机和电视连的是同一个 WiFi', true);
  };
  xhr.send(JSON.stringify({ code: code }));
}

function loadAuth(){
  var xhr = new XMLHttpRequest();
  xhr.open('GET', '/auth');
  xhr.onload = function(){
    var d = {};
    try { d = JSON.parse(xhr.responseText); } catch (e){}
    if (d.ok !== true){
      authNowEl.textContent = '读不到访问码状态';
      return;
    }
    if (d.writable === false){
      authNowEl.textContent = '访问码文件所在的目录写不了（' + (d.codeFile || '?') +
        '），这门设不上，也改不了。';
      document.getElementById('saveCode').disabled = true;
      document.getElementById('offCode').disabled = true;
      return;
    }
    authNowEl.textContent = d.enabled
      ? '当前：已设访问码。这台电视只有输过码的手机能操作。'
      : '当前：没设访问码 —— 和电视连同一个 WiFi 的任何人都能打开这个页面。';
  };
  xhr.onerror = function(){ authNowEl.textContent = '读不到访问码状态'; };
  xhr.send();
}

function postCode(cur, next){
  var xhr = new XMLHttpRequest();
  xhr.open('POST', '/password');
  xhr.setRequestHeader('Content-Type', 'application/json');
  xhr.onload = function(){
    var d = {};
    try { d = JSON.parse(xhr.responseText); } catch (e){}
    if (d.ok !== true){
      authSay(d.error || ('改失败（HTTP ' + xhr.status + '）'), true);
      return;
    }
    document.getElementById('curCode').value = '';
    document.getElementById('newCode').value = '';
    document.getElementById('newCode2').value = '';
    authSay(d.enabled
      ? '已保存。别的手机上的登录已经失效，下次进来要输新码（这台手机不用重输）。'
      : '已关掉访问码。现在谁连上 WiFi 都能打开这个页面。', false);
    loadAuth();
  };
  xhr.onerror = function(){ authSay('网络中断，没保存成', true); };
  xhr.send(JSON.stringify({ current: cur, next: next }));
}

document.getElementById('saveCode').onclick = function(){
  var n1 = document.getElementById('newCode').value;
  var n2 = document.getElementById('newCode2').value;
  if (!n1){ authSay('新访问码不能空着。要关掉访问码请点下面那个按钮。', true); return; }
  if (n1 !== n2){ authSay('两次输入的新访问码不一样', true); return; }
  authSay('正在保存…', false);
  postCode(document.getElementById('curCode').value, n1);
};

document.getElementById('offCode').onclick = function(){
  if (!confirm('关掉访问码？关掉之后，和这台电视连同一个 WiFi 的任何人，' +
               '打开这个页面就能传素材、删素材。')) return;
  authSay('正在关闭…', false);
  postCode(document.getElementById('curCode').value, '');
};

gateBtnEl.onclick = doLogin;
gateCodeEl.addEventListener('keydown', function(e){
  if (e.key === 'Enter') doLogin();
});

loadList();
checkStorage();
loadRemote();
loadAuth();
</script>
</body>
</html>
"""
