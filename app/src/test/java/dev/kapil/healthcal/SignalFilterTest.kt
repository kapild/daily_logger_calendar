package dev.kapil.healthcal

import org.junit.Assert.assertEquals
import org.junit.Test

class SignalFilterTest {
    private var nextId = 1L
    private fun s(minute: Int, type: SignalType, place: String? = null, source: String = "location") =
        Signal(nextId++, minute * 60_000L, type, place, source, "")

    /** Oct 19: left home 8:30, drove, at the office from 9:00; geofences re-fired at 11:36 and 3:33. */
    @Test fun dropsTheRepeatedGeofenceBursts() {
        val day = listOf(
            s(510, SignalType.LEAVE, "home"),
            s(512, SignalType.CAR_ON, source = "bluetooth"),
            s(540, SignalType.CAR_OFF, source = "bluetooth"),
            s(619, SignalType.ARRIVE, "office", "wifi"),
            s(696, SignalType.LEAVE, "home"),
            s(696, SignalType.LEAVE, "gym"),
            s(696, SignalType.ARRIVE, "office"),
            s(820, SignalType.ARRIVE, "office", "wifi"),
            s(933, SignalType.LEAVE, "home"),
            s(933, SignalType.LEAVE, "gym"),
            s(933, SignalType.ARRIVE, "office"),
        )
        val prior = listOf(s(0, SignalType.LEAVE, "gym")) // last time at the gym, long ago
        val kept = SignalFilter.clean(prior + day).drop(1)
        assertEquals(day.take(4), kept)
    }

    @Test fun aRealReturnIsKept() {
        val signals = listOf(
            s(0, SignalType.ARRIVE, "office", "wifi"),
            s(60, SignalType.CAR_ON, source = "bluetooth"),
            s(90, SignalType.CAR_OFF, source = "bluetooth"),
            s(120, SignalType.ARRIVE, "office", "wifi"),
        )
        assertEquals(signals, SignalFilter.clean(signals))
    }

    @Test fun aFirstLeaveIsKeptEvenWithNoArrivalLogged() {
        // Home was set up while at home, so no "arrived home" was ever logged.
        val signals = listOf(s(510, SignalType.LEAVE, "home"))
        assertEquals(signals, SignalFilter.clean(signals))
    }

    @Test fun leavingAfterArrivingIsKept() {
        val signals = listOf(
            s(0, SignalType.ARRIVE, "gym"),
            s(60, SignalType.LEAVE, "gym"),
            s(61, SignalType.LEAVE, "gym"),
        )
        assertEquals(signals.take(2), SignalFilter.clean(signals))
    }
}
