package pl.asystent.leki.core.storage

import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** AES-256-GCM kluczem DEK (programowo, `javax.crypto`). IV zawsze z `cipher.iv` (generuje go dostawca), nigdy własny. */
class AesGcmCipher(dek: ByteArray) : DataCipher {
    init { require(dek.size == 32) { "DEK ma 32 bajty" } }
    private val key = SecretKeySpec(dek.copyOf(), "AES")

    override fun encrypt(plain: ByteArray, aad: ByteArray): DataCipher.Sealed {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key)
        c.updateAAD(aad)
        return DataCipher.Sealed(c.iv, c.doFinal(plain))
    }

    override fun decrypt(iv: ByteArray, body: ByteArray, aad: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        c.updateAAD(aad)
        return try { c.doFinal(body) } catch (e: AEADBadTagException) { throw AuthFailedException(cause = e) }
    }
}
