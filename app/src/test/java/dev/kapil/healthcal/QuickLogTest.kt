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
