package dev.kapil.healthcal

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Builds calendar events from the signal log:
 *  - "Left home | At: 8:02 am", "Arrived office | At: 9:01 am", ...
 *  - "At work | Dur: 8 hours 39 mins | Start: 9:01 am End: 5:40 pm", and "Gym | ..."
 *  - "Commute to work by shuttle | Dur: 52 mins | Start: ... End: ..."
 *  - "Drive | Dur: 23 mins | Start: ... End: ..." for car trips that aren't part of a commute
 *
 * [PlaceTimeline] decides the order of the day; this file only turns it into
 * events. Every event is tagged with the id of the signal that created it, so
 * the same day always produces the same events and re-runs are safe.
 */
object PlacesEngine {
    const val TAG_ROOT = "pl:"

    private const val MIN = 60_000L
    private const val DAY_MS = 24 * 60 * MIN
    private const val MARKER_MS = 1 * MIN          // length of "Arrived ..." / "Left ..." events

    suspend fun run(context: Context, from: Instant, to: Instant): String =
        SyncLock.mutex.withLock {
            withContext(Dispatchers.IO) { runLocked(context.applicationContext, from, to) }
        }

    private fun runLocked(context: Context, from: Instant, to: Instant): String {
        val config = PlacesConfig(context)
        if (!config.enabled) return "Places and drives are off."
        val calendarId = config.calendarId
        if (calendarId < 0) return "Places skipped: choose a calendar for places and drives."

        val now = to.toEpochMilli()
        // Read a few extra days so stays that began before the window are understood.
        val signalsFrom = from.minus(Duration.ofDays(3)).toEpochMilli()
        val shuttleNames = config.shuttleSsids
        val signals = SignalLog.get(context).between(signalsFrom, now).map { s ->
            // A network listed under Shuttle is the shuttle, even if it was logged as a place.
            if (s.type == SignalType.ARRIVE && s.source == "wifi" && s.detail in shuttleNames) {
                s.copy(type = SignalType.SHUTTLE, place = null)
            } else {
                s
            }
        }
        val geofenced = Place.entries.filter { config.location(it) != null }.toSet()
        val desired = build(PlaceTimeline.build(signals, geofenced), now, calendarId)

        val c = CalendarReconciler.apply(
            context = context,
            calendarId = calendarId,
            desired = desired,
            windowStartMs = signalsFrom - DAY_MS,
            windowEndMs = now + DAY_MS,
            staleTagPrefix = TAG_ROOT,
            staleFromMs = from.toEpochMilli(),
        )
        return "Places: ${signals.size} signals. Added ${c.added}, fixed ${c.fixed}, " +
            "removed ${c.removed + c.duplicates}, ${c.same} unchanged."
    }

    private fun build(t: Timeline, now: Long, calendarId: Long): List<DesiredEvent> {
        val out = ArrayList<DesiredEvent>()

        for ((index, v) in t.visits.withIndex()) {
            val place = v.place
            val name = place.label.lowercase()
            if (place.markers && v.startKnown) {
                out += marker(
                    calendarId, "Arrived $name", v.start,
                    how(v.startSource, v.startDetail), "${TAG_ROOT}arrive:${v.startId}",
                )
            }

            val end = v.end ?: continue // still there
            // A fresh exit might be undone by a quick return; wait before writing it.
            val isLatest = index == t.visits.lastIndex
            if (isLatest && now - end <= PlaceTimeline.FLAP_MS) continue

            if (place.markers && v.endKnown) {
                out += marker(
                    calendarId, "Left $name", end,
                    how(v.endSource, v.endDetail), "${TAG_ROOT}leave:${v.endId}",
                )
            }
            val spanTitle = place.spanTitle
            if (spanTitle != null && v.startKnown && end - v.start >= PlaceTimeline.MIN_STAY_MS) {
                out += span(calendarId, spanTitle, v, end)
            }
        }

        for (trip in t.trips) {
            val legs = t.drives.filter { it.start < trip.end && it.end > trip.start }
            val driveMs = legs.sumOf { minOf(it.end, trip.end) - maxOf(it.start, trip.start) }
            val mostlyCar = driveMs * 10 >= (trip.end - trip.start) * 6
            val byShuttle = t.shuttleTimes.any { it in trip.start..trip.end }
            val mode = when {
                byShuttle && legs.isNotEmpty() -> "shuttle and car"
                byShuttle -> "shuttle"
                mostlyCar -> "car"
                else -> null // a short drive at one end doesn't make it a car commute
            }
            out += trip(calendarId, trip, mode, legs)
        }
        for (d in t.drives) {
            val partOfTrip = t.trips.any { d.start < it.end && d.end > it.start }
            if (!partOfTrip) out += drive(calendarId, d)
        }
        return out
    }

    private fun marker(cal: Long, title: String, ts: Long, how: String, tag: String) = DesiredEvent(
        tag = tag,
        calendarId = cal,
        startMs = ts,
        endMs = ts + MARKER_MS,
        title = EventFormatter.markerTitle(title, Instant.ofEpochMilli(ts)),
        description = "$title at ${clock(ts)}\nDetected by: $how\n\n${EventFormatter.TAG_PREFIX}$tag]",
    )

    private fun span(cal: Long, title: String, v: Visit, endTs: Long): DesiredEvent {
        val tag = "${TAG_ROOT}stay:${v.startId}"
        val length = Duration.ofMillis(endTs - v.start)
        val ending = if (v.endKnown) {
            "left ${clock(endTs)}"
        } else {
            "exit not detected, so this ends when you arrived at ${v.endDetail.lowercase()} (${clock(endTs)})"
        }
        return DesiredEvent(
            tag = tag,
            calendarId = cal,
            startMs = v.start,
            endMs = endTs,
            title = EventFormatter.spanTitle(title, length, Instant.ofEpochMilli(v.start), Instant.ofEpochMilli(endTs)),
            description = "Arrived ${clock(v.start)}, $ending\n\n${EventFormatter.TAG_PREFIX}$tag]",
        )
    }

    private fun trip(cal: Long, t: Trip, mode: String?, legs: List<Drive>): DesiredEvent {
        val tag = "${TAG_ROOT}trip:${t.id}"
        val base = when {
            t.from == Place.HOME && t.to == Place.OFFICE -> "Commute to work"
            t.from == Place.OFFICE && t.to == Place.HOME -> "Commute home"
            else -> "${t.from.label} to ${t.to.label.lowercase()}"
        }
        val by = if (mode == null) "" else " by $mode"
        val length = Duration.ofMillis(t.end - t.start)
        val driveLines = legs.joinToString("") { d ->
            "\nDrive: ${clock(d.start)} – ${clock(d.end)} (${EventFormatter.dur(Duration.ofMillis(d.end - d.start))}, ${d.device})"
        }
        return DesiredEvent(
            tag = tag,
            calendarId = cal,
            startMs = t.start,
            endMs = t.end,
            title = EventFormatter.spanTitle("$base$by", length, Instant.ofEpochMilli(t.start), Instant.ofEpochMilli(t.end)),
            description = "Left ${t.from.label.lowercase()} ${clock(t.start)}, " +
                "arrived ${t.to.label.lowercase()} ${clock(t.end)}$driveLines\n\n${EventFormatter.TAG_PREFIX}$tag]",
        )
    }

    private fun drive(cal: Long, d: Drive): DesiredEvent {
        val tag = "${TAG_ROOT}drive:${d.id}"
        val length = Duration.ofMillis(d.end - d.start)
        return DesiredEvent(
            tag = tag,
            calendarId = cal,
            startMs = d.start,
            endMs = d.end,
            title = EventFormatter.spanTitle("Drive", length, Instant.ofEpochMilli(d.start), Instant.ofEpochMilli(d.end)),
            description = "Car connected ${clock(d.start)}, disconnected ${clock(d.end)}\n" +
                "Car: ${d.device}\n\n${EventFormatter.TAG_PREFIX}$tag]",
        )
    }

    private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())

    private fun clock(ms: Long): String = CLOCK.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

    private fun how(source: String, detail: String): String = when (source) {
        "location" -> "location"
        "wifi" -> "Wi-Fi \"$detail\""
        "car" -> "car Bluetooth connecting ($detail)"
        "shuttle" -> "shuttle Wi-Fi \"$detail\""
        "next-arrival" -> "arriving at ${detail.lowercase()}"
        else -> source
    }
}
