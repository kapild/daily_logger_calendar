package dev.kapil.healthcal

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val prefs = Prefs(ctx)
        val mode = inputData.getString(KEY_MODE) ?: MODE_RECENT
        val now = Instant.now()

        // Quick run right after a place/drive signal: rebuild the last 2 days of places only.
        if (mode == MODE_PLACES) {
            prefs.placesStatus = step { PlacesEngine.run(ctx, now.minus(Duration.ofDays(2)), now) }
            return Result.success()
        }

        val from = if (mode == MODE_BACKFILL) {
            prefs.backfillStart.atStartOfDay(ZoneId.systemDefault()).toInstant()
        } else {
            now.minus(Duration.ofDays(RECENT_DAYS))
        }

        prefs.runningSince = System.currentTimeMillis()
        prefs.lastStatus = if (mode == MODE_BACKFILL) {
            "Backfilling from ${prefs.backfillStart}…"
        } else {
            "Syncing the last $RECENT_DAYS days…"
        }

        try {
            prefs.lastStatus = step { SyncEngine.run(ctx, from, now).summary() }
            prefs.placesStatus = step { PlacesEngine.run(ctx, from, now) }

            // Housekeeping: re-arm watchers (cheap, idempotent) and trim old signals.
            PlaceMonitor.registerAll(ctx)
            SignalLog.get(ctx).pruneOlderThan(now.minus(Duration.ofDays(400)).toEpochMilli())

            prefs.lastRunAt = System.currentTimeMillis()
        } finally {
            prefs.runningSince = 0L
        }
        return Result.success()
    }

    /** Runs one part of the sync and turns failures into a readable status line. */
    private suspend fun step(block: suspend () -> String): String = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: SecurityException) {
        "Missing permission. Open HealthCal and grant the access it asks for.\n(${e.message})"
    } catch (e: Exception) {
        "Failed: ${e.javaClass.simpleName}: ${e.message}"
    }

    companion object {
        const val KEY_MODE = "mode"
        const val MODE_RECENT = "recent"
        const val MODE_BACKFILL = "backfill"
        const val MODE_PLACES = "places"
        const val RECENT_DAYS = 7L

        private const val WORK_MANUAL = "healthcal-manual"
        private const val WORK_PERIODIC = "healthcal-periodic"
        private const val WORK_PLACES = "healthcal-places"
        private const val WORK_PLACES_AFTER_LEAVE = "healthcal-places-leave"

        /** Queue a one-off full sync. Requests made while one is running wait their turn. */
        fun runNow(context: Context, mode: String) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInputData(workDataOf(KEY_MODE to mode))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_MANUAL, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }

        /**
         * Update place events shortly after a signal. A "left" signal waits 11 minutes,
         * because leaving and returning within 10 minutes is treated as never having left.
         */
        fun schedulePlaces(context: Context, afterLeave: Boolean) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInputData(workDataOf(KEY_MODE to MODE_PLACES))
                .setInitialDelay(if (afterLeave) 11L else 2L, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                if (afterLeave) WORK_PLACES_AFTER_LEAVE else WORK_PLACES,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }

        /** Every 6 hours, re-check the last 7 days and fill any gaps. */
        fun setPeriodic(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) {
                wm.cancelUniqueWork(WORK_PERIODIC)
                return
            }
            val request = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
                .setInputData(workDataOf(KEY_MODE to MODE_RECENT))
                .build()
            wm.enqueueUniquePeriodicWork(WORK_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
