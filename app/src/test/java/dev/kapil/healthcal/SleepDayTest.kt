package dev.kapil.healthcal

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SleepDayTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private val today = LocalDate.of(2026, 10, 11)

    private data class Night(val name: String, val wakeMs: Long, val asleepMinutes: Long)

    private fun at(day: Int, hour: Int, minute: Int = 0): Long =
        ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    private fun pick(nights: List<Night>) =
        SleepDay.pick(nights, today, zone, { it.wakeMs }, { it.asleepMinutes })

    @Test fun picksTheSleepThatEndedToday() {
        val lastNight = Night("last night", at(11, 6, 22), 440)
        val nightBefore = Night("night before", at(10, 6, 0), 480)
        assertEquals(lastNight, pick(listOf(nightBefore, lastNight)))
    }

    @Test fun aNapDoesNotReplaceTheNight() {
        val lastNight = Night("last night", at(11, 6, 22), 440)
        val nap = Night("nap", at(11, 14, 30), 30)
        assertEquals(lastNight, pick(listOf(lastNight, nap)))
    }

    @Test fun afterMidnightKeepsShowingYesterdaysNight() {
        // 1 am on the 11th: nothing has ended today. Yesterday had a night
        // and an afternoon nap; the night is still the one to show.
        val night = Night("night", at(10, 6, 34), 475)
        val nap = Night("nap", at(10, 16, 30), 77)
        assertEquals(night, pick(listOf(nap, night)))
    }

    @Test fun todaysSleepReplacesYesterdays() {
        val yesterday = Night("yesterday", at(10, 6, 34), 475)
        val today = Night("today", at(11, 6, 10), 400)
        assertEquals(today, pick(listOf(yesterday, today)))
    }

    @Test fun nothingOlderThanYesterdayIsShown() {
        assertNull(pick(listOf(Night("two days ago", at(9, 6, 0), 480))))
        assertNull(pick(emptyList()))
    }

    @Test fun wakeDateIsLocalNotUtc() {
        // 11:30 pm on the 9th in Los Angeles is already the 10th in UTC, but
        // locally it is two days back from the 11th, so it is not shown.
        val late = Night("late", at(9, 23, 30), 60)
        assertNull(pick(listOf(late)))
    }

    @Test fun allDayEventsRunMidnightToMidnightUtc() {
        assertEquals("sleepday:2026-10-11", SleepDay.tag(today))
        val start = SleepDay.allDayStartMs(today)
        assertEquals(java.time.Instant.parse("2026-10-11T00:00:00Z").toEpochMilli(), start)
        assertEquals(start + 24L * 60 * 60 * 1000, SleepDay.allDayEndMs(today))
    }
}
