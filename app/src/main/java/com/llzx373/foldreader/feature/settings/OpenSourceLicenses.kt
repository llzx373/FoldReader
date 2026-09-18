package com.llzx373.foldreader.feature.settings

/**
 * 「设置 → 关于 → 开源许可」弹窗展示的第三方组件署名清单。
 *
 * 每一条都必须与实际依赖图对得上：漫画容器（zip/tar/7z/rar）与 PDF 渲染路径引用的库
 * 同样有署名要求（junrar 走 UnRAR License），不能只列 Compose / AndroidX 这些「主角」。
 * 变更依赖时同步这里，改动由 OpenSourceLicensesTest 兜底。
 */
internal val openSourceLicenses: List<Pair<String, String>> = listOf(
    "Jetpack Compose（UI / Material3 / Material Icons）" to "Apache License 2.0",
    "AndroidX（Activity / Lifecycle / Navigation / DataStore / Window / Adaptive）" to "Apache License 2.0",
    "Room 持久化库" to "Apache License 2.0",
    "kotlinx-coroutines" to "Apache License 2.0",
    "OpenCC 繁简转换字表（BYVoid/OpenCC）" to "Apache License 2.0",
    "Apache Commons Compress（zip / tar / 7z 容器）" to "Apache License 2.0",
    "XZ for Java（7z 的 LZMA 解码）" to "Public Domain",
    "junrar（RAR 容器读取）" to "UnRAR License",
    "PDFBox-Android（PDF 元数据与文本）" to "Apache License 2.0",
    "androidx.pdf（PDF 渲染与沙箱文档服务）" to "Apache License 2.0",
)
