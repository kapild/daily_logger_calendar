package dev.kapil.healthcal

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommuteStatsTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private fun at(day: Int, h: Int, m: Int) = ZonedDateTime.of(2026, 10, day, h, m, 0, 0, zone).toInstant().toEpochMilli()
    private fun e(kind: String, title: String, day: Int, sh: Int, sm: Int, eh: Int, em: Int, desc: String = "") =
        PlaceEvent(kind, title, at(day, sh, sm), at(day, eh, em), desc)

    // Two real days from the Logger calendar.
    private val events = listOf(
        e("trip", "Commute to work", 6, 8, 1, 9, 5),
        e("leave", "Left home", 6, 8, 1, 8, 2),
        e("stay", "At work", 6, 9, 5, 17, 56),
        e("stay", "At work", 6, 11, 59, 17, 56), // a duplicate stay, counted once
        e("stay", "At work", 6, 17, 43, 17, 56),
        e("trip", "Work to home via shuttle and car", 6, 17, 56, 18, 55),
        e("arrive", "Arrived home", 6, 18, 55, 18, 56),
        e("trip", "Home to work via shuttle and car", 21, 7, 30, 8, 58, "Shuttle: 8:56 AM"),
        e("stay", "At work", 21, 8, 58, 17, 18),
        e("trip", "Commute home", 21, 17, 18, 18, 44),
        e("laundry", "Washer", 21, 20, 30, 21, 35),
        e("arrive", "Arrived home", 25, 18, 0, 18, 1), // a weekend day with no office stay
    )

    @Test fun buildsOfficeDays() {
        val days = CommuteStats.days(events, zone)
        assertEquals(listOf(LocalDate.of(2026, 10, 6), LocalDate.of(2026, 10, 21)), days.map { it.date })
        val oct6 = days[0]
        assertEquals(531, oct6.officeMin)
        assertEquals(64, oct6.commuteIn)
        assertEquals(59, oct6.commuteOut)
        assertTrue(oct6.outShuttle)
        assertEquals(8 * 60 + 1, oct6.leftHome)
        assertEquals(18 * 60 + 55, oct6.arrivedHome)
        val oct21 = days[1]
        assertTrue(oct21.inShuttle)
        assertEquals(88, oct21.commuteIn)
        assertEquals(86, oct21.commuteOut)
    }

    @Test fun averagesAndComparison() {
        val days = CommuteStats.days(events, zone)
        val (recent, prior) = CommuteStats.compare(days, LocalDate.of(2026, 10, 25), 14)
        assertEquals(1, recent.days)
        assertEquals(500.0, recent.officeMin!!, 0.1)
        assertEquals(1, prior.days)
        assertEquals("▼ 31 min less", CommuteStats.delta(recent.officeMin, prior.officeMin, isClock = false))
        assertEquals("▼ 31 min earlier", CommuteStats.delta(7.5 * 60, 8.0 * 60 + 1, isClock = true))
        assertNull(CommuteStats.delta(null, 1.0, false))
    }

    @Test fun weeklyTrend() {
        val t = CommuteStats.trend(CommuteStats.days(events, zone), monthly = false)
        assertEquals(listOf("Oct 5", "Oct 19"), t.map { it.label })
        assertEquals(61.5, t[0].commuteMin!!, 0.1)
        assertEquals("Oct", CommuteStats.trend(CommuteStats.days(events, zone), monthly = true).single().label)
    }

    @Test fun heatmapSlots() {
        val days = CommuteStats.days(events, zone)
        val grid = CommuteStats.heat(days, LocalTime.of(6, 30), LocalTime.of(11, 0)) { it.leftHome }
        assertEquals(1, grid[1][3]) // Tuesday, 8:00–8:30
        assertEquals(1, grid[2][2]) // Wednesday, 7:30–8:00
    }

    @Test fun formatting() {
        assertEquals("8h 35m", CommuteStats.duration(515.0))
        assertEquals("40 min", CommuteStats.duration(39.75))
        assertEquals("8:11 am", CommuteStats.clock(491.0))
        assertEquals("6:33 pm", CommuteStats.clock(18 * 60 + 33.0))
        assertEquals("12:05 pm", CommuteStats.clock(12 * 60 + 5.0))
    }
}
