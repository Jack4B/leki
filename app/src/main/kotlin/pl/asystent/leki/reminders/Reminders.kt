package pl.asystent.leki.reminders

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
    /** Identyfikatory alarmów kolejnych osób są przesunięte o wielokrotność tej liczby (zwykłe id sięgają ok. 2 000 000, odłożone ok. 10 100 000). */
    const val OFFSET = 100_000_000

    /** Osoba w aplikacji z jej danymi: numer w kolejności ([index]) decyduje o przesunięciu identyfikatorów alarmów. */
    class Person(val pid: String, val index: Int, val name: String, val state: AppState)

    fun people(store: Store): List<Person> = store.allStates().mapIndexed { i, (info, st) -> Person(info.id, i, info.name, st) }

    /** To samo bez uruchomionego magazynu (odbiornik po restarcie telefonu, gdy aplikacja nie działa). */
    fun peopleFromDisk(dir: java.io.File): List<Person> =
        Store.readAll(dir).states.mapIndexed { i, (id, st) -> Person(id, i, st.patient.trim().ifEmpty { "Osoba ${i + 1}" }, st) }
    private const val TEST_NOW_ID = 5

    /** Osobny identyfikator odłożonego przypomnienia dla każdej pory dnia i dnia, żeby odłożenie dwóch pór nie nadpisywało się (ponad zakresem zwykłych id, które sięgają ok. 2 000 000). */
    fun snoozeId(pid: String, date: String, slot: String): Int = SNOOZE_BASE + Math.floorMod("$pid|$date|$slot".hashCode(), 100_000)

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

    private fun pending(ctx: Context, id: Int, title: String?, body: String?, create: Boolean, date: String? = null, slot: String? = null, pid: String? = null, who: String? = null): PendingIntent? {
        val i = Intent(ctx, ReminderReceiver::class.java).setAction(ACTION).putExtra("id", id)
        if (title != null) i.putExtra("title", title)
        if (body != null) i.putExtra("body", body)
        if (date != null) i.putExtra("date", date)
        if (slot != null) i.putExtra("slot", slot)
        if (pid != null) i.putExtra("pid", pid)
        if (who != null) i.putExtra("who", who)
        val flags = PendingIntent.FLAG_IMMUTABLE or if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE
        return PendingIntent.getBroadcast(ctx, id, i, flags)
    }

    /** Ustawia alarmy zgodnie z planem (14 dni do przodu) i kasuje poprzednie. Wywoływane po każdej zmianie danych. */
    @Synchronized
    fun reschedule(ctx: Context, people: List<Person>) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getStringSet(KEY_IDS, emptySet())!!.forEach { idStr ->
            val id = idStr.toIntOrNull() ?: return@forEach
            pending(ctx, id, null, null, false)?.let { am.cancel(it); it.cancel() }
        }
        ensureChannel(ctx)
        val exact = canScheduleExact(ctx)
        val ids = HashSet<String>()
        val zone = ZoneId.systemDefault()
        people.forEach { p ->
            // przy więcej niż jednej osobie w powiadomieniu jest jej nazwa, żeby było wiadomo, czyje to leki
            val who = if (people.size > 1) p.name else null
            p.state.reminders(zone, System.currentTimeMillis(), LocalDate.now()).forEach { r ->
                val id = r.id + p.index * OFFSET
                val pi = pending(ctx, id, r.title, r.body, true, r.date?.toString(), r.slotId, p.pid, who) ?: return@forEach
                setAlarm(am, r.atMillis, pi, exact)
                ids.add(id.toString())
            }
        }
        // commit zamiast apply: lista identyfikatorów musi być zapisana, zanim proces zostanie zabity, inaczej zostają alarmy-sieroty
        prefs.edit().putStringSet(KEY_IDS, ids).commit()
    }

    /** Dokładny alarm, a gdy system odmówi (cofnięta zgoda, polityka producenta), zwykły alarm: lepsze spóźnione przypomnienie niż awaria. */
    private fun setAlarm(am: AlarmManager, at: Long, pi: PendingIntent, exact: Boolean) {
        try {
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: SecurityException) {
            runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
        }
    }

    /** Kanał powiadomień wyłączony przez użytkownika w ustawieniach systemu (zgoda ogólna może być przy tym udzielona). */
    fun channelBlocked(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 26) return false
        ensureChannel(ctx)
        return ctx.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE
    }

    /** Otwiera ustawienia powiadomień aplikacji (a gdy zablokowany jest sam kanał, ustawienia tego kanału). */
    fun openNotificationSettings(ctx: Context) {
        val i = if (Build.VERSION.SDK_INT >= 26 && channelBlocked(ctx)) Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL)
        else Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        runCatching { ctx.startActivity(i.putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** Ustawienia aplikacji w systemie (tam producenci telefonów trzymają zgodę na pracę w tle i autostart). */
    fun openAppSettings(ctx: Context) {
        runCatching { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /**
     * Próba w dwóch krokach, żeby odróżnić przyczyny: powiadomienie pokazane od razu (sprawdza zgodę i kanał)
     * oraz drugie z alarmu po kilku sekundach (sprawdza, czy system dowozi alarmy w tle).
     */
    fun sendTest(ctx: Context) {
        show(ctx, TEST_NOW_ID, "Test 1/2: powiadomienia działają", "Za chwilę przyjdzie drugie, z alarmu w tle.")
        ensureChannel(ctx)
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = pending(ctx, 2, "Test 2/2: alarm w tle działa", "Tak będzie wyglądać przypomnienie o dawkach.", true) ?: return
        setAlarm(am, System.currentTimeMillis() + 8000, pi, canScheduleExact(ctx))
    }

    /** Odłożenie przypomnienia o dawkach: ten sam dzwonek za [SNOOZE_MIN] minut (przy dzwonku aplikacja i tak sprawdzi, co zostało do wzięcia). */
    fun snooze(ctx: Context, title: String, body: String, date: String, slot: String, pid: String, who: String?) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = pending(ctx, snoozeId(pid, date, slot), title, body, true, date, slot, pid, who) ?: return
        setAlarm(am, System.currentTimeMillis() + SNOOZE_MIN * 60_000L, pi, canScheduleExact(ctx))
    }

    private fun actionIntent(ctx: Context, action: String, id: Int, title: String, body: String, date: String, slot: String, pid: String?, who: String?): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, id, Intent(ctx, ReminderReceiver::class.java).setAction(action).putExtra("id", id).putExtra("title", title).putExtra("body", body).putExtra("date", date).putExtra("slot", slot)
                .putExtra("pid", pid).putExtra("who", who),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** Potwierdzenie po „Wzięte”: krótkie, ciche powiadomienie z przyciskiem „Cofnij” (znika po 30 s). */
    fun showTaken(ctx: Context, id: Int, what: String, date: String, keys: List<String>, pid: String?) {
        if (!canNotify(ctx)) return
        ensureChannel(ctx)
        val undo = PendingIntent.getBroadcast(
            ctx, id, Intent(ctx, ReminderReceiver::class.java).setAction(ACTION_UNDO).putExtra("id", id).putExtra("date", date).putExtra("pid", pid).putStringArrayListExtra("keys", ArrayList(keys)),
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

    fun show(ctx: Context, id: Int, title: String, body: String, date: String? = null, slot: String? = null, pid: String? = null, who: String? = null) {
        if (!canNotify(ctx)) return
        ensureChannel(ctx)
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE)
        // nazwa leku to dane zdrowotne: na zablokowanym ekranie (gdy użytkownik ukrywa wrażliwą treść) pokazujemy wersję neutralną
        val publicVersion = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_pill)
            .setColor(0xFF0B6B58.toInt())
            .setContentTitle("Asystent leków")
            .setContentText(if (id % OFFSET == REMINDER_BACKUP || id % OFFSET == REMINDER_STOCK) "Przypomnienie z aplikacji" else "Czas na przyjęcie leku")
            .build()
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_pill)
            .setColor(0xFF0B6B58.toInt())
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setContentTitle(if (who.isNullOrEmpty()) title else "$who: $title")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .apply {
                if (date != null && slot != null) {
                    addAction(0, "Wzięte", actionIntent(ctx, ACTION_TAKEN, id, title, body, date, slot, pid, who))
                    addAction(0, "Odłóż o $SNOOZE_MIN min", actionIntent(ctx, ACTION_SNOOZE, id, title, body, date, slot, pid, who))
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
        val who = intent.getStringExtra("who")
        val app = context.applicationContext as? pl.asystent.leki.LekiApp
        // alarmy ustawione przez wcześniejszą wersję nie mają osoby: to dane pierwszej osoby
        val pid = intent.getStringExtra("pid") ?: "p1"
        val date = dateStr?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        when (intent.action) {
            Reminders.ACTION -> {
                var body = intent.getStringExtra("body") ?: ""
                var show = true
                // przypomnienie o dawkach: w chwili dzwonka sprawdzamy, co jeszcze zostało do wzięcia (np. po odłożeniu albo gdy dawki odhaczono w aplikacji)
                if (date != null && slot != null) {
                    val s = app?.store?.profileState(pid) ?: runCatching { Store.readAll(context.filesDir).states.firstOrNull { it.first == pid }?.second }.getOrNull()
                    if (s != null) {
                        val open = s.openDoses(date, slot)
                        // przypomnienia wyłączone po zaplanowaniu (także odłożone) już nie dzwonią
                        if (open.isEmpty() || !s.settings.remindOn) show = false else body = open.joinToString(", ") { it.med.name + " " + num(it.dose) + " " + it.med.unit }
                    } else show = false   // osoba usunięta w międzyczasie: jej przypomnienia już nie dzwonią
                }
                if (show) Reminders.show(context, id, intent.getStringExtra("title") ?: "Asystent leków", body, dateStr, slot, pid, who)
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
                        val keys = (store.profileState(pid) ?: return@Thread).openDoses(date, slot).map { it.key }
                        store.updateProfile(pid) { it.markSlot(date, slot, LocalDate.now(), LocalDateTime.now()) }
                        store.flush()   // zapis od razu: proces może zostać zabity zaraz po odebraniu komunikatu
                        // potwierdzenie z możliwością cofnięcia: jeden dotyk w powiadomieniu nie może po cichu psuć statystyk zgodności
                        if (keys.isNotEmpty()) Reminders.showTaken(context, id, intent.getStringExtra("title")?.removePrefix("Pora na leki: ") ?: "dawki", dateStr!!, keys, pid)
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
                        store.updateProfile(pid) { st ->
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
                if (dateStr != null && slot != null) Reminders.snooze(context, intent.getStringExtra("title") ?: "Asystent leków", intent.getStringExtra("body") ?: "", dateStr, slot, pid, who)
            }
        }
    }
}

/** Ustawia alarmy od nowa na podstawie aktualnych danych wszystkich osób (stan z pamięci aplikacji, a gdy go nie ma, z plików). */
private fun BroadcastReceiver.refresh(context: Context) {
    val pending = goAsync()
    Thread {
        try {
            val store = (context.applicationContext as? pl.asystent.leki.LekiApp)?.store
            Reminders.reschedule(context, if (store != null) Reminders.people(store) else Reminders.peopleFromDisk(context.filesDir))
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
