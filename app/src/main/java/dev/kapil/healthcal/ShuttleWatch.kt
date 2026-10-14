package dev.kapil.healthcal

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Notices when you get off the shuttle. Whichever of these comes first ends the ride:
 *  - your car's Bluetooth connecting ([BluetoothReceiver])
 *  - the phone joining another Wi-Fi network ([SignalReceiver])
 *  - this check, every [CHECK_MINUTES] while a ride is open, finding the phone
 *    no longer on the shuttle Wi-Fi. Android wakes apps when a Wi-Fi network
 *    connects but not when one drops, so this covers dropping to mobile data.
 */
object ShuttleWatch {
    const val CHECK_MINUTES = 3L

    /** Checks stop after this, in case "off" is never seen. */
    private const val GIVE_UP_MS = 4 * 60 * 60_000L

    private const val WORK = "healthcal-shuttle-watch"

    /** The shuttle signal that opened the current ride, or null when not on a ride. */
    fun openRide(context: Context): Signal? {
        val last = SignalLog.get(context).lastOfTypes(SignalType.SHUTTLE, SignalType.SHUTTLE_OFF)
        return last?.takeIf { it.type == SignalType.SHUTTLE && System.currentTimeMillis() - it.ts < GIVE_UP_MS }
    }

    fun start(context: Context) {
        val request = OneTimeWorkRequestBuilder<ShuttleWatchWorker>()
            .setInitialDelay(CHECK_MINUTES, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, request)
    }

    internal fun check(context: Context) {
        val ride = openRide(context) ?: return
        val ssid = PlaceMonitor.currentSsid(context)
        if (ssid != null && ssid in PlacesConfig(context).shuttleSsids) {
            start(context) // still on the shuttle
        } else {
            Signals.record(context, System.currentTimeMillis(), SignalType.SHUTTLE_OFF, null, "wifi", ride.detail)
        }
    }
}

class ShuttleWatchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        runCatching { ShuttleWatch.check(applicationContext) }
        return Result.success()
    }
}
