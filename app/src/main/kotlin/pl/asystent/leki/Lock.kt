package pl.asystent.leki

import android.content.Context
import android.os.SystemClock
import android.view.WindowManager
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import pl.asystent.leki.core.AppLock

/** Kontroler blokady aplikacji; null tam, gdzie go nie ma (np. testy zrzutów ekranu). */
val LocalLock = staticCompositionLocalOf<LockController?> { null }

/**
 * Blokada aplikacji odciskiem palca, twarzą albo blokadą ekranu telefonu (system sam pyta o PIN, wzór lub hasło; aplikacja ich nie widzi ani nie przechowuje).
 * Ustawienia są w pamięci tego urządzenia (SharedPreferences), a nie w kopii zapasowej: kopia wczytana na innym telefonie nie może zamknąć dostępu do danych.
 * Zasady czasowe ([AppLock]) liczone są zegarem monotonicznym.
 */
class LockController(private val activity: FragmentActivity) {
    private val prefs = activity.getSharedPreferences("security", Context.MODE_PRIVATE)

    var enabled by mutableStateOf(prefs.getBoolean("enabled", false)); private set
    var graceIndex by mutableIntStateOf(prefs.getInt("grace", 1)); private set
    var secureWindow by mutableStateOf(prefs.getBoolean("secure", false)); private set
    /** Prawda, gdy aplikacja jest zasłonięta ekranem blokady (świeży start przy włączonej blokadzie jest zablokowany). */
    var locked by mutableStateOf(enabled); private set
    var message by mutableStateOf(""); private set

    private var leftAt: Long? = null
    private var prompting = false

    init { applyWindowFlags() }

    fun available(): Boolean = BiometricManager.from(activity).canAuthenticate(AUTH) == BiometricManager.BIOMETRIC_SUCCESS

    fun onStop() { leftAt = SystemClock.elapsedRealtime() }

    fun onStart() {
        if (AppLock.shouldLock(enabled, leftAt, SystemClock.elapsedRealtime(), graceIndex)) locked = true
    }

    /** Pyta system o uwierzytelnienie; [onOk] wywoływane po sukcesie. Jednocześnie trwa najwyżej jedno pytanie. */
    fun authenticate(subtitle: String, onOk: () -> Unit, onFail: (String) -> Unit = {}) {
        if (prompting) return
        prompting = true
        try {
            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { prompting = false; message = ""; onOk() }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { prompting = false; message = errString.toString(); onFail(errString.toString()) }
            }
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Asystent leków")
                .setSubtitle(subtitle)
                .setAllowedAuthenticators(AUTH)
                .build()
            BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback).authenticate(info)
        } catch (e: Exception) {
            prompting = false
            message = "Nie można teraz wyświetlić okna odblokowania."
            onFail(message)
        }
    }

    fun unlock() = authenticate("Odblokuj, żeby zobaczyć dane", onOk = { locked = false })

    /** Włącza blokadę po udanym uwierzytelnieniu (żeby nie dało się zamknąć sobie dostępu, gdy coś nie działa). */
    fun enable(done: (Boolean) -> Unit) {
        if (!available()) { done(false); return }
        authenticate("Potwierdź, żeby włączyć blokadę", onOk = {
            prefs.edit().putBoolean("enabled", true).apply(); enabled = true; locked = false; applyWindowFlags(); done(true)
        }, onFail = { done(false) })
    }

    fun disable(done: (Boolean) -> Unit) {
        authenticate("Potwierdź, żeby wyłączyć blokadę", onOk = {
            prefs.edit().putBoolean("enabled", false).apply(); enabled = false; locked = false; applyWindowFlags(); done(true)
        }, onFail = { done(false) })
    }

    fun setGrace(i: Int) { graceIndex = i; prefs.edit().putInt("grace", i).apply() }

    fun setSecure(on: Boolean) { secureWindow = on; prefs.edit().putBoolean("secure", on).apply(); applyWindowFlags() }

    /** Ukrycie w ostatnich aplikacjach i blokada zrzutów ekranu (opcja; wymaga włączonej blokady). */
    private fun applyWindowFlags() {
        val w = activity.window
        if (enabled && secureWindow) w.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else w.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    private companion object {
        const val AUTH = BIOMETRIC_WEAK or DEVICE_CREDENTIAL
    }
}
