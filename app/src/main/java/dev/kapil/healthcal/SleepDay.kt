package dev.kapil.healthcal

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The "today's sleep" marker: one all-day event on today's date, carrying
 * your most recent night's sleep in its title.
 *
 * It exists for the watch. A watch only keeps calendar events that have not
 * ended yet, so it never has the real sleep event, which ended when you woke.
 * An all-day event for today has not ended, so the watch keeps it and the
 * watch face can read the sleep duration from its title.
 */
object SleepDay {
    /** Tag prefix for marker events: "sleepday:2026-10-11". */
    const val TAG_PREFIX = "sleepday:"

    fun tag(date: LocalDate): String = "$TAG_PREFIX$date"

    /**
     * Which sleep to show on [today]'s marker.
     *
     * If a sleep ended today, the one with the most time asleep, so a nap
     * never replaces the night. If none has ended today yet (it is after
     * midnight and you have not slept, or the night has not synced), the
     * same choice from yesterday, so the marker keeps showing your last
     * night until a new one arrives. Nothing older than yesterday is shown.
     */
    fun <T> pick(
        nights: List<T>,
        today: LocalDate,
        zone: ZoneId,
        wakeMs: (T) -> Long,
        asleepMinutes: (T) -> Long,
    ): T? {
        val byWakeDate = nights.groupBy { Instant.ofEpochMilli(wakeMs(it)).atZone(zone).toLocalDate() }
        val latest = byWakeDate[today] ?: byWakeDate[today.minusDays(1)] ?: return null
        return latest.maxByOrNull(asleepMinutes)
    }

    /** An all-day event is stored from midnight UTC to the next midnight UTC. */
    fun allDayStartMs(date: LocalDate): Long = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    fun allDayEndMs(date: LocalDate): Long = allDayStartMs(date.plusDays(1))
}
