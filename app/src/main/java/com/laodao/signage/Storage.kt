package com.laodao.signage

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * 素材根目录的选址。
 *
 * 为什么不继续直接用 getExternalFilesDir()：
 * 电视上报回来的三个目录全空、手机传 6 个文件成功 0 个，根子就在这个目录上。
 * 老电视（Android 5.x）上 `Android/data/包名/files/` 有两个坑：
 *   1. 有可能建不出来、或者建出来写不进去 —— 上传会整批失败，而失败原因还被页面盖掉了；
 *   2. 这个目录被系统藏起来，电视自带的文件管理器根本看不到，想手动拷素材都找不着地方。
 *
 * 所以改成「按优先级挨个实测，谁能写用谁」：
 *   1. 公共目录 /sdcard/signage/      文件管理器看得见、U 盘拷得进、手动删得掉（要存储权限）
 *   2. App 外部私有目录               Android/data/包名/files/，不要权限，但系统会隐藏
 *   3. 内部私有目录                   /data/data/包名/files/，一定写得进，但只有本 App 看得见
 *
 * 「能不能写」是**实测**出来的（建目录 + 写 1 字节探针 + 删掉），不是猜的。
 * 结果缓存在内存里，权限变化后调 invalidate() 重选。
 */
object Storage {

    /** 公共目录名。放在外部存储根目录下，插上电脑或 U 盘一眼就能看到 */
    const val PUBLIC_DIR_NAME = "signage"

    private const val PROBE_NAME = ".signage-write-test"

    /** 一个候选位置，给 /status 摊开用 */
    data class Slot(
        val label: String,
        val path: String,
        val writable: Boolean,
        val note: String
    )

    private class Choice(val root: File, val label: String, val note: String)

    @Volatile private var chosen: Choice? = null
    @Volatile private var chosenWritable = false
    @Volatile private var slots: List<Slot> = emptyList()

    /** 权限拿到、或者存储挂载状态变了以后叫一下，下次访问会重新选址 */
    fun invalidate() {
        synchronized(this) {
            chosen = null
            chosenWritable = false
            slots = emptyList()
        }
    }

    fun root(context: Context): File = resolve(context).root

    /** 用哪个位置，中文名，直接给 /status 显示 */
    fun rootLabel(context: Context): String = resolve(context).label

    fun isWritable(context: Context): Boolean {
        resolve(context)
        return chosenWritable
    }

    /** 根目录下的一个子目录，不存在就建 */
    fun dir(context: Context, sub: String): File {
        val d = File(root(context), sub)
        if (!d.isDirectory) d.mkdirs()
        return d
    }

    /** 根目录下的一个文件（比如端侧配置 signage-remote.json） */
    fun file(context: Context, name: String): File = File(root(context), name)

    /** 所有候选位置 + 各自能不能写。手机打开 /status 就能看到，排错不用连电脑 */
    fun diagnose(context: Context): List<Slot> {
        resolve(context)
        return slots
    }

    // ==================== 内部实现 ====================

    private fun resolve(context: Context): Choice {
        chosen?.let { return it }
        synchronized(this) {
            chosen?.let { return it }

            val ok = ArrayList<Pair<Choice, Boolean>>()
            val diag = ArrayList<Slot>()

            for (c in candidates(context)) {
                val w = probe(c.root)
                ok.add(c to w)
                diag.add(Slot(c.label, c.root.absolutePath, w, c.note))
            }

            slots = diag

            var pick = ok.firstOrNull { it.second }?.first
            if (pick == null) {
                // 一个都写不进去（盘满 / 全被挂载走）。至少返回内部私有目录，别让调用方拿到 null
                pick = ok.lastOrNull()?.first
                    ?: Choice(context.filesDir, "内部私有目录", "")
            } else if (pick.root.name == PUBLIC_DIR_NAME && !hasContent(pick.root)) {
                // 升级兼容：公共目录还空着、而原来的私有目录里有东西，就继续用私有目录，
                // 免得升一次版本素材看着像"没了"。往公共目录里放个文件就会切过去。
                val legacy = ok.drop(1)
                    .firstOrNull { (c, w) -> w && hasContent(c.root) }
                    ?.first
                if (legacy != null) pick = legacy
            }

            val decided = pick
            chosen = decided
            chosenWritable = ok.any { it.first === decided && it.second }

            Log.i(
                Config.TAG,
                "素材根目录：${decided.root.absolutePath}（${decided.label}${if (chosenWritable) "" else "，实测不可写！"}）"
            )
            return decided
        }
    }

    @Suppress("DEPRECATION")
    private fun candidates(context: Context): List<Choice> {
        val out = ArrayList<Choice>()

        // 1. 公共目录。能写就用它 —— 电视自带的文件管理器也翻得到
        try {
            val ext = Environment.getExternalStorageDirectory()
            if (ext != null && ext.absolutePath.isNotEmpty()) {
                out.add(
                    Choice(
                        File(ext, PUBLIC_DIR_NAME),
                        "公共目录",
                        "文件管理器里能直接看到、能拷能删"
                    )
                )
            }
        } catch (e: Exception) {
            Log.w(Config.TAG, "读外部存储根目录失败", e)
        }

        // 2. App 外部私有目录。老机器上可能有多个卷（内置存储 / SD 卡 / U 盘），逐个试
        try {
            val dirs = context.getExternalFilesDirs(null)
            if (dirs != null) {
                for (i in dirs.indices) {
                    val d = dirs[i] ?: continue
                    out.add(
                        Choice(
                            d,
                            if (i == 0) "App 外部目录" else "App 外部目录 #${i + 1}",
                            "Android/data 下的私有目录，系统会隐藏"
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(Config.TAG, "枚举外部存储目录失败", e)
        }

        // 3. 内部私有目录。兜底的兜底
        out.add(Choice(context.filesDir, "内部私有目录", "只有本 App 能访问，电视上翻不到"))
        return out
    }

    /** 实测能不能写：建目录 → 写 1 字节 → 删掉。任何一步失败都算不能写 */
    private fun probe(root: File): Boolean = try {
        if (!root.isDirectory && !root.mkdirs() && !root.isDirectory) {
            false
        } else {
            val f = File(root, PROBE_NAME)
            FileOutputStream(f).use { it.write(1) }
            f.delete()
            true
        }
    } catch (e: Exception) {
        false
    }

    /** 这个根目录下是不是已经有素材了（只认三个素材子目录里的文件） */
    private fun hasContent(root: File): Boolean {
        val subs = arrayOf("media", Config.MANUAL_DIR, "${Config.REMOTE_DIR}/media")
        for (s in subs) {
            val d = File(root, s)
            val files = d.listFiles() ?: continue
            for (f in files) {
                if (f.isFile && !f.name.startsWith(".")) return true
            }
        }
        return false
    }
}
