package com.laodao.signage

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar

/**
 * playlist.json 的数据模型。字段规范见 docs/playlist.md。
 *
 * 解析用 Android 自带的 org.json，不引任何第三方库——这东西没必要为它加依赖。
 */

data class PlaylistDefaults(
    val imageDurationSec: Int,
    val muted: Boolean,
    val volume: Float
)

data class PlaylistEntry(
    /** 文件名，相对素材目录 */
    val file: String,
    /** "video" / "image"，null = 按扩展名推断 */
    val type: String?,
    /** 图片停留秒数，null = 用 defaults */
    val durationSec: Int?,
    val muted: Boolean?,
    val volume: Float?,
    /** 每日生效起点 HH:mm */
    val startTime: String?,
    /** 每日生效终点 HH:mm */
    val endTime: String?,
    /** 生效星期，1=周一 … 7=周日，null = 每天 */
    val days: List<Int>?,
    /** 字节数，用于增量下载判断 */
    val size: Long?,
    /** 小写 sha256，填了端侧就强校验 */
    val sha256: String?
)

data class Playlist(
    val schemaVersion: Int,
    val revision: String,
    val assetBaseUrl: String?,
    val defaults: PlaylistDefaults,
    val entries: List<PlaylistEntry>
)

/** 交给播放器的最终一项：文件已经确定，时段已经过滤过 */
data class PlaybackItem(
    val file: File,
    val isVideo: Boolean,
    val durationMs: Long,
    val muted: Boolean,
    val volume: Float
)

object PlaylistParser {

    /** 端侧认识的最高版本。清单比这个新，整份忽略 */
    const val SCHEMA_VERSION = 1

    fun parse(text: String): Playlist? {
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            return null
        }

        val schema = root.optInt("schemaVersion", 1)
        if (schema > SCHEMA_VERSION) return null

        val dj = root.optJSONObject("defaults")
        val defaults = PlaylistDefaults(
            imageDurationSec = (dj?.optInt("imageDurationSec", -1) ?: -1).takeIf { it > 0 }
                ?: (Config.IMAGE_DURATION_MS / 1000).toInt(),
            muted = dj?.optBoolean("muted", Config.MUTED) ?: Config.MUTED,
            volume = (dj?.optDouble("volume", -1.0) ?: -1.0).toFloat()
                .takeIf { it >= 0f }
                ?: (if (Config.MUTED) 0f else 1f)
        )

        val arr = root.optJSONArray("items") ?: JSONArray()
        val entries = ArrayList<PlaylistEntry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("file", "").trim()
            if (name.isEmpty()) continue
            if (name.contains("..")) continue

            entries.add(
                PlaylistEntry(
                    file = name,
                    type = o.optString("type", "").trim().ifEmpty { null },
                    durationSec = optPositiveInt(o, "durationSec"),
                    muted = if (o.has("muted")) o.optBoolean("muted") else null,
                    volume = optVolume(o),
                    startTime = o.optString("startTime", "").trim().ifEmpty { null },
                    endTime = o.optString("endTime", "").trim().ifEmpty { null },
                    days = parseDays(o.optJSONArray("days")),
                    size = if (o.has("size")) o.optLong("size", -1L).takeIf { it >= 0 } else null,
                    sha256 = o.optString("sha256", "").trim().lowercase().ifEmpty { null }
                )
            )
        }

        return Playlist(
            schemaVersion = schema,
            revision = root.optString("revision", "").trim(),
            assetBaseUrl = root.optString("assetBaseUrl", "").trim().ifEmpty { null },
            defaults = defaults,
            entries = entries
        )
    }

    private fun optPositiveInt(o: JSONObject, key: String): Int? {
        if (!o.has(key)) return null
        val v = o.optInt(key, -1)
        return if (v > 0) v else null
    }

    private fun optVolume(o: JSONObject): Float? {
        if (!o.has("volume")) return null
        val v = o.optDouble("volume", -1.0).toFloat()
        return if (v >= 0f) v.coerceAtMost(1f) else null
    }

    private fun parseDays(arr: JSONArray?): List<Int>? {
        if (arr == null || arr.length() == 0) return null
        val out = ArrayList<Int>(arr.length())
        for (i in 0 until arr.length()) {
            val v = arr.optInt(i, -1)
            if (v in 1..7 && v !in out) out.add(v)
        }
        return out.ifEmpty { null }
    }
}

object PlaylistResolver {

    /**
     * 把清单落成实际可播的列表：过滤时段/星期、确认文件存在、按数组顺序排列。
     * 清单里的顺序就是播放顺序，不再按文件名排序。
     */
    fun resolve(playlist: Playlist, dir: File, now: Calendar): List<PlaybackItem> {
        val out = ArrayList<PlaybackItem>(playlist.entries.size)
        for (e in playlist.entries) {
            if (!Schedule.inWindow(e, now)) continue
            val f = File(dir, e.file)
            if (!f.isFile) continue

            val video = when (e.type?.lowercase()) {
                "video" -> true
                "image", "photo", "picture", "img" -> false
                else -> MediaLibrary.isVideo(f)
            }

            out.add(
                PlaybackItem(
                    file = f,
                    isVideo = video,
                    durationMs = (e.durationSec ?: playlist.defaults.imageDurationSec) * 1000L,
                    muted = e.muted ?: playlist.defaults.muted,
                    volume = e.volume ?: playlist.defaults.volume
                )
            )
        }
        return out
    }
}

object Schedule {

    /** 当前时间是否落在该 item 的生效时段/星期内 */
    fun inWindow(entry: PlaylistEntry, now: Calendar): Boolean {
        entry.days?.let { days ->
            if (todayIndex(now) !in days) return false
        }

        val s = entry.startTime?.let { parseMinuteOfDay(it) } ?: return true
        val e = entry.endTime?.let { parseMinuteOfDay(it) } ?: return true

        val cur = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        return if (s <= e) {
            cur in s until e
        } else {
            // 跨午夜，如 22:00 ~ 02:00
            cur >= s || cur < e
        }
    }

    /** 1=周一 … 7=周日 */
    fun todayIndex(now: Calendar): Int = (now.get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1

    private fun parseMinuteOfDay(hhmm: String): Int? {
        val parts = hhmm.split(":")
        if (parts.size != 2) return null
        val h = parts[0].trim().toIntOrNull() ?: return null
        val m = parts[1].trim().toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }
}
