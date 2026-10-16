package dev.kapil.healthcal

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class WidgetWeekTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private val today = LocalDate.of(2026, 10, 16) // a Friday

    @Before fun locale() = Locale.setDefault(Locale.US)

    private fun at(day: Int, hour: Int, minute: Int = 0): Long =
        ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    private fun week(
        sleeps: List<SleepSpan> = emptyList(),
        workouts: List<WorkoutSpan> = emptyList(),
        steps: Map<LocalDate, Long>? = emptyMap(),
        calories: Map<LocalDate, Long>? = emptyMap(),
    ) = WeekBuilder.build(today, zone, sleeps, workouts, steps, calories, now = 0L)

    @Test fun sevenDaysEndingToday() {
        val w = week()
        assertEquals(7, w.days.size)
        assertEquals(LocalDate.of(2026, 10, 10), w.days.first())
        assertEquals(today, w.days.last())
        assertEquals(6, w.todayIndex)
    }

    @Test fun sleepIsFiledUnderTheWakeDayAndANapDoesNotReplaceIt() {
        val night = SleepSpan(at(15, 23, 57), at(16, 6, 58), 400)
        val nap = SleepSpan(at(16, 14, 0), at(16, 14, 30), 30)
        val w = week(sleeps = listOf(nap, night))
        assertEquals(400L, w.sleep[6]!!.asleepMin)
        assertNull(w.sleep[5])
        assertEquals("6h 40m", WidgetText.value(Metric.SLEEP, w, 6))
        assertEquals("Fri · today · 11:57 PM – 6:58 AM · 95% efficient", WidgetText.detail(Metric.SLEEP, w, 6, zone))
        assertEquals("—", WidgetText.value(Metric.SLEEP, w, 5))
        assertEquals("Avg 6h 40m", WidgetText.summary(Metric.SLEEP, w))
    }

    @Test fun workoutsAddUpPerDay() {
        val w = week(workouts = listOf(
            WorkoutSpan(at(14, 7, 0), at(14, 7, 45), "Running"),
            WorkoutSpan(at(14, 18, 0), at(14, 18, 30), "Strength training"),
            WorkoutSpan(at(12, 7, 0), at(12, 7, 20), "Running"),
        ))
        assertEquals("75 min", WidgetText.value(Metric.WORKOUTS, w, 4))
        assertEquals("Wed · Running, Strength training", WidgetText.detail(Metric.WORKOUTS, w, 4, zone))
        assertEquals("Rest", WidgetText.value(Metric.WORKOUTS, w, 5))
        assertEquals("2 of 7 days", WidgetText.summary(Metric.WORKOUTS, w))
    }

    @Test fun stepsAndCalories() {
        val w = week(
            steps = mapOf(today to 4210L, today.minusDays(1) to 10_500L),
            calories = mapOf(today to 900L, today.minusDays(1) to 2400L, today.minusDays(2) to 2200L),
        )
        assertEquals("4,210", WidgetText.value(Metric.STEPS, w, 6))
        assertEquals("Fri · today · so far", WidgetText.detail(Metric.STEPS, w, 6, zone))
        assertEquals("14,710 this week", WidgetText.summary(Metric.STEPS, w))
        assertEquals("2,400 cal", WidgetText.value(Metric.CALORIES, w, 5))
        // Today is partial, so the average uses the finished days only.
        assertEquals("Avg 2,300 a day", WidgetText.summary(Metric.CALORIES, w))
    }

    @Test fun missingPermissionSaysSo() {
        val w = week(steps = null, calories = null)
        assertEquals("—", WidgetText.value(Metric.STEPS, w, 6))
        assertEquals("Allow steps in HealthCal", WidgetText.detail(Metric.STEPS, w, 6, zone))
        assertEquals("No access", WidgetText.summary(Metric.CALORIES, w))
        assertEquals(0.0, WidgetText.barValue(Metric.STEPS, w, 6), 0.0)
    }

    @Test fun shortDurations() {
        assertEquals("45m", WidgetText.hm(45))
        assertEquals("7h 5m", WidgetText.hm(425))
        assertEquals("F", WidgetText.dayLetter(today))
    }
}
