package dev.kapil.healthcal

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The "today's sleep" marker: one all-day event on the day you woke up,
 * carrying last night's sleep in its title.
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
     * Which night to show for [today]: of the sleeps that ended today, the
     * one with the most time asleep, so a nap never replaces the night.
     * Returns null when no sleep ended today.
     */
    fun <T> pick(
        nights: List<T>,
        today: LocalDate,
        zone: ZoneId,
        wakeMs: (T) -> Long,
        asleepMinutes: (T) -> Long,
    ): T? = nights
        .filter { Instant.ofEpochMilli(wakeMs(it)).atZone(zone).toLocalDate() == today }
        .maxByOrNull(asleepMinutes)

    /** An all-day event is stored from midnight UTC to the next midnight UTC. */
    fun allDayStartMs(date: LocalDate): Long = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    fun allDayEndMs(date: LocalDate): Long = allDayStartMs(date.plusDays(1))
}
