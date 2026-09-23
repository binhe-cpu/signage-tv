package com.laodao.signage

/**
 * 播放游标：只管「接下来该播第几条」。
 *
 * 刻意完全不碰 Android API（不需要 Context、View、Handler），
 * 所以能整块搬进 JVM 测试台跑真实断言。
 *
 * 这个类是为了修一个实打实的 bug：原来播放列表存在 MainActivity 里，
 * 一轮播完之后重扫目录，发现「当前这条还在新列表里」就只把游标指回去，
 * 既不重新起播、也不再排下一次的定时器 —— 屏幕就冻在最后一条上再也不动了。
 * 现在把「该不该起播」这件事收敛成 playing 标志，任何路径都不会漏。
 *
 * 约定：
 *   update()  返回 >= 0  → 调用方必须立刻去播这个下标
 *             返回  -1  → 保持现状，别打断（当前这条还在播、且新列表里没新东西）
 *   advance() 返回 >= 0  → 下一条的下标
 *             返回  -1  → 列表空了，调用方该去重扫目录 / 重新同步
 */
class Sequencer {

    var items: List<PlaybackItem> = emptyList()
        private set

    /** 当前播到第几条，-1 表示还没开始 */
    var index: Int = -1
        private set

    /**
     * true = play() 已经发起过，正在等它播完（图片等定时器，视频等 STATE_ENDED）。
     * 这是判断「要不要重新起播」的唯一依据。
     */
    var playing: Boolean = false
        private set

    /**
     * 换一批素材。返回要立刻起播的下标，-1 表示不用动。
     *
     * 优先级：
     *   1. 列表里出现了以前没见过的文件（新拷进来的、手机上刚传的）→ 播它；
     *   2. 否则当前这条还在 → 继续播，不打断；
     *   3. 当前这条没了（被删了）→ 停在原位播现在落在这一位上的那条；
     *   4. 都没有 → 从第一条开始。
     */
    fun update(newItems: List<PlaybackItem>): Int {
        val oldPaths = HashSet<String>(items.size)
        for (it in items) oldPaths.add(it.file.absolutePath)
        val currentPath = items.getOrNull(index)?.file?.absolutePath
        val oldIndex = index

        items = newItems

        if (newItems.isEmpty()) {
            index = -1
            playing = false
            return -1
        }

        var target = -1
        for (i in newItems.indices) {
            if (newItems[i].file.absolutePath !in oldPaths) {
                target = i
                break
            }
        }
        if (target < 0) {
            if (currentPath != null) {
                for (i in newItems.indices) {
                    if (newItems[i].file.absolutePath == currentPath) {
                        target = i
                        break
                    }
                }
            }
        }
        if (target < 0 && currentPath != null && oldIndex >= 0) {
            // 当前这条从新列表里没了 —— 在手机上被删掉了，或者 U 盘拔了。
            // 停在原来那个位置，播现在落在这一位上的那条，而不是回到第一条：
            // 删一张促销图不该让整屏从头再来一遍，那看着像应用重启了。
            target = oldIndex.coerceAtMost(newItems.size - 1)
        }
        if (target < 0) target = 0

        val targetPath = newItems[target].file.absolutePath

        // 还在播同一条，且列表里没有新东西 —— 什么都别做，打断视频最亏
        if (playing && currentPath == targetPath) {
            index = target
            return -1
        }

        // 要换内容了：这里只负责报出该播哪条，真正的起播交给调用方
        playing = false
        index = target
        return target
    }

    /**
     * 当前这条播完了。
     * 返回下一条的下标；列表空了返回 -1。
     */
    fun advance(): Int {
        playing = false
        if (items.isEmpty()) {
            index = -1
            return -1
        }
        index = if (index + 1 >= items.size) 0 else index + 1
        return index
    }

    /** 调用方已经真的开始播这条了 */
    fun started(i: Int) {
        if (items.isEmpty()) return
        index = i.coerceIn(0, items.size - 1)
        playing = true
    }

    /** 播放被打断/进入占位页 */
    fun stopped() {
        playing = false
    }

    /**
     * 兜底自检：什么都没在播、但列表里明明有内容 → 返回该播的下标。
     * 正常情况下返回 -1（说明播放正在进行中）。
     */
    fun resumeIndex(): Int {
        if (playing || items.isEmpty()) return -1
        return if (index in items.indices) index else 0
    }

    fun currentPath(): String? = items.getOrNull(index)?.file?.absolutePath

    fun currentName(): String? = items.getOrNull(index)?.file?.name

    fun reset() {
        items = emptyList()
        index = -1
        playing = false
    }
}
