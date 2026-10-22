package me.rerere.rikkahub.testing

import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.cert.Certificate
import java.util.Collections
import java.util.Date
import javax.crypto.spec.SecretKeySpec

/** JVM-only key storage for settings persistence tests. Never registered in application code. */
class TestAndroidKeyStore : KeyStoreSpi() {
    override fun engineGetKey(alias: String?, password: CharArray?): Key = SecretKeySpec(ByteArray(32) { 17 }, "AES")
    override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit
    override fun engineStore(stream: OutputStream?, password: CharArray?) = Unit
    override fun engineContainsAlias(alias: String?) = true
    override fun engineAliases() = Collections.enumeration(listOf("orangechat_local_secrets_v1"))
    override fun engineSize() = 1
    override fun engineIsKeyEntry(alias: String?) = true
    override fun engineIsCertificateEntry(alias: String?) = false
    override fun engineGetCreationDate(alias: String?) = Date(0)
    override fun engineGetCertificateAlias(cert: Certificate?): String? = null
    override fun engineGetCertificate(alias: String?): Certificate? = null
    override fun engineGetCertificateChain(alias: String?): Array<Certificate>? = null
    override fun engineDeleteEntry(alias: String?) = Unit
    override fun engineSetCertificateEntry(alias: String?, cert: Certificate?) = Unit
    override fun engineSetKeyEntry(alias: String?, key: Key?, password: CharArray?, chain: Array<out Certificate>?) = Unit
    override fun engineSetKeyEntry(alias: String?, key: ByteArray?, chain: Array<out Certificate>?) = Unit

    class TestProvider : Provider("OrangeChatTestKeyStore", 1.0, "Test fixture") {
        init { put("KeyStore.AndroidKeyStore", TestAndroidKeyStore::class.java.name) }
    }
}
