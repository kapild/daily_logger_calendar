package dev.kapil.healthcal

import android.content.Context

/**
 * The places HealthCal knows about.
 * [markers]   -> write "Arrived ..." / "Left ..." events
 * [spanTitle] -> write one event covering the whole stay
 */
enum class Place(
    val key: String,
    val label: String,
    val defaultRadiusM: Int,
    val markers: Boolean,
    val spanTitle: String?,
) {
    HOME("home", "Home", 150, markers = true, spanTitle = null),
    OFFICE("office", "Office", 250, markers = true, spanTitle = "At work"),
    GYM("gym", "Gym", 120, markers = false, spanTitle = "Gym");

    companion object {
        fun fromKey(key: String?): Place? = entries.firstOrNull { it.key == key }
    }
}

data class PlaceLocation(val lat: Double, val lng: Double, val radiusM: Int)

/** Settings for places and drives, stored alongside the rest of the app's prefs. */
class PlacesConfig(context: Context) {
    private val sp = Prefs(context).sp

    var enabled: Boolean
        get() = sp.getBoolean("places_enabled", false)
        set(v) { sp.edit().putBoolean("places_enabled", v).apply() }

    var calendarId: Long
        get() = sp.getLong("places_cal", -1L)
        set(v) { sp.edit().putLong("places_cal", v).apply() }

    fun location(p: Place): PlaceLocation? {
        val lat = sp.getString("${p.key}_lat", null)?.toDoubleOrNull() ?: return null
        val lng = sp.getString("${p.key}_lng", null)?.toDoubleOrNull() ?: return null
        return PlaceLocation(lat, lng, sp.getInt("${p.key}_radius", p.defaultRadiusM))
    }

    fun setLocation(p: Place, loc: PlaceLocation?) {
        val e = sp.edit()
        if (loc == null) {
            e.remove("${p.key}_lat").remove("${p.key}_lng").remove("${p.key}_radius")
        } else {
            e.putString("${p.key}_lat", loc.lat.toString())
                .putString("${p.key}_lng", loc.lng.toString())
                .putInt("${p.key}_radius", loc.radiusM)
        }
        e.apply()
    }

    fun ssids(p: Place): Set<String> = sp.getStringSet("${p.key}_ssids", null)?.toSet() ?: emptySet()

    fun setSsids(p: Place, v: Set<String>) {
        sp.edit().putStringSet("${p.key}_ssids", HashSet(v)).apply()
    }

    var shuttleSsids: Set<String>
        get() = sp.getStringSet("shuttle_ssids", null)?.toSet() ?: emptySet()
        set(v) { sp.edit().putStringSet("shuttle_ssids", HashSet(v)).apply() }

    /** Bluetooth MAC addresses of your car(s). */
    var carDevices: Set<String>
        get() = sp.getStringSet("car_devices", null)?.toSet() ?: emptySet()
        set(v) { sp.edit().putStringSet("car_devices", HashSet(v)).apply() }

    fun placeForSsid(ssid: String): Place? = Place.entries.firstOrNull { ssid in ssids(it) }
}
