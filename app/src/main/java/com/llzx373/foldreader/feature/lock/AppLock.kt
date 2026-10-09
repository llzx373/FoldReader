package com.llzx373.foldreader.feature.lock

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * M34 隐私锁（应用锁）：BiometricPrompt 生物识别/锁屏凭据。
 *
 * 口径：只锁冷启动（进程内解锁一次后不再锁，退后台不重复锁）；
 * 「显示隐藏的书籍」入口走同一验证。解锁状态是进程内存（[AppLockState.unlocked]），
 * 不落盘——进程被杀了下次启动自然回到锁定。
 */
object AppLock {

    private const val AUTHENTICATORS =
        BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL

    /** 进程内是否已解锁（应用锁开启时的门闩）；Compose 状态，解锁即放行界面。 */
    var unlocked: Boolean by mutableStateOf(false)

    /** 设备有没有可用的验证手段（生物识别或锁屏凭据）；没有就不该允许开启应用锁。 */
    fun canAuthenticate(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    /**
     * 弹系统验证框。BIOMETRIC_WEAK or DEVICE_CREDENTIAL 组合自带「使用密码」回退，
     * 不能再调 setNegativeButtonText（Android 10+ 会抛 IllegalArgumentException）。
     *
     * [onFinished] 在成功与**任何**错误/取消路径都会调到——调用方用它复位
     * 「正在弹验证」门闩（用户取消不走 [onFailed]，没有它按钮会永久失效）。
     */
    fun prompt(
        activity: FragmentActivity,
        onSuccess: () -> Unit,
        onFailed: (String) -> Unit = {},
        onFinished: () -> Unit = {},
    ) {
        if (!canAuthenticate(activity)) {
            onFailed("设备未设置锁屏密码或生物识别")
            onFinished()
            return
        }
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            AuthCallback(onSuccess, onFailed, onFinished),
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("解锁 FoldReader")
                .setAllowedAuthenticators(AUTHENTICATORS)
                .build(),
        )
    }

    /**
     * 验证结果分发：成功与**任何**错误/取消路径都调 [onFinished]。
     * 用户主动取消（含负按钮/手势返回）不算失败，不调 [onFailed] 安静收掉。
     */
    internal class AuthCallback(
        private val onSuccess: () -> Unit,
        private val onFailed: (String) -> Unit,
        private val onFinished: () -> Unit,
    ) : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            onSuccess()
            onFinished()
        }

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON
            ) {
                onFailed(errString.toString())
            }
            onFinished()
        }
    }
}

/**
 * 应用锁门：[enabled] 且进程内未解锁时用全屏锁定页盖住整个 App，
 * 进入即自动弹一次系统验证，也可点按钮重试。验证成功后放行本次进程。
 */
@Composable
fun AppLockGate(enabled: Boolean, content: @Composable () -> Unit) {
    if (!enabled || AppLock.unlocked) {
        content()
        return
    }
    val context = LocalContext.current
    // 设备验证不可用时不能把用户锁死（比如锁屏密码被移除），直接放行
    val usable = remember { AppLock.canAuthenticate(context) }
    if (!usable) {
        content()
        return
    }
    var prompting by remember { mutableStateOf(false) }
    val launch: () -> Unit = {
        val activity = context as? FragmentActivity
        if (activity != null && !prompting) {
            prompting = true
            AppLock.prompt(
                activity,
                onSuccess = { AppLock.unlocked = true },
                onFailed = { prompting = false },
                // 用户取消（返回键/负按钮）不走 onFailed，门闩必须在这里复位
                onFinished = { prompting = false },
            )
        }
    }
    LaunchedEffect(Unit) { launch() }
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("FoldReader 已锁定", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = launch) { Text("解锁") }
        }
    }
}
