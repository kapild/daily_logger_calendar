package dev.kapil.healthcal

import android.content.SharedPreferences
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date

/** One line in the Found list: a health record from the last scan, or a place signal. */
private data class FoundRow(
    val key: String,
    val kind: Kind,
    val startMs: Long,
    val endMs: Long?,
    val title: String,
    val detail: String,
    val result: ScanResult?,
)

@Composable
fun FoundTab(resumeTick: Int) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }

    var report by remember { mutableStateOf<ScanReport?>(null) }
    var signals by remember { mutableStateOf(emptyList<Signal>()) }
    var loaded by remember { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf<Kind?>(null) }
    var changeTick by remember { mutableLongStateOf(prefs.lastRunAt + prefs.signalsChangedAt) }
    var runningSince by remember { mutableLongStateOf(prefs.runningSince) }

    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            changeTick = prefs.lastRunAt + prefs.signalsChangedAt
            runningSince = prefs.runningSince
        }
        prefs.sp.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.sp.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    LaunchedEffect(changeTick, resumeTick) {
        val r = withContext(Dispatchers.IO) { ScanReportStore.load(ctx) }
        val now = System.currentTimeMillis()
        val from = r?.fromMs ?: (now - SyncWorker.RECENT_DAYS * 86_400_000L)
        signals = withContext(Dispatchers.IO) { SignalLog.get(ctx).between(from, now) }.reversed()
        report = r
        loaded = true
    }

    val rows = remember(report, signals) {
        val health = report?.items.orEmpty().map {
            FoundRow(
                key = it.tag,
                kind = it.kind,
                startMs = it.startMs,
                endMs = it.endMs,
                title = it.title,
                detail = "From ${it.source}, into ${it.calendar}",
                result = it.result,
            )
        }
        val places = signals.map {
            FoundRow(
                key = "signal:${it.id}",
                kind = Kind.PLACES,
                startMs = it.ts,
                endMs = null,
                title = signalTitle(it),
                detail = "Detected by ${signalVia(it)}",
                result = null,
            )
        }
        (health + places).sortedByDescending { it.startMs }
    }
    val counts = remember(rows) { rows.groupingBy { it.kind }.eachCount() }
    val shown = remember(rows, filter) { if (filter == null) rows else rows.filter { it.kind == filter } }

    val isRunning = runningSince > 0 && System.currentTimeMillis() - runningSince < 20 * 60_000L
    val backfillLabel = prefs.backfillStart.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "intro") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = scanSummary(report, signals.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = { SyncWorker.runNow(ctx, SyncWorker.MODE_BACKFILL) },
                    enabled = !isRunning,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Backfill and repair from $backfillLabel") }
                OutlinedButton(
                    onClick = { SyncWorker.runNow(ctx, SyncWorker.MODE_RECENT) },
                    enabled = !isRunning,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Sync last ${SyncWorker.RECENT_DAYS} days") }
                if (isRunning) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                if (rows.isNotEmpty()) KindFilter(filter, counts) { filter = it }
            }
        }

        if (loaded && rows.isEmpty()) {
            item(key = "empty") {
                Notice(
                    "Nothing found yet. Tap Sync to read your sleep and workouts from Health Connect. " +
                        "Place signals appear here as you come and go.",
                    Tone.NEUTRAL,
                )
            }
        } else if (shown.isEmpty() && filter != null) {
            item(key = "empty-filter") {
                Notice("No ${filter?.label?.lowercase()} in this scan.", Tone.NEUTRAL)
            }
        }

        items(shown, key = { it.key }) { row -> FoundItem(row) }
    }
}

@Composable
private fun FoundItem(row: FoundRow) {
    StripeRow(row.kind) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = row.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            row.result?.let {
                Spacer(Modifier.width(8.dp))
                Pill(it.label, it.tone())
            }
        }
        Text(
            whenText(row.startMs, row.endMs),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            row.detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 1.dp),
        )
    }
}

private fun ScanResult.tone(): Tone = when (this) {
    ScanResult.ADDED, ScanResult.FIXED -> Tone.GOOD
    ScanResult.SAME -> Tone.NEUTRAL
    ScanResult.IGNORED, ScanResult.REMOVED -> Tone.WAIT
    ScanResult.FAILED -> Tone.BAD
}

private fun scanSummary(report: ScanReport?, signalCount: Int): String {
    if (report == null) return "Every sleep session, workout and place signal HealthCal finds shows up here."
    val sleep = report.items.count { it.kind == Kind.SLEEP }
    val workouts = report.items.count { it.kind == Kind.WORKOUT }
    val ran = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(report.ranAt))
    val range = DateFormat.getDateInstance(DateFormat.MEDIUM)
    val from = range.format(Date(report.fromMs))
    val to = range.format(Date(report.toMs))
    return "Last scan $ran looked at $from to $to. Found $sleep sleep " +
        (if (sleep == 1) "session" else "sessions") + ", $workouts " +
        (if (workouts == 1) "workout" else "workouts") + " and $signalCount place " +
        (if (signalCount == 1) "signal." else "signals.")
}
