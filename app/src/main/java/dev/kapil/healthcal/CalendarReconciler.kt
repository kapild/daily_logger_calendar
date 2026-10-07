package dev.kapil.healthcal

import android.content.Context

enum class Outcome { ADDED, FIXED, SAME, FAILED, REMOVED }

class ReconcileCounts {
    var added = 0
    var fixed = 0
    var same = 0
    var duplicates = 0
    var removed = 0

    /** Events left out because you removed them in the app. */
    var skipped = 0

    /** What happened to each desired event, by tag. */
    val outcomes = HashMap<String, Outcome>()
}

/**
 * Makes one calendar match a list of desired events, matched by tag:
 *  - missing          -> insert
 *  - different        -> update
 *  - duplicated       -> keep oldest, delete the rest
 *  - removed by you   -> never written, and deleted if still in the calendar
 *  - stale (optional) -> delete our own events whose tag starts with
 *                        [staleTagPrefix] and that are no longer desired
 */
object CalendarReconciler {

    fun apply(
        context: Context,
        calendarId: Long,
        desired: List<DesiredEvent>,
        windowStartMs: Long,
        windowEndMs: Long,
        staleTagPrefix: String? = null,
        staleFromMs: Long = windowStartMs,
    ): ReconcileCounts {
        val counts = ReconcileCounts()
        val existing = CalendarRepo
            .queryTaggedEvents(context, calendarId, windowStartMs, windowEndMs)
            .groupBy { it.tag }
        val blocked = RemovedEvents.tags(context)
        val wanted = ArrayList<DesiredEvent>()
        for (want in desired.distinctBy { it.tag }) {
            if (want.tag !in blocked) {
                wanted += want
                continue
            }
            // You removed this one in the app: keep it out of the calendar.
            counts.skipped++
            counts.outcomes[want.tag] = Outcome.REMOVED
            for (e in existing[want.tag].orEmpty()) {
                if (CalendarRepo.delete(context, e.eventId)) counts.removed++
            }
        }

        for (want in wanted) {
            val matches = existing[want.tag].orEmpty().sortedBy { it.eventId }
            if (matches.isEmpty()) {
                if (CalendarRepo.insert(context, want)) {
                    counts.added++
                    counts.outcomes[want.tag] = Outcome.ADDED
                } else {
                    counts.outcomes[want.tag] = Outcome.FAILED
                }
                continue
            }
            val keep = matches.first()
            for (extra in matches.drop(1)) {
                if (CalendarRepo.delete(context, extra.eventId)) counts.duplicates++
            }
            if (keep.sameAs(want)) {
                counts.same++
                counts.outcomes[want.tag] = Outcome.SAME
            } else if (CalendarRepo.update(context, keep.eventId, want)) {
                counts.fixed++
                counts.outcomes[want.tag] = Outcome.FIXED
            } else {
                counts.outcomes[want.tag] = Outcome.FAILED
            }
        }

        if (staleTagPrefix != null) {
            val wantedTags = wanted.mapTo(HashSet()) { it.tag }
            for ((tag, events) in existing) {
                if (!tag.startsWith(staleTagPrefix) || tag in wantedTags) continue
                for (e in events) {
                    if (e.startMs >= staleFromMs && CalendarRepo.delete(context, e.eventId)) counts.removed++
                }
            }
        }
        return counts
    }

    private fun TaggedEvent.sameAs(d: DesiredEvent): Boolean =
        startMs == d.startMs &&
            endMs == d.endMs &&
            title == d.title &&
            normalize(description) == normalize(d.description)

    private fun normalize(s: String) = s.replace("\r\n", "\n").trim()
}
