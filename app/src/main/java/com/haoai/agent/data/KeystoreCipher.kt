package com.haoai.agent.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class KeystoreCipher {

    /** 仅查找已存在的密钥，绝不顺手生成——解密路径生成新密钥会掩盖"换机后无法恢复"的事实。 */
    private fun getKey(): SecretKey? = runCatching {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        ks.getKey(ALIAS, null) as? SecretKey
    }.getOrNull()

    private fun obtainKey(): SecretKey? = getKey() ?: runCatching {
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        gen.generateKey()
    }.getOrNull()

    /**
     * @return 密文；null 表示加密失败（Keystore 不可用等）。
     * 绝不能返回空串冒充成功——那会把 API key 永久抹掉。
     */
    fun encrypt(plain: String): String? {
        if (plain.isEmpty()) return ""
        return runCatching {
            val key = obtainKey() ?: error("AndroidKeyStore 不可用")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(iv + ct, Base64.NO_WRAP)
        }.getOrNull()
    }

    fun decrypt(cipherText: String?): String {
        if (cipherText.isNullOrBlank()) return ""
        return runCatching {
            val data = Base64.decode(cipherText, Base64.NO_WRAP)
            val key = getKey() ?: return ""
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, data, 0, 12))
            String(cipher.doFinal(data, 12, data.size - 12), Charsets.UTF_8)
        }.getOrDefault("")
    }

    private companion object {
        const val ALIAS = "haoai_master_key"
    }
}
