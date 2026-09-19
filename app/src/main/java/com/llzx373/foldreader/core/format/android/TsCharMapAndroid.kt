package com.llzx373.foldreader.core.format.android

import android.content.Context
import com.llzx373.foldreader.core.format.clean.TsCharMap

/**
 * 繁简字表的 Android 侧加载：从 `assets/ts_map.txt` 读取并进程内缓存。
 *
 * **刻意放在 `clean/` 之外**：`clean/` 整包必须保持纯 JVM（不引用任何 Android API），
 * 桌面清理工具 `tools/cleaner` 直接把那个目录当源码用。粘合层放在这里，
 * 「clean/ 是纯的」这条不变量就是绝对的，不需要靠排除清单维持。
 *
 * 做成扩展函数，调用点仍写作 `TsCharMap.load(context)`。
 */
private object TsCharMapCache {
    @Volatile
    var map: Map<Char, Char>? = null
}

fun TsCharMap.load(context: Context): Map<Char, Char> {
    TsCharMapCache.map?.let { return it }
    return synchronized(TsCharMapCache) {
        TsCharMapCache.map ?: TsCharMap.parse(
            context.assets.open(TsCharMap.ASSET_NAME).use { it.readBytes() }.toString(Charsets.UTF_8),
        ).also { TsCharMapCache.map = it }
    }
}
