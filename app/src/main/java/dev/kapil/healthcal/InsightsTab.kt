package dev.kapil.healthcal

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Chart colours, stepped for light and dark (from a colour-blind-checked palette). */
private class InsightColors(dark: Boolean) {
    val commute = if (dark) Color(0xFF3987E5) else Color(0xFF2A78D6)
    val shuttle = if (dark) Color(0xFFD95926) else Color(0xFFEB6834)
    val office = if (dark) Color(0xFF199E70) else Color(0xFF1BAF7A)
    val grid = if (dark) Color(0xFF34332F) else Color(0xFFECEBE7)
    val heatLow = if (dark) Color(0xFF23262B) else Color(0xFFF1F5FB)
    val heatHigh = if (dark) Color(0xFF8DB8F0) else Color(0xFF14427A)
}

private enum class Range(val label: String, val days: Long, val compareDays: Long) {
    MONTH("4 weeks", 28, 14),
    QUARTER("3 months", 91, 45),
    ALL("All", 400, 30),
}

/**
 * Work and commute insights from Daily Logger's own places events: time at
 * the office, commute each way, when you leave and get home, and how those
 * change week to week or month to month.
 */
@Composable
fun InsightsTab(resumeTick: Int) {
    val ctx = LocalContext.current
    val zone = ZoneId.systemDefault()
    var range by rememberSaveable { mutableStateOf(Range.MONTH) }
    var monthly by rememberSaveable { mutableStateOf(false) }
    var days by remember { mutableStateOf<List<OfficeDay>?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(resumeTick, range) {
        val calendarId = PlacesConfig(ctx).calendarId
        when {
            !hasCalendarPermission(ctx) -> problem = "Allow calendar access in Setup to see insights."
            calendarId < 0 -> problem = "Turn on places in Setup; insights are built from the places calendar."
            else -> {
                problem = null
                val now = System.currentTimeMillis()
                val from = LocalDate.now(zone).minusDays(range.days).atStartOfDay(zone).toInstant().toEpochMilli()
                days = withContext(Dispatchers.IO) {
                    val events = CalendarRepo.queryTaggedEvents(ctx, calendarId, from, now).mapNotNull { e ->
                        val parts = e.tag.split(":")
                        if (parts.size < 3 || parts[0] != "pl") null
                        else PlaceEvent(parts[1], e.title.substringBefore("|").trim(), e.startMs, e.endMs, e.description)
                    }
                    CommuteStats.days(events, zone)
                }
            }
        }
    }

    val colors = InsightColors(isSystemInDarkTheme())
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "range") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Range.entries.forEach { r -> FilterChip(selected = r == range, onClick = { range = r }, label = { Text(r.label) }) }
            }
        }
        problem?.let { p -> item(key = "problem") { Notice(p, Tone.WAIT) } }
        val list = days
        if (problem == null && list == null) item(key = "loading") { Text("Reading the calendar…") }
        if (problem == null && list != null && list.isEmpty()) {
            item(key = "empty") { Notice("No office days in this range yet.", Tone.NEUTRAL) }
        }
        if (problem == null && !list.isNullOrEmpty()) {
            val today = LocalDate.now(zone)
            val (recent, prior) = CommuteStats.compare(list, today, range.compareDays)
            item(key = "summary") { Summary(recent, prior, range.compareDays, list.size) }
            item(key = "timeline") {
                Section("Each office day, door to door") {
                    Legend(colors)
                    DayTimeline(list.takeLast(30).reversed(), colors)
                    Caption("Number at the end = time at the office. A missing bar is a trip the app didn't see.")
                }
            }
            item(key = "trend") {
                Section(if (monthly) "Month by month" else "Week by week") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !monthly, onClick = { monthly = false }, label = { Text("Weeks") })
                        FilterChip(selected = monthly, onClick = { monthly = true }, label = { Text("Months") })
                    }
                    val trend = CommuteStats.trend(list, monthly).takeLast(8)
                    Text("Hours at the office, per day", style = MaterialTheme.typography.labelLarge)
                    Bars(trend, colors.office) { p -> p.officeMin / 60 to "%.1fh".format(p.officeMin / 60) }
                    Text("One-way commute, minutes", style = MaterialTheme.typography.labelLarge)
                    Bars(trend, colors.commute) { p -> (p.commuteMin ?: 0.0) to (p.commuteMin?.let { "%.0f".format(it) } ?: "–") }
                    Caption("Averages per office day; the small number under each bar is how many office days it covers.")
                }
            }
            item(key = "heat") {
                Section("When you leave and get home") {
                    Text("Leave home", style = MaterialTheme.typography.labelLarge)
                    Heatmap(CommuteStats.heat(list, LocalTime.of(6, 30), LocalTime.of(11, 0)) { it.leftHome }, LocalTime.of(6, 30), colors)
                    Text("Back home", style = MaterialTheme.typography.labelLarge)
                    Heatmap(CommuteStats.heat(list, LocalTime.of(16, 30), LocalTime.of(21, 0)) { it.arrivedHome }, LocalTime.of(16, 30), colors)
                    Caption("Office days in half-hour slots; darker = more days.")
                }
            }
        }
    }
}

@Composable
private fun Caption(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun Summary(recent: Averages, prior: Averages, span: Long, total: Int) {
    Section("Last $span days") {
        Caption("${recent.days} office days, compared with the $span days before (${prior.days} office days). $total office days in this range.")
        val tiles = listOf(
            Triple("At the office", recent.officeMin?.let(CommuteStats::duration), CommuteStats.delta(recent.officeMin, prior.officeMin, false)),
            Triple("To work", recent.commuteIn?.let(CommuteStats::duration), CommuteStats.delta(recent.commuteIn, prior.commuteIn, false)),
            Triple("Home", recent.commuteOut?.let(CommuteStats::duration), CommuteStats.delta(recent.commuteOut, prior.commuteOut, false)),
            Triple("Leave home", recent.leftHome?.let(CommuteStats::clock), CommuteStats.delta(recent.leftHome, prior.leftHome, true)),
            Triple("Back home", recent.arrivedHome?.let(CommuteStats::clock), CommuteStats.delta(recent.arrivedHome, prior.arrivedHome, true)),
        )
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (name, value, delta) ->
                    Column(
                        Modifier
                            .weight(1f)
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                            .padding(12.dp),
                    ) {
                        Text(name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(value ?: "–", style = MaterialTheme.typography.titleLarge)
                        Text(delta ?: " ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Legend(colors: InsightColors) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf("Commute" to colors.commute, "Shuttle + car" to colors.shuttle, "Office" to colors.office).forEach { (label, c) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(c, RoundedCornerShape(2.dp)))
                Spacer(Modifier.width(4.dp))
                Text(label, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private val DAY_LABEL: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE MMM d", Locale.getDefault())

/** One row per day on a 6 am – 9 pm scale: commute in, office, commute home. Newest first. */
@Composable
private fun DayTimeline(days: List<OfficeDay>, colors: InsightColors) {
    val measurer = rememberTextMeasurer()
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    val small = TextStyle(fontSize = 10.sp, color = ink)
    val rowH = 22.dp
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(rowH * days.size + 20.dp),
    ) {
        val labelW = 74.dp.toPx()
        val endPad = 44.dp.toPx()
        val x0 = labelW
        val x1 = size.width - endPad
        val startMin = 6 * 60f
        val endMin = 21 * 60f
        fun x(min: Int) = x0 + (x1 - x0) * ((min - startMin) / (endMin - startMin)).coerceIn(0f, 1f)
        val rh = rowH.toPx()
        // Grid every 2 hours
        for (h in 6..20 step 2) {
            val gx = x(h * 60)
            drawLine(colors.grid, Offset(gx, 0f), Offset(gx, rh * days.size), strokeWidth = 1.dp.toPx())
            val label = CommuteStats.clock(h * 60.0).replace(":00", "")
            val m = measurer.measure(label, small)
            drawText(m, topLeft = Offset(gx - m.size.width / 2, rh * days.size + 4.dp.toPx()))
        }
        days.forEachIndexed { i, d ->
            val top = i * rh + rh * 0.2f
            val h = rh * 0.6f
            val name = measurer.measure(DAY_LABEL.format(d.date), small)
            drawText(name, topLeft = Offset(0f, i * rh + (rh - name.size.height) / 2))
            fun seg(a: Int, b: Int, c: Color) {
                val l = x(a)
                val r = x(b)
                if (r - l > 1f) drawRoundRect(c, Offset(l, top), Size(r - l - 1.5f, h), CornerRadius(3.dp.toPx()))
            }
            if (d.leftHome != null && d.commuteIn != null) seg(d.leftHome, d.arriveOffice, if (d.inShuttle) colors.shuttle else colors.commute)
            seg(d.arriveOffice, d.leaveOffice, colors.office)
            var end = d.leaveOffice
            if (d.arrivedHome != null && d.commuteOut != null) {
                seg(d.leaveOffice, d.arrivedHome, if (d.outShuttle) colors.shuttle else colors.commute)
                end = d.arrivedHome
            }
            val t = measurer.measure(CommuteStats.duration(d.officeMin.toDouble()), small)
            drawText(t, topLeft = Offset(x(end) + 4.dp.toPx(), i * rh + (rh - t.size.height) / 2))
        }
    }
}

/** A small bar chart with the value above each bar and the period and day count below. */
@Composable
private fun Bars(periods: List<Period>, color: Color, value: (Period) -> Pair<Double, String>) {
    val measurer = rememberTextMeasurer()
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    val small = TextStyle(fontSize = 10.sp, color = ink)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(140.dp),
    ) {
        if (periods.isEmpty()) return@Canvas
        val values = periods.map(value)
        val max = values.maxOf { it.first }.takeIf { it > 0 } ?: 1.0
        val bottom = size.height - 28.dp.toPx()
        val top = 16.dp.toPx()
        val slot = size.width / periods.size
        val bw = slot * 0.55f
        periods.forEachIndexed { i, p ->
            val (v, label) = values[i]
            val cx = slot * i + slot / 2
            val bh = ((bottom - top) * (v / max)).toFloat()
            drawRoundRect(color, Offset(cx - bw / 2, bottom - bh), Size(bw, bh), CornerRadius(4.dp.toPx()))
            centered(measurer.measure(label, small), cx, bottom - bh - 14.dp.toPx())
            centered(measurer.measure(p.label, small), cx, bottom + 3.dp.toPx())
            centered(measurer.measure("${p.days}d", small), cx, bottom + 15.dp.toPx())
        }
    }
}

private fun DrawScope.centered(m: androidx.compose.ui.text.TextLayoutResult, cx: Float, y: Float) =
    drawText(m, topLeft = Offset(cx - m.size.width / 2, y))

/** Weekday rows (Mon–Fri) by half-hour columns, starting at [from]. */
@Composable
private fun Heatmap(grid: Array<IntArray>, from: LocalTime, colors: InsightColors) {
    val measurer = rememberTextMeasurer()
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    val small = TextStyle(fontSize = 10.sp, color = ink)
    val names = listOf("Mon", "Tue", "Wed", "Thu", "Fri")
    val max = (grid.maxOf { r -> r.maxOrNull() ?: 0 }).coerceAtLeast(2)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height((5 * 24 + 18).dp),
    ) {
        val labelW = 34.dp.toPx()
        val cols = grid[0].size
        val cw = (size.width - labelW) / cols
        val ch = 24.dp.toPx()
        for (r in 0 until 5) {
            val n = measurer.measure(names[r], small)
            drawText(n, topLeft = Offset(0f, r * ch + (ch - n.size.height) / 2))
            for (c in 0 until cols) {
                val v = grid[r][c]
                val fill = lerp(colors.heatLow, colors.heatHigh, v.toFloat() / max)
                drawRoundRect(fill, Offset(labelW + c * cw + 1f, r * ch + 1f), Size(cw - 2f, ch - 2f), CornerRadius(3.dp.toPx()))
                if (v > 0) {
                    val t = measurer.measure(
                        v.toString(),
                        small.copy(color = if (v.toFloat() / max > 0.5f) Color.White else ink),
                    )
                    drawText(t, topLeft = Offset(labelW + c * cw + (cw - t.size.width) / 2, r * ch + (ch - t.size.height) / 2))
                }
            }
        }
        val start = from.hour * 60 + from.minute
        for (c in 0..cols step 2) {
            val label = CommuteStats.clock((start + c * 30).toDouble()).replace(":00", "")
            val t = measurer.measure(label, small)
            drawText(t, topLeft = Offset((labelW + c * cw - t.size.width / 2).coerceIn(0f, size.width - t.size.width), 5 * ch + 3.dp.toPx()))
        }
    }
}
