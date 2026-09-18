package com.llzx373.foldreader.core.debug

import android.app.Application
import android.os.Build
import android.os.Process
import com.llzx373.foldreader.BuildConfig

/**
 * 当前是否跑在**隔离进程**里。
 *
 * `android:isolatedProcess="true"` 的服务（如 androidx.pdf 的 PDF 文档沙箱）跑在独立进程里，
 * 但它会用同一个 `Application` 类，于是 `onCreate` 会被再执行一遍。那里的进程没有 UserManager、
 * 没有任何权限，访问 SharedPreferences / 文件 / 数据库会直接抛：
 *
 * ```
 * IllegalStateException: SharedPreferences cannot be accessed if UserManager is not available.
 * (e.g. from inside an isolated process)
 * ```
 *
 * 进程一死，依赖它的 Binder 服务就连不上（典型表现是调用方**永久挂起**而不是报错），
 * 所以初始化前必须先判断。
 *
 * 两个判据都留：`Process.isIsolated()` 是语义上正确的那个（隔离进程跑在 90000+ 段 UID），
 * 进程名比对兜住厂商实现差异。
 */
internal fun isIsolatedProcess(): Boolean {
    if (Process.isIsolated()) return true
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
    val processName = Application.getProcessName() ?: return false
    return processName != BuildConfig.APPLICATION_ID
}
