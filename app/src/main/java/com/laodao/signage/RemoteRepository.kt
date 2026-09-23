package com.laodao.signage

import android.content.Context
import android.util.Log
import java.io.File
import java.util.Calendar

/**
 * 远端清单的总入口。
 *
 * sync() 干三件事：
 *   1. 拉 playlist.json（失败就用上次的缓存，离线不停播）
 *   2. 按清单把缺的素材下下来（增量）
 *   3. 按当前时间过一遍时段/星期，产出真正要播的列表
 *
 * 返回 null 表示这台机器没配远端，调用方该走本地目录。
 */
object RemoteRepository {

    data class SyncResult(
        val items: List<PlaybackItem>,
        val playlist: Playlist?,
        val online: Boolean,
        val changed: Boolean,
        val message: String
    )

    private fun root(context: Context): File = Storage.root(context)

    fun mediaDir(context: Context): File = Storage.dir(context, "${Config.REMOTE_DIR}/media")

    private fun playlistCache(context: Context) = File(root(context), "playlist.json")
    private fun revisionFile(context: Context) = File(root(context), "revision.txt")

    /** 是否配了远端。UI 用它决定提示语 */
    fun isConfigured(context: Context): Boolean = RemoteConfig.load(context) != null

    /** 刷新间隔（秒），没配就用默认 */
    fun refreshIntervalSec(context: Context): Int =
        RemoteConfig.load(context)?.refreshIntervalSec ?: Config.DEFAULT_REFRESH_SEC

    /**
     * 当前生效的清单版本号（上次成功同步时记下来的）。
     *
     * 回传和 /status 都要看它：电脑上发布的是 A 版、屏上还是 B 版，
     * 这是"改了内容但屏上没变"最先要确认的一件事。
     */
    fun readRevision(context: Context): String =
        runCatching { revisionFile(context).takeIf { it.isFile }?.readText()?.trim() }
            .getOrNull() ?: ""

    /** 上次缓存的清单。起播时先用它，不用等网络 */
    fun readCachedPlaylist(context: Context): Playlist? {
        val f = playlistCache(context)
        if (!f.isFile) return null
        return try {
            PlaylistParser.parse(f.readText())
        } catch (e: Exception) {
            null
        }
    }

    /** 必须在后台线程调用（会走网络） */
    fun sync(context: Context): SyncResult? {
        val cfg = RemoteConfig.load(context) ?: return null

        val cache = playlistCache(context)
        val dir = mediaDir(context)
        val url = cfg.resolvedPlaylistUrl

        var playlist: Playlist? = null
        var online = false
        var error: String? = null

        // 1. 拉清单
        try {
            val text = Http.getText(url)
            val parsed = PlaylistParser.parse(text)
            if (parsed == null) {
                error = "清单解析失败（schemaVersion 可能比端侧新）"
            } else {
                playlist = parsed
                online = true
                runCatching {
                    cache.parentFile?.mkdirs()
                    cache.writeText(text)
                }
            }
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
            Log.w(Config.TAG, "拉取清单失败：$url", e)
        }

        // 2. 远端不可用就吃缓存
        var fromCache = false
        if (playlist == null && cache.isFile) {
            playlist = try {
                PlaylistParser.parse(cache.readText())
            } catch (e: Exception) {
                null
            }
            fromCache = playlist != null
        }

        val p = playlist
        if (p == null) {
            return SyncResult(
                items = emptyList(),
                playlist = null,
                online = false,
                changed = false,
                message = "没有可用清单" + (error?.let { "：$it" } ?: "")
            )
        }

        // 3. 同步素材（只有真的连上远端才下载；吃缓存时用已有文件）
        var assetNote = ""
        if (online) {
            val assetBase = RemoteConfig.assetBaseFor(cfg.assetBaseUrlOverride, p.assetBaseUrl, url)

            val report = try {
                AssetSyncer.sync(p.entries, assetBase, dir, cfg.prune)
            } catch (e: Exception) {
                Log.w(Config.TAG, "素材同步出错", e)
                AssetSyncer.Report(0, 0, 0, 0L, listOf("*"))
            }
            assetNote = "；" + report.summary()
        }

        // 4. 按当前时间过滤
        val items = PlaylistResolver.resolve(p, dir, Calendar.getInstance())

        // 5. 记录 revision，用来判断"这次清单变了没"
        val last = runCatching { revisionFile(context).takeIf { it.isFile }?.readText() }.getOrNull()
        val changed = online && last != p.revision
        if (online && changed) {
            runCatching {
                revisionFile(context).parentFile?.mkdirs()
                revisionFile(context).writeText(p.revision)
            }
        }

        val source = if (online) "远端" else if (fromCache) "缓存（离线）" else "无"
        val message = "$source；清单 ${p.entries.size} 项，当前时段可播 ${items.size} 项$assetNote"

        Log.i(Config.TAG, message)
        return SyncResult(items, p, online, changed, message)
    }
}
