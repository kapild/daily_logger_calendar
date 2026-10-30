package dev.kapil.healthcal

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.provider.CalendarContract
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.time.LocalDate
import java.util.Date
import java.util.concurrent.TimeUnit

/** An upcoming event as the watch needs it. */
data class FeedEvent(
    val title: String,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    val location: String?,
    val calendar: String?,
    /** The phone's event id, for opening it in Calendar; not sent to the watch. */
    val eventId: Long = -1,
)

/**
 * Sends the Next Up watch face what it can't get on its own: every calendar
 * on this phone for the next week, and last night's sleep from Health
 * Connect. The calendar copy Android shares with watch apps leaves many
 * events out. The watch app has the same application id as this app, which is
 * what lets the Wear data layer connect them.
 */
object WatchFeed {
    const val PATH = "/nextup/feed"
    private const val CAPABILITY = "nextup_watch"
    private const val TAG = "DailyLoggerWatch"
    private const val PREFS = "watch_feed"
    private const val DAY = 86_400_000L

    /** Even with nothing new, send at least this often so the watch knows the feed is current. */
    private const val HEARTBEAT_MS = 3 * 60 * 60_000L

    // ------------------------------------------------------------ pure

    /** The feed without its send time, so an unchanged feed can be recognised and skipped. */
    fun content(events: List<FeedEvent>, sleepDay: LocalDate?, asleepMin: Long?): JSONObject = JSONObject().apply {
        put("v", 1)
        put("events", JSONArray().apply {
            events.sortedWith(compareBy({ it.begin }, { it.title })).forEach { e ->
                put(
                    JSONObject()
                        .put("title", e.title)
                        .put("begin", e.begin)
                        .put("end", e.end)
                        .put("allDay", e.allDay)
                        .put("location", e.location ?: "")
                        .put("calendar", e.calendar ?: ""),
                )
            }
        })
        if (sleepDay != null && asleepMin != null && asleepMin > 0) {
            put("sleep", JSONObject().put("day", sleepDay.toString()).put("asleepMin", asleepMin))
        }
    }

    // ------------------------------------------------------------ phone side

    /**
     * Events in the phone's visible calendars overlapping [from, to), with
     * recurring events expanded. Leaves out cancelled and declined events, and
     * Daily Logger's own events (sleep, places, quick log), which aren't
     * appointments.
     */
    fun readEvents(context: Context, from: Long, to: Long): List<FeedEvent> {
        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
            CalendarContract.Instances.DESCRIPTION,
            CalendarContract.Instances.SELF_ATTENDEE_STATUS,
            CalendarContract.Instances.EVENT_ID,
        )
        // STATUS is often empty, so a plain "!= cancelled" would drop those events too.
        val selection = "${CalendarContract.Instances.VISIBLE} = 1 AND (${CalendarContract.Instances.STATUS} IS NULL OR " +
            "${CalendarContract.Instances.STATUS} != ${CalendarContract.Events.STATUS_CANCELED})"
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(from.toString())
            .appendPath(to.toString())
            .build()
        val out = ArrayList<FeedEvent>()
        context.contentResolver.query(uri, projection, selection, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext()) {
                val description = c.getString(6) ?: ""
                if (EventFormatter.TAG_PREFIX in description) continue
                if (!c.isNull(7) && c.getInt(7) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) continue
                out += FeedEvent(
                    title = c.getString(0)?.trim().orEmpty().ifEmpty { "(No title)" },
                    begin = c.getLong(1),
                    end = c.getLong(2),
                    allDay = c.getInt(3) != 0,
                    location = c.getString(4),
                    calendar = c.getString(5),
                    eventId = c.getLong(8),
                )
            }
        }
        return out
    }

    private fun sp(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** One line for the Setup tab. */
    fun status(context: Context): String = sp(context).getString("status", null)
        ?: "Not sent yet. It sends when you open the app and whenever your calendar changes."

    /** Build the feed and send it if it changed (or [force]). Returns the status line. */
    suspend fun push(context: Context, force: Boolean = false): String = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val status = try {
            if (!hasCalendarPermission(app)) return@withContext "Needs calendar access."
            val now = System.currentTimeMillis()
            val events = readEvents(app, now - DAY, now + 8 * DAY)
            val week = runCatching { WidgetData.get(app) }.getOrNull()
            val night = week?.let { it.sleep[it.todayIndex] }
            val body = content(events, week?.days?.last(), night?.asleepMin)
            val hash = body.toString().hashCode()
            val prefs = sp(app)
            val unchanged = hash == prefs.getInt("hash", 0) && now - prefs.getLong("sentAt", 0) < HEARTBEAT_MS
            if (unchanged && !force) return@withContext status(app)

            body.put("sentAt", now)
            val request = PutDataMapRequest.create(PATH).apply { dataMap.putString("json", body.toString()) }
                .asPutDataRequest()
                .setUrgent()
            Tasks.await(Wearable.getDataClient(app).putDataItem(request), 20, TimeUnit.SECONDS)
            prefs.edit().putInt("hash", hash).putLong("sentAt", now).apply()

            val watches = runCatching {
                Tasks.await(
                    Wearable.getCapabilityClient(app).getCapability(CAPABILITY, CapabilityClient.FILTER_REACHABLE),
                    10, TimeUnit.SECONDS,
                ).nodes
            }.getOrDefault(emptySet())
            val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(now))
            val what = "${events.size} events" + if (night != null) " and last night's sleep" else ""
            if (watches.isEmpty()) {
                "Saved $what at $time. Next Up isn't on a connected watch right now; it gets them when it is."
            } else {
                "Sent $what to ${watches.first().displayName} at $time."
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not send to the watch", e)
            "Couldn't send to the watch: ${e.javaClass.simpleName}. Is the Pixel Watch app set up on this phone?"
        }
        sp(app).edit().putString("status", status).apply()
        status
    }
}

/** Sends a fresh feed shortly after any calendar on the phone changes. */
class CalendarWatchJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            runBlocking { runCatching { WatchFeed.push(applicationContext) } }
            runCatching { NextUpBoardWidget.refresh(applicationContext) }
            schedule(applicationContext) // a content-triggered job fires once
            jobFinished(params, false)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = false

    companion object {
        private const val JOB_ID = 4202

        fun ensureScheduled(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            if (scheduler.getPendingJob(JOB_ID) == null) schedule(context)
        }

        fun schedule(context: Context) {
            if (!hasCalendarPermission(context)) return
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, CalendarWatchJob::class.java))
                .addTriggerContentUri(
                    JobInfo.TriggerContentUri(CalendarContract.CONTENT_URI, JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS),
                )
                .setTriggerContentUpdateDelay(5_000)
                .setTriggerContentMaxDelay(60_000)
                .build()
            runCatching { scheduler.schedule(job) }
        }
    }
}
