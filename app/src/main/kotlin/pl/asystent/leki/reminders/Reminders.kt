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
import pl.asystent.leki.core.reminders
import java.time.LocalDate
import java.time.ZoneId

object Reminders {
    private const val CHANNEL = "reminders"
    private const val PREFS = "reminders"
    private const val KEY_IDS = "ids"
    const val ACTION = "pl.asystent.leki.REMIND"

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

    private fun pending(ctx: Context, id: Int, title: String?, body: String?, create: Boolean): PendingIntent? {
        val i = Intent(ctx, ReminderReceiver::class.java).setAction(ACTION).putExtra("id", id)
        if (title != null) i.putExtra("title", title)
        if (body != null) i.putExtra("body", body)
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
        if (!s.settings.remindOn) {
            prefs.edit().putStringSet(KEY_IDS, emptySet()).apply()
            return
        }
        ensureChannel(ctx)
        val list = s.reminders(ZoneId.systemDefault(), System.currentTimeMillis(), LocalDate.now())
        val exact = canScheduleExact(ctx)
        list.forEach { r ->
            val pi = pending(ctx, r.id, r.title, r.body, true) ?: return@forEach
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.atMillis, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.atMillis, pi)
        }
        prefs.edit().putStringSet(KEY_IDS, list.map { it.id.toString() }.toSet()).apply()
    }

    /** Natychmiastowe powiadomienie próbne (po kilku sekundach). */
    fun scheduleTest(ctx: Context) {
        ensureChannel(ctx)
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = pending(ctx, 2, "Asystent leków", "Tak będzie wyglądać przypomnienie.", true) ?: return
        val at = System.currentTimeMillis() + 8000
        if (canScheduleExact(ctx)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    fun show(ctx: Context, id: Int, title: String, body: String) {
        if (!canNotify(ctx)) return
        ensureChannel(ctx)
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_pill)
            .setColor(0xFF0B6B58.toInt())
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
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
        if (intent.action != Reminders.ACTION) return
        Reminders.show(context, intent.getIntExtra("id", 0), intent.getStringExtra("title") ?: "Asystent leków", intent.getStringExtra("body") ?: "")
    }
}

/** Po restarcie telefonu i po aktualizacji aplikacji alarmy trzeba ustawić od nowa. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val (state, _) = Store.read(context.filesDir)
        Reminders.reschedule(context, state)
    }
}
