package dev.kapil.healthcal

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
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
import java.time.ZoneId

/** Night palette, matching the mock. */
private object Palette {
    val card = Color(0xFF1C1F24)
    val chip = Color(0xFF2C3038)
    val text = Color(0xFFF5F6F8)
    val muted = Color(0xFFA0A6B0)
    val onAccent = Color(0xFF141418)

    fun accent(m: Metric) = when (m) {
        Metric.SLEEP -> Color(0xFFB7A8FF)
        Metric.WORKOUTS -> Color(0xFF4FD1B5)
        Metric.STEPS -> Color(0xFF6CB4F5)
        Metric.CALORIES -> Color(0xFFF2A65A)
    }
}

private val KEY_METRIC = stringPreferencesKey("metric")
private val KEY_DAYS_AGO = intPreferencesKey("days_ago")
private val KEY_REFRESH = longPreferencesKey("refresh")

private val P_METRIC = ActionParameters.Key<String>("metric")
private val P_DAYS_AGO = ActionParameters.Key<Int>("days_ago")

/**
 * Home-screen widget: the last seven days of sleep, workouts, steps and
 * calories. Small sizes show one metric at a time with tabs; tall sizes show
 * all four as rows. Tap a tab to switch metric and a day to see its values.
 */
class HealthWidget : GlanceAppWidget() {
    // Exact, not Responsive: the bars are sized from the widget's real height.
    override val sizeMode = SizeMode.Exact
    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val initial = WidgetData.get(context)
        provideContent {
            val prefs = currentState<Preferences>()
            val refreshAt = prefs[KEY_REFRESH] ?: 0L
            var week by remember { mutableStateOf(initial) }
            LaunchedEffect(refreshAt) {
                if (refreshAt != 0L) week = WidgetData.get(context, force = true) ?: week
            }
            val metric = prefs[KEY_METRIC]?.let { runCatching { Metric.valueOf(it) }.getOrNull() } ?: Metric.SLEEP
            Content(week, metric, prefs[KEY_DAYS_AGO] ?: 0)
        }
    }

    companion object {
        val SMALL = DpSize(250.dp, 170.dp)
        val TALL = DpSize(250.dp, 290.dp)
    }
}

class HealthWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = HealthWidget()
}

class SelectMetric : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        updateAppWidgetState(context, glanceId) { it[KEY_METRIC] = parameters[P_METRIC] ?: Metric.SLEEP.name }
        HealthWidget().update(context, glanceId)
    }
}

class SelectDay : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        updateAppWidgetState(context, glanceId) { it[KEY_DAYS_AGO] = parameters[P_DAYS_AGO] ?: 0 }
        HealthWidget().update(context, glanceId)
    }
}

class RefreshWidget : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        updateAppWidgetState(context, glanceId) { it[KEY_REFRESH] = System.currentTimeMillis() }
        HealthWidget().update(context, glanceId)
    }
}

/** Re-reads Health Connect and redraws every placed widget. Safe to call when none is placed. */
suspend fun refreshHealthWidgets(context: Context) {
    WidgetData.get(context, force = true)
    HealthWidget().updateAll(context)
}

// ---------------------------------------------------------------- layout

private fun style(color: Color, size: TextUnit, bold: Boolean = false, align: TextAlign = TextAlign.Start) =
    TextStyle(
        color = ColorProvider(color),
        fontSize = size,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        textAlign = align,
    )

@Composable
private fun Content(week: Week?, metric: Metric, daysAgo: Int) {
    val card = GlanceModifier.fillMaxSize().background(Palette.card).cornerRadius(20.dp).padding(14.dp)
    if (week == null) {
        Column(card.clickable(actionStartActivity<MainActivity>()), verticalAlignment = Alignment.CenterVertically) {
            Text("Health this week", style = style(Palette.text, 16.sp, bold = true))
            Spacer(GlanceModifier.height(6.dp))
            Text("Open Daily Logger and allow Health Connect access.", style = style(Palette.muted, 13.sp))
        }
        return
    }
    val day = (week.todayIndex - daysAgo).coerceIn(0, week.todayIndex)
    val height = LocalSize.current.height.value
    val tall = height >= HealthWidget.TALL.height.value
    Column(card) {
        if (tall) Rows(week, day, rowBarHeight(height)) else Tabbed(week, metric, day, tabBarHeight(height))
    }
}

@Composable
private fun ColumnScope.Tabbed(week: Week, metric: Metric, day: Int, barHeight: Dp) {
    val zone = ZoneId.systemDefault()
    val accent = Palette.accent(metric)
    Row(GlanceModifier.fillMaxWidth()) {
        Metric.entries.forEachIndexed { i, m ->
            if (i > 0) Spacer(GlanceModifier.width(4.dp))
            val on = m == metric
            Box(
                GlanceModifier
                    .defaultWeight()
                    .background(if (on) Palette.accent(m) else Palette.chip)
                    .cornerRadius(12.dp)
                    .padding(vertical = 5.dp)
                    .clickable(actionRunCallback<SelectMetric>(actionParametersOf(P_METRIC to m.name))),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    m.label,
                    style = style(if (on) Palette.onAccent else Palette.muted, 11.sp, bold = on, align = TextAlign.Center),
                    maxLines = 1,
                )
            }
        }
    }
    Spacer(GlanceModifier.height(8.dp))
    Text(
        WidgetText.value(metric, week, day),
        style = style(accent, 26.sp, bold = true),
        maxLines = 1,
        modifier = GlanceModifier.clickable(actionStartActivity<MainActivity>()),
    )
    Text(WidgetText.detail(metric, week, day, zone), style = style(Palette.muted, 12.sp), maxLines = 1)
    Spacer(GlanceModifier.defaultWeight())
    Bars(week, metric, day, barHeight = barHeight, letters = true)
    Spacer(GlanceModifier.height(4.dp))
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            WidgetText.summary(metric, week),
            style = style(Palette.text, 12.sp),
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight(),
        )
        RefreshButton()
    }
}

@Composable
private fun ColumnScope.Rows(week: Week, day: Int, barHeight: Dp) {
    val zone = ZoneId.systemDefault()
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("This week", style = style(Palette.text, 15.sp, bold = true), modifier = GlanceModifier.defaultWeight())
        Text(WidgetText.dayName(week, day), style = style(Palette.muted, 12.sp))
        Spacer(GlanceModifier.width(8.dp))
        RefreshButton()
    }
    Metric.entries.forEach { m ->
        Column(GlanceModifier.fillMaxWidth().defaultWeight(), verticalAlignment = Alignment.Bottom) {
        Row(
            GlanceModifier.fillMaxWidth().clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(m.label, style = style(Palette.accent(m), 13.sp, bold = true))
            Spacer(GlanceModifier.width(6.dp))
            Text(
                WidgetText.summary(m, week),
                style = style(Palette.muted, 11.sp),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight(),
            )
            Text(WidgetText.value(m, week, day), style = style(Palette.text, 13.sp, bold = true), maxLines = 1)
        }
        Spacer(GlanceModifier.height(3.dp))
        Bars(week, m, day, barHeight = barHeight, letters = m == Metric.CALORIES)
        }
    }
    // The detail line for sleep is the one people ask about; keep it visible here too.
    Spacer(GlanceModifier.height(4.dp))
    Text(WidgetText.detail(Metric.SLEEP, week, day, zone), style = style(Palette.muted, 11.sp), maxLines = 1)
}

/*
 * Bar heights: whatever height is left after the text. The fixed parts are
 * estimates with some slack, so larger system font sizes still fit.
 */

/** Tabs, big value, detail, day letters and summary take about 160dp. */
private fun tabBarHeight(widgetHeight: Float): Dp = (widgetHeight - 160f).coerceIn(34f, 160f).dp

/** Header, four label lines, day letters and the detail line take about 200dp; the rest is split four ways. */
private fun rowBarHeight(widgetHeight: Float): Dp = ((widgetHeight - 200f) / 4f).coerceIn(22f, 90f).dp

/** Seven bars, oldest first. The selected day is full colour; tapping a bar selects its day. */
@Composable
private fun Bars(week: Week, metric: Metric, selected: Int, barHeight: Dp, letters: Boolean) {
    val accent = Palette.accent(metric)
    val values = week.days.indices.map { WidgetText.barValue(metric, week, it) }
    val max = values.maxOrNull()?.takeIf { it > 0 } ?: 1.0
    Row(GlanceModifier.fillMaxWidth()) {
        week.days.forEachIndexed { i, d ->
            val on = i == selected
            val h = if (values[i] <= 0) 3.dp else (barHeight.value * (values[i] / max)).coerceAtLeast(4.0).toFloat().dp
            Column(
                GlanceModifier
                    .defaultWeight()
                    .padding(horizontal = 2.5.dp)
                    .clickable(actionRunCallback<SelectDay>(actionParametersOf(P_DAYS_AGO to week.todayIndex - i))),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    GlanceModifier.fillMaxWidth().height(barHeight),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Box(
                        GlanceModifier
                            .fillMaxWidth()
                            .height(h)
                            .cornerRadius(4.dp)
                            .background(if (on) accent else accent.copy(alpha = 0.32f)),
                    ) {}
                }
                if (letters) {
                    Spacer(GlanceModifier.height(2.dp))
                    Text(
                        WidgetText.dayLetter(d),
                        style = style(if (on) Palette.text else Palette.muted, 11.sp, bold = on, align = TextAlign.Center),
                        modifier = GlanceModifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun RefreshButton() {
    Box(
        GlanceModifier
            .background(Palette.chip)
            .cornerRadius(10.dp)
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .clickable(actionRunCallback<RefreshWidget>()),
    ) {
        Text("↻", style = style(Palette.muted, 12.sp))
    }
}
