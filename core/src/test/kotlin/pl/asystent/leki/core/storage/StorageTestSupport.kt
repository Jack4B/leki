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

val TEST_DEK = ByteArray(32) { (it * 7 + 1).toByte() }

class Killed : RuntimeException("proces zabity")

/** Pamięć zamiast katalogu: zapis i usunięcie są atomowe. */
open class MemOps(val files: MutableMap<String, ByteArray> = linkedMapOf()) : FileOps {
    var writes = 0
    override fun read(name: String) = files[name]?.copyOf()
    override fun write(name: String, bytes: ByteArray) { writes++; files[name] = bytes.copyOf() }
    override fun delete(name: String) { if (files.remove(name) != null) writes++ }
    override fun list(): Collection<String> = files.keys.toList()
}

/** Zabija proces tuż przed [killAt]-tą operacją zmieniającą pliki (zapis lub usunięcie), licząc od 1. */
class KillingOps(private val inner: MemOps, private val killAt: Int) : FileOps {
    var ops = 0
    private fun tick() { ops++; if (ops == killAt) throw Killed() }
    override fun read(name: String) = inner.read(name)
    override fun write(name: String, bytes: ByteArray) { tick(); inner.write(name, bytes) }
    override fun delete(name: String) { if (inner.files.containsKey(name)) tick(); inner.delete(name) }
    override fun list() = inner.list()
}
