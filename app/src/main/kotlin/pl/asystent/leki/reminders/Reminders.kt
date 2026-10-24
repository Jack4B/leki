package pl.asystent.leki.reminders

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import pl.asystent.leki.MainActivity
import pl.asystent.leki.R
import pl.asystent.leki.Store
import pl.asystent.leki.core.AppState
import pl.asystent.leki.core.REMINDER_BACKUP
import pl.asystent.leki.core.REMINDER_STOCK
import pl.asystent.leki.core.clearTimer
import pl.asystent.leki.core.markSlot
import pl.asystent.leki.core.unmarkDoses
import pl.asystent.leki.core.num
import pl.asystent.leki.core.openDoses
import pl.asystent.leki.core.reminders
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

object Reminders {
    private const val CHANNEL = "reminders"
    private const val PREFS = "reminders"
    private const val KEY_IDS = "ids"
    const val ACTION = "pl.asystent.leki.REMIND"
    /** Przyciski w powiadomieniu o dawkach: odhacz wszystko z tej pory albo przypomnij za [SNOOZE_MIN] minut. */
    const val ACTION_TAKEN = "pl.asystent.leki.REMIND_TAKEN"
    const val ACTION_SNOOZE = "pl.asystent.leki.REMIND_SNOOZE"
    const val ACTION_UNDO = "pl.asystent.leki.REMIND_UNDO"
    const val SNOOZE_MIN = 15
    private const val SNOOZE_BASE = 10_000_000

    /** Osobny identyfikator odłożonego przypomnienia dla każdej pory dnia i dnia, żeby odłożenie dwóch pór nie nadpisywało się (ponad zakresem zwykłych id, które sięgają ok. 2 000 000). */
    fun snoozeId(date: String, slot: String): Int = SNOOZE_BASE + Math.floorMod("$date|$slot".hashCode(), 100_000)

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            val ch = NotificationChannel(CHANNEL, ctx.getString(R.string.channel_reminders), NotificationManager.IMPORTANCE_HIGH)
            ch.enableVibration(true)
            nm.createNotificationChannel(ch)
        }
    }

    fun canNotify(ctx: Context): Boolean = NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    fun canScheduleExact(ctx: Context): Boolean {
        val am = ctx.getSystemService(AlarmManager::class.java)
        return Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
    }

    private fun pending(ctx: Context, id: Int, title: String?, body: String?, create: Boolean, date: String? = null, slot: String? = null): PendingIntent? {
        val i = Intent(ctx, ReminderReceiver::class.java).setAction(ACTION).putExtra("id", id)
        if (title != null) i.putExtra("title", title)
        if (body != null) i.putExtra("body", body)
        if (date != null) i.putExtra("date", date)
        if (slot != null) i.putExtra("slot", slot)
        val flags = PendingIntent.FLAG_IMMUTABLE or if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE
        return PendingIntent.getBroadcast(ctx, id, i, flags)
    }

    /** Ustawia alarmy zgodnie z planem (14 dni do przodu) i kasuje poprzednie. Wywoływane po każdej zmianie danych. */
    @Synchronized
    fun reschedule(ctx: Context, s: AppState) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getStringSet(KEY_IDS, emptySet())!!.forEach { idStr ->
            val id = idStr.toIntOrNull() ?: return@forEach
            pending(ctx, id, null, null, false)?.let { am.cancel(it); it.cancel() }
        }
        ensureChannel(ctx)
        val list = s.reminders(ZoneId.systemDefault(), System.currentTimeMillis(), LocalDate.now())
        val exact = canScheduleExact(ctx)
        list.forEach { r ->
            val pi = pending(ctx, r.id, r.title, r.body, true, r.date?.toString(), r.slotId) ?: return@forEach
            setAlarm(am, r.atMillis, pi, exact)
        }
        // commit zamiast apply: lista identyfikatorów musi być zapisana, zanim proces zostanie zabity, inaczej zostają alarmy-sieroty
        prefs.edit().putStringSet(KEY_IDS, list.map { it.id.toString() }.toSet()).commit()
    }

    /** Dokładny alarm, a gdy system odmówi (cofnięta zgoda, polityka producenta), zwykły alarm: lepsze spóźnione przypomnienie niż awaria. */
    private fun setAlarm(am: AlarmManager, at: Long, pi: PendingIntent, exact: Boolean) {
        try {
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: SecurityException) {
            runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
        }
    }

    /** Natychmiastowe powiadomienie próbne (po kilku sekundach). */
    fun scheduleTest(ctx: Context) {
        ensureChannel(ctx)
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = pending(ctx, 2, "Asystent leków", "Tak będzie wyglądać przypomnienie.", true) ?: return
        val at = System.currentTimeMillis() + 8000
        setAlarm(am, at, pi, canScheduleExact(ctx))
    }

    /** Odłożenie przypomnienia o dawkach: ten sam dzwonek za [SNOOZE_MIN] minut (przy dzwonku aplikacja i tak sprawdzi, co zostało do wzięcia). */
    fun snooze(ctx: Context, title: String, body: String, date: String, slot: String) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = pending(ctx, snoozeId(date, slot), title, body, true, date, slot) ?: return
        setAlarm(am, System.currentTimeMillis() + SNOOZE_MIN * 60_000L, pi, canScheduleExact(ctx))
    }

    private fun actionIntent(ctx: Context, action: String, id: Int, title: String, body: String, date: String, slot: String): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, id, Intent(ctx, ReminderReceiver::class.java).setAction(action).putExtra("id", id).putExtra("title", title).putExtra("body", body).putExtra("date", date).putExtra("slot", slot),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** Potwierdzenie po „Wzięte”: krótkie, ciche powiadomienie z przyciskiem „Cofnij” (znika po 30 s). */
    fun showTaken(ctx: Context, id: Int, what: String, date: String, keys: List<String>) {
        if (!canNotify(ctx)) return
        ensureChannel(ctx)
        val undo = PendingIntent.getBroadcast(
            ctx, id, Intent(ctx, ReminderReceiver::class.java).setAction(ACTION_UNDO).putExtra("id", id).putExtra("date", date).putStringArrayListExtra("keys", ArrayList(keys)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_pill)
            .setColor(0xFF0B6B58.toInt())
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(NotificationCompat.Builder(ctx, CHANNEL).setSmallIcon(R.drawable.ic_stat_pill).setContentTitle("Asystent leków").setContentText("Odhaczono dawki").build())
            .setContentTitle("Odhaczono dawki")
            .setContentText(what)
            .setSilent(true)
            .setAutoCancel(true)
            .setTimeoutAfter(30_000)
            .addAction(0, "Cofnij", undo)
            .build()
        try { NotificationManagerCompat.from(ctx).notify(id, n) } catch (e: SecurityException) { /* brak zgody na powiadomienia */ }
    }

    fun show(ctx: Context, id: Int, title: String, body: String, date: String? = null, slot: String? = null) {
        if (!canNotify(ctx)) return
        ensureChannel(ctx)
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE)
        // nazwa leku to dane zdrowotne: na zablokowanym ekranie (gdy użytkownik ukrywa wrażliwą treść) pokazujemy wersję neutralną
        val publicVersion = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_pill)
            .setColor(0xFF0B6B58.toInt())
            .setContentTitle("Asystent leków")
            .setContentText(if (id == REMINDER_BACKUP || id == REMINDER_STOCK) "Przypomnienie z aplikacji" else "Czas na przyjęcie leku")
            .build()
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_pill)
            .setColor(0xFF0B6B58.toInt())
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .apply {
                if (date != null && slot != null) {
                    addAction(0, "Wzięte", actionIntent(ctx, ACTION_TAKEN, id, title, body, date, slot))
                    addAction(0, "Odłóż o $SNOOZE_MIN min", actionIntent(ctx, ACTION_SNOOZE, id, title, body, date, slot))
                }
            }
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(id, n)
        } catch (e: SecurityException) {
            // brak zgody na powiadomienia
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra("id", 0)
        val dateStr = intent.getStringExtra("date")
        val slot = intent.getStringExtra("slot")
        val date = dateStr?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        when (intent.action) {
            Reminders.ACTION -> {
                var body = intent.getStringExtra("body") ?: ""
                var show = true
                // przypomnienie o dawkach: w chwili dzwonka sprawdzamy, co jeszcze zostało do wzięcia (np. po odłożeniu albo gdy dawki odhaczono w aplikacji)
                if (date != null && slot != null) {
                    val s = (context.applicationContext as? pl.asystent.leki.LekiApp)?.store?.state?.value
                    if (s != null) {
                        val open = s.openDoses(date, slot)
                        // przypomnienia wyłączone po zaplanowaniu (także odłożone) już nie dzwonią
                        if (open.isEmpty() || !s.settings.remindOn) show = false else body = open.joinToString(", ") { it.med.name + " " + num(it.dose) + " " + it.med.unit }
                    }
                }
                if (show) Reminders.show(context, id, intent.getStringExtra("title") ?: "Asystent leków", body, dateStr, slot)
                // okno alarmów jest kroczące (14 dni): po każdym przypomnieniu uzupełniamy je, żeby nie wygasło, gdy aplikacja długo nie jest otwierana
                refresh(context)
            }
            Reminders.ACTION_TAKEN -> {
                NotificationManagerCompat.from(context).cancel(id)
                if (date == null || slot == null) return
                val pending = goAsync()
                Thread {
                    try {
                        val store = (context.applicationContext as pl.asystent.leki.LekiApp).store
                        val keys = store.state.value.openDoses(date, slot).map { it.key }
                        store.update { it.markSlot(date, slot, LocalDate.now(), LocalDateTime.now()) }
                        store.flush()   // zapis od razu: proces może zostać zabity zaraz po odebraniu komunikatu
                        // potwierdzenie z możliwością cofnięcia: jeden dotyk w powiadomieniu nie może po cichu psuć statystyk zgodności
                        if (keys.isNotEmpty()) Reminders.showTaken(context, id, intent.getStringExtra("title")?.removePrefix("Pora na leki: ") ?: "dawki", dateStr!!, keys)
                    } catch (e: Exception) {
                        android.util.Log.e("Reminders", "Nie udało się odhaczyć dawek z powiadomienia", e)
                    } finally {
                        pending.finish()
                    }
                }.start()
            }
            Reminders.ACTION_UNDO -> {
                NotificationManagerCompat.from(context).cancel(id)
                val keys = intent.getStringArrayListExtra("keys")
                if (date == null || keys.isNullOrEmpty()) return
                val pending = goAsync()
                Thread {
                    try {
                        val store = (context.applicationContext as pl.asystent.leki.LekiApp).store
                        store.update { st ->
                            // stoper po cofniętej dawce też gasimy (tylko wtedy, gdy odlicza właśnie ten lek)
                            val timerMeds = keys.mapNotNull { k -> st.med(k.substringBefore('|'))?.takeIf { it.timerMin > 0 }?.name }
                            val r = st.unmarkDoses(date, keys)
                            if (st.timerMed in timerMeds) r.clearTimer() else r
                        }
                        store.flush()
                    } catch (e: Exception) {
                        android.util.Log.e("Reminders", "Nie udało się cofnąć odhaczenia z powiadomienia", e)
                    } finally {
                        pending.finish()
                    }
                }.start()
            }
            Reminders.ACTION_SNOOZE -> {
                NotificationManagerCompat.from(context).cancel(id)
                if (dateStr != null && slot != null) Reminders.snooze(context, intent.getStringExtra("title") ?: "Asystent leków", intent.getStringExtra("body") ?: "", dateStr, slot)
            }
        }
    }
}

/** Ustawia alarmy od nowa na podstawie aktualnych danych (stan z pamięci aplikacji, a gdy go nie ma, z pliku). */
private fun BroadcastReceiver.refresh(context: Context) {
    val pending = goAsync()
    Thread {
        try {
            val state = (context.applicationContext as? pl.asystent.leki.LekiApp)?.store?.state?.value ?: Store.read(context.filesDir).first
            Reminders.reschedule(context, state)
        } catch (e: Exception) {
            android.util.Log.e("Reminders", "Nie udało się odświeżyć alarmów", e)
        } finally {
            pending.finish()
        }
    }.start()
}

/** Po restarcie telefonu, aktualizacji aplikacji, zmianie strefy czasu lub zegara i po zmianie zgody na dokładne alarmy trzeba ustawić alarmy od nowa. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        refresh(context)
    }

    companion object {
        private val ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
        )
    }
}
