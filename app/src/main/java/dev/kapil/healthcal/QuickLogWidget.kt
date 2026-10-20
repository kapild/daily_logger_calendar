package dev.kapil.healthcal

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val QL_CARD = Color(0xFF1C1F24)
private val QL_TILE = Color(0xFF2C3038)
private val QL_TEXT = Color(0xFFF5F6F8)
private val QL_MUTED = Color(0xFFA0A6B0)
private val QL_ACCENT = Color(0xFFF2B36B)
private val QL_ON_ACCENT = Color(0xFF141418)

private val KEY_REV = longPreferencesKey("rev")
private val P_TILE = ActionParameters.Key<String>("tile")
private val P_ENTRY = ActionParameters.Key<String>("entry")

/**
 * Quick log on the home screen: tap a tile to log it now. The tile turns
 * amber and ignores taps for two minutes; a line with Edit and Undo shows for
 * 15 seconds.
 */
class QuickLogWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact
    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            currentState<Preferences>()[KEY_REV] // read so a bump recomposes
            QuickLogContent()
        }
    }

    companion object {
        /** Redraw every placed Quick log widget. */
        suspend fun refresh(context: Context) {
            val ids = GlanceAppWidgetManager(context).getGlanceIds(QuickLogWidget::class.java)
            for (id in ids) {
                updateAppWidgetState(context, id) { it[KEY_REV] = System.nanoTime() }
                QuickLogWidget().update(context, id)
            }
        }
    }
}

class QuickLogWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuickLogWidget()
}

class LogTileNow : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val tileId = parameters[P_TILE] ?: return
        QuickLog.logFromWidget(context, tileId)
    }
}

class UndoEntry : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        parameters[P_ENTRY]?.let { QuickLog.delete(context, it) }
    }
}

private fun qs(color: Color, size: Int, bold: Boolean = false, align: TextAlign = TextAlign.Start) = TextStyle(
    color = ColorProvider(color),
    fontSize = size.sp,
    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
    textAlign = align,
)

@Composable
private fun QuickLogContent() {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    val tiles = QuickLog.tiles(context).filter { it.on }.take(9)
    val entries = QuickLog.entries(context)
    val counts = QuickLog.countsOn(entries, LocalDate.now(zone), zone)
    val now = System.currentTimeMillis()
    // The newest entry made in the last few minutes, by tap time (its id starts with the tap time).
    val last = QuickLog.toConfirm(entries, now)
    val size = LocalSize.current
    val showLast = last != null
    val tileHeight = (((size.height.value - 64f - (if (showLast) 44f else 0f)) / 3f) - 6f).coerceIn(48f, 110f).dp

    Column(
        GlanceModifier.fillMaxSize().background(QL_CARD).cornerRadius(20.dp).padding(12.dp),
    ) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Quick log", style = qs(QL_TEXT, 15, bold = true), modifier = GlanceModifier.defaultWeight())
            Text(
                "✏️",
                style = qs(QL_MUTED, 14),
                modifier = GlanceModifier.padding(4.dp).clickable(actionStartActivity(openLogIntent(context, null))),
            )
        }
        Spacer(GlanceModifier.height(6.dp))
        if (tiles.isEmpty()) {
            Text("Turn on some tiles in Daily Logger, Log tab.", style = qs(QL_MUTED, 13))
        }
        tiles.chunked(3).forEach { row ->
            Row(GlanceModifier.fillMaxWidth()) {
                row.forEachIndexed { i, t ->
                    if (i > 0) Spacer(GlanceModifier.width(6.dp))
                    TileButton(t, counts[t.id] ?: 0, tileHeight, QuickLog.coolingDown(entries, t.id, now))
                }
                // Keep tiles the same width in a short last row.
                repeat(3 - row.size) {
                    Spacer(GlanceModifier.width(6.dp))
                    Box(GlanceModifier.defaultWeight().height(tileHeight)) {}
                }
            }
            Spacer(GlanceModifier.height(6.dp))
        }
        if (last != null) LastEntry(last, zone)
    }
}

@Composable
private fun androidx.glance.layout.RowScope.TileButton(
    t: Tile,
    count: Int,
    height: androidx.compose.ui.unit.Dp,
    justLogged: LogEntry?,
) {
    val base = GlanceModifier
        .defaultWeight()
        .height(height)
        .background(if (justLogged != null) QL_ACCENT else QL_TILE)
        .cornerRadius(14.dp)
    // A tile logged in the last two minutes has no tap action, so a second tap does nothing.
    val modifier = if (justLogged != null) base else base.clickable(actionRunCallback<LogTileNow>(actionParametersOf(P_TILE to t.id)))
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically) {
        Text(t.emoji, style = qs(QL_TEXT, if (height.value >= 72f) 24 else 18, align = TextAlign.Center))
        val label = when {
            justLogged != null -> "✓ ${t.label}"
            count > 0 -> "${t.label} · $count"
            else -> t.label
        }
        val color = when {
            justLogged != null -> QL_ON_ACCENT
            count > 0 -> QL_ACCENT
            else -> QL_TEXT
        }
        Text(label, style = qs(color, 12, bold = justLogged != null || count > 0, align = TextAlign.Center), maxLines = 1)
    }
}

@Composable
private fun ColumnScope.LastEntry(e: LogEntry, zone: ZoneId) {
    val context = LocalContext.current
    val time = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()).format(Instant.ofEpochMilli(e.startMs).atZone(zone))
    Row(
        GlanceModifier.fillMaxWidth().background(Color(0xFFF0F2F5)).cornerRadius(12.dp).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${e.emoji} ${e.label} logged · $time", style = qs(Color(0xFF191C22), 13, bold = true), maxLines = 1, modifier = GlanceModifier.defaultWeight())
        Text(
            "EDIT",
            style = qs(Color(0xFF1E6EBE), 12, bold = true),
            modifier = GlanceModifier.padding(horizontal = 6.dp).clickable(actionStartActivity(openLogIntent(context, e.id))),
        )
        Text(
            "UNDO",
            style = qs(Color(0xFF1E6EBE), 12, bold = true),
            modifier = GlanceModifier.padding(start = 6.dp).clickable(actionRunCallback<UndoEntry>(actionParametersOf(P_ENTRY to e.id))),
        )
    }
}

/** Opens the app on the Log tab, editing [entryId] if given. */
fun openLogIntent(context: Context, entryId: String?): Intent =
    Intent(context, MainActivity::class.java)
        .setAction(MainActivity.ACTION_OPEN_LOG + (entryId ?: "")) // distinct per entry so extras aren't reused
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .putExtra(MainActivity.EXTRA_ENTRY, entryId)
