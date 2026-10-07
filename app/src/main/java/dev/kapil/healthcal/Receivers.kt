package dev.kapil.healthcal

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.IntentCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent

/** Receives geofence transitions and Wi-Fi connections. Only logs; never writes the calendar. */
class SignalReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            PlaceMonitor.ACTION_GEOFENCE -> onGeofence(context, intent)
            PlaceMonitor.ACTION_WIFI -> onWifi(context)
        }
    }

    private fun onGeofence(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) return
        val type = when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> SignalType.ARRIVE
            Geofence.GEOFENCE_TRANSITION_EXIT -> SignalType.LEAVE
            else -> return
        }
        // Use the time of the location fix, which is closer to the real crossing.
        val ts = event.triggeringLocation?.time ?: System.currentTimeMillis()
        for (fence in event.triggeringGeofences.orEmpty()) {
            val place = Place.fromKey(fence.requestId) ?: continue
            Signals.record(context, ts, type, place.key, "location", "geofence")
        }
    }

    private fun onWifi(context: Context) {
        val config = PlacesConfig(context)
        if (!config.enabled) return
        val ssid = PlaceMonitor.currentSsid(context) ?: return
        val now = System.currentTimeMillis()
        // A shuttle network is never a place, even if the same name is also listed under one.
        if (ssid in config.shuttleSsids) {
            Signals.record(context, now, SignalType.SHUTTLE, null, "wifi", ssid)
            return
        }
        config.placeForSsid(ssid)?.let { place ->
            Signals.record(context, now, SignalType.ARRIVE, place.key, "wifi", ssid)
        }
    }
}

/** Car Bluetooth connect / disconnect = drive start / end. */
class BluetoothReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val config = PlacesConfig(context)
        if (!config.enabled) return
        val device = IntentCompat.getParcelableExtra(
            intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java
        ) ?: return
        if (device.address !in config.carDevices) return
        val type = when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> SignalType.CAR_ON
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> SignalType.CAR_OFF
            else -> return
        }
        Signals.record(context, System.currentTimeMillis(), type, null, "bluetooth", PlaceMonitor.deviceName(device))
    }
}

/** Geofences and the Wi-Fi watcher don't survive a reboot; re-arm them. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        PlaceMonitor.registerAll(context)
    }
}
