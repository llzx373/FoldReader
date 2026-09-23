package com.llzx373.foldreader.core.ai.android

import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * JVM 单测用的内存版 `AndroidKeyStore` JCA Provider。
 *
 * Robolectric 不提供 AndroidKeyStore 实现（robolectric/robolectric#1518 仍开放），
 * 而 [CredentialStore] 在 AppContainer 构建期就会 `KeyStore.getInstance("AndroidKeyStore")`，
 * 没有该 Provider 时所有走 Application 的 Robolectric 用例都会在 onCreate 阶段崩掉。
 * 本 Provider 经 `java.security.properties`（见 app/build.gradle.kts 的 Test 任务配置）
 * 注册进测试 JVM：KeyStore 是进程内静态 Map，KeyGenerator 收到 KeyGenParameterSpec 后
 * 生成一把随机 AES-256 密钥并按 alias 入"库"——语义与真机一致，仅不做硬件隔离。
 */
private const val ANDROID_KEYSTORE_NAME = "AndroidKeyStore"

/** 进程内共享的内存密钥库（alias → key）。 */
private val fakeKeys = ConcurrentHashMap<String, Key>()

// android.jar 的 java.security.Provider 只有旧的 (String, double, String) 构造器
@Suppress("DEPRECATION")
class FakeAndroidKeyStoreProvider : Provider(
    ANDROID_KEYSTORE_NAME,
    1.0,
    "In-memory AndroidKeyStore for JVM unit tests",
) {
    init {
        putService(
            Service(
                this, "KeyStore", ANDROID_KEYSTORE_NAME,
                FakeKeyStoreSpi::class.java.name, null, null,
            ),
        )
        putService(
            Service(
                this, "KeyGenerator", "AES",
                FakeKeyGeneratorSpi::class.java.name, null, null,
            ),
        )
    }

    /** 内存 KeyStore：只支持 CredentialStore 用到的 getKey。 */
    class FakeKeyStoreSpi : KeyStoreSpi() {
        override fun engineGetKey(alias: String?, password: CharArray?): Key? =
            fakeKeys[alias]

        override fun engineSetKeyEntry(alias: String?, key: Key?, password: CharArray?, chain: Array<out Certificate>?) {
            fakeKeys[alias!!] = key!!
        }

        override fun engineSetKeyEntry(alias: String?, key: ByteArray?, chain: Array<out Certificate>?) =
            throw UnsupportedOperationException()

        override fun engineGetCertificate(alias: String?): Certificate? = null
        override fun engineGetCertificateAlias(cert: Certificate?): String? = null
        override fun engineGetCertificateChain(alias: String?): Array<out Certificate>? = null
        override fun engineGetCreationDate(alias: String?): Date = Date()
        override fun engineSetCertificateEntry(alias: String?, cert: Certificate?) =
            throw UnsupportedOperationException()

        override fun engineDeleteEntry(alias: String?) {
            fakeKeys.remove(alias)
        }

        override fun engineAliases(): java.util.Enumeration<String> =
            java.util.Collections.enumeration(fakeKeys.keys)

        override fun engineContainsAlias(alias: String?): Boolean = fakeKeys.containsKey(alias)
        override fun engineSize(): Int = fakeKeys.size
        override fun engineIsKeyEntry(alias: String?): Boolean = fakeKeys.containsKey(alias)
        override fun engineIsCertificateEntry(alias: String?): Boolean = false
        override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit
        override fun engineStore(stream: OutputStream?, password: CharArray?) = Unit
    }

    /** 收 KeyGenParameterSpec 拿 alias，生成随机 AES-256 密钥入"库"。 */
    class FakeKeyGeneratorSpi : KeyGeneratorSpi() {
        private var alias: String? = null

        override fun engineInit(params: AlgorithmParameterSpec?, random: SecureRandom?) {
            alias = params?.let {
                runCatching {
                    it.javaClass.getMethod("getKeystoreAlias").invoke(it) as? String
                }.getOrNull()
            } ?: throw IllegalArgumentException("仅支持 KeyGenParameterSpec")
        }

        override fun engineInit(random: SecureRandom?) = Unit
        override fun engineInit(keysize: Int, random: SecureRandom?) = Unit

        override fun engineGenerateKey(): SecretKey {
            val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
                .let { SecretKeySpec(it, "AES") }
            fakeKeys[alias!!] = key
            return key
        }
    }
}
