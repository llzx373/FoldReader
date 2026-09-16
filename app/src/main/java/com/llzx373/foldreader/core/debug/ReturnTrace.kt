package com.llzx373.foldreader.core.debug

import android.os.SystemClock
import android.util.Log
import com.llzx373.foldreader.BuildConfig

/** 返回转场的帧级诊断日志（仅 debug 包），排查书架跳动用。tag: ReturnTrace */
object ReturnTrace {
    private const val TAG = "ReturnTrace"
    private val t0 = SystemClock.uptimeMillis()

    fun log(msg: String) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "+${SystemClock.uptimeMillis() - t0}ms $msg")
        }
    }
}
