package dev.kapil.healthcal

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Washer and dryer cycles, from LG ThinQ notifications.
 *
 * ThinQ posts "Washing started." when a cycle begins (sometimes several
 * times), "Washing done" and "The cycle has finished..." when it ends, and
 * "There is a problem with the washer..." on errors. [classify] turns one
 * notification into a signal; [cycles] pairs starts with ends.
 */
object Laundry {
    const val SOURCE = "thinq"

    /** Packages whose notifications are read. ThinQ is com.lgeha.nuts. */
    fun isThinQ(packageName: String): Boolean = packageName.startsWith("com.lgeha")

    enum class Kind { START, END, PROBLEM }

    data class Parsed(val kind: Kind, val appliance: String)

    /** What a ThinQ notification means, or null if it isn't about a cycle. */
    fun classify(title: String?, text: String?): Parsed? {
        val all = listOfNotNull(title, text).joinToString(" ").lowercase(Locale.ROOT)
        if (all.isBlank()) return null
        val appliance = when {
            "dryer" in all || "drying" in all -> "Dryer"
            "washer" in all || "washing" in all || "wash" in all || "laundry" in all || "cycle" in all -> "Washer"
            else -> return null
        }
        return when {
            // Reminders about wet laundry left in the drum are not the end of a cycle.
            "not been removed" in all || "remove it" in all -> null
            "problem" in all || "error" in all -> Parsed(Kind.PROBLEM, appliance)
            "started" in all || "has started" in all -> Parsed(Kind.START, appliance)
            "done" in all || "finished" in all || "complete" in all -> Parsed(Kind.END, appliance)
            else -> null
        }
    }

    data class Cycle(
        val id: Long,
        val appliance: String,
        val start: Long,
        /** Null while running, or when no end was ever seen. */
        val end: Long?,
        val problems: List<Long>,
    )

    /** Longest believable cycle. A start with no end after this is given up on. */
    val MAX_CYCLE_MS: Long = Duration.ofHours(5).toMillis()

    /**
     * Pairs each start with the next end for the same appliance. Repeated
     * starts while a cycle is running belong to that cycle, as do problem
     * reports. An end with no start before it is ignored.
     */
    fun cycles(signals: List<Signal>): List<Cycle> {
        val open = HashMap<String, Cycle>()
        val out = ArrayList<Cycle>()
        for (s in signals.sortedWith(compareBy({ it.ts }, { it.id }))) {
            if (s.source != SOURCE) continue
            val appliance = s.detail
            var cur = open[appliance]
            if (cur != null && s.ts - cur.start > MAX_CYCLE_MS) {
                // Never heard its end; keep it as unfinished and start afresh.
                out += cur
                open.remove(appliance)
                cur = null
            }
            when (s.type) {
                SignalType.APPLIANCE_ON ->
                    if (cur == null) open[appliance] = Cycle(s.id, appliance, s.ts, null, emptyList())
                SignalType.APPLIANCE_PROBLEM ->
                    if (cur != null) open[appliance] = cur.copy(problems = cur.problems + s.ts)
                SignalType.APPLIANCE_OFF -> if (cur != null) {
                    out += cur.copy(end = s.ts)
                    open.remove(appliance)
                }
                else -> Unit
            }
        }
        out += open.values
        return out.sortedBy { it.start }
    }

    /** Used for a running cycle's expected length until a few cycles have finished. */
    val DEFAULT_CYCLE_MS: Long = Duration.ofHours(1).toMillis()

    /** Expected length of a cycle: the median of this appliance's finished cycles, or [DEFAULT_CYCLE_MS]. */
    fun expectedLength(all: List<Cycle>, appliance: String): Long {
        val done = all.filter { it.appliance == appliance && it.end != null }.map { it.end!! - it.start }.sorted()
        return if (done.isEmpty()) DEFAULT_CYCLE_MS else done[done.size / 2]
    }

    /**
     * Calendar events, all under one tag per cycle so each is updated in place:
     *  - as soon as it starts: "Washer running | At: 2:54 pm", blocked out for the expected length
     *  - once finished: "Washer | Dur: 52 mins | Start: ... End: ..."
     *  - no finish within 5 hours: "Washer started | At: 2:54 pm"
     */
    fun events(signals: List<Signal>, now: Long, calendarId: Long, zone: ZoneId = ZoneId.systemDefault()): List<DesiredEvent> {
        val clock = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
        fun time(ms: Long) = clock.format(Instant.ofEpochMilli(ms).atZone(zone))
        val all = cycles(signals)
        return all.map { c ->
            val tag = "${PlacesEngine.TAG_ROOT}laundry:${c.id}"
            val problems = c.problems.joinToString("") { "\nThinQ reported a problem at ${time(it)}" }
            val end = c.end
            if (end != null) {
                DesiredEvent(
                    tag = tag,
                    calendarId = calendarId,
                    startMs = c.start,
                    endMs = end,
                    title = EventFormatter.spanTitle(
                        c.appliance, Duration.ofMillis(end - c.start), Instant.ofEpochMilli(c.start), Instant.ofEpochMilli(end),
                    ),
                    description = "${c.appliance} started ${time(c.start)}, finished ${time(end)}$problems\n" +
                        "From LG ThinQ notifications\n\n${EventFormatter.TAG_PREFIX}$tag]",
                )
            } else if (now - c.start > MAX_CYCLE_MS) {
                // Never heard the end: note the start so the load isn't forgotten.
                DesiredEvent(
                    tag = tag,
                    calendarId = calendarId,
                    startMs = c.start,
                    endMs = c.start + 60_000L,
                    title = EventFormatter.markerTitle("${c.appliance} started", Instant.ofEpochMilli(c.start)),
                    description = "${c.appliance} started ${time(c.start)}; no finish notification arrived$problems\n" +
                        "From LG ThinQ notifications\n\n${EventFormatter.TAG_PREFIX}$tag]",
                )
            } else {
                val expected = expectedLength(all, c.appliance)
                DesiredEvent(
                    tag = tag,
                    calendarId = calendarId,
                    startMs = c.start,
                    endMs = c.start + expected,
                    title = EventFormatter.markerTitle("${c.appliance} running", Instant.ofEpochMilli(c.start)),
                    description = "${c.appliance} started ${time(c.start)}. Shown until about ${time(c.start + expected)}, " +
                        "the usual length; it updates when the cycle finishes.$problems\n" +
                        "From LG ThinQ notifications\n\n${EventFormatter.TAG_PREFIX}$tag]",
                )
            }
        }
    }
}
