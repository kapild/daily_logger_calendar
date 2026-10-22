package dev.kapil.healthcal

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchFeedTest {
    private val ride = FeedEvent("Newark @ 4:22 pm", 2_000, 3_800, false, null, "gShuttle")
    private val treat = FeedEvent("Trick-or-Treat Event", 9_000, 20_000, false, "Fremont", "Events")

    @Test fun carriesEventsAndSleepInTheShapeTheWatchReads() {
        val o = WatchFeed.content(listOf(treat, ride), LocalDate.of(2026, 10, 22), 435)
        val events = o.getJSONArray("events")
        assertEquals(2, events.length())
        assertEquals("Newark @ 4:22 pm", events.getJSONObject(0).getString("title")) // sorted by start
        assertEquals("gShuttle", events.getJSONObject(0).getString("calendar"))
        assertEquals("", events.getJSONObject(0).getString("location"))
        assertEquals("2026-10-22", o.getJSONObject("sleep").getString("day"))
        assertEquals(435L, o.getJSONObject("sleep").getLong("asleepMin"))
    }

    @Test fun noSleepWhenThereIsNone() {
        assertFalse(WatchFeed.content(listOf(ride), LocalDate.of(2026, 10, 22), null).has("sleep"))
        assertFalse(WatchFeed.content(listOf(ride), null, null).has("sleep"))
    }

    @Test fun sameEventsInAnyOrderGiveTheSameFeed() {
        val a = WatchFeed.content(listOf(ride, treat), null, null).toString()
        val b = WatchFeed.content(listOf(treat, ride), null, null).toString()
        assertEquals(a, b)
        assertTrue(a.contains("\"v\":1"))
    }
}
