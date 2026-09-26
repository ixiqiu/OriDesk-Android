package com.xinjiyuan.oridesk

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 用 Android Keystore 做 AES/GCM 加密的小工具（契约 §4.3：客户端 topic/token 加密存储）。
 *
 * **为什么不用 androidx.security:security-crypto**：
 * 1. 该库已被 Google 标记废弃，新工程不该再引入；
 * 2. 本工程刻意把依赖压到最少（见 app/build.gradle.kts）——本机没有 Android 工具链，
 *    编译验证只能靠云端 CI，每多一个依赖就多一份失败概率。
 * 直接调 Keystore 只多几十行，且密钥永不出安全硬件，安全性并不更差。
 *
 * 密钥由系统托管，**卸载应用即失效**（这是期望行为：重装后重新注册订阅即可）。
 */
object SecureStore {

    private const val TAG = "SecureStore"
    private const val KEY_ALIAS = "oridesk_secure_store_v1"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                // 刻意**不**要求用户认证：推送服务要在后台无人值守时解密 topic 才能订阅。
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    /** 加密为 Base64(iv || ciphertext)。失败返回 null，调用方按"没有存过"处理。 */
    fun encrypt(plain: String): String? = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(iv + body, Base64.NO_WRAP)
    } catch (t: Throwable) {
        // 常见于 KeyStore 被系统重置（换机、恢复出厂、部分 ROM 的清理行为）。
        // 不能让它冒泡：一个坏掉的密钥不该让整个应用起不来。
        Log.w(TAG, "加密失败，按未保存处理", t)
        null
    }

    /** 解密 [encrypt] 的产物。任何异常都返回 null（宁可重新注册，也不要崩溃）。 */
    fun decrypt(encoded: String?): String? {
        if (encoded.isNullOrEmpty()) return null
        return try {
            val raw = Base64.decode(encoded, Base64.NO_WRAP)
            if (raw.size <= IV_BYTES) return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(GCM_TAG_BITS, raw, 0, IV_BYTES),
            )
            String(cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES), Charsets.UTF_8)
        } catch (t: Throwable) {
            Log.w(TAG, "解密失败（密钥可能已被系统重置），按未保存处理", t)
            null
        }
    }

    fun isAvailable(context: Context): Boolean = try {
        secretKey() != null
    } catch (t: Throwable) {
        Log.w(TAG, "Keystore 不可用", t)
        false
    }
}
