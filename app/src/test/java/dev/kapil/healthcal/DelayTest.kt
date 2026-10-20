package dev.kapil.healthcal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DelayTest {
    private val min = 60_000L

    private fun event(start: Long, end: Long, allDay: Boolean = false) =
        DesiredEvent(tag = "t", calendarId = 1, startMs = start, endMs = end, title = "x", description = "", allDay = allDay)

    @Test fun aMarkerHappenedAtItsStart() {
        // "Left home | At: 8:24 am", written at 8:40.
        assertEquals(0L, Delay.happenedAt(event(0, min), writtenAt = 16 * min))
    }

    @Test fun aFinishedSpanHappenedAtItsEnd() {
        // A commute 8:24 to 8:56, written at 9:05.
        assertEquals(32 * min, Delay.happenedAt(event(0, 32 * min), writtenAt = 41 * min))
    }

    @Test fun aRunningSpanCountsFromItsStart() {
        // "Washer running" written two minutes after it started, ending in the future.
        assertEquals(0L, Delay.happenedAt(event(0, 60 * min), writtenAt = 2 * min))
    }

    @Test fun anAllDayMarkerHasNoDelay() {
        assertEquals(5 * min, Delay.happenedAt(event(0, 1440 * min, allDay = true), writtenAt = 5 * min))
    }

    @Test fun summaryUsesMediansAndTheSlowest() {
        val t = listOf(
            Timing("a", 1, 0, happenedAt = 0, writtenAt = 2 * min, uploadedAt = 5 * min),
            Timing("b", 1, 0, happenedAt = 0, writtenAt = 3 * min, uploadedAt = 40 * min),
            Timing("c", 1, 0, happenedAt = 0, writtenAt = 12 * min, uploadedAt = null),
        )
        val s = Delay.summarize(t, since = 0)!!
        assertEquals(3, s.count)
        assertEquals(3 * min, s.medianToCalendarMs)
        assertEquals(37 * min, s.medianToGoogleMs) // of 3 and 37 minutes
        assertEquals(40 * min, s.slowestTotalMs)
        assertEquals("b", s.slowestTitleTag)
        assertNull(Delay.summarize(t, since = 100 * min))
    }

    @Test fun readableLine() {
        val t = Timing("a", 1, 0, happenedAt = 0, writtenAt = 3 * min, uploadedAt = 14 * min)
        assertEquals(
            "Happened 8:24 AM · in calendar 3 min later · on Google 11 min after that",
            Delay.line(t, now = 20 * min) { "8:24 AM" },
        )
        val waiting = t.copy(uploadedAt = null)
        assertEquals(
            "Happened 8:24 AM · in calendar 3 min later · not on Google yet (17 min so far)",
            Delay.line(waiting, now = 20 * min) { "8:24 AM" },
        )
        assertEquals("1 h 5 min", Delay.text(65 * min))
    }
}
