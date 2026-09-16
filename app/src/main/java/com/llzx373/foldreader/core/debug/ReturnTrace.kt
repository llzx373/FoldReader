package com.llzx373.foldreader.core.debug

import android.os.SystemClock
import android.util.Log
import com.llzx373.foldreader.BuildConfig

/**
 * 返回转场 / 折叠适配的帧级诊断日志（仅 debug 包写 logcat）。
 * tag: ReturnTrace；同时写入 [DiagnosticLog]（应用内可导出，见设置页「诊断」）。
 */
object ReturnTrace {
    private const val TAG = "ReturnTrace"
    private val t0 = SystemClock.uptimeMillis()

    fun log(msg: String) {
        if (!BuildConfig.DEBUG && !DiagnosticLog.isEnabled) return
        val line = "+${SystemClock.uptimeMillis() - t0}ms $msg"
        if (BuildConfig.DEBUG) Log.d(TAG, line)
        DiagnosticLog.line(line)
    }
}
