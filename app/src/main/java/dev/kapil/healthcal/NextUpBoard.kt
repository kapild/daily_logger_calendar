package dev.kapil.healthcal

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One tile on the Next Up widget. */
data class BoardTile(
    val event: FeedEvent,
    val state: State,
    /** "22 min ago", "in 8 min", "ends in 12 min". */
    val relative: String,
    /** "5:55 – 6:25 PM", "Fri 10:00 AM – 5:00 PM". */
    val times: String,
    /** How full the bar is, 0 to 100. */
    val bar: Int,
) {
    enum class State { DONE, NOW, NEXT }
}

/** The two events that just ended and the next two (an event in progress counts as next). */
data class Board(val done: List<BoardTile>, val next: List<BoardTile>)

/**
 * Builds the Next Up widget from calendar events. No Android code, so it is
 * unit-tested. Bars use a two-hour scale: an upcoming event's bar fills as it
 * gets closer, a finished event's bar shrinks as it recedes, and an event in
 * progress shows the share of it still to go.
 */
object NextUpBoard {
    const val SCALE_MIN = 120L
    private const val MIN = 60_000L

    fun build(events: List<FeedEvent>, now: Long, zone: ZoneId = ZoneId.systemDefault()): Board {
        val timed = events.filter { !it.allDay && it.end > it.begin }.distinctBy { Triple(it.title, it.begin, it.end) }
        val done = timed.filter { it.end <= now }.sortedBy { it.end }.takeLast(2) // older first
            .map { e ->
                val ago = (now - e.end) / MIN
                BoardTile(e, BoardTile.State.DONE, ago(ago), times(e, now, zone), bar(SCALE_MIN - ago, SCALE_MIN))
            }
        val next = timed.filter { it.end > now }.sortedWith(compareBy({ it.begin }, { it.end })).take(2)
            .map { e ->
                if (e.begin <= now) {
                    val left = ceilMinutes(e.end - now)
                    val total = maxOf(1L, (e.end - e.begin) / MIN)
                    BoardTile(e, BoardTile.State.NOW, "ends in ${span(left)}", times(e, now, zone), bar(left, total))
                } else {
                    val until = ceilMinutes(e.begin - now)
                    BoardTile(e, BoardTile.State.NEXT, until(until, e, zone), times(e, now, zone), bar(SCALE_MIN - until, SCALE_MIN))
                }
            }
        return Board(done, next)
    }

    /** Percent, with a sliver always showing so an empty-looking bar still reads as a bar. */
    private fun bar(part: Long, whole: Long): Int = (part * 100 / whole).toInt().coerceIn(3, 100)

    private fun ceilMinutes(ms: Long): Long = (ms + MIN - 1) / MIN

    /** "8 min", "1h 17m", "5h". */
    fun span(min: Long): String = when {
        min < 60 -> "$min min"
        min % 60 == 0L -> "${min / 60}h"
        else -> "${min / 60}h ${min % 60}m"
    }

    private fun ago(min: Long): String = when {
        min < 1 -> "just ended"
        min < 5 * 60 -> "${span(min)} ago"
        min < 24 * 60 -> "${min / 60}h ago"
        else -> "yesterday"
    }

    /** "in 8 min" within a day, otherwise the weekday ("Fri"). */
    private fun until(min: Long, e: FeedEvent, zone: ZoneId): String =
        if (min < 24 * 60) "in ${span(min)}"
        else DateTimeFormatter.ofPattern("EEE", Locale.getDefault()).format(Instant.ofEpochMilli(e.begin).atZone(zone))

    private fun sameDay(a: Long, b: Long, zone: ZoneId) =
        Instant.ofEpochMilli(a).atZone(zone).toLocalDate() == Instant.ofEpochMilli(b).atZone(zone).toLocalDate()

    /** "5:55 – 6:25 PM", with the weekday when it isn't today. */
    fun times(e: FeedEvent, now: Long, zone: ZoneId): String {
        val h = DateTimeFormatter.ofPattern("h:mm", Locale.getDefault())
        val hA = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
        val day = DateTimeFormatter.ofPattern("EEE ", Locale.getDefault())
        val start = Instant.ofEpochMilli(e.begin).atZone(zone)
        val end = Instant.ofEpochMilli(e.end).atZone(zone)
        val prefix = if (sameDay(e.begin, now, zone)) "" else day.format(start)
        val sameHalf = start.toLocalDate() == end.toLocalDate() && (start.hour < 12) == (end.hour < 12)
        return prefix + (if (sameHalf) h.format(start) else hA.format(start)) + " – " + hA.format(end)
    }
}
