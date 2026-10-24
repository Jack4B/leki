package pl.asystent.leki

import android.app.Application
import pl.asystent.leki.reminders.Reminders

class LekiApp : Application() {
    lateinit var store: Store
        private set

    override fun onCreate() {
        super.onCreate()
        store = Store(filesDir) { s ->
            Reminders.reschedule(this, s)
            runCatching { BpWidget.refresh(this, s) }
            // automatyczna kopia do wybranego folderu (gdy włączona i należna); po sukcesie oznaczamy datę ostatniej kopii w danych
            runCatching { AutoBackup.maybeRun(this, s) { d -> store.update { it.copy(lastBackup = d) } } }
        }
        runCatching { Reminders.ensureChannel(this) }
        // błąd alarmów (np. cofnięta zgoda, limit alarmów) nie może uniemożliwiać uruchomienia aplikacji
        runCatching { Reminders.reschedule(this, store.state.value) }
        // pliki tymczasowe z udostępniania i aparatu (kopie danych, zdjęcia wyników) nie zostają na dysku po zamknięciu aplikacji
        Thread { runCatching { listOf("share", "camera").forEach { java.io.File(cacheDir, it).deleteRecursively() } } }.start()
    }
}
