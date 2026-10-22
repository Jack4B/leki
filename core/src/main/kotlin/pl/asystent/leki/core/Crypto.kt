package pl.asystent.leki.core

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Zaszyfrowana kopia zapasowa, zgodna z poprzednimi wersjami aplikacji:
 * klucz AES-256 z hasła przez PBKDF2-HMAC-SHA256, szyfr AES-256-GCM (znacznik uwierzytelniający na końcu szyfrogramu).
 */
object BackupCrypto {
    const val ITERATIONS = 600_000
    private val b64 = Base64.getEncoder()
    private val unb64 = Base64.getDecoder()

    class WrongPasswordException : Exception("Złe hasło albo uszkodzony plik.")

    private fun key(password: String, salt: ByteArray, iter: Int): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, iter, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(bytes, "AES")
    }

    fun encrypt(password: String, plain: String, iter: Int = ITERATIONS): String {
        val rnd = SecureRandom()
        val salt = ByteArray(16).also(rnd::nextBytes)
        val iv = ByteArray(12).also(rnd::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(password, salt, iter), GCMParameterSpec(128, iv))
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Backup.json.encodeToString(
            JsonElement.serializer(),
            buildJsonObject {
                put("app", "asystent-lekow-enc"); put("v", 1); put("kdf", "PBKDF2-SHA256"); put("iter", iter)
                put("cipher", "AES-256-GCM"); put("salt", b64.encodeToString(salt)); put("iv", b64.encodeToString(iv)); put("ct", b64.encodeToString(ct))
            },
        )
    }

    fun decrypt(password: String, envelope: JsonElement): String {
        val o = envelope as? JsonObject ?: throw Backup.FormatException("format")
        fun s(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw Backup.FormatException("format")
        val iterD = (o["iter"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: throw Backup.FormatException("format")
        // zakres sprawdzamy przed zamianą na Int (inaczej ogromna liczba zawija się do małej) i z rozsądną górną granicą (czas na telefonie)
        if (iterD !in 100_000.0..2_000_000.0) throw Backup.FormatException("format")
        val iter = Math.round(iterD).toInt()
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(password, unb64.decode(s("salt")), iter), GCMParameterSpec(128, unb64.decode(s("iv"))))
            String(c.doFinal(unb64.decode(s("ct"))), Charsets.UTF_8)
        } catch (e: java.security.GeneralSecurityException) {
            throw WrongPasswordException()
        } catch (e: IllegalArgumentException) {
            throw Backup.FormatException("format") // niepoprawny base64 w pliku
        }
    }
}
