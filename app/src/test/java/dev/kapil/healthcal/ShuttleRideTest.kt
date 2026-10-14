package dev.kapil.healthcal

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShuttleRideTest {
    private val zone = ZoneId.systemDefault()
    private var nextId = 1L

    private fun at(hour: Int, minute: Int = 0): Long =
        ZonedDateTime.of(2026, 10, 13, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    private fun signal(ts: Long, type: SignalType, place: Place? = null, source: String = "location", detail: String = "") =
        Signal(nextId++, ts, type, place?.key, source, detail)

    private fun shuttleOn(ts: Long) = signal(ts, SignalType.SHUTTLE, source = "wifi", detail = "gShuttle")
    private fun shuttleOff(ts: Long) = signal(ts, SignalType.SHUTTLE_OFF, source = "wifi", detail = "gShuttle")

    private val geofenced = setOf(Place.HOME, Place.OFFICE)

    /** The evening in the report: shuttle home, then a restaurant, then home. */
    private fun eveningWithRestaurant() = listOf(
        signal(at(9), SignalType.ARRIVE, Place.OFFICE),
        signal(at(16, 55), SignalType.LEAVE, Place.OFFICE),
        shuttleOn(at(16, 58)),
        shuttleOff(at(17, 36)),
        signal(at(18, 51), SignalType.ARRIVE, Place.HOME),
    )

    @Test fun rideRunsFromShuttleWifiOnToOff() {
        val t = PlaceTimeline.build(eveningWithRestaurant(), geofenced)
        assertEquals(listOf(at(16, 58) to at(17, 36)), t.rides.map { it.start to it.end })
        // The door-to-door trip is unchanged.
        assertEquals(listOf(at(16, 55) to at(18, 51)), t.trips.map { it.start to it.end })
    }

    @Test fun briefDropIsTheSameRide() {
        val signals = listOf(shuttleOn(at(8, 20)), shuttleOff(at(8, 35)), shuttleOn(at(8, 38)), shuttleOff(at(9, 2)))
        val t = PlaceTimeline.build(signals, geofenced)
        assertEquals(listOf(at(8, 20) to at(9, 2)), t.rides.map { it.start to it.end })
    }

    @Test fun longerBreakIsTwoRides() {
        val signals = listOf(shuttleOn(at(8, 0)), shuttleOff(at(8, 20)), shuttleOn(at(8, 45)), shuttleOff(at(9, 5)))
        assertEquals(2, PlaceTimeline.build(signals, geofenced).rides.size)
    }

    @Test fun rideWithoutOffIsNotWritten() {
        val signals = listOf(shuttleOn(at(16, 58)), signal(at(18, 51), SignalType.ARRIVE, Place.HOME))
        assertTrue(PlaceTimeline.build(signals, geofenced).rides.isEmpty())
    }

    @Test fun eventsSplitTheRideFromTheDoorToDoorTrip() {
        val events = PlacesEngine.build(PlaceTimeline.build(eveningWithRestaurant(), geofenced), at(23), calendarId = 1)
        val titles = events.map { it.title }
        assertTrue(titles.toString(), titles.any { it.startsWith("Commute home by shuttle | Dur: 38 mins |") })
        assertTrue(titles.toString(), titles.any { it.startsWith("Work to home via shuttle | Dur: 1 hour 56 mins |") })
        assertTrue(titles.none { it.startsWith("Commute home by shuttle | Dur: 1 hour") })
    }

    @Test fun carConnectingEndsTheRide() {
        // The receiver logs "off" with the car's Bluetooth when the car connects mid-ride.
        val signals = listOf(
            signal(at(16, 55), SignalType.LEAVE, Place.OFFICE),
            shuttleOn(at(16, 58)),
            signal(at(17, 38), SignalType.SHUTTLE_OFF, source = "bluetooth", detail = "gShuttle"),
            signal(at(17, 38), SignalType.CAR_ON, source = "bluetooth", detail = "Car"),
            shuttleOff(at(17, 40)), // the 3-minute check, too late: ignored
            signal(at(18, 30), SignalType.CAR_OFF, source = "bluetooth", detail = "Car"),
            signal(at(18, 51), SignalType.ARRIVE, Place.HOME),
        )
        val t = PlaceTimeline.build(signals, geofenced)
        assertEquals(listOf(Triple(at(16, 58), at(17, 38), "bluetooth")), t.rides.map { Triple(it.start, it.end, it.endSource) })
        val titles = PlacesEngine.build(t, at(23), calendarId = 1).map { it.title }
        assertTrue(titles.toString(), titles.any { it.startsWith("Commute home by shuttle | Dur: 40 mins |") })
        assertTrue(titles.toString(), titles.any { it.startsWith("Work to home via shuttle and car | Dur: 1 hour 56 mins |") })
    }

    @Test fun wifiOffBeforeTheCarWins() {
        val signals = listOf(
            shuttleOn(at(16, 58)),
            shuttleOff(at(17, 36)),
            signal(at(17, 38), SignalType.SHUTTLE_OFF, source = "bluetooth", detail = "gShuttle"),
        )
        assertEquals(listOf(at(17, 36)), PlaceTimeline.build(signals, geofenced).rides.map { it.end })
    }

    @Test fun carCommuteIsUnchanged() {
        val signals = listOf(
            signal(at(16, 55), SignalType.LEAVE, Place.OFFICE),
            signal(at(16, 56), SignalType.CAR_ON, source = "bluetooth", detail = "Car"),
            signal(at(17, 40), SignalType.CAR_OFF, source = "bluetooth", detail = "Car"),
            signal(at(17, 42), SignalType.ARRIVE, Place.HOME),
        )
        val events = PlacesEngine.build(PlaceTimeline.build(signals, geofenced), at(23), calendarId = 1)
        assertTrue(events.map { it.title }.toString(), events.any { it.title.startsWith("Commute home by car | Dur: 47 mins |") })
    }
}
