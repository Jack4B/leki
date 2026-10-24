package pl.asystent.leki.core.storage

import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Zwykły AES-GCM jako DEK w testach (IV z `cipher.iv`, jak w aplikacji). */
class SoftCipher(key: ByteArray = ByteArray(32) { (it * 7 + 1).toByte() }) : DataCipher {
    private val k = SecretKeySpec(key, "AES")
    override fun encrypt(plain: ByteArray, aad: ByteArray): DataCipher.Sealed {
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, k); c.updateAAD(aad)
        return DataCipher.Sealed(c.iv, c.doFinal(plain))
    }
    override fun decrypt(iv: ByteArray, body: ByteArray, aad: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, iv)); c.updateAAD(aad)
        return try { c.doFinal(body) } catch (e: AEADBadTagException) { throw AuthFailedException(cause = e) }
    }
}

/** Atrapa KEK: osobny klucz na alias; brakujący alias rzuca [KeyUnavailableException] z `aliasMissing`. */
class FakeKeyWrap(val aliases: MutableMap<String, ByteArray> = linkedMapOf()) : KeyWrap {
    fun add(alias: String, seed: Int = alias.hashCode()) { aliases[alias] = ByteArray(32) { (it * 11 + seed).toByte() } }
    private fun cipher(alias: String) = SoftCipher(aliases[alias] ?: throw KeyUnavailableException("brak aliasu $alias", aliasMissing = true))
    override fun wrap(alias: String, dek: ByteArray, aad: ByteArray) = cipher(alias).encrypt(dek, aad)
    override fun unwrap(alias: String, iv: ByteArray, body: ByteArray, aad: ByteArray) = cipher(alias).decrypt(iv, body, aad)
}
