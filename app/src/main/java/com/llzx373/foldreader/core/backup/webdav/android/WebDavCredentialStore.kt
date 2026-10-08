package com.llzx373.foldreader.core.backup.webdav.android

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
 * WebDAV 密码的加解密容器（M25）——与 AI API Key 的 CredentialStore 同一口径：
 * AndroidKeyStore 托管 AES/GCM 密钥，密文 `IV(12) + 密文` Base64 后进私有 SharedPreferences。
 *
 * 红线：密码明文不出存储边界——不记日志、不进备份、异常消息不携带明文；
 * 解密失败（密钥被系统作废、密文损坏）只清空存储并返回 null。
 */
class WebDavCredentialStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // 惰性获取：AppContainer 构建期不碰 AndroidKeyStore——未配置 WebDAV 时零接触。
    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    @Synchronized
    fun savePassword(password: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val ciphertext = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        val combined = cipher.iv + ciphertext
        prefs.edit()
            .putString(PREF_PASSWORD, Base64.encodeToString(combined, Base64.NO_WRAP))
            .apply()
    }

    @Synchronized
    fun readPassword(): String? {
        val encoded = prefs.getString(PREF_PASSWORD, null) ?: return null
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
        prefs.edit().remove(PREF_PASSWORD).apply()
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
        const val KEY_ALIAS = "foldreader_webdav_password"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_LENGTH = 12
        const val GCM_TAG_LENGTH_BITS = 128
        const val PREFS_NAME = "webdav_credentials"
        const val PREF_PASSWORD = "password"
    }
}
