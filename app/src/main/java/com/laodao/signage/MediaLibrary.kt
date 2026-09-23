package com.laodao.signage

import android.content.Context
import java.io.File

/**
 * 本地素材目录的读写。
 *
 * 三个目录各管一摊，物理隔离，互不覆盖：
 *   media/    本地素材，U 盘、文件管理器、adb 都能往里放
 *   remote/   远端清单同步下来的素材
 *   manual/   手机扫码上传的素材
 *
 * 这三个目录挂在哪个"根"下面不写死 —— 由 Storage 实测选出可写的位置，
 * 优先公共目录 /sdcard/signage/（电视的文件管理器看得见），退而求其次才是
 * Android/data 私有目录、内部私有目录。理由见 Storage 的注释。
 */
object MediaLibrary {

    private val VIDEO_EXT = setOf("mp4", "mkv", "webm", "ts", "mov", "m4v", "avi", "3gp")
    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "bmp")

    /** 子目录最多往下翻几层，防呆（也防有人放个软链接链回去） */
    private const val MAX_DEPTH = 4

    /** 本地素材目录：电视的文件管理器最先找到的就是它 */
    fun dir(context: Context): File = Storage.dir(context, "media")

    /** 手机扫码上传的素材目录 */
    fun manualDir(context: Context): File = Storage.dir(context, Config.MANUAL_DIR)

    /**
     * 扫描素材目录。**会往下翻子目录** —— 有些电视自带的文件管理器拷文件时会顺手再建一层，
     * 不递归就会变成「文件明明放进去了，屏幕上却说没有素材」。
     *
     * 排序按「相对目录的路径」升序：
     *   - 用 01_、02_ 前缀控制播放顺序；
     *   - 也可以按子目录分批，比如新建一个「国庆活动」文件夹。
     * 点开头的文件跳过（手机上传写到一半的 .xxx.part 就靠这个挡掉）。
     */
    fun scan(dir: File): List<File> {
        val out = ArrayList<File>()
        collect(dir, out, 0)
        if (out.isEmpty()) return emptyList()
        val prefix = dir.absolutePath
        return out.sortedBy { relPath(it, prefix).lowercase() }
    }

    private fun collect(dir: File, out: MutableList<File>, depth: Int) {
        if (depth > MAX_DEPTH) return
        val files = dir.listFiles() ?: return
        for (f in files) {
            val name = f.name
            if (name.startsWith(".")) continue
            if (f.isDirectory) {
                collect(f, out, depth + 1)
            } else if (f.isFile && isMedia(name)) {
                out.add(f)
            }
        }
    }

    private fun relPath(f: File, rootPath: String): String =
        f.absolutePath.removePrefix(rootPath).trimStart('/', '\\')

    /** 把本地目录扫出来的文件包成播放项，走跟远端清单一样的下游 */
    fun localItems(dir: File): List<PlaybackItem> =
        scan(dir).map { f -> itemOf(f) }

    /**
     * 手机扫码上传的素材，按上传时间排序（先传的先播）。
     * 上传中的临时文件以 "." 开头，这里会跳过。
     */
    fun manualItems(context: Context): List<PlaybackItem> {
        val files = manualDir(context).listFiles() ?: return emptyList()
        return files
            .filter { it.isFile && !it.name.startsWith(".") && isMedia(it.name) }
            .sortedBy { it.lastModified() }
            .map { f -> itemOf(f) }
    }

    private fun itemOf(f: File) = PlaybackItem(
        file = f,
        isVideo = isVideo(f),
        durationMs = Config.IMAGE_DURATION_MS,
        muted = Config.MUTED,
        volume = if (Config.MUTED) 0f else 1f
    )

    fun isVideo(file: File): Boolean = isVideo(file.name)

    fun isVideo(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in VIDEO_EXT

    fun isMedia(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in VIDEO_EXT || ext in IMAGE_EXT
    }
}
