package dev.kapil.healthcal

import android.content.Context
import org.json.JSONObject

/**
 * How long each event took to reach Google, in two legs:
 *  1. from when it happened (you left home at 8:24) to when Daily Logger wrote it to the calendar on the phone;
 *  2. from then to when the phone's calendar marked it as uploaded to Google.
 * Google then sends it to your other devices; that last leg can't be seen from the phone.
 */
data class Timing(
    val tag: String,
    val calendarId: Long,
    val startMs: Long,
    val happenedAt: Long,
    val writtenAt: Long,
    val uploadedAt: Long? = null,
) {
    val toCalendarMs: Long get() = (writtenAt - happenedAt).coerceAtLeast(0)
    val toGoogleMs: Long? get() = uploadedAt?.let { (it - writtenAt).coerceAtLeast(0) }
}

data class DelaySummary(
    val count: Int,
    val medianToCalendarMs: Long,
    /** Null when no event has been seen reaching Google yet. */
    val medianToGoogleMs: Long?,
    val slowestTotalMs: Long?,
    val slowestTitleTag: String?,
)

object Delay {
    private const val MINUTE = 60_000L

    /**
     * When the thing an event records actually happened. A one-minute marker
     * ("Left home | At: 8:24 am") happened at its start; a span (a drive, a
     * stay) is complete at its end. A span still in progress (a running washer)
     * counts from its start. All-day markers are written as soon as they can
     * be, so they count from when they were written.
     */
    fun happenedAt(e: DesiredEvent, writtenAt: Long): Long = when {
        e.allDay -> writtenAt
        e.endMs - e.startMs <= MINUTE -> e.startMs
        e.endMs > writtenAt -> e.startMs
        else -> e.endMs
    }.coerceAtMost(writtenAt)

    fun summarize(timings: Collection<Timing>, since: Long): DelaySummary? {
        val recent = timings.filter { it.writtenAt >= since }
        if (recent.isEmpty()) return null
        val toCal = recent.map { it.toCalendarMs }.sorted()
        val toGoogle = recent.mapNotNull { it.toGoogleMs }.sorted()
        val slowest = recent.filter { it.uploadedAt != null }.maxByOrNull { it.uploadedAt!! - it.happenedAt }
        return DelaySummary(
            count = recent.size,
            medianToCalendarMs = toCal[toCal.size / 2],
            medianToGoogleMs = toGoogle.getOrNull(toGoogle.size / 2),
            slowestTotalMs = slowest?.let { it.uploadedAt!! - it.happenedAt },
            slowestTitleTag = slowest?.tag,
        )
    }

    /** "under a minute", "4 min", "1 h 12 min". */
    fun text(ms: Long): String {
        val min = ms / MINUTE
        return when {
            min < 1 -> "under a minute"
            min < 60 -> "$min min"
            min % 60 == 0L -> "${min / 60} h"
            else -> "${min / 60} h ${min % 60} min"
        }
    }

    /** One line for an event: "Happened 8:24 am · in calendar 3 min later · on Google 11 min after that". */
    fun line(t: Timing, now: Long, clock: (Long) -> String): String {
        val first = "Happened ${clock(t.happenedAt)} · in calendar ${if (t.toCalendarMs < MINUTE) "within a minute" else text(t.toCalendarMs) + " later"}"
        val second = t.toGoogleMs?.let { " · on Google ${if (it < MINUTE) "within a minute" else text(it) + " after that"}" }
            ?: " · not on Google yet (${text(now - t.writtenAt)} so far)"
        return first + second
    }
}

/** Timings kept on the phone for the last 30 days. */
object LatencyLog {
    private const val PREFS = "latency"
    private const val KEEP_MS = 30L * 24 * 60 * 60_000L

    private fun sp(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun recordWritten(context: Context, e: DesiredEvent, now: Long = System.currentTimeMillis()) {
        val t = Timing(e.tag, e.calendarId, e.startMs, Delay.happenedAt(e, now), now)
        sp(context).edit().putString(e.tag, encode(t)).apply()
    }

    fun all(context: Context): Map<String, Timing> {
        val cutoff = System.currentTimeMillis() - KEEP_MS
        val out = HashMap<String, Timing>()
        val stale = ArrayList<String>()
        for ((tag, v) in sp(context).all) {
            val t = (v as? String)?.let { runCatching { decode(tag, it) }.getOrNull() }
            if (t == null || t.writtenAt < cutoff) stale += tag else out[tag] = t
        }
        if (stale.isNotEmpty()) sp(context).edit().apply { stale.forEach { remove(it) } }.apply()
        return out
    }

    fun pending(context: Context): List<Timing> = all(context).values.filter { it.uploadedAt == null }

    fun markUploaded(context: Context, timings: List<Timing>, now: Long = System.currentTimeMillis()) {
        if (timings.isEmpty()) return
        val edit = sp(context).edit()
        for (t in timings) edit.putString(t.tag, encode(t.copy(uploadedAt = now)))
        edit.apply()
    }

    private fun encode(t: Timing): String = JSONObject().apply {
        put("cal", t.calendarId)
        put("start", t.startMs)
        put("happened", t.happenedAt)
        put("written", t.writtenAt)
        t.uploadedAt?.let { put("uploaded", it) }
    }.toString()

    private fun decode(tag: String, s: String): Timing {
        val o = JSONObject(s)
        return Timing(
            tag = tag,
            calendarId = o.getLong("cal"),
            startMs = o.getLong("start"),
            happenedAt = o.getLong("happened"),
            writtenAt = o.getLong("written"),
            uploadedAt = if (o.has("uploaded")) o.getLong("uploaded") else null,
        )
    }
}
