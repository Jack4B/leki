package pl.asystent.leki

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import pl.asystent.leki.core.storage.AuthFailedException
import pl.asystent.leki.core.storage.DataCipher
import pl.asystent.leki.core.storage.FileOps
import pl.asystent.leki.core.storage.KeyFile
import pl.asystent.leki.core.storage.KeyUnavailableException
import pl.asystent.leki.core.storage.KeyWrap
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Klucz KEK w Android Keystore (AES-256-GCM, bez wymagania uwierzytelnienia, żeby działał w tle przy zablokowanym ekranie).
 * StrongBox próbujemy, a przy braku wracamy do zwykłego TEE. Klucz nie jest eksportowany. Błędy Keystore zamieniamy na
 * [KeyUnavailableException] (brak aliasu lub klucz niedostępny), a błąd uwierzytelnienia GCM na [AuthFailedException]; nic nie kasuje plików.
 */
object KeystoreKeyWrap : KeyWrap {
    private const val PROVIDER = "AndroidKeyStore"

    private fun store(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    /** Aliasy `leki_kek_<n>` obecne w Keystore. */
    fun aliases(): List<String> = try { store().aliases().toList().filter { KeyFile.aliasNumber(it) != null } } catch (e: Exception) { emptyList() }

    /** Tworzy klucz o podanym aliasie (StrongBox, a gdy niedostępny zwykłe TEE). Nie nadpisuje istniejącego. */
    fun createKey(alias: String) {
        require(KeyFile.aliasNumber(alias) != null)
        if (store().containsAlias(alias)) return
        fun spec(strongBox: Boolean): KeyGenParameterSpec {
            val b = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
            if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) b.setIsStrongBoxBacked(true)
            return b.build()
        }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        try { gen.init(spec(true)); gen.generateKey() }
        catch (e: Exception) { gen.init(spec(false)); gen.generateKey() }      // StrongBoxUnavailableException albo inny błąd StrongBox
    }

    fun deleteAlias(alias: String) { runCatching { store().deleteEntry(alias) } }
    fun deleteAllAliases() { aliases().forEach { deleteAlias(it) } }

    private fun key(alias: String): SecretKey {
        try {
            if (!store().containsAlias(alias)) throw KeyUnavailableException("brak aliasu", aliasMissing = true)
            return store().getKey(alias, null) as? SecretKey ?: throw KeyUnavailableException("brak aliasu", aliasMissing = true)
        } catch (e: KeyUnavailableException) { throw e }
        catch (e: GeneralSecurityException) { throw KeyUnavailableException("klucz niedostępny", false, e) }
        catch (e: ProviderException) { throw KeyUnavailableException("klucz niedostępny", false, e) }
    }

    override fun wrap(alias: String, dek: ByteArray, aad: ByteArray): DataCipher.Sealed = try {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(alias))
        c.updateAAD(aad)
        DataCipher.Sealed(c.iv, c.doFinal(dek))        // IV tylko z cipher.iv
    } catch (e: KeyUnavailableException) { throw e }
    catch (e: GeneralSecurityException) { throw KeyUnavailableException("klucz niedostępny", false, e) }
    catch (e: ProviderException) { throw KeyUnavailableException("klucz niedostępny", false, e) }

    override fun unwrap(alias: String, iv: ByteArray, body: ByteArray, aad: ByteArray): ByteArray = try {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(alias), javax.crypto.spec.GCMParameterSpec(128, iv))
        c.updateAAD(aad)
        c.doFinal(body)
    } catch (e: KeyUnavailableException) { throw e }
    catch (e: AEADBadTagException) { throw AuthFailedException(cause = e) }
    catch (e: GeneralSecurityException) { throw KeyUnavailableException("klucz niedostępny", false, e) }
    catch (e: ProviderException) { throw KeyUnavailableException("klucz niedostępny", false, e) }
}

/** Katalog danych aplikacji jako [FileOps]: zapis atomowy przez [AtomicFile] (usuwanie kasuje też `.bak` i `.new`). */
class AndroidFileOps(private val dir: File) : FileOps {
    override fun read(name: String): ByteArray? {
        val f = File(dir, name)
        if (!f.exists() && !File(dir, "$name.bak").exists()) return null
        return AtomicFile(f).readFully()
    }

    override fun write(name: String, bytes: ByteArray) {
        dir.mkdirs()
        val atomic = AtomicFile(File(dir, name))
        var out: java.io.FileOutputStream? = null
        try {
            out = atomic.startWrite()
            out.write(bytes)
            atomic.finishWrite(out)
        } catch (e: IOException) {
            out?.let { atomic.failWrite(it) }
            throw e
        }
    }

    override fun delete(name: String) { AtomicFile(File(dir, name)).delete() }
    override fun list(): Collection<String> = dir.list()?.toList().orEmpty()
}
