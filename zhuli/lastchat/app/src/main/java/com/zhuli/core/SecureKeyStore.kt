package com.zhuli.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.zhuli.core.ZhuliLog as Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API Key 的加密存放（第 9 步 · 需求 §3.2「只存 App 私有目录」的加强版）。
 *
 * 做法：Android Keystore 里生成一把不可导出的 AES-256 密钥（硬件级最好，ROM 不支持就退软件级），
 * 用它以 GCM 模式加密 Key，密文（IV + 密文）以 Base64 存 SharedPreferences。
 * 好处：`adb backup` 拔走私有目录也解不开——密钥在 Keystore 里，不在文件里。
 *
 * 兼容：老版本把 Key 明文存在 `zhuli_prefs/link_api_key`，这里首次读取时**自动迁移**：
 * 加密存起来 + 把明文抹掉。Keystore 不可用（极老 ROM）时降级为明文并打标记，绝不因此崩。
 */
object SecureKeyStore {

    private const val TAG = "zhuli.key"
    private const val SP = "zhuli_secure"
    private const val K_ENC = "api_key_enc"
    private const val K_PLAIN = "api_key_plain_fallback"
    private const val K_FAILED = "api_key_enc_failed"
    private const val ALIAS = "zhuli_api_key_v1"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private fun sp(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(SP, Context.MODE_PRIVATE)

    fun get(ctx: Context): String {
        val app = ctx.applicationContext
        val enc = sp(app).getString(K_ENC, null)
        if (!enc.isNullOrBlank()) {
            try { return decrypt(enc) } catch (t: Throwable) {
                Log.w(TAG, "decrypt failed", t)
                // 解不开就当没配：让用户重填，别把乱码当 Key 发出去
                return ""
            }
        }
        // 老版本遗留的明文（只在极老机型出现过）：迁成密文；迁不动按 fail-closed 当没配
        val plain = sp(app).getString(K_PLAIN, null)
        if (!plain.isNullOrBlank()) {
            return if (put(ctx, plain)) get(ctx) else ""
        }
        // 从老版本明文迁移
        val legacy = legacyPlain(ctx)
        if (legacy.isNotBlank()) {
            if (put(ctx, legacy)) {
                Log.i(TAG, "legacy key migrated to keystore")
                return get(ctx)
            }
            return ""
        }
        return ""
    }

    /**
     * 保存 Key。
     *
     * D6 / §11-7（fail-closed）：加密不可用时**拒绝保存**并打标记，绝不退回明文落盘。
     * 返回 false 表示没存进去，调用方要把这件事告诉用户（宁可暂时不能上云，也不留明文）。
     */
    fun put(ctx: Context, value: String): Boolean {
        val app = ctx.applicationContext
        val v = value.trim()
        if (v.isEmpty()) { clear(ctx); return true }
        return try {
            sp(app).edit().putString(K_ENC, encrypt(v)).remove(K_PLAIN).remove(K_FAILED).apply()
            clearLegacy(ctx)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "keystore unavailable, refuse to store key in plaintext", t)
            sp(app).edit().remove(K_ENC).remove(K_PLAIN).putBoolean(K_FAILED, true).apply()
            false
        }
    }

    fun clear(ctx: Context) {
        sp(ctx.applicationContext).edit().clear().apply()
        clearLegacy(ctx)
    }

    /** 给设置面板显示用：不吐 Key 本体，只给状态和尾号。 */
    fun status(ctx: Context): String {
        if (sp(ctx.applicationContext).getBoolean(K_FAILED, false)) {
            return "加密存储不可用，已拒绝保存（fail-closed）"
        }
        val v = get(ctx)
        if (v.isBlank()) return "未配置"
        val tail = if (v.length >= 4) v.takeLast(4) else "****"
        return "已配置（加密存储 · 尾号 $tail）"
    }

    // ==================== 加解密 ====================

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // 不要求用户解锁才能用：提醒/后台链路也要能读 Key
                .setUserAuthenticationRequired(false)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val buf = ByteArray(1 + iv.size + ct.size)
        buf[0] = iv.size.toByte()
        System.arraycopy(iv, 0, buf, 1, iv.size)
        System.arraycopy(ct, 0, buf, 1 + iv.size, ct.size)
        return Base64.encodeToString(buf, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val buf = Base64.decode(encoded, Base64.NO_WRAP)
        val ivLen = buf[0].toInt()
        val iv = buf.copyOfRange(1, 1 + ivLen)
        val ct = buf.copyOfRange(1 + ivLen, buf.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    // ==================== 老明文迁移 ====================

    private const val LEGACY_SP = "zhuli_prefs"
    private const val LEGACY_KEY = "link_api_key"

    private fun legacyPlain(ctx: Context): String =
        try {
            ctx.applicationContext.getSharedPreferences(LEGACY_SP, Context.MODE_PRIVATE)
                .getString(LEGACY_KEY, "") ?: ""
        } catch (t: Throwable) { "" }

    private fun clearLegacy(ctx: Context) {
        try {
            ctx.applicationContext.getSharedPreferences(LEGACY_SP, Context.MODE_PRIVATE)
                .edit().remove(LEGACY_KEY).apply()
        } catch (_: Throwable) {}
    }
}
