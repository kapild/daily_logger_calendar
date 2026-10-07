package dev.kapil.healthcal

import android.content.Context
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.ZoneId

/** One calendar HealthCal writes to, what it's used for, and how its uploads look. */
private data class CalendarCard(
    val id: Long,
    val status: CalendarStatus?, // null if the calendar is gone from the phone
    val roles: List<String>,     // "sleep", "workouts", "places"
    val total: Int,
    val waiting: Int,
    val uploadKnown: Boolean,
)

private data class Diagnosis(
    val text: String,
    val tone: Tone,
    val canSyncNow: Boolean,
    val fixLabel: String? = null, // shows a button that turns sync + visibility on
)

@Composable
fun EventsTab(resumeTick: Int) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val scope = rememberCoroutineScope()

    var cards by remember { mutableStateOf(emptyList<CalendarCard>()) }
    var events by remember { mutableStateOf(emptyList<OurEvent>()) }
    var removed by remember { mutableStateOf(emptyList<RemovedEvent>()) }
    var askRemove by remember { mutableStateOf<OurEvent?>(null) }
    var loading by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }
    var filter by rememberSaveable { mutableStateOf<Kind?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var runTick by remember { mutableLongStateOf(prefs.lastRunAt) }
    val calGranted = hasCalendarPermission(ctx)

    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "last_run_at" || key == "places_status") runTick = System.currentTimeMillis()
        }
        prefs.sp.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.sp.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    LaunchedEffect(resumeTick, reload, runTick) {
        if (!hasCalendarPermission(ctx)) {
            loading = false
            return@LaunchedEffect
        }
        loading = true
        val (c, e) = withContext(Dispatchers.IO) { loadCalendarState(ctx) }
        removed = withContext(Dispatchers.IO) { RemovedEvents.all(ctx) }.sortedByDescending { it.startMs }
        cards = c
        events = e
        loading = false
    }

    val localIds = remember(cards) { cards.filter { it.status?.isLocal == true }.map { it.id }.toSet() }
    val names = remember(cards) { cards.associate { it.id to (it.status?.name ?: "Missing calendar") } }
    val counts = remember(events) { events.groupingBy { Kind.ofTag(it.tag) }.eachCount() }
    val shown = remember(events, filter) {
        if (filter == null) events else events.filter { Kind.ofTag(it.tag) == filter }
    }

    fun syncNow(status: CalendarStatus) {
        val ok = CalendarRepo.requestSync(status.account, status.accountType)
        message = if (ok) {
            "Asked Android to sync ${status.name}. Checking again in a few seconds."
        } else {
            "Android didn't accept the sync request. Use Sync now in the Google Calendar app instead."
        }
        scope.launch {
            delay(8_000)
            reload++
        }
    }

    fun turnOn(status: CalendarStatus) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) { CalendarRepo.enableSync(ctx, status.id) }
            message = if (ok) {
                "Turned on sync for ${status.name}."
            } else {
                "Couldn't change ${status.name}. Open Google Calendar, Settings, ${status.name}, and turn Sync on."
            }
            if (ok) CalendarRepo.requestSync(status.account, status.accountType)
            delay(1_000)
            reload++
        }
    }

    fun remove(e: OurEvent) {
        scope.launch {
            val deleted = withContext(Dispatchers.IO) {
                // Wait for any running sync so it can't write the event back.
                SyncLock.mutex.withLock {
                    RemovedEvents.add(ctx, RemovedEvent(e.tag, e.title, e.startMs, e.endMs, System.currentTimeMillis()))
                    CalendarRepo.delete(ctx, e.eventId)
                }
            }
            message = if (deleted) {
                "Removed \"${e.title}\". Syncs will leave it out from now on."
            } else {
                "Couldn't delete \"${e.title}\" right now. The next sync will remove it."
            }
            reload++
        }
    }

    fun restore(r: RemovedEvent) {
        scope.launch {
            withContext(Dispatchers.IO) { RemovedEvents.restore(ctx, r.tag) }
            SyncWorker.runNow(ctx, SyncWorker.MODE_BACKFILL)
            message = "Restoring \"${r.title}\". It's back in your calendar when the sync finishes."
            reload++
        }
    }

    askRemove?.let { e ->
        AlertDialog(
            onDismissRequest = { askRemove = null },
            title = { Text("Remove this event?") },
            text = {
                Text(
                    "\"${e.title}\" is deleted from your calendar, and syncs won't add it again. " +
                        "You can restore it from Removed by you, at the bottom of this tab."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    askRemove = null
                    remove(e)
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { askRemove = null }) { Text("Keep") }
            },
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "intro") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "The exact events HealthCal has written, and whether each one has reached Google. " +
                            "Tap Remove on any you don't want synced.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { reload++ }) { Text("Refresh") }
                }
                if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }

        if (!calGranted) {
            item(key = "no-permission") {
                Notice("Grant calendar access in the Setup tab to see events here.", Tone.WAIT)
            }
            return@LazyColumn
        }

        if (!loading && cards.isEmpty()) {
            item(key = "no-calendars") {
                Notice("No calendar chosen yet. Pick one for sleep and workouts in the Setup tab.", Tone.WAIT)
            }
        }

        message?.let { m -> item(key = "message") { Notice(m, Tone.NEUTRAL) } }

        items(cards, key = { "cal:${it.id}" }) { card ->
            CalendarCardView(
                card = card,
                onSyncNow = { card.status?.let { syncNow(it) } },
                onTurnOn = { card.status?.let { turnOn(it) } },
            )
        }

        if (events.isNotEmpty()) {
            item(key = "filter") { KindFilter(filter, counts) { filter = it } }
        } else if (!loading && cards.isNotEmpty()) {
            item(key = "no-events") {
                Notice("No HealthCal events in these calendars yet. Run a sync from the Found tab.", Tone.NEUTRAL)
            }
        }

        items(shown, key = { "ev:${it.eventId}" }) { e ->
            EventItem(
                event = e,
                calendarName = if (cards.size > 1) names[e.calendarId] else null,
                isLocal = e.calendarId in localIds,
                onRemove = { askRemove = e },
            )
        }

        if (removed.isNotEmpty()) {
            item(key = "removed-header") {
                Column(
                    Modifier.padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        "Removed by you (${removed.size})",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "Syncs leave these out of your calendar. Restore one to sync it again.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(removed, key = { "rm:${it.tag}" }) { r ->
                RemovedItem(r, onRestore = { restore(r) })
            }
        }
    }
}

@Composable
private fun CalendarCardView(card: CalendarCard, onSyncNow: () -> Unit, onTurnOn: () -> Unit) {
    val status = card.status
    Section(status?.name ?: "Missing calendar") {
        if (status != null) {
            Text(
                "${status.account.ifBlank { "On this phone only" }}. Holds ${card.roles.joinToNaturalList()}.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val d = diagnose(card)
        Notice(d.text, d.tone)
        if (d.fixLabel != null || d.canSyncNow) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                d.fixLabel?.let { label -> Button(onClick = onTurnOn) { Text(label) } }
                if (d.canSyncNow) OutlinedButton(onClick = onSyncNow) { Text("Sync now") }
            }
        }
    }
}

@Composable
private fun EventItem(event: OurEvent, calendarName: String?, isLocal: Boolean, onRemove: () -> Unit) {
    StripeRow(Kind.ofTag(event.tag)) {
        Text(
            event.title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            whenText(event.startMs, event.endMs),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                calendarName ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            when {
                isLocal -> Pill("Phone only", Tone.BAD)
                event.upload == Upload.ON_SERVER -> Pill("On Google", Tone.GOOD)
                event.upload == Upload.WAITING -> Pill("Waiting to upload", Tone.WAIT)
                else -> Unit
            }
            TextButton(onClick = onRemove) { Text("Remove") }
        }
    }
}

@Composable
private fun RemovedItem(event: RemovedEvent, onRestore: () -> Unit) {
    StripeRow(Kind.ofTag(event.tag)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    event.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    whenText(event.startMs, event.endMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onRestore) { Text("Restore") }
        }
    }
}

private fun diagnose(c: CalendarCard): Diagnosis {
    val s = c.status ?: return Diagnosis(
        "This calendar is no longer on the phone. Choose another one in Setup.",
        Tone.BAD,
        canSyncNow = false,
    )
    if (s.isLocal) return Diagnosis(
        "This calendar lives only on the phone, so nothing in it reaches Google Calendar. " +
            "Choose a Google calendar in Setup, then run Backfill.",
        Tone.BAD,
        canSyncNow = false,
    )
    if (!s.syncEvents) return Diagnosis(
        "Sync is off for this calendar on this phone, so Google never receives these events.",
        Tone.BAD,
        canSyncNow = false,
        fixLabel = "Turn on sync",
    )
    if (!s.visible) return Diagnosis(
        "This calendar is hidden in your calendar apps, so its events don't show even when they sync.",
        Tone.WAIT,
        canSyncNow = true,
        fixLabel = "Show it",
    )
    if (s.masterSyncOn == false) return Diagnosis(
        "Auto-sync is off on this phone, so uploads only happen when you ask. Tap Sync now, or turn on " +
            "Automatically sync app data in Settings, Passwords & accounts.",
        Tone.WAIT,
        canSyncNow = true,
    )
    if (s.accountSyncOn == false) return Diagnosis(
        "Calendar sync is off for ${s.account}. Tap Sync now, or turn it on in Settings, " +
            "Passwords & accounts, ${s.account}, Account sync, Calendar.",
        Tone.WAIT,
        canSyncNow = true,
    )
    if (c.total == 0) return Diagnosis("No HealthCal events here yet.", Tone.NEUTRAL, canSyncNow = true)
    if (!c.uploadKnown) return Diagnosis(
        "${c.total} events are in this calendar on the phone. This phone doesn't report upload status.",
        Tone.NEUTRAL,
        canSyncNow = true,
    )
    if (c.waiting > 0) return Diagnosis(
        "${c.waiting} of ${c.total} events are still waiting to upload to Google. Tap Sync now to push them.",
        Tone.WAIT,
        canSyncNow = true,
    )
    return Diagnosis("All ${c.total} events are on Google Calendar.", Tone.GOOD, canSyncNow = true)
}

private fun loadCalendarState(ctx: Context): Pair<List<CalendarCard>, List<OurEvent>> {
    val prefs = Prefs(ctx)
    val places = PlacesConfig(ctx)

    val roles = LinkedHashMap<Long, MutableList<String>>()
    if (prefs.exportSleep && prefs.sleepCalendarId >= 0) {
        roles.getOrPut(prefs.sleepCalendarId) { mutableListOf() } += "sleep"
    }
    if (prefs.exportExercise && prefs.exerciseCalendarId >= 0) {
        roles.getOrPut(prefs.exerciseCalendarId) { mutableListOf() } += "workouts"
    }
    if (places.enabled && places.calendarId >= 0) {
        roles.getOrPut(places.calendarId) { mutableListOf() } += "places"
    }

    val from = prefs.backfillStart.minusDays(2).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val to = System.currentTimeMillis() + 86_400_000L
    val events = CalendarRepo.listOurEvents(ctx, roles.keys, from, to)

    val cards = roles.map { (id, r) ->
        val mine = events.filter { it.calendarId == id }
        CalendarCard(
            id = id,
            status = runCatching { CalendarRepo.calendarStatus(ctx, id) }.getOrNull(),
            roles = r,
            total = mine.size,
            waiting = mine.count { it.upload == Upload.WAITING },
            uploadKnown = mine.any { it.upload != Upload.UNKNOWN },
        )
    }
    return cards to events
}

private fun List<String>.joinToNaturalList(): String = when (size) {
    0 -> "nothing yet"
    1 -> first()
    2 -> "${this[0]} and ${this[1]}"
    else -> dropLast(1).joinToString(", ") + " and " + last()
}
