package com.llzx373.foldreader.feature.lock

import androidx.biometric.BiometricPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 应用锁验证结果分发：成功与**任何**错误/取消路径都必须调 onFinished
 * （复位「正在弹验证」门闩）；用户主动取消不算失败、不调 onFailed。
 */
class AppLockTest {

    private class Recorder {
        var failed: String? = null
        var finished = 0
    }

    private fun callback(rec: Recorder) = AppLock.AuthCallback(
        onSuccess = {},
        onFailed = { rec.failed = it },
        onFinished = { rec.finished++ },
    )

    @Test
    fun `用户取消（返回键手势）复位门闩且不算失败`() {
        val rec = Recorder()
        callback(rec).onAuthenticationError(BiometricPrompt.ERROR_USER_CANCELED, "已取消")
        assertNull(rec.failed)
        assertEquals(1, rec.finished)
    }

    @Test
    fun `负按钮取消复位门闩且不算失败`() {
        val rec = Recorder()
        callback(rec).onAuthenticationError(BiometricPrompt.ERROR_NEGATIVE_BUTTON, "取消")
        assertNull(rec.failed)
        assertEquals(1, rec.finished)
    }

    @Test
    fun `硬件错误复位门闩并报失败`() {
        val rec = Recorder()
        callback(rec).onAuthenticationError(BiometricPrompt.ERROR_HW_UNAVAILABLE, "硬件不可用")
        assertEquals("硬件不可用", rec.failed)
        assertEquals(1, rec.finished)
    }
}
