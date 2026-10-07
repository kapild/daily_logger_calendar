package dev.kapil.healthcal

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource

/**
 * Sets up the low-power watchers. Nothing here polls:
 *  - geofences are tracked by Play Services and wake us on enter/exit
 *  - Android wakes us when any Wi-Fi network connects
 *  - car Bluetooth uses a manifest receiver (see BluetoothReceiver)
 */
object PlaceMonitor {
    const val ACTION_GEOFENCE = "dev.kapil.healthcal.GEOFENCE"
    const val ACTION_WIFI = "dev.kapil.healthcal.WIFI"

    fun hasFineLocation(ctx: Context) = granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasBackgroundLocation(ctx: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            granted(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    fun hasBluetooth(ctx: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            granted(ctx, Manifest.permission.BLUETOOTH_CONNECT)

    private fun granted(ctx: Context, permission: String) =
        ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED

    private fun pendingIntent(ctx: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(ctx, SignalReceiver::class.java).setAction(action)
        // Mutable: Play Services / the system attach extras when they fire it.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(ctx, requestCode, intent, flags)
    }

    /** Arms (or disarms) all watchers to match current settings. Safe to call repeatedly. */
    @SuppressLint("MissingPermission")
    fun registerAll(context: Context) {
        val ctx = context.applicationContext
        val config = PlacesConfig(ctx)
        val geofencing = LocationServices.getGeofencingClient(ctx)
        val connectivity = ctx.getSystemService(ConnectivityManager::class.java)
        val geoIntent = pendingIntent(ctx, ACTION_GEOFENCE, 1)
        val wifiIntent = pendingIntent(ctx, ACTION_WIFI, 2)

        runCatching { connectivity?.unregisterNetworkCallback(wifiIntent) }

        if (!config.enabled) {
            geofencing.removeGeofences(geoIntent)
            return
        }

        // Geofences for every place that has a location set.
        if (hasFineLocation(ctx) && hasBackgroundLocation(ctx)) {
            val fences = Place.entries.mapNotNull { place ->
                val loc = config.location(place) ?: return@mapNotNull null
                Geofence.Builder()
                    .setRequestId(place.key)
                    .setCircularRegion(loc.lat, loc.lng, loc.radiusM.toFloat())
                    .setExpirationDuration(Geofence.NEVER_EXPIRE)
                    .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
                    .setNotificationResponsiveness(60_000) // batch up to 1 min to save battery
                    .build()
            }
            geofencing.removeGeofences(geoIntent).addOnCompleteListener {
                if (fences.isNotEmpty()) {
                    val request = GeofencingRequest.Builder()
                        .setInitialTrigger(0) // don't log a fake "arrived" just because we registered
                        .addGeofences(fences)
                        .build()
                    geofencing.addGeofences(request, geoIntent)
                }
            }
        }

        // Wake on any Wi-Fi connection; SignalReceiver checks which network it is.
        val wifiRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        runCatching { connectivity?.registerNetworkCallback(wifiRequest, wifiIntent) }
    }

    /** Name of the Wi-Fi network the phone is on, or null. Needs location permission. */
    @Suppress("DEPRECATION")
    fun currentSsid(context: Context): String? {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java) ?: return null
        val raw = wifi.connectionInfo?.ssid ?: return null
        val ssid = raw.removeSurrounding("\"")
        return if (ssid.isBlank() || ssid == "<unknown ssid>") null else ssid
    }

    @SuppressLint("MissingPermission")
    fun currentLocation(context: Context, onResult: (Location?) -> Unit) {
        if (!hasFineLocation(context)) {
            onResult(null)
            return
        }
        LocationServices.getFusedLocationProviderClient(context)
            .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)
            .addOnSuccessListener { location -> onResult(location) }
            .addOnFailureListener { onResult(null) }
    }

    /** Paired Bluetooth devices as (address, name). */
    @SuppressLint("MissingPermission")
    fun bondedDevices(context: Context): List<Pair<String, String>> = try {
        val manager = context.getSystemService(BluetoothManager::class.java)
        manager?.adapter?.bondedDevices
            ?.map { it.address to (it.name ?: it.address) }
            ?.sortedBy { it.second.lowercase() }
            ?: emptyList()
    } catch (e: SecurityException) {
        emptyList()
    }

    @SuppressLint("MissingPermission")
    fun deviceName(device: BluetoothDevice): String = try {
        device.name ?: device.address
    } catch (e: SecurityException) {
        device.address
    }
}
