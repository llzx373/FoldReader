package com.llzx373.foldreader.core.ai.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 项目首个持久化秘密存储：AI API Key 的加解密容器。
 *
 * 密钥由 AndroidKeyStore 托管（AES/GCM/NoPadding），密文以 `IV(12字节) + 密文`
 * 拼接后 Base64 编码存进私有 SharedPreferences。GCM 禁止随机 IV 复用，
 * 所以每次加密都取 `cipher.iv` 与密文一起持久化，解密时拆前缀还原。
 *
 * 红线：本类任何日志、异常消息都不得携带明文 key——因此全程不记日志，
 * 解密失败（密钥被系统作废、密文损坏等）只清空存储并返回 null。
 */
class CredentialStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // 惰性获取：AppContainer 构建期不碰 AndroidKeyStore——
    // 未配置 AI 时零接触，也让走 Application 的 Robolectric 用例不被波及。
    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    @Synchronized
    fun saveKey(key: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val ciphertext = cipher.doFinal(key.toByteArray(Charsets.UTF_8))
        val combined = cipher.iv + ciphertext
        prefs.edit()
            .putString(PREF_API_KEY, Base64.encodeToString(combined, Base64.NO_WRAP))
            .apply()
    }

    @Synchronized
    fun readKey(): String? {
        val encoded = prefs.getString(PREF_API_KEY, null) ?: return null
        return try {
            val combined = Base64.decode(encoded, Base64.NO_WRAP)
            require(combined.size > GCM_IV_LENGTH)
            val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
            val ciphertext = combined.copyOfRange(GCM_IV_LENGTH, combined.size)
            val key = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
                ?: throw KeyStoreException()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            clear()
            null
        }
    }

    @Synchronized
    fun clear() {
        prefs.edit().remove(PREF_API_KEY).apply()
    }

    private fun getOrCreateSecretKey(): SecretKey {
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private class KeyStoreException : Exception()

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "foldreader_ai_api_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_LENGTH = 12
        const val GCM_TAG_LENGTH_BITS = 128
        const val PREFS_NAME = "ai_credentials"
        const val PREF_API_KEY = "api_key"
    }
}
