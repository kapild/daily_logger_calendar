package dev.kapil.healthcal

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import android.view.View
import android.widget.RemoteViews
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The Next Up home-screen widget: the last two events and how long ago they
 * ended, a "now" line, and the next two with a countdown. It reads every
 * visible calendar on the phone (the same list sent to the watch), redraws
 * every minute while the phone is awake, and at once when the calendar changes.
 */
object NextUpBoardWidget {
    private const val ACTION_TICK = "dev.kapil.healthcal.NEXT_UP_TICK"
    private const val DAY = 86_400_000L
    private const val RED = 0xFFDE3C3C.toInt()
    private const val ORANGE = 0xFFE1781E.toInt()

    private val DONE = arrayOf(
        Ids(R.id.nu_done_0, R.id.nu_done_0_title, R.id.nu_done_0_rel, 0, R.id.nu_done_0_bar, 0),
        Ids(R.id.nu_done_1, R.id.nu_done_1_title, R.id.nu_done_1_rel, 0, R.id.nu_done_1_bar, 0),
    )
    private val NEXT = arrayOf(
        Ids(R.id.nu_next_0, R.id.nu_next_0_title, R.id.nu_next_0_rel, R.id.nu_next_0_times, R.id.nu_next_0_bar, R.id.nu_next_0_bar_now),
        Ids(R.id.nu_next_1, R.id.nu_next_1_title, R.id.nu_next_1_rel, R.id.nu_next_1_times, R.id.nu_next_1_bar, R.id.nu_next_1_bar_now),
    )

    private class Ids(val tile: Int, val title: Int, val rel: Int, val times: Int, val bar: Int, val barNow: Int)

    private fun ids(context: Context): IntArray =
        AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, NextUpBoardReceiver::class.java))

    /** Redraw every placed Next Up widget. Cheap; safe to call when none is placed. */
    fun refresh(context: Context) {
        val ids = ids(context)
        if (ids.isEmpty()) return
        AppWidgetManager.getInstance(context).updateAppWidget(ids, render(context))
    }

    /**
     * Redraw at the start of every minute. The alarm doesn't wake the phone,
     * so while the screen is off it waits, and the widget catches up when the
     * phone wakes.
     */
    fun arm(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val now = System.currentTimeMillis()
        am.setRepeating(AlarmManager.RTC, now - now % 60_000 + 60_000, 60_000, tick(context))
    }

    fun disarm(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(tick(context))
    }

    internal fun isTick(intent: Intent) = intent.action == ACTION_TICK

    private fun tick(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, NextUpBoardReceiver::class.java).setAction(ACTION_TICK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun render(context: Context): RemoteViews {
        val v = RemoteViews(context.packageName, R.layout.next_up_widget)
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        v.setTextViewText(
            R.id.nu_now,
            "Now " + DateTimeFormatter.ofPattern("h:mm", Locale.getDefault()).format(Instant.ofEpochMilli(now).atZone(zone)),
        )

        if (!hasCalendarPermission(context)) {
            hideAll(v)
            v.setViewVisibility(R.id.nu_empty, View.VISIBLE)
            v.setTextViewText(R.id.nu_empty, "Open Daily Logger and allow calendar access.")
            v.setOnClickPendingIntent(R.id.nu_empty, openApp(context))
            return v
        }

        val events = runCatching { WatchFeed.readEvents(context, now - 2 * DAY, now + 7 * DAY) }.getOrDefault(emptyList())
        val board = NextUpBoard.build(events, now, zone)

        v.setViewVisibility(R.id.nu_done_row, if (board.done.isEmpty()) View.GONE else View.VISIBLE)
        DONE.forEachIndexed { i, ids ->
            val tile = board.done.getOrNull(i)
            if (tile == null) {
                v.setViewVisibility(ids.tile, View.INVISIBLE)
                return@forEachIndexed
            }
            v.setViewVisibility(ids.tile, View.VISIBLE)
            v.setTextViewText(ids.title, tile.event.title)
            v.setTextViewText(ids.rel, tile.relative)
            v.setProgressBar(ids.bar, 100, tile.bar, false)
            v.setOnClickPendingIntent(ids.tile, openEvent(context, tile.event, 10 + i))
        }

        NEXT.forEachIndexed { i, ids ->
            val tile = board.next.getOrNull(i)
            if (tile == null) {
                v.setViewVisibility(ids.tile, View.GONE)
                return@forEachIndexed
            }
            val running = tile.state == BoardTile.State.NOW
            v.setViewVisibility(ids.tile, View.VISIBLE)
            v.setInt(ids.tile, "setBackgroundResource", if (running) R.drawable.nu_tile_now else R.drawable.nu_tile_next)
            v.setTextViewText(ids.title, tile.event.title)
            v.setTextViewText(ids.rel, tile.relative)
            v.setTextColor(ids.rel, if (running) ORANGE else RED)
            v.setTextViewText(ids.times, tile.times)
            v.setViewVisibility(ids.bar, if (running) View.GONE else View.VISIBLE)
            v.setViewVisibility(ids.barNow, if (running) View.VISIBLE else View.GONE)
            v.setProgressBar(if (running) ids.barNow else ids.bar, 100, tile.bar, false)
            v.setOnClickPendingIntent(ids.tile, openEvent(context, tile.event, 20 + i))
        }

        if (board.next.isEmpty()) {
            v.setViewVisibility(R.id.nu_empty, View.VISIBLE)
            v.setTextViewText(R.id.nu_empty, "Nothing else in the next 7 days.")
        } else {
            v.setViewVisibility(R.id.nu_empty, View.GONE)
        }
        return v
    }

    private fun hideAll(v: RemoteViews) {
        v.setViewVisibility(R.id.nu_done_row, View.GONE)
        NEXT.forEach { v.setViewVisibility(it.tile, View.GONE) }
    }

    private fun openEvent(context: Context, e: FeedEvent, code: Int): PendingIntent {
        val intent = if (e.eventId > 0) {
            Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, e.eventId))
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, e.begin)
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, e.end)
        } else {
            // No id: open the calendar at that time.
            Intent(Intent.ACTION_VIEW, CalendarContract.CONTENT_URI.buildUpon().appendPath("time").appendPath(e.begin.toString()).build())
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(context, code, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 30,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

class NextUpBoardReceiver : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        NextUpBoardWidget.refresh(context)
        NextUpBoardWidget.arm(context)
        CalendarWatchJob.ensureScheduled(context) // redraws on calendar changes
    }

    override fun onEnabled(context: Context) = NextUpBoardWidget.arm(context)

    override fun onDisabled(context: Context) = NextUpBoardWidget.disarm(context)

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (NextUpBoardWidget.isTick(intent)) NextUpBoardWidget.refresh(context)
    }
}
