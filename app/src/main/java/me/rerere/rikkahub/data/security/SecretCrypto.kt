/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.security

import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Reads the legacy encrypted settings format used by an intermediate build.
 *
 * [associatedData] binds a ciphertext to its storage field, so moving an encrypted value
 * from one preference/column to another makes authentication fail instead of silently
 * changing the meaning of the secret.
 */
object SecretCrypto {
    private const val KEY_ALIAS = "orangechat_local_secrets_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val PREFIX = "enc:v1:"
    private const val GCM_TAG_LENGTH_BITS = 128

    fun isEncrypted(value: String?): Boolean = value?.startsWith(PREFIX) == true

    fun decrypt(storedValue: String?, associatedData: String): String? {
        if (storedValue == null || !isEncrypted(storedValue)) return storedValue

        val envelope = Base64.decode(storedValue.removePrefix(PREFIX), Base64.NO_WRAP)
        require(envelope.isNotEmpty()) { "Encrypted secret envelope is empty" }
        val ivLength = envelope[0].toInt() and 0xff
        require(ivLength in 12 until envelope.size) { "Encrypted secret envelope has an invalid IV" }

        val iv = envelope.copyOfRange(1, 1 + ivLength)
        val ciphertext = envelope.copyOfRange(1 + ivLength, envelope.size)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, getExistingKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            updateAAD(associatedData.toByteArray(Charsets.UTF_8))
        }
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }

    private fun getExistingKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return keyStore.getKey(KEY_ALIAS, null) as? SecretKey
            ?: error("Legacy encryption key is unavailable")
    }

}
