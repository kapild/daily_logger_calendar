package dev.kapil.healthcal

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.provider.CalendarContract
import android.util.Log

/**
 * Notes the moment each new event reaches Google. Android tells this job when
 * the calendar changes, which includes Google's sync marking our events as
 * uploaded; the job then checks the events still waiting. Nothing runs while
 * no event is waiting.
 */
class UploadWatch : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            check(applicationContext)
            jobFinished(params, false)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = false

    companion object {
        private const val TAG = "DailyLoggerUpload"
        private const val JOB_ID = 4201
        private const val DAY = 86_400_000L

        /** Look at the events still waiting and note any that are now on Google. Re-arms itself while some wait. */
        fun check(context: Context) {
            try {
                if (!hasCalendarPermission(context)) return
                // Only recent writes: an event that never shows as uploaded shouldn't keep this alive.
                val recent = System.currentTimeMillis() - 2 * DAY
                val pending = LatencyLog.pending(context).filter { it.writtenAt >= recent }
                if (pending.isEmpty()) return
                val from = pending.minOf { it.startMs } - DAY
                val to = pending.maxOf { it.startMs } + DAY
                val events = CalendarRepo.listOurEvents(context, pending.map { it.calendarId }.toSet(), from, to)
                // This phone doesn't share upload status, so there's nothing to watch for.
                if (events.none { it.upload != Upload.UNKNOWN }) return
                val onServer = events
                    .filter { it.upload == Upload.ON_SERVER }
                    .mapTo(HashSet()) { it.tag }
                val uploaded = pending.filter { it.tag in onServer }
                LatencyLog.markUploaded(context, uploaded)
                if (uploaded.size < pending.size) schedule(context)
            } catch (e: Exception) {
                Log.w(TAG, "Upload check failed", e)
            }
        }

        /** Wait for the next calendar change. Cheap; call after writing events. */
        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, UploadWatch::class.java))
                .addTriggerContentUri(
                    JobInfo.TriggerContentUri(
                        CalendarContract.Events.CONTENT_URI,
                        JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS,
                    ),
                )
                .setTriggerContentUpdateDelay(1_000)
                .setTriggerContentMaxDelay(10_000)
                .build()
            runCatching { scheduler.schedule(job) }.onFailure { Log.w(TAG, "Could not watch uploads", it) }
        }
    }
}
