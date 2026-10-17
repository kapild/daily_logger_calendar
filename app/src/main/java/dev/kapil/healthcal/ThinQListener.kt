package dev.kapil.healthcal

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat

/**
 * Reads LG ThinQ notifications ("Washing started.", "Washing done") and logs
 * them as signals. Every other app's notifications are ignored. The sync job
 * turns the signals into "Washer | Dur: ..." events.
 */
class ThinQListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!Laundry.isThinQ(sbn.packageName)) return
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()
        val parsed = Laundry.classify(title, text) ?: return
        val type = when (parsed.kind) {
            Laundry.Kind.START -> SignalType.APPLIANCE_ON
            Laundry.Kind.END -> SignalType.APPLIANCE_OFF
            Laundry.Kind.PROBLEM -> SignalType.APPLIANCE_PROBLEM
        }
        Signals.record(applicationContext, sbn.postTime, type, null, Laundry.SOURCE, parsed.appliance)
    }

    companion object {
        /** Whether you have allowed Daily Logger to read notifications. */
        fun isAllowed(context: Context): Boolean =
            context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

        /** Opens the system screen where notification access is turned on. */
        fun settingsIntent(context: Context): Intent =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                    Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    ComponentName(context, ThinQListener::class.java).flattenToString(),
                )
            } else {
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            }
    }
}
