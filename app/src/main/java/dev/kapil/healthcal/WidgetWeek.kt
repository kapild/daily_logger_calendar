package dev.kapil.healthcal

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A sleep session as the widget needs it. Times are epoch millis. */
data class SleepSpan(val start: Long, val end: Long, val asleepMin: Long)

/** An exercise session as the widget needs it. */
data class WorkoutSpan(val start: Long, val end: Long, val name: String)

/** One night, filed under the date you woke up. */
data class Night(val asleepMin: Long, val bedMs: Long, val wakeMs: Long) {
    val inBedMin: Long get() = (wakeMs - bedMs) / 60_000
}

/**
 * Seven days ending today, oldest first: what the widget shows. A null entry
 * means no data for that day; a null list means it could not be read at all
 * (usually a missing permission).
 */
data class Week(
    val days: List<LocalDate>,
    val sleep: List<Night?>,
    val workoutMin: List<Long>,
    val workoutNames: List<String>,
    val steps: List<Long?>?,
    val calories: List<Long?>?,
    val updatedAt: Long,
) {
    val todayIndex: Int get() = days.lastIndex
}

enum class Metric(val label: String) {
    SLEEP("Sleep"),
    WORKOUTS("Workouts"),
    STEPS("Steps"),
    CALORIES("Calories"),
}

/** Turns Health Connect records into a [Week]. No Android code, so it can be tested. */
object WeekBuilder {
    fun days(today: LocalDate): List<LocalDate> = (6 downTo 0).map { today.minusDays(it.toLong()) }

    fun build(
        today: LocalDate,
        zone: ZoneId,
        sleeps: List<SleepSpan>,
        workouts: List<WorkoutSpan>,
        steps: Map<LocalDate, Long>?,
        calories: Map<LocalDate, Long>?,
        now: Long,
    ): Week {
        val days = days(today)
        fun dateOf(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

        // The longest sleep that ended on a day is that day's night, so a nap never replaces it.
        val nights = sleeps.groupBy { dateOf(it.end) }.mapValues { (_, s) ->
            s.maxBy { it.asleepMin }.let { Night(it.asleepMin, it.start, it.end) }
        }
        val byDay = workouts.groupBy { dateOf(it.start) }
        return Week(
            days = days,
            sleep = days.map { nights[it] },
            workoutMin = days.map { d -> byDay[d].orEmpty().sumOf { (it.end - it.start) / 60_000 } },
            workoutNames = days.map { d -> byDay[d].orEmpty().map { it.name }.distinct().joinToString(", ") },
            steps = steps?.let { m -> days.map { m[it] } },
            calories = calories?.let { m -> days.map { m[it] } },
            updatedAt = now,
        )
    }
}

/** Every piece of text the widget shows. */
object WidgetText {
    private fun locale() = Locale.getDefault()

    fun hm(min: Long): String {
        val h = min / 60
        val m = min % 60
        return if (h == 0L) "${m}m" else "${h}h ${m}m"
    }

    private fun count(n: Long) = String.format(locale(), "%,d", n)

    fun dayLetter(d: LocalDate): String = DateTimeFormatter.ofPattern("EEEEE", locale()).format(d)

    fun dayName(w: Week, i: Int): String {
        val name = DateTimeFormatter.ofPattern("EEE", locale()).format(w.days[i])
        return if (i == w.todayIndex) "$name · today" else name
    }

    /** The big number for one day. */
    fun value(metric: Metric, w: Week, i: Int): String = when (metric) {
        Metric.SLEEP -> w.sleep[i]?.let { hm(it.asleepMin) } ?: "—"
        Metric.WORKOUTS -> if (w.workoutMin[i] > 0) "${w.workoutMin[i]} min" else "Rest"
        Metric.STEPS -> w.steps?.get(i)?.let { count(it) } ?: "—"
        Metric.CALORIES -> w.calories?.get(i)?.let { "${count(it)} cal" } ?: "—"
    }

    /** The line under the big number. */
    fun detail(metric: Metric, w: Week, i: Int, zone: ZoneId): String {
        val day = dayName(w, i)
        val clock = DateTimeFormatter.ofPattern("h:mm a", locale())
        fun time(ms: Long) = clock.format(Instant.ofEpochMilli(ms).atZone(zone))
        return when (metric) {
            Metric.SLEEP -> w.sleep[i]?.let { n ->
                val efficiency = if (n.inBedMin > 0) " · ${n.asleepMin * 100 / n.inBedMin}% efficient" else ""
                "$day · ${time(n.bedMs)} – ${time(n.wakeMs)}$efficiency"
            } ?: "$day · no sleep logged"
            Metric.WORKOUTS -> w.workoutNames[i].takeIf { it.isNotEmpty() }?.let { "$day · $it" } ?: day
            Metric.STEPS -> when {
                w.steps == null -> "Allow steps in HealthCal"
                i == w.todayIndex -> "$day · so far"
                else -> day
            }
            Metric.CALORIES -> when {
                w.calories == null -> "Allow calories in HealthCal"
                i == w.todayIndex -> "$day · so far"
                else -> day
            }
        }
    }

    /** The week in one line. */
    fun summary(metric: Metric, w: Week): String = when (metric) {
        Metric.SLEEP -> {
            val nights = w.sleep.filterNotNull()
            if (nights.isEmpty()) "No sleep logged this week" else "Avg ${hm(nights.sumOf { it.asleepMin } / nights.size)}"
        }
        Metric.WORKOUTS -> "${w.workoutMin.count { it > 0 }} of 7 days"
        Metric.STEPS -> w.steps?.let { s -> "${count(s.sumOf { it ?: 0 })} this week" } ?: "No access"
        Metric.CALORIES -> w.calories?.let { c ->
            // Today is still going, so leave it out of the average when there are other days.
            val full = c.dropLast(1).filterNotNull().ifEmpty { c.filterNotNull() }
            if (full.isEmpty()) "No data" else "Avg ${count(full.sum() / full.size)} a day"
        } ?: "No access"
    }

    /** Bar height, in any unit; only the ratios matter. */
    fun barValue(metric: Metric, w: Week, i: Int): Double = when (metric) {
        Metric.SLEEP -> (w.sleep[i]?.asleepMin ?: 0L).toDouble()
        Metric.WORKOUTS -> w.workoutMin[i].toDouble()
        Metric.STEPS -> (w.steps?.get(i) ?: 0L).toDouble()
        Metric.CALORIES -> (w.calories?.get(i) ?: 0L).toDouble()
    }
}
