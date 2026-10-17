package dev.kapil.healthcal

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LaundryTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private var nextId = 1L

    private fun at(hour: Int, minute: Int): Long =
        ZonedDateTime.of(2026, 10, 17, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    private fun sig(ts: Long, type: SignalType, appliance: String = "Washer") =
        Signal(nextId++, ts, type, null, Laundry.SOURCE, appliance)

    @Test fun readsThinQMessages() {
        assertEquals(Laundry.Parsed(Laundry.Kind.START, "Washer"), Laundry.classify("LG ThinQ", "Washing started."))
        assertEquals(Laundry.Parsed(Laundry.Kind.END, "Washer"), Laundry.classify("LG ThinQ", "Washing done"))
        assertEquals(
            Laundry.Parsed(Laundry.Kind.END, "Washer"),
            Laundry.classify("Washer", "The cycle has finished. Please unload the laundry."),
        )
        assertEquals(
            Laundry.Parsed(Laundry.Kind.PROBLEM, "Washer"),
            Laundry.classify("LG ThinQ", "There is a problem with the washer. Check the app."),
        )
        assertEquals(Laundry.Parsed(Laundry.Kind.START, "Dryer"), Laundry.classify("LG ThinQ", "Drying started."))
    }

    @Test fun ignoresRemindersAndOtherMessages() {
        assertNull(Laundry.classify("Washer", "Laundry has not been removed after washing. Leaving wet laundry in the washer can cause odors. Please remove it."))
        assertNull(Laundry.classify("LG ThinQ", "You're doing a great job saving energy."))
        assertNull(Laundry.classify(null, null))
    }

    @Test fun onlyThinQIsRead() {
        assertTrue(Laundry.isThinQ("com.lgeha.nuts"))
        assertEquals(false, Laundry.isThinQ("com.whatsapp"))
    }

    @Test fun repeatedStartsAndEndsMakeOneCycle() {
        // What the phone showed on Oct 17: three starts and a problem at 2:54, done at 3:10.
        val signals = listOf(
            sig(at(14, 54), SignalType.APPLIANCE_ON),
            sig(at(14, 54), SignalType.APPLIANCE_PROBLEM),
            sig(at(14, 54), SignalType.APPLIANCE_ON),
            sig(at(14, 55), SignalType.APPLIANCE_ON),
            sig(at(15, 10), SignalType.APPLIANCE_OFF),
            sig(at(15, 10), SignalType.APPLIANCE_OFF),
        )
        val cycles = Laundry.cycles(signals)
        assertEquals(1, cycles.size)
        assertEquals(at(14, 54), cycles[0].start)
        assertEquals(at(15, 10), cycles[0].end)
        assertEquals(1, cycles[0].problems.size)

        val events = Laundry.events(signals, now = at(15, 11), calendarId = 7, zone = zone)
        assertEquals(1, events.size)
        assertEquals(at(14, 54), events[0].startMs)
        assertEquals(at(15, 10), events[0].endMs)
        assertTrue(events[0].title, events[0].title.startsWith("Washer | Dur:"))
        assertTrue(events[0].description.contains("problem"))
    }

    @Test fun aRunningCycleHasNoEventYet() {
        val signals = listOf(sig(at(14, 54), SignalType.APPLIANCE_ON))
        assertTrue(Laundry.events(signals, now = at(15, 30), calendarId = 7, zone = zone).isEmpty())
    }

    @Test fun aStartWithNoEndIsNotedAfterFiveHours() {
        val signals = listOf(sig(at(9, 0), SignalType.APPLIANCE_ON))
        val events = Laundry.events(signals, now = at(15, 0), calendarId = 7, zone = zone)
        assertEquals(1, events.size)
        assertTrue(events[0].title, events[0].title.startsWith("Washer started"))
    }

    @Test fun washerAndDryerAreSeparate() {
        val signals = listOf(
            sig(at(10, 0), SignalType.APPLIANCE_ON, "Washer"),
            sig(at(11, 0), SignalType.APPLIANCE_OFF, "Washer"),
            sig(at(11, 5), SignalType.APPLIANCE_ON, "Dryer"),
            sig(at(12, 0), SignalType.APPLIANCE_OFF, "Dryer"),
        )
        val cycles = Laundry.cycles(signals)
        assertEquals(listOf("Washer", "Dryer"), cycles.map { it.appliance })
    }

    @Test fun anEndWithoutAStartIsIgnored() {
        assertTrue(Laundry.cycles(listOf(sig(at(15, 10), SignalType.APPLIANCE_OFF))).isEmpty())
    }
}
