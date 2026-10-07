package dev.kapil.healthcal

/** One continuous stay at a place. */
internal class Visit(
    val place: Place,
    val start: Long,
    val startId: Long,
    val startSource: String,
    val startDetail: String,
    /** False when we only saw the exit, never the arrival. */
    val startKnown: Boolean = true,
) {
    /** Null while you're still there. */
    var end: Long? = null
    var endId: Long = 0L
    var endSource: String = ""
    var endDetail: String = ""

    /** False when no exit was seen; we only know it ended before the next arrival. */
    var endKnown: Boolean = true
}

internal data class Trip(val from: Place, val to: Place, val start: Long, val end: Long, val id: Long)

internal data class Drive(val start: Long, val end: Long, val id: Long, val device: String)

internal class Timeline(
    val visits: List<Visit>,
    val trips: List<Trip>,
    val drives: List<Drive>,
    val shuttleTimes: List<Long>,
)

/**
 * Turns the raw signal log into an ordered day: stay, exit, trip, arrival, stay, ...
 *
 * The order is enforced, not just hoped for. Once you've left a place you are
 * on the way somewhere, and that only ends when you arrive:
 *
 *  - A second "left" for the place you already left is ignored. Geofences
 *    sometimes repeat an exit; the first one stands.
 *  - If the place has a geofence, seeing its Wi-Fi name again after the
 *    geofence said you left is not a return (the same name can exist on a
 *    shuttle or elsewhere on campus). Only the geofence can bring you back.
 *  - Back within 10 minutes means you never left.
 *  - A return shorter than 10 minutes is dropped, and the first exit stands.
 *  - If an exit was never seen, the stay ends when the car or shuttle was
 *    detected. With no such hint, no "left" time is invented.
 *
 * This file has no Android imports so the rules can be tested on their own.
 */
internal object PlaceTimeline {
    private const val MIN = 60_000L
    const val FLAP_MS = 10 * MIN           // leaving and coming back within this = never left
    const val COMMUTE_MAX_MS = 180 * MIN   // longer gaps aren't treated as one trip
    const val MIN_DRIVE_MS = 2 * MIN
    const val MAX_DRIVE_MS = 300 * MIN
    const val MIN_STAY_MS = 10 * MIN       // shorter visits are drive-bys

    fun build(signals: List<Signal>, geofenced: Set<Place>): Timeline {
        val visits = ArrayList<Visit>()
        val drives = ArrayList<Drive>()
        val shuttleTimes = ArrayList<Long>()

        var current: Visit? = null  // where you are now
        var last: Visit? = null     // the place you most recently left, while on the way somewhere
        var hint: Signal? = null    // car or shuttle seen while at a geofenced place
        var carOn: Signal? = null

        fun close(v: Visit, ts: Long, id: Long, source: String, detail: String, known: Boolean) {
            v.end = ts
            v.endId = id
            v.endSource = source
            v.endDetail = detail
            v.endKnown = known
            current = null
            last = v
            hint = null
        }

        // Getting into the car or onto the shuttle. For a Wi-Fi-only place this is
        // the exit. For a geofenced place it's kept as a fallback in case the
        // geofence never reports the exit.
        fun moving(s: Signal, source: String) {
            val cur = current ?: return
            if (cur.place in geofenced) {
                if (hint == null) hint = s
            } else {
                close(cur, s.ts, s.id, source, s.detail, known = true)
            }
        }

        for (s in signals) {
            when (s.type) {
                SignalType.ARRIVE -> {
                    val place = Place.fromKey(s.place) ?: continue
                    val cur = current
                    if (cur != null && cur.place == place) {
                        hint = null // still here, so an earlier car or shuttle signal wasn't a departure
                        continue
                    }

                    val prev = last
                    if (cur == null && prev != null && prev.place == place) {
                        // You've left this place and haven't arrived anywhere else yet.
                        if (s.source == "wifi" && place in geofenced) {
                            continue // the geofence says you left; a Wi-Fi name doesn't bring you back
                        }
                        val leftAt = prev.end ?: s.ts
                        if (s.ts - leftAt <= FLAP_MS) {
                            if (prev.startKnown) {
                                // Back within 10 minutes: never left.
                                prev.end = null
                                prev.endKnown = true
                                current = prev
                                last = null
                                hint = null
                                continue
                            }
                            visits.remove(prev) // an exit with no arrival, undone by this arrival
                        }
                    }

                    if (cur != null) {
                        // Arrived somewhere new without seeing the exit from the last place.
                        val h = hint
                        if (h != null && s.ts - h.ts in MIN..COMMUTE_MAX_MS) {
                            val source = if (h.type == SignalType.SHUTTLE) "shuttle" else "car"
                            close(cur, h.ts, h.id, source, h.detail, known = true)
                        } else {
                            close(cur, s.ts, s.id, "next-arrival", place.label, known = false)
                        }
                    }

                    val v = Visit(place, s.ts, s.id, s.source, s.detail)
                    visits += v
                    current = v
                    last = null
                    hint = null
                }

                SignalType.LEAVE -> {
                    val place = Place.fromKey(s.place) ?: continue
                    val cur = current
                    val prev = last
                    if (cur != null) {
                        // An exit for some other place while you're here is stale; ignore it.
                        if (cur.place == place) close(cur, s.ts, s.id, s.source, s.detail, known = true)
                    } else if (prev == null || prev.place != place) {
                        // Left a place we never saw you arrive at.
                        val v = Visit(place, s.ts, s.id, s.source, s.detail, startKnown = false)
                        visits += v
                        close(v, s.ts, s.id, s.source, s.detail, known = true)
                    }
                    // Otherwise: a repeated exit from the place you already left. The first one stands.
                }

                SignalType.CAR_ON -> {
                    if (carOn == null) carOn = s
                    moving(s, "car")
                }

                SignalType.CAR_OFF -> {
                    val on = carOn
                    if (on != null && s.ts - on.ts in MIN_DRIVE_MS..MAX_DRIVE_MS) {
                        drives += Drive(on.ts, s.ts, on.id, on.detail)
                    }
                    carOn = null
                }

                SignalType.SHUTTLE -> {
                    shuttleTimes += s.ts
                    moving(s, "shuttle")
                }
            }
        }

        // A short hop back to the place you just left isn't a new visit.
        val kept = ArrayList<Visit>()
        for (v in visits) {
            val prev = kept.lastOrNull()
            val end = v.end
            val briefReturn = prev != null && prev.place == v.place && v.startKnown &&
                end != null && end - v.start < MIN_STAY_MS
            if (!briefReturn) kept += v
        }

        // Every exit is followed by a trip to wherever you arrive next.
        val trips = ArrayList<Trip>()
        for (i in 0 until kept.size - 1) {
            val a = kept[i]
            val b = kept[i + 1]
            val leftAt = a.end ?: continue
            if (!a.endKnown || !b.startKnown || a.place == b.place) continue
            if (b.start - leftAt in MIN..COMMUTE_MAX_MS) {
                trips += Trip(a.place, b.place, leftAt, b.start, a.endId)
            }
        }

        return Timeline(kept, trips, drives, shuttleTimes)
    }
}
