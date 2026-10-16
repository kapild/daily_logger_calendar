package dev.kapil.healthcal

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

/**
 * Reads the widget's week straight from Health Connect (not from the calendar,
 * which can lag behind) and keeps the last result so taps redraw instantly.
 */
object WidgetData {
    private const val TAG = "DailyLoggerWidget"
    private const val PREFS = "health_widget"
    private const val KEY_WEEK = "week"
    private const val FRESH_MS = 15 * 60_000L

    /** The cached week if it is recent and from today, otherwise a fresh read. */
    suspend fun get(context: Context, force: Boolean = false): Week? {
        val cached = cached(context)
        val today = LocalDate.now()
        val fresh = cached != null && cached.days.last() == today &&
            System.currentTimeMillis() - cached.updatedAt < FRESH_MS
        if (fresh && !force) return cached
        return load(context) ?: cached
    }

    private suspend fun load(context: Context): Week? = try {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val first = today.minusDays(6)
        val granted = HealthReader.grantedPermissions(context)

        // A night that ended on the first day began the evening before.
        val from = first.minusDays(1).atStartOfDay(zone).toInstant()
        val to = today.plusDays(1).atStartOfDay(zone).toInstant()

        val sleeps = if (HealthReader.SLEEP in granted) {
            HealthReader.readSleep(context, from, to).map {
                SleepSpan(
                    start = it.startTime.toEpochMilli(),
                    end = it.endTime.toEpochMilli(),
                    asleepMin = EventFormatter.asleepOrInBed(it).toMinutes(),
                )
            }
        } else emptyList()
        val workouts = if (HealthReader.EXERCISE in granted) {
            HealthReader.readExercise(context, first.atStartOfDay(zone).toInstant(), to).map {
                WorkoutSpan(
                    start = it.startTime.toEpochMilli(),
                    end = it.endTime.toEpochMilli(),
                    name = it.title?.takeIf { t -> t.isNotBlank() } ?: EventFormatter.exerciseName(it.exerciseType),
                )
            }
        } else emptyList()
        val steps = if (HealthReader.STEPS in granted) HealthReader.dailySteps(context, first, today) else null
        val calories = if (HealthReader.CALORIES in granted) HealthReader.dailyCalories(context, first, today) else null

        WeekBuilder.build(today, zone, sleeps, workouts, steps, calories, System.currentTimeMillis())
            .also { save(context, it) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Could not read Health Connect", e)
        null
    }

    private fun cached(context: Context): Week? = try {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_WEEK, null)?.let(::decode)
    } catch (e: Exception) {
        null
    }

    private fun save(context: Context, w: Week) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_WEEK, encode(w)).apply()
    }

    private fun encode(w: Week): String = JSONObject().apply {
        put("days", JSONArray(w.days.map { it.toString() }))
        put("sleep", JSONArray(w.sleep.map { n ->
            n?.let { JSONArray(listOf(it.asleepMin, it.bedMs, it.wakeMs)) } ?: JSONObject.NULL
        }))
        put("workoutMin", JSONArray(w.workoutMin))
        put("workoutNames", JSONArray(w.workoutNames))
        w.steps?.let { put("steps", JSONArray(it.map { v -> v ?: JSONObject.NULL })) }
        w.calories?.let { put("calories", JSONArray(it.map { v -> v ?: JSONObject.NULL })) }
        put("updatedAt", w.updatedAt)
    }.toString()

    private fun decode(s: String): Week {
        val o = JSONObject(s)
        fun longs(key: String): List<Long?>? = o.optJSONArray(key)?.let { a ->
            (0 until a.length()).map { if (a.isNull(it)) null else a.getLong(it) }
        }
        val days = o.getJSONArray("days").let { a -> (0 until a.length()).map { LocalDate.parse(a.getString(it)) } }
        val sleep = o.getJSONArray("sleep").let { a ->
            (0 until a.length()).map { i ->
                if (a.isNull(i)) null else a.getJSONArray(i).let { Night(it.getLong(0), it.getLong(1), it.getLong(2)) }
            }
        }
        val names = o.getJSONArray("workoutNames").let { a -> (0 until a.length()).map { a.getString(it) } }
        return Week(
            days = days,
            sleep = sleep,
            workoutMin = longs("workoutMin")!!.map { it ?: 0L },
            workoutNames = names,
            steps = longs("steps"),
            calories = longs("calories"),
            updatedAt = o.getLong("updatedAt"),
        )
    }
}
