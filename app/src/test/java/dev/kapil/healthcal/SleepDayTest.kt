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

    @Test fun nothingWhenNoSleepEndedToday() {
        assertNull(pick(listOf(Night("night before", at(10, 6, 0), 480))))
        assertNull(pick(emptyList()))
    }

    @Test fun wakeDateIsLocalNotUtc() {
        // 11:30 pm on the 10th in Los Angeles is already the 11th in UTC.
        val lateNap = Night("late", at(10, 23, 30), 60)
        assertNull(pick(listOf(lateNap)))
    }

    @Test fun allDayEventsRunMidnightToMidnightUtc() {
        assertEquals("sleepday:2026-10-11", SleepDay.tag(today))
        val start = SleepDay.allDayStartMs(today)
        assertEquals(java.time.Instant.parse("2026-10-11T00:00:00Z").toEpochMilli(), start)
        assertEquals(start + 24L * 60 * 60 * 1000, SleepDay.allDayEndMs(today))
    }
}
