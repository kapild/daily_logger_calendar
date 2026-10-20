package dev.kapil.healthcal

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Redraws the Quick log widget when something on it expires: the "logged"
 * line after 15 seconds and a tile's two-minute lock. A widget can't run a
 * timer itself, so each tap books two one-off redraws.
 */
class QuickLogRefresh(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        runCatching { QuickLogWidget.refresh(applicationContext) }
        return Result.success()
    }

    companion object {
        fun scheduleFor(context: Context, tappedAt: Long) {
            val now = System.currentTimeMillis()
            for ((name, at) in listOf("confirm" to tappedAt + QuickLog.CONFIRM_MS, "unlock" to tappedAt + QuickLog.COOLDOWN_MS)) {
                val delay = (at - now).coerceAtLeast(0) + 500 // just after it expires
                val work = OneTimeWorkRequestBuilder<QuickLogRefresh>().setInitialDelay(delay, TimeUnit.MILLISECONDS).build()
                WorkManager.getInstance(context).enqueueUniqueWork("quicklog-$name-$tappedAt", ExistingWorkPolicy.KEEP, work)
            }
        }
    }
}
