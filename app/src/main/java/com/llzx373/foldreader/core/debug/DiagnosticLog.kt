package com.llzx373.foldreader.core.debug

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import android.util.Log
import android.view.WindowManager
import com.llzx373.foldreader.BuildConfig
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 应用内诊断日志（返回跳动 / 折叠适配排查）。
 *
 * 为什么需要：真机（阔折叠内屏）问题复现时不一定有 adb，且 logcat 需要连电脑。
 * 这里把 [ReturnTrace] 的输出同时写进应用私有目录，设置页可开开关、打标记、
 * 分享/复制整份日志（含设备与屏幕信息）——发我一份就能定位。
 *
 * - 落盘：`filesDir/diagnostics/return-trace.log`（超过 [MAX_BYTES] 轮转为 `.1.log`）
 * - 崩溃：未捕获异常先把堆栈 + 崩溃前最近若干行落盘到 `cacheDir/diagnostics/crash-*.txt`
 *   再交给系统（无论开关是否打开都留现场）
 * - 分享：导出到 `cacheDir/diagnostics/` 后经 FileProvider 走系统分享
 * - 默认开关：debug 构建开、release 构建关（设置页可手动打开）
 */
object DiagnosticLog {

    /** FileProvider authority 后缀（与 AndroidManifest 中一致）：`<applicationId>.fileprovider` */
    const val FILE_PROVIDER_SUFFIX = ".fileprovider"

    private const val TAG = "DiagnosticLog"
    private const val PREFS = "diagnostics"
    private const val KEY_ENABLED = "return_trace_enabled"
    private const val DIR_NAME = "diagnostics"
    private const val FILE_NAME = "return-trace.log"
    private const val PREV_FILE_NAME = "return-trace.1.log"
    private const val MAX_BYTES = 1_500_000L
    private const val MAX_BUFFERED_LINES = 8_000
    private const val RECENT_LINES = 800
    private const val FLUSH_INTERVAL_MS = 500L
    private const val MAX_EXPORT_TAIL_BYTES = 400_000
    private const val MAX_CRASH_FILES = 3

    private val lock = Any()

    /**
     * 写文件这一侧单独一把锁。
     *
     * [flush] 会从三处并发进入：调度线程（[flusher]）、快照/打标记/清空/开关的调用方线程、
     * 崩溃处理线程（[flushPendingSync]）。不串行的话会出现两个 [BufferedWriter] 同时写同一文件、
     * 或 [rotate] 重命名文件与写入交错。锁序恒为 ioLock → lock，不存在反向持有。
     */
    private val ioLock = Any()
    private val pending = ArrayDeque<String>()
    private val recent = ArrayDeque<String>()

    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "foldreader-diagnostic-log").apply { isDaemon = true }
    }

    private var appContext: Context? = null
    private var prefs: SharedPreferences? = null

    /** 日志文件句柄。所有读写都在 [ioLock] 内。 */
    private var writer: BufferedWriter? = null
    private var started = false

    /** 有日志才排一次落盘：空闲时不再每 500ms 唤醒一次调度线程。 */
    private val flusher = FlushScheduler(
        schedule = { task ->
            runCatching { executor.schedule(task, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS) }.isSuccess
        },
        flush = { runCatching { flush() } },
    )

    @Volatile
    var isEnabled: Boolean = false
        private set

    fun init(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        appContext = app
        val store = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = store
        isEnabled = store.getBoolean(KEY_ENABLED, BuildConfig.DEBUG)
        installCrashHandler()
        line(
            "==== 启动 ${BuildConfig.VERSION_NAME} debug=${BuildConfig.DEBUG} " +
                "记录=${if (isEnabled) "开" else "关"} ====",
        )
        // 设备/屏幕信息直接写进日志正文：只发这一个 txt 也能对齐"什么机器、什么形态"
        header(app, app).trim().lines().forEach { line(it) }
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        // 先记一行再改开关：否则"关闭"这一步因为已关而不落盘
        line("==== 记录开关 → ${if (enabled) "开" else "关"} ====")
        isEnabled = enabled
        prefs?.edit()?.putBoolean(KEY_ENABLED, enabled)?.apply()
        runCatching { flush() }
    }

    /** 用户手动打点：复现前后各点一次，便于圈定区间 */
    fun mark(label: String = "") {
        line("======== 标记 ${stamp()} ${label.trim()} ========")
        runCatching { flush() }
    }

    /** ReturnTrace 的每条输出都会进这里 */
    fun line(message: String) {
        val stamped = "${stamp()} $message"
        val queued = synchronized(lock) {
            recent.addLast(stamped)
            while (recent.size > RECENT_LINES) recent.removeFirst()
            if (!isEnabled) return@synchronized false
            pending.addLast(stamped)
            while (pending.size > MAX_BUFFERED_LINES) pending.removeFirst()
            true
        }
        if (queued) flusher.onData()
    }

    /**
     * 整份日志（设备信息 + 上一段日志尾部 + 当前日志 + 最新崩溃转储）。
     * [displayContext] 传 Activity（Compose 里就是 LocalContext.current）时能读到分辨旋转方向。
     */
    fun snapshot(displayContext: Context? = null): String = synchronized(ioLock) {
        val context = appContext ?: return@synchronized "诊断日志未初始化\n"
        runCatching { flush() }
        buildString {
            append(header(context, displayContext ?: context))
            crashFiles(context).firstOrNull()?.let { crash ->
                appendLine("==== 最新崩溃转储：${crash.name} ====")
                appendLine(tailOf(crash, MAX_EXPORT_TAIL_BYTES))
                appendLine()
            }
            val prev = File(logDir(context), PREV_FILE_NAME)
            if (prev.exists()) {
                appendLine("==== 上一段日志尾部（${prev.name}） ====")
                appendLine(tailOf(prev, MAX_EXPORT_TAIL_BYTES))
                appendLine()
            }
            val current = File(logDir(context), FILE_NAME)
            appendLine("==== 当前日志（${current.name}，${current.length()} bytes） ====")
            append(current.takeIf { it.exists() }?.readText() ?: "（空）")
        }
    }

    /** 导出为可分享的 txt（cacheDir/diagnostics），失败返回 null */
    fun export(context: Context): File? = runCatching {
        val dir = File(context.cacheDir, DIR_NAME).apply { mkdirs() }
        val file = File(dir, "foldreader-log-${fileStamp()}.txt")
        file.writeText(snapshot(context))
        file
    }.getOrNull()

    fun clear() {
        val context = appContext ?: return
        synchronized(lock) {
            pending.clear()
            recent.clear()
        }
        runCatching {
            closeWriter()
            File(logDir(context), FILE_NAME).delete()
            File(logDir(context), PREV_FILE_NAME).delete()
            crashFiles(context).forEach { it.delete() }
        }
        line("==== 日志已清空 ====")
    }

    // ---- 内部实现 ----

    private fun logDir(context: Context): File =
        File(context.filesDir, DIR_NAME).apply { mkdirs() }

    private fun crashFiles(context: Context): List<File> =
        File(context.cacheDir, DIR_NAME)
            .listFiles { f -> f.isFile && f.name.startsWith("crash-") }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

    /** 调用方须持有 [ioLock]。 */
    private fun ensureWriter(context: Context): BufferedWriter? {
        writer?.let { return it }
        val file = File(logDir(context), FILE_NAME)
        val opened = BufferedWriter(FileWriter(file, true))
        writer = opened
        return opened
    }

    private fun closeWriter() = synchronized(ioLock) {
        runCatching { writer?.flush() }
        runCatching { writer?.close() }
        writer = null
    }

    private fun flush() {
        synchronized(ioLock) {
            val context = appContext ?: return
            val lines = drainPending() ?: return
            val file = File(logDir(context), FILE_NAME)
            if (file.length() > MAX_BYTES) rotate(context, file)
            val out = ensureWriter(context) ?: return
            lines.forEach { out.appendLine(it) }
            out.flush()
        }
    }

    /** 取出并清空待落盘队列；空则返回 null。 */
    private fun drainPending(): List<String>? = synchronized(lock) {
        if (pending.isEmpty()) return@synchronized null
        val lines = pending.toList()
        pending.clear()
        lines
    }

    private fun rotate(context: Context, file: File) {
        closeWriter()
        val prev = File(logDir(context), PREV_FILE_NAME)
        runCatching {
            prev.delete()
            file.renameTo(prev)
        }
    }

    /** 崩溃现场：同步落盘（进程即将结束，不能异步） */
    private fun recordCrash(thread: Thread, throwable: Throwable) {
        val context = appContext
        val tail = synchronized(lock) { recent.toList() }
        val text = buildString {
            appendLine("!!!! FATAL on ${thread.name} @ ${stamp()}")
            appendLine(throwable.stackTraceToString())
            appendLine("---- 崩溃前最近 ${tail.size} 行 ----")
            tail.forEach { appendLine(it) }
        }
        runCatching {
            if (context != null) {
                val dir = File(context.cacheDir, DIR_NAME).apply { mkdirs() }
                File(dir, "crash-${fileStamp()}.txt").writeText(text)
                crashFiles(context).drop(MAX_CRASH_FILES).forEach { it.delete() }
            }
            flushPendingSync(context, text)
        }
        Log.e(TAG, text)
    }

    private fun flushPendingSync(context: Context?, text: String) {
        if (context == null) return
        synchronized(ioLock) {
            val out = ensureWriter(context) ?: return
            out.append(text)
            out.flush()
        }
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { recordCrash(thread, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun header(context: Context, displayContext: Context): String {
        val config: Configuration = context.resources.configuration
        val metrics = context.resources.displayMetrics
        val bounds = runCatching {
            displayContext.getSystemService(WindowManager::class.java)
                ?.currentWindowMetrics
                ?.bounds
                ?.toShortString()
        }.getOrNull()
        // 应用上下文读不到 display（会抛 UnsupportedOperationException），用 Activity 上下文重试
        val rotation = runCatching { displayContext.display?.rotation?.toString() }.getOrNull()
            ?: runCatching { context.display?.rotation?.toString() }.getOrNull()
        val orientation = when (config.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> "landscape"
            Configuration.ORIENTATION_PORTRAIT -> "portrait"
            else -> "undefined"
        }
        return buildString {
            appendLine("# FoldReader 诊断日志（返回跳动 / 折叠适配排查）")
            appendLine("# 生成时间: ${stamp()}")
            appendLine(
                "# 设备: ${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE}" +
                    " (API ${Build.VERSION.SDK_INT})",
            )
            appendLine("# 应用: ${BuildConfig.VERSION_NAME} debug=${BuildConfig.DEBUG}")
            appendLine(
                "# 屏幕: ${config.screenWidthDp}x${config.screenHeightDp}dp" +
                    " density=${config.densityDpi}dpi smallestWidth=${config.smallestScreenWidthDp}dp" +
                    " $orientation rotation=${rotation ?: "?"}",
            )
            appendLine(
                "# 像素: ${metrics.widthPixels}x${metrics.heightPixels} density=${metrics.density}" +
                    " windowBounds=${bounds ?: "?"}",
            )
            appendLine("# 记录开关: ${if (isEnabled) "开" else "关"}")
            appendLine()
        }
    }

    private fun tailOf(file: File, maxBytes: Int): String = runCatching {
        val text = file.readText()
        if (text.length <= maxBytes) {
            text
        } else {
            "…（前 ${text.length - maxBytes} 字符已省略）\n" + text.takeLast(maxBytes)
        }
    }.getOrDefault("（读取失败）")

    private fun stamp(): String =
        SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())

    private fun fileStamp(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
}
