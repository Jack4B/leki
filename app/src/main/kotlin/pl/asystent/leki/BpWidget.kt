package pl.asystent.leki

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import pl.asystent.leki.core.AppState
import pl.asystent.leki.core.hhmm
import pl.asystent.leki.core.plDate
import java.time.LocalDate

/**
 * Widżet na ekran główny: jedno dotknięcie otwiera ekran dodawania pomiaru ciśnienia. Pokazuje tylko, kiedy był ostatni pomiar
 * (bez wartości), bo ekran główny widzą też inni. Odświeża się po każdym zapisie danych w aplikacji.
 */
class BpWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val s = runCatching { (context.applicationContext as? LekiApp)?.store?.state?.value ?: Store.read(context.filesDir).first }.getOrNull()
        ids.forEach { manager.updateAppWidget(it, views(context, s)) }
    }

    companion object {
        fun lastText(s: AppState?, today: LocalDate): String {
            val last = s?.bp?.maxWithOrNull(compareBy({ it.date }, { it.time })) ?: return "Brak pomiarów"
            val day = when (last.date) { today -> "dziś"; today.minusDays(1) -> "wczoraj"; else -> plDate(last.date) }
            return "Ostatni pomiar: $day ${last.time.hhmm()}"
        }

        private fun views(ctx: Context, s: AppState?): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_bp)
            v.setTextViewText(R.id.widget_last, lastText(s, LocalDate.now()))
            val open = PendingIntent.getActivity(
                ctx, 7,
                Intent(ctx, MainActivity::class.java).setAction(MainActivity.ACTION_ADD_BP).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            v.setOnClickPendingIntent(R.id.widget_root, open)
            return v
        }

        /** Odświeża wszystkie widżety po zmianie danych. */
        fun refresh(ctx: Context, s: AppState) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, BpWidget::class.java))
            ids.forEach { mgr.updateAppWidget(it, views(ctx, s)) }
        }
    }
}
