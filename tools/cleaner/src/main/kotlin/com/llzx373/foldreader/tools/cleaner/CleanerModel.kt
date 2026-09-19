package com.llzx373.foldreader.tools.cleaner

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.llzx373.foldreader.core.format.EncodingDetector
import com.llzx373.foldreader.core.format.clean.CleanLevel
import com.llzx373.foldreader.core.format.clean.CleanProfile
import com.llzx373.foldreader.core.format.clean.CleanReport
import com.llzx373.foldreader.core.format.clean.CleanToggles
import com.llzx373.foldreader.core.format.clean.NovelCleaner
import com.llzx373.foldreader.core.format.clean.TsCharMap
import java.io.BufferedReader
import java.io.File
import java.io.StringReader
import java.io.StringWriter
import java.nio.charset.Charset

/** 编码下拉里表示「按内容自动判断」。 */
const val AUTO_ENCODING = "自动检测"

/**
 * 界面状态与动作。
 *
 * 这里**不含任何清洗逻辑**——全部调 `core/format/clean/` 里那份实现（与 App 共用同一批源码），
 * 所以工具里看到的结果就是 App 导入时的结果。
 */
class CleanerModel {

    var file: File? by mutableStateOf(null)
        private set

    var encodingChoice: String by mutableStateOf(AUTO_ENCODING)
    var encodingNote: String by mutableStateOf("")
        private set

    var rawText: String by mutableStateOf("")
        private set

    var level: CleanLevel by mutableStateOf(CleanLevel.STANDARD)
    var toggles: CleanToggles by mutableStateOf(CleanToggles.preset(CleanLevel.STANDARD))

    var tsMapPath: String by mutableStateOf(defaultTsMapPath())

    /** 实际会用到的繁简字表来源，给界面显示（路径找不到时回落到打包资源）。 */
    val tsMapSource: String
        get() = tsMapPath.takeIf { it.isNotEmpty() && File(it).isFile }
            ?: "打包内置的 ${TsCharMap.ASSET_NAME}"

    var resultText: String by mutableStateOf("")
        private set

    var report: CleanReport? by mutableStateOf(null)
        private set

    var status: String by mutableStateOf("打开一个 TXT，然后点「清洗」")
        private set

    /** 上一次操作是不是失败——界面据此把状态文字标红。 */
    var hasError: Boolean by mutableStateOf(false)
        private set

    var elapsedMs: Long by mutableStateOf(0L)
        private set

    var filter: String by mutableStateOf("")

    /** 按当前档位重置开关。 */
    fun applyLevel(newLevel: CleanLevel) {
        level = newLevel
        toggles = CleanToggles.preset(if (newLevel == CleanLevel.CUSTOM) CleanLevel.STANDARD else newLevel)
    }

    /** 逐项改动后档位落到「自定义」。 */
    fun setToggle(newToggles: CleanToggles) {
        toggles = newToggles
        level = CleanLevel.CUSTOM
    }

    fun open(selected: File) {
        runCatching {
            val bytes = selected.readBytes()
            val detection = EncodingDetector.detect(bytes)
            val charset = charsetFor(encodingChoice) ?: detection.charset
            // 与 App 的解码路径一致：BOM 不留给正文
            val text = String(bytes, charset).removePrefix("\uFEFF")
            file = selected
            rawText = text
            encodingNote = buildString {
                append("检测：${detection.charset.name()}（置信度 ${"%.2f".format(detection.confidence)}）")
                if (charset != detection.charset) append("；实际按 ${charset.name()} 解码")
                append("；${bytes.size} 字节 / ${text.length} 字符 / ${text.count { it == '\n' } + 1} 行")
            }
            resultText = ""
            report = null
            hasError = false
            status = "已读取，点「清洗」"
        }.onFailure { fail("读取失败", it) }
    }

    /** 换编码后重新解码同一个文件。 */
    fun reDecode() {
        file?.let { open(it) }
    }

    fun clean() {
        if (rawText.isEmpty()) {
            fail("无法清洗", IllegalStateException("还没有打开文件"))
            return
        }
        runCatching {
            val tsMap = if (toggles.traditionalToSimplified) loadTsMap() else emptyMap()
            val profile = CleanProfile(level = level, toggles = toggles)
            val writer = StringWriter(rawText.length)
            val started = System.nanoTime()
            val result = NovelCleaner.cleanStream(
                reader = BufferedReader(StringReader(rawText)),
                writer = writer,
                profile = profile,
                tsMap = tsMap,
            )
            elapsedMs = (System.nanoTime() - started) / 1_000_000
            resultText = writer.toString()
            report = result
            hasError = false
            status = result.summary()
        }.onFailure { fail("清洗失败", it) }
    }

    fun saveTo(target: File) {
        runCatching { target.writeText(resultText) }
            .onSuccess {
                hasError = false
                status = "已写出 ${target.absolutePath}"
            }
            .onFailure { fail("写出失败", it) }
    }

    /**
     * 统一的失败反馈：状态栏给出**整条 cause 链**，同时把堆栈打到 stderr。
     *
     * cause 链不能省：这类错误常常是静态初始化失败（`ExceptionInInitializerError` 的
     * `message` 是 null，真正的原因在 cause 里），只打印 `message` 会得到一句「读取失败：null」，
     * 完全没法定位。
     */
    private fun fail(what: String, error: Throwable) {
        error.printStackTrace()
        hasError = true
        status = "$what：${describe(error)}"
    }

    private fun describe(error: Throwable): String = buildString {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 6) {
            if (depth > 0) append("  ←  ")
            append(current.toString())
            current = current.cause
            depth++
        }
    }

    /** 繁简字表优先用界面上那个路径；分发包里没有仓库目录，回落到打包进来的资源。 */
    private fun loadTsMap(): Map<Char, Char> {
        val f = File(tsMapPath)
        if (f.isFile) return TsCharMap.parse(f.readText())
        val bundled = javaClass.getResourceAsStream("/${TsCharMap.ASSET_NAME}")
        requireNotNull(bundled) { "找不到繁简字表：既不在 $tsMapPath，也不在打包资源里" }
        return bundled.use { TsCharMap.parse(it.readBytes().toString(Charsets.UTF_8)) }
    }

    private fun charsetFor(name: String): Charset? =
        if (name == AUTO_ENCODING) null else EncodingDetector.forNameOrNull(name)
}

/** 支持的编码下拉项。 */
val ENCODING_CHOICES = listOf(
    AUTO_ENCODING,
    "UTF-8",
    "GBK",
    "GB18030",
    "Big5",
    "UTF-16LE",
    "UTF-16BE",
)

/**
 * 找繁简字表。工具的 workingDir 可能是仓库根，也可能是模块目录，两种都试。
 */
private fun defaultTsMapPath(): String {
    val relative = "app/src/main/assets/ts_map.txt"
    val candidates = listOf(
        File(relative),
        File("../$relative"),
        File("../../$relative"),
    )
    return candidates.firstOrNull { it.isFile }?.absolutePath.orEmpty()
}
