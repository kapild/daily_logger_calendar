package dev.kapil.healthcal

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.Locale

private const val TEXT = 0xFFF5F6F8.toInt()
private const val MUTED = 0xFFA0A6B0.toInt()
private const val ACCENT = 0xFFF2B36B.toInt()

private val TILE_IDS = intArrayOf(
    R.id.ql_tile_0, R.id.ql_tile_1, R.id.ql_tile_2, R.id.ql_tile_3, R.id.ql_tile_4,
    R.id.ql_tile_5, R.id.ql_tile_6, R.id.ql_tile_7, R.id.ql_tile_8,
)
private val EMOJI_IDS = intArrayOf(
    R.id.ql_emoji_0, R.id.ql_emoji_1, R.id.ql_emoji_2, R.id.ql_emoji_3, R.id.ql_emoji_4,
    R.id.ql_emoji_5, R.id.ql_emoji_6, R.id.ql_emoji_7, R.id.ql_emoji_8,
)
private val LABEL_IDS = intArrayOf(
    R.id.ql_label_0, R.id.ql_label_1, R.id.ql_label_2, R.id.ql_label_3, R.id.ql_label_4,
    R.id.ql_label_5, R.id.ql_label_6, R.id.ql_label_7, R.id.ql_label_8,
)
private val ROW_IDS = intArrayOf(R.id.ql_row_0, R.id.ql_row_1, R.id.ql_row_2)

/**
 * Quick log on the home screen. Built from plain views rather than Glance so
 * a tap is drawn straight away: the tile greys out and a "Logging Coffee…"
 * line appears before anything is saved. The tile stays grey, ignoring taps,
 * for two minutes; the line becomes "Coffee logged" with Edit and Undo and
 * goes away after 15 seconds.
 */
object QuickLogWidget {
    private const val ACTION_TAP = "dev.kapil.healthcal.QUICK_LOG_TAP"
    private const val ACTION_UNDO = "dev.kapil.healthcal.QUICK_LOG_UNDO"
    private const val EXTRA_TILE = "tile"
    private const val EXTRA_ENTRY = "entry"

    /** Tiles tapped whose entry isn't saved yet; drawn grey with a "Logging…" line. */
    private val logging: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Redraw every placed Quick log widget from what is saved. */
    fun refresh(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, QuickLogWidgetReceiver::class.java))
        if (ids.isEmpty()) return
        manager.updateAppWidget(ids, render(context))
    }

    internal fun onReceive(context: Context, intent: Intent, pending: () -> (() -> Unit)) {
        when (intent.action) {
            ACTION_TAP -> {
                val tileId = intent.getStringExtra(EXTRA_TILE) ?: return
                val now = System.currentTimeMillis()
                if (tileId in logging || QuickLog.coolingDown(QuickLog.entries(context), tileId, now) != null) return
                // Show the tap before doing any work.
                logging += tileId
                refresh(context)
                val finish = pending()
                scope.launch {
                    try {
                        QuickLog.logFromWidget(context, tileId)
                    } finally {
                        logging -= tileId
                        refresh(context)
                        finish()
                    }
                }
            }
            ACTION_UNDO -> {
                val id = intent.getStringExtra(EXTRA_ENTRY) ?: return
                val finish = pending()
                scope.launch {
                    try {
                        QuickLog.delete(context, id)
                    } finally {
                        finish()
                    }
                }
            }
        }
    }

    private fun render(context: Context): RemoteViews {
        val v = RemoteViews(context.packageName, R.layout.quick_log_widget)
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val tiles = QuickLog.tiles(context).filter { it.on }.take(TILE_IDS.size)
        val entries = QuickLog.entries(context)
        val counts = QuickLog.countsOn(entries, LocalDate.now(zone), zone)
        val busy = synchronized(logging) { logging.toSet() }

        for (i in TILE_IDS.indices) {
            val t = tiles.getOrNull(i)
            if (t == null) {
                v.setViewVisibility(TILE_IDS[i], View.INVISIBLE)
                continue
            }
            v.setViewVisibility(TILE_IDS[i], View.VISIBLE)
            val count = counts[t.id] ?: 0
            val grey = t.id in busy || QuickLog.coolingDown(entries, t.id, now) != null
            v.setTextViewText(EMOJI_IDS[i], t.emoji)
            v.setTextViewText(
                LABEL_IDS[i],
                when {
                    grey && count > 0 -> "✓ ${t.label} · $count"
                    grey -> "✓ ${t.label}"
                    count > 0 -> "${t.label} · $count"
                    else -> t.label
                },
            )
            v.setTextColor(LABEL_IDS[i], if (grey) MUTED else if (count > 0) ACCENT else TEXT)
            v.setInt(TILE_IDS[i], "setBackgroundResource", if (grey) R.drawable.ql_tile_done else R.drawable.ql_tile)
            v.setOnClickPendingIntent(TILE_IDS[i], broadcast(context, ACTION_TAP, EXTRA_TILE, t.id, i))
        }
        // Drop a whole row when it has no tiles.
        for (r in ROW_IDS.indices) {
            v.setViewVisibility(ROW_IDS[r], if (tiles.size > r * 3) View.VISIBLE else View.GONE)
        }

        v.setOnClickPendingIntent(R.id.ql_open, activity(context, null, 100))

        val pendingTile = busy.lastOrNull()?.let { id -> tiles.firstOrNull { it.id == id } }
        val last = QuickLog.toConfirm(entries, now)
        when {
            pendingTile != null -> {
                v.setViewVisibility(R.id.ql_line, View.VISIBLE)
                v.setTextViewText(R.id.ql_line_text, "${pendingTile.emoji} Logging ${pendingTile.label}…")
                v.setViewVisibility(R.id.ql_line_edit, View.GONE)
                v.setViewVisibility(R.id.ql_line_undo, View.GONE)
            }
            last != null -> {
                val time = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
                    .format(Instant.ofEpochMilli(last.startMs).atZone(zone))
                v.setViewVisibility(R.id.ql_line, View.VISIBLE)
                v.setTextViewText(R.id.ql_line_text, "${last.emoji} ${last.label} logged · $time")
                v.setViewVisibility(R.id.ql_line_edit, View.VISIBLE)
                v.setViewVisibility(R.id.ql_line_undo, View.VISIBLE)
                v.setOnClickPendingIntent(R.id.ql_line_edit, activity(context, last.id, 101))
                v.setOnClickPendingIntent(R.id.ql_line_undo, broadcast(context, ACTION_UNDO, EXTRA_ENTRY, last.id, 102))
            }
            else -> v.setViewVisibility(R.id.ql_line, View.GONE)
        }
        return v
    }

    private fun broadcast(context: Context, action: String, key: String, value: String, code: Int): PendingIntent {
        val intent = Intent(context, QuickLogWidgetReceiver::class.java)
            .setAction(action)
            .setData(Uri.parse("quicklog://$action/$value")) // one PendingIntent per tile or entry
            .putExtra(key, value)
        return PendingIntent.getBroadcast(context, code, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun activity(context: Context, entryId: String?, code: Int): PendingIntent =
        PendingIntent.getActivity(
            context, code, openLogIntent(context, entryId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}

class QuickLogWidgetReceiver : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        QuickLogWidget.refresh(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        QuickLogWidget.onReceive(context.applicationContext, intent, ::keepAlive)
    }

    /** Lets the work started by a tap finish after onReceive returns. */
    private fun keepAlive(): () -> Unit {
        val result = goAsync()
        return { result.finish() }
    }
}

/** Opens the app on the Log tab, editing [entryId] if given. */
fun openLogIntent(context: Context, entryId: String?): Intent =
    Intent(context, MainActivity::class.java)
        .setAction(MainActivity.ACTION_OPEN_LOG + (entryId ?: "")) // distinct per entry so extras aren't reused
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .putExtra(MainActivity.EXTRA_ENTRY, entryId)
