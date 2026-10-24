package pl.asystent.leki

import android.app.Application
import pl.asystent.leki.reminders.Reminders

class LekiApp : Application() {
    lateinit var store: Store
        private set

    override fun onCreate() {
        super.onCreate()
        store = Store(filesDir) { st ->
            Reminders.reschedule(this, Reminders.people(st))
            runCatching { BpWidget.refresh(this, st.state.value) }
            // automatyczna kopia do wybranego folderu (gdy włączona i należna): obejmuje wszystkie osoby; po sukcesie oznaczamy datę ostatniej kopii
            runCatching { AutoBackup.maybeRun(this, st.exportStates()) { d -> st.markBackup(d) } }
        }
        runCatching { Reminders.ensureChannel(this) }
        // błąd alarmów (np. cofnięta zgoda, limit alarmów) nie może uniemożliwiać uruchomienia aplikacji
        runCatching { Reminders.reschedule(this, Reminders.people(store)) }
        // pliki tymczasowe z udostępniania i aparatu (kopie danych, zdjęcia wyników) nie zostają na dysku po zamknięciu aplikacji
        Thread { runCatching { listOf("share", "camera").forEach { java.io.File(cacheDir, it).deleteRecursively() } } }.start()
    }
}
