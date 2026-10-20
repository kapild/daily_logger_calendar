package dev.kapil.healthcal

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickLogTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private fun at(day: Int, h: Int, m: Int) = ZonedDateTime.of(2026, 10, day, h, m, 0, 0, zone).toInstant().toEpochMilli()

    @Test fun anEntryBecomesAFifteenMinuteEvent() {
        val e = LogEntry("abc", "coffee", "Coffee", "☕", at(20, 3, 39), 15, "Oat latte")
        val ev = QuickLog.events(listOf(e), calendarId = 4).single()
        assertEquals("ql:abc", ev.tag)
        assertEquals(at(20, 3, 39), ev.startMs)
        assertEquals(at(20, 3, 54), ev.endMs)
        assertTrue(ev.title, ev.title.startsWith("Coffee | At:"))
        assertTrue(ev.description.startsWith("Oat latte\n"))
        assertTrue(ev.description.endsWith("[hc:ql:abc]"))
    }

    @Test fun intimateIsAPlainTile() {
        val t = QuickLog.DEFAULT_TILES.first { it.id == "intimate" }
        assertEquals("Intimate", t.label)
        assertEquals(9, QuickLog.DEFAULT_TILES.size)
    }

    @Test fun countsOnlyToday() {
        val entries = listOf(
            LogEntry("a", "coffee", "Coffee", "☕", at(20, 8, 0), 15),
            LogEntry("b", "coffee", "Coffee", "☕", at(20, 14, 0), 15),
            LogEntry("c", "coffee", "Coffee", "☕", at(19, 14, 0), 15),
            LogEntry("d", "water", "Water", "💧", at(20, 9, 0), 15),
        )
        assertEquals(mapOf("coffee" to 2, "water" to 1), QuickLog.countsOn(entries, LocalDate.of(2026, 10, 20), zone))
    }

    @Test fun idsRememberWhenTheyWereTapped() {
        val now = at(20, 3, 54)
        val id = QuickLog.newId(now)
        assertEquals(now, QuickLog.tappedAt(LogEntry(id, "coffee", "Coffee", "☕", at(20, 3, 39), 15)))
    }
}

class QuickLogCooldownTest {
    private fun tapped(tile: String, at: Long) = LogEntry(QuickLog.newId(at), tile, tile, "", at, 15)

    @Test fun aTileStaysLockedForTwoMinutes() {
        val t0 = 1_800_000_000_000L
        val entries = listOf(tapped("coffee", t0))
        assertEquals(entries[0], QuickLog.coolingDown(entries, "coffee", t0 + 1_000))
        assertEquals(entries[0], QuickLog.coolingDown(entries, "coffee", t0 + 119_000))
        assertEquals(null, QuickLog.coolingDown(entries, "coffee", t0 + 120_000))
        assertEquals(null, QuickLog.coolingDown(entries, "water", t0 + 1_000))
    }

    @Test fun theLoggedLineShowsForFifteenSeconds() {
        val t0 = 1_800_000_000_000L
        val entries = listOf(tapped("coffee", t0 - 60_000), tapped("snack", t0))
        assertEquals("snack", QuickLog.toConfirm(entries, t0 + 14_000)?.tileId)
        assertEquals(null, QuickLog.toConfirm(entries, t0 + 15_000))
    }
}
