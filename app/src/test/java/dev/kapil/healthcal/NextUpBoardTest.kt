package dev.kapil.healthcal

import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class NextUpBoardTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    @Before fun locale() = Locale.setDefault(Locale.US)

    private fun at(h: Int, m: Int, day: Int = 29) = ZonedDateTime.of(2026, 10, day, h, m, 0, 0, zone).toInstant().toEpochMilli()
    private fun ev(title: String, b: Long, e: Long, allDay: Boolean = false) = FeedEvent(title, b, e, allDay, null, "gShuttle")

    private val day = listOf(
        ev("Lunch", at(12, 30), at(13, 15)),
        ev("4:11 PM @ Ardenwood", at(16, 11), at(16, 41)),
        ev("Newark @ 4:55 pm", at(16, 55), at(17, 25)),
        ev("5:55 pm @ Newark", at(17, 55), at(18, 25)),
        ev("Check car", at(18, 0), at(18, 30)),
        ev("Dinner", at(19, 30), at(20, 30)),
        ev("Halloween", at(0, 0, 31), at(0, 0, 32 - 1), allDay = true),
    )

    @Test fun lastTwoDoneOlderFirstAndNextTwo() {
        val b = NextUpBoard.build(day, at(17, 47), zone)
        assertEquals(listOf("4:11 PM @ Ardenwood", "Newark @ 4:55 pm"), b.done.map { it.event.title })
        assertEquals(listOf("1h 6m ago", "22 min ago"), b.done.map { it.relative })
        assertEquals(listOf("5:55 pm @ Newark", "Check car"), b.next.map { it.event.title })
        assertEquals(listOf("in 8 min", "in 13 min"), b.next.map { it.relative })
        assertEquals("5:55 – 6:25 PM", b.next[0].times)
    }

    @Test fun barsFillAsEventsGetCloserAndShrinkAsTheyRecede() {
        val b = NextUpBoard.build(day, at(17, 47), zone)
        assertEquals(93, b.next[0].bar) // 8 of 120 minutes away
        assertEquals(81, b.done[1].bar) // 22 of 120 minutes ago
        assertEquals(45, b.done[0].bar) // 66 minutes ago
    }

    @Test fun anEventInProgressCountsDownToItsEnd() {
        val b = NextUpBoard.build(day, at(18, 13), zone)
        val now = b.next[0]
        assertEquals(BoardTile.State.NOW, now.state)
        assertEquals("ends in 12 min", now.relative)
        assertEquals(40, now.bar) // 12 of 30 minutes left
        assertEquals("Check car", b.next[1].event.title)
        assertEquals("ends in 17 min", b.next[1].relative)
    }

    @Test fun allDayEventsAreLeftOut() {
        val b = NextUpBoard.build(day, at(21, 0), zone)
        assertEquals(emptyList<String>(), b.next.map { it.event.title })
        assertEquals(listOf("Check car", "Dinner"), b.done.map { it.event.title })
    }

    @Test fun laterDaysShowTheWeekday() {
        val fri = listOf(ev("Focus block", at(10, 0, 30), at(17, 0, 30)))
        val b = NextUpBoard.build(fri, at(17, 47), zone)
        assertEquals("in 16h 13m", b.next[0].relative)
        assertEquals("Fri 10:00 AM – 5:00 PM", b.next[0].times)
    }
}
