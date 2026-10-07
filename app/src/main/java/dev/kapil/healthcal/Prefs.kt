package dev.kapil.healthcal

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import java.time.LocalDate

/** All app settings and the last sync status, in one SharedPreferences file. */
class Prefs(context: Context) {
    val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("healthcal", Context.MODE_PRIVATE)

    var sleepCalendarId: Long
        get() = sp.getLong("sleep_cal", -1L)
        set(v) { sp.edit().putLong("sleep_cal", v).apply() }

    var exerciseCalendarId: Long
        get() = sp.getLong("exercise_cal", -1L)
        set(v) { sp.edit().putLong("exercise_cal", v).apply() }

    var exportSleep: Boolean
        get() = sp.getBoolean("export_sleep", true)
        set(v) { sp.edit().putBoolean("export_sleep", v).apply() }

    var exportExercise: Boolean
        get() = sp.getBoolean("export_exercise", true)
        set(v) { sp.edit().putBoolean("export_exercise", v).apply() }

    var autoSync: Boolean
        get() = sp.getBoolean("auto_sync", false)
        set(v) { sp.edit().putBoolean("auto_sync", v).apply() }

    var backfillStart: LocalDate
        get() = sp.getString("backfill_start", null)
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: LocalDate.now().minusDays(30)
        set(v) { sp.edit().putString("backfill_start", v.toString()).apply() }

    /** Package names of apps whose records should be ignored. */
    var excludedSources: Set<String>
        get() = sp.getStringSet("excluded_sources", null)?.toSet() ?: emptySet()
        set(v) { sp.edit().putStringSet("excluded_sources", HashSet(v)).apply() }

    /** Package names seen writing sleep/exercise to Health Connect. */
    var seenSources: Set<String>
        get() = sp.getStringSet("seen_sources", null)?.toSet() ?: emptySet()
        set(v) { sp.edit().putStringSet("seen_sources", HashSet(v)).apply() }

    var lastStatus: String
        get() = sp.getString("last_status", null) ?: "Not synced yet."
        set(v) { sp.edit().putString("last_status", v).apply() }

    /** Epoch millis when the current sync started, or 0 when idle. */
    var runningSince: Long
        get() = sp.getLong("running_since", 0L)
        set(v) { sp.edit().putLong("running_since", v).apply() }

    var lastRunAt: Long
        get() = sp.getLong("last_run_at", 0L)
        set(v) { sp.edit().putLong("last_run_at", v).apply() }

    var placesStatus: String
        get() = sp.getString("places_status", null) ?: ""
        set(v) { sp.edit().putString("places_status", v).apply() }

    /** Bumped whenever a new place/drive signal is logged, so the UI can refresh. */
    var signalsChangedAt: Long
        get() = sp.getLong("signals_changed_at", 0L)
        set(v) { sp.edit().putLong("signals_changed_at", v).apply() }
}

/** Human-readable app name for a package, falling back to the package name. */
fun appLabel(context: Context, pkg: String): String = try {
    val pm = context.packageManager
    @Suppress("DEPRECATION")
    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
} catch (e: PackageManager.NameNotFoundException) {
    pkg
}
