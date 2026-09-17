package com.llzx373.foldreader.core.reader

/**
 * 行几何缓存：重绘（滚动新页进入视口、选区拖动逐帧重绘、标注变化）时复用上一次算出的
 * [LineBox] 列表，省掉逐字符 measureText 与随之而来的临时 String 分配。
 *
 * 键 = [Page] **实例身份**（不是结构相等：内容变更后新建的 Page 必须重新度量）
 * + 全部参与几何计算的参数。颜色不进键 —— 主题切换不使几何失效。
 * 缓存持有 Page 强引用，因此实例身份不会因 GC 复用地址而串味。
 * 绘制同时发生在主线程与离屏位图渲染的 Default 线程，故整体加锁。
 */
class LineBoxCache(private val maxSize: Int = 32) {

    private class Entry(
        val page: Page,
        val widthPx: Float,
        val innerPaddingPx: Float,
        val innerOnRight: Boolean,
        val extraTopPadPx: Float,
        val density: Float,
        val scaledDensity: Float,
        val config: LayoutConfig,
        val boxes: List<LineBox>,
    ) {
        fun matches(
            page: Page,
            widthPx: Float,
            innerPaddingPx: Float,
            innerOnRight: Boolean,
            extraTopPadPx: Float,
            density: Float,
            scaledDensity: Float,
            config: LayoutConfig,
        ): Boolean =
            this.page === page &&
                this.widthPx == widthPx &&
                this.innerPaddingPx == innerPaddingPx &&
                this.innerOnRight == innerOnRight &&
                this.extraTopPadPx == extraTopPadPx &&
                this.density == density &&
                this.scaledDensity == scaledDensity &&
                this.config == config
    }

    private val entries = ArrayDeque<Entry>()

    /** 命中则复用；未命中用 [build] 计算并写入（LRU 淘汰最久未用项）。 */
    fun getOrBuild(
        page: Page,
        widthPx: Float,
        innerPaddingPx: Float,
        innerOnRight: Boolean,
        extraTopPadPx: Float,
        density: Float,
        scaledDensity: Float,
        config: LayoutConfig,
        build: () -> List<LineBox>,
    ): List<LineBox> = synchronized(entries) {
        val hit = entries.firstOrNull {
            it.matches(page, widthPx, innerPaddingPx, innerOnRight, extraTopPadPx, density, scaledDensity, config)
        }
        if (hit != null) {
            entries.remove(hit)
            entries.addLast(hit)
            return hit.boxes
        }
        val boxes = build()
        entries.addLast(
            Entry(page, widthPx, innerPaddingPx, innerOnRight, extraTopPadPx, density, scaledDensity, config, boxes),
        )
        while (entries.size > maxSize) entries.removeFirst()
        boxes
    }

    fun clear() {
        synchronized(entries) { entries.clear() }
    }

    val size: Int get() = synchronized(entries) { entries.size }
}
