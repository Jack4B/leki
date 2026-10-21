package pl.asystent.leki

import android.app.Application
import pl.asystent.leki.reminders.Reminders

class LekiApp : Application() {
    lateinit var store: Store
        private set

    override fun onCreate() {
        super.onCreate()
        store = Store(filesDir) { s -> Reminders.reschedule(this, s) }
        Reminders.ensureChannel(this)
        Reminders.reschedule(this, store.state.value)
    }
}
