package dev.kapil.healthcal

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant

/** One lock for every calendar writer, so health and places never race. */
object SyncLock {
    val mutex = Mutex()
}

data class SyncResult(
    val sleepRead: Int,
    val workoutsRead: Int,
    val added: Int,
    val fixed: Int,
    val alreadyThere: Int,
    val duplicatesRemoved: Int,
    val notes: List<String>,
) {
    fun summary(): String = buildString {
        append("Found $sleepRead sleep sessions and $workoutsRead workouts. ")
        append("Added $added, fixed $fixed, $alreadyThere already in calendar")
        if (duplicatesRemoved > 0) append(", removed $duplicatesRemoved duplicates")
        append('.')
        notes.forEach { append("\n").append(it) }
    }
}

/**
 * Health Connect -> calendar. Idempotent: inserts missing events, fixes ones
 * that drifted, removes duplicates. Safe to re-run anytime.
 */
object SyncEngine {

    suspend fun run(context: Context, from: Instant, to: Instant): SyncResult =
        SyncLock.mutex.withLock {
            withContext(Dispatchers.IO) { reconcile(context.applicationContext, from, to) }
        }

    /** One event we'd like in the calendar, plus what the Found tab needs to show it. */
    private class Pending(val event: DesiredEvent, val kind: Kind, val source: String, val ignored: Boolean)

    private suspend fun reconcile(context: Context, from: Instant, to: Instant): SyncResult {
        val prefs = Prefs(context)
        val excluded = prefs.excludedSources
        val seen = prefs.seenSources.toMutableSet()
        val labels = HashMap<String, String>()
        fun label(pkg: String) = labels.getOrPut(pkg) { appLabel(context, pkg) }

        val notes = mutableListOf<String>()
        val pending = mutableListOf<Pending>()
        var sleepRead = 0
        var workoutsRead = 0

        if (prefs.exportSleep) {
            val calendarId = prefs.sleepCalendarId
            if (calendarId < 0) {
                notes += "Sleep skipped: choose a sleep calendar first."
            } else {
                val records = HealthReader.readSleep(context, from, to)
                sleepRead = records.size
                for (r in records) {
                    val pkg = r.metadata.dataOrigin.packageName
                    seen += pkg
                    pending += Pending(EventFormatter.sleep(r, calendarId, label(pkg)), Kind.SLEEP, label(pkg), pkg in excluded)
                }
            }
        }

        if (prefs.exportExercise) {
            val calendarId = prefs.exerciseCalendarId
            if (calendarId < 0) {
                notes += "Workouts skipped: choose a workout calendar first."
            } else {
                val records = HealthReader.readExercise(context, from, to)
                workoutsRead = records.size
                for (r in records) {
                    val pkg = r.metadata.dataOrigin.packageName
                    seen += pkg
                    pending += Pending(EventFormatter.exercise(r, calendarId, label(pkg)), Kind.WORKOUT, label(pkg), pkg in excluded)
                }
            }
        }

        prefs.seenSources = seen
        val desired = pending.filter { !it.ignored }.map { it.event }

        // Look a little wider than the read window so sessions that began just
        // before `from` (e.g. last night's sleep) are still matched.
        val windowStart = from.minus(Duration.ofDays(2)).toEpochMilli()
        val windowEnd = to.plus(Duration.ofDays(1)).toEpochMilli()

        var added = 0
        var fixed = 0
        var same = 0
        var duplicates = 0
        var skipped = 0
        val outcomes = HashMap<String, Outcome>()
        for ((calendarId, events) in desired.groupBy { it.calendarId }) {
            val c = CalendarReconciler.apply(context, calendarId, events, windowStart, windowEnd)
            added += c.added
            fixed += c.fixed
            same += c.same
            duplicates += c.duplicates
            skipped += c.skipped
            outcomes.putAll(c.outcomes)
        }
        if (skipped > 0) notes += "Left out $skipped you removed."

        saveReport(context, from, to, pending, outcomes)
        return SyncResult(sleepRead, workoutsRead, added, fixed, same, duplicates, notes)
    }

    /** Remember every record this scan found, for the Found tab. */
    private fun saveReport(
        context: Context,
        from: Instant,
        to: Instant,
        pending: List<Pending>,
        outcomes: Map<String, Outcome>,
    ) {
        val calendarNames = runCatching {
            CalendarRepo.listWritableCalendars(context).associate { it.id to it.name }
        }.getOrDefault(emptyMap())

        val items = pending.distinctBy { it.event.tag }.map { p ->
            val result = if (p.ignored) {
                ScanResult.IGNORED
            } else {
                when (outcomes[p.event.tag]) {
                    Outcome.ADDED -> ScanResult.ADDED
                    Outcome.FIXED -> ScanResult.FIXED
                    Outcome.SAME -> ScanResult.SAME
                    Outcome.REMOVED -> ScanResult.REMOVED
                    Outcome.FAILED, null -> ScanResult.FAILED
                }
            }
            ScanItem(
                tag = p.event.tag,
                kind = p.kind,
                startMs = p.event.startMs,
                endMs = p.event.endMs,
                title = p.event.title,
                source = p.source,
                calendar = calendarNames[p.event.calendarId] ?: "Calendar #${p.event.calendarId}",
                result = result,
            )
        }.sortedByDescending { it.startMs }

        runCatching {
            ScanReportStore.save(context, ScanReport(System.currentTimeMillis(), from.toEpochMilli(), to.toEpochMilli(), items))
        }
    }
}
