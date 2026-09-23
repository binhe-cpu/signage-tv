package com.laodao.signage

import android.util.Log
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * 素材增量同步：把清单里列到的文件补齐到本地目录。
 *
 * 判断"要不要下"的顺序是：文件不存在 → 下；大小对不上 → 下；
 * 清单给了 sha256 且对不上 → 下；否则跳过。
 * 所以日常「只换一张图」的时刻，端侧只下那一张，不会重复搬几十 MB。
 */
object AssetSyncer {

    data class Report(
        val downloaded: Int,
        val skipped: Int,
        val deleted: Int,
        val bytes: Long,
        val failed: List<String>
    ) {
        fun summary(): String =
            "下载 $downloaded 个 / 跳过 $skipped 个 / 清理 $deleted 个" +
                (if (bytes > 0) "，共 ${bytes / 1024 / 1024} MB" else "") +
                (if (failed.isNotEmpty()) "，失败 ${failed.size} 个" else "")
    }

    fun sync(entries: List<PlaylistEntry>, baseUrl: String, dir: File, prune: Boolean): Report {
        dir.mkdirs()

        val wanted = LinkedHashSet<String>()
        var downloaded = 0
        var skipped = 0
        var bytes = 0L
        val failed = ArrayList<String>()

        for (e in entries) {
            val rel = e.file.replace('\\', '/').trimStart('/')
            if (rel.isEmpty()) continue
            wanted.add(rel)

            val dest = File(dir, rel)
            if (!inside(dir, dest)) {
                Log.w(Config.TAG, "清单里的路径越界，已忽略：${e.file}")
                continue
            }

            val needDownload = when {
                !dest.isFile -> true
                e.size != null && dest.length() != e.size -> true
                e.sha256 != null && !e.sha256.equals(sha256(dest), ignoreCase = true) -> true
                else -> false
            }

            if (!needDownload) {
                skipped++
                continue
            }

            try {
                val n = Http.download(RemoteConfig.assetUrl(baseUrl, rel), dest)
                if (e.size != null && n != e.size) {
                    throw IOException("大小不符，期望 ${e.size}，实际 $n")
                }
                if (e.sha256 != null && !e.sha256.equals(sha256(dest), ignoreCase = true)) {
                    throw IOException("sha256 校验不通过")
                }
                downloaded++
                bytes += n
            } catch (ex: Exception) {
                Log.w(Config.TAG, "素材下载失败：${e.file}", ex)
                failed.add(e.file)
            }
        }

        var deleted = 0
        // 清单是空的就别动了——多半是发布端出了问题，不能把屏上的素材清空
        if (prune && wanted.isNotEmpty()) {
            val stale = dir.walkTopDown()
                .filter { it.isFile && !it.name.endsWith(".part") }
                .filter { it.relativeTo(dir).path.replace('\\', '/') !in wanted }
                .toList()
            for (f in stale) {
                if (f.delete()) deleted++
            }
            dir.walkBottomUp()
                .filter { it.isDirectory && it != dir && it.listFiles()?.isEmpty() == true }
                .forEach { it.delete() }
        }

        return Report(downloaded, skipped, deleted, bytes, failed)
    }

    /** 防止清单里的 `..` 把文件写到目录外面 */
    private fun inside(root: File, target: File): Boolean = try {
        target.canonicalPath.startsWith(root.canonicalPath + File.separator)
    } catch (e: Exception) {
        false
    }

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}
