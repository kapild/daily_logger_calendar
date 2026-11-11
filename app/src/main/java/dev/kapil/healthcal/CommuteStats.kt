package dev.kapil.healthcal

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** A places event from the calendar: its tag kind ("trip", "stay", "leave", ...), title and times. */
data class PlaceEvent(val kind: String, val title: String, val startMs: Long, val endMs: Long, val description: String)

/** One office day, door to door. Times are minutes after midnight; null when the app didn't see it. */
data class OfficeDay(
    val date: LocalDate,
    val officeMin: Int,
    val arriveOffice: Int,
    val leaveOffice: Int,
    val commuteIn: Int?,
    val inShuttle: Boolean,
    val commuteOut: Int?,
    val outShuttle: Boolean,
    val leftHome: Int?,
    val arrivedHome: Int?,
)

/** Averages over a set of office days; null where no day had the value. */
data class Averages(
    val days: Int,
    val officeMin: Double?,
    val commuteIn: Double?,
    val commuteOut: Double?,
    val leftHome: Double?,
    val arrivedHome: Double?,
)

/** One bar in the weekly or monthly trend. */
data class Period(val start: LocalDate, val label: String, val days: Int, val officeMin: Double, val commuteMin: Double?)

/**
 * Work and commute numbers built from Daily Logger's own places events in the
 * calendar ("At work" stays and commute trips). No Android code, so it is
 * unit-tested.
 */
object CommuteStats {
    private const val MIN = 60_000L

    /** Turns places events into office days, oldest first. A day counts when it has at least one stay at the office. */
    fun days(events: List<PlaceEvent>, zone: ZoneId): List<OfficeDay> {
        fun date(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        fun clock(ms: Long): Int = Instant.ofEpochMilli(ms).atZone(zone).toLocalTime().let { it.hour * 60 + it.minute }
        val byDay = events.groupBy { date(it.startMs) }
        return byDay.keys.sorted().mapNotNull { day ->
            val list = byDay.getValue(day)
            val stays = list.filter { it.kind == "stay" && it.title.startsWith("At work") }.sortedBy { it.startMs }
            if (stays.isEmpty()) return@mapNotNull null
            val arrive = stays.minOf { it.startMs }
            val leave = stays.maxOf { it.endMs }
            val trips = list.filter { it.kind == "trip" }
            val toWork = trips.filter { it.title.startsWith("Commute to work") || it.title.startsWith("Home to work") }
                .minByOrNull { kotlin.math.abs(it.endMs - arrive) }
            val toHome = trips.filter { it.title.startsWith("Commute home") || it.title.startsWith("Work to home") }
                .minByOrNull { kotlin.math.abs(it.startMs - leave) }
            val leftHome = toWork?.startMs
                ?: list.filter { it.kind == "leave" && it.title == "Left home" && it.startMs < arrive }.maxOfOrNull { it.startMs }
            val arrivedHome = toHome?.endMs
                ?: list.filter { it.kind == "arrive" && it.title == "Arrived home" && it.startMs > leave }.minOfOrNull { it.startMs }
            OfficeDay(
                date = day,
                officeMin = (union(stays.map { it.startMs to it.endMs }) / MIN).toInt(),
                arriveOffice = clock(arrive),
                leaveOffice = clock(leave),
                commuteIn = toWork?.let { ((it.endMs - it.startMs) / MIN).toInt() },
                inShuttle = toWork?.let(::isShuttle) ?: false,
                commuteOut = toHome?.let { ((it.endMs - it.startMs) / MIN).toInt() },
                outShuttle = toHome?.let(::isShuttle) ?: false,
                leftHome = leftHome?.let(::clock),
                arrivedHome = arrivedHome?.let(::clock),
            )
        }
    }

    private fun isShuttle(e: PlaceEvent) = "shuttle" in e.title.lowercase() || "Shuttle:" in e.description

    /** Total length of overlapping intervals, counted once. */
    fun union(intervals: List<Pair<Long, Long>>): Long {
        var total = 0L
        var cur: Pair<Long, Long>? = null
        for ((s, e) in intervals.sortedBy { it.first }) {
            val c = cur
            cur = if (c != null && s <= c.second) c.first to maxOf(c.second, e) else {
                if (c != null) total += c.second - c.first
                s to e
            }
        }
        cur?.let { total += it.second - it.first }
        return total
    }

    fun averages(days: List<OfficeDay>): Averages {
        fun avg(f: (OfficeDay) -> Int?): Double? = days.mapNotNull(f).takeIf { it.isNotEmpty() }?.average()
        return Averages(
            days = days.size,
            officeMin = avg { it.officeMin },
            commuteIn = avg { it.commuteIn },
            commuteOut = avg { it.commuteOut },
            leftHome = avg { it.leftHome },
            arrivedHome = avg { it.arrivedHome },
        )
    }

    /** The last [spanDays] days ending on [end], and the [spanDays] before them. */
    fun compare(days: List<OfficeDay>, end: LocalDate, spanDays: Long): Pair<Averages, Averages> {
        val cut = end.minusDays(spanDays)
        val prior = cut.minusDays(spanDays)
        return averages(days.filter { it.date > cut && it.date <= end }) to averages(days.filter { it.date > prior && it.date <= cut })
    }

    /** Averages per week (starting Monday) or per month. */
    fun trend(days: List<OfficeDay>, monthly: Boolean): List<Period> =
        days.groupBy {
            if (monthly) it.date.withDayOfMonth(1) else it.date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        }.toSortedMap().map { (start, ds) ->
            val legs = ds.flatMap { listOfNotNull(it.commuteIn, it.commuteOut) }
            Period(
                start = start,
                label = if (monthly) "${start.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }}"
                else "${start.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }} ${start.dayOfMonth}",
                days = ds.size,
                officeMin = ds.map { it.officeMin }.average(),
                commuteMin = legs.takeIf { it.isNotEmpty() }?.average(),
            )
        }

    /**
     * Counts per weekday (Mon..Fri rows) and half-hour slot from [from] to [to]
     * (columns) of a time of day picked by [pick].
     */
    fun heat(days: List<OfficeDay>, from: LocalTime, to: LocalTime, pick: (OfficeDay) -> Int?): Array<IntArray> {
        val start = from.hour * 60 + from.minute
        val slots = ((to.hour * 60 + to.minute) - start) / 30
        val grid = Array(5) { IntArray(slots) }
        for (d in days) {
            val row = d.date.dayOfWeek.value - 1
            val t = pick(d) ?: continue
            val col = (t - start) / 30
            if (row in 0..4 && t >= start && col < slots) grid[row][col]++
        }
        return grid
    }

    /** "8h 35m", "40 min". */
    fun duration(min: Double): String {
        val m = Math.round(min).toInt()
        return if (m >= 60) "${m / 60}h ${"%02d".format(m % 60)}m" else "$m min"
    }

    /** "8:11 am" from minutes after midnight. */
    fun clock(min: Double): String {
        val m = Math.round(min).toInt()
        val h = m / 60
        return "${(h + 11) % 12 + 1}:${"%02d".format(m % 60)} ${if (h < 12) "am" else "pm"}"
    }

    /** "▲ 21 min more", "▼ 10 min earlier"; null when either side is missing or there's no change. */
    fun delta(now: Double?, before: Double?, isClock: Boolean): String? {
        if (now == null || before == null) return null
        val d = Math.round(now - before)
        if (d == 0L) return "Same as before"
        val word = if (isClock) (if (d > 0) "later" else "earlier") else (if (d > 0) "more" else "less")
        return "${if (d > 0) "▲" else "▼"} ${duration(kotlin.math.abs(d).toDouble())} $word"
    }
}
