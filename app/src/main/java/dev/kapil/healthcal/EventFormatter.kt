package dev.kapil.healthcal

import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.SleepSessionRecord
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What a calendar event should look like for one Health Connect record. */
data class DesiredEvent(
    val tag: String,          // stable id, e.g. "sleep:<healthConnectId>"
    val calendarId: Long,
    val startMs: Long,
    val endMs: Long,
    val title: String,
    val description: String,
)

object EventFormatter {
    /** Every event we write ends with "[hc:<tag>]" so re-runs can find it again. */
    const val TAG_PREFIX = "[hc:"

    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())

    fun sleep(r: SleepSessionRecord, calendarId: Long, sourceLabel: String): DesiredEvent {
        val offset = r.startZoneOffset ?: systemOffset(r.startTime)
        val inBed = Duration.between(r.startTime, r.endTime)
        val stages = r.stages.sortedBy { it.startTime }

        fun total(vararg types: Int): Duration = stages
            .filter { it.stage in types }
            .fold(Duration.ZERO) { acc, s -> acc.plus(Duration.between(s.startTime, s.endTime)) }

        val deepSegments = stages.filter { it.stage == SleepSessionRecord.STAGE_TYPE_DEEP }
        val deep = total(SleepSessionRecord.STAGE_TYPE_DEEP)
        val rem = total(SleepSessionRecord.STAGE_TYPE_REM)
        val light = total(SleepSessionRecord.STAGE_TYPE_LIGHT)
        val unstaged = total(SleepSessionRecord.STAGE_TYPE_SLEEPING)
        val awake = total(
            SleepSessionRecord.STAGE_TYPE_AWAKE,
            SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
            SleepSessionRecord.STAGE_TYPE_OUT_OF_BED,
        )
        val asleep = deep.plus(rem).plus(light).plus(unstaged)
        val headline = if (!asleep.isZero) asleep else inBed

        val tag = "sleep:${r.metadata.id}"
        val notes = buildString {
            appendLine("Bedtime ${time(r.startTime, offset)}, woke ${time(r.endTime, offset)}")
            appendLine("Time in bed: ${dur(inBed)}")
            if (stages.isEmpty()) {
                appendLine("No sleep stages recorded.")
            } else {
                appendLine("Asleep: ${dur(asleep)}")
                appendLine()
                val n = deepSegments.size
                appendLine("Deep: ${dur(deep)} in $n ${if (n == 1) "segment" else "segments"}")
                deepSegments.forEach { s ->
                    appendLine(
                        "  • ${time(s.startTime, offset)} – ${time(s.endTime, offset)} " +
                            "(${dur(Duration.between(s.startTime, s.endTime))})"
                    )
                }
                appendLine("REM: ${dur(rem)}")
                appendLine("Light: ${dur(light)}")
                if (!unstaged.isZero) appendLine("Asleep, no stage: ${dur(unstaged)}")
                appendLine("Awake: ${dur(awake)}")
            }
            if (!r.notes.isNullOrBlank()) {
                appendLine()
                appendLine(r.notes)
            }
            appendLine()
            appendLine("Source: $sourceLabel")
            append("$TAG_PREFIX$tag]")
        }

        return DesiredEvent(
            tag = tag,
            calendarId = calendarId,
            startMs = r.startTime.toEpochMilli(),
            endMs = r.endTime.toEpochMilli(),
            title = spanTitle("Sleep", headline, r.startTime, r.endTime, offset),
            description = notes,
        )
    }

    fun exercise(r: ExerciseSessionRecord, calendarId: Long, sourceLabel: String): DesiredEvent {
        val offset = r.startZoneOffset ?: systemOffset(r.startTime)
        val type = exerciseName(r.exerciseType)
        val length = Duration.between(r.startTime, r.endTime)

        val tag = "ex:${r.metadata.id}"
        val notes = buildString {
            appendLine("$type, ${time(r.startTime, offset)} – ${time(r.endTime, offset)}")
            appendLine("Duration: ${dur(length)}")
            val title = r.title
            if (!title.isNullOrBlank() && title != type) appendLine("Title: $title")
            if (!r.notes.isNullOrBlank()) appendLine(r.notes)
            appendLine()
            appendLine("Source: $sourceLabel")
            append("$TAG_PREFIX$tag]")
        }

        return DesiredEvent(
            tag = tag,
            calendarId = calendarId,
            startMs = r.startTime.toEpochMilli(),
            endMs = r.endTime.toEpochMilli(),
            title = spanTitle(type, length, r.startTime, r.endTime, offset),
            description = notes,
        )
    }

    private val TITLE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

    /**
     * Title for anything with a start and end:
     * "Sleep | Dur: 7 hours 20 mins | Start: 11:02 pm End: 6:22 am"
     * For sleep, Dur is time asleep; Start/End are bedtime and wake time.
     */
    fun spanTitle(name: String, length: Duration, start: Instant, end: Instant, offset: ZoneOffset? = null): String =
        "$name | Dur: ${longDur(length)} | Start: ${titleTime(start, offset)} End: ${titleTime(end, offset)}"

    /** Title for a moment in time: "Arrived home | At: 11:21 pm" */
    fun markerTitle(name: String, at: Instant): String = "$name | At: ${titleTime(at, null)}"

    /** "7 hours 20 mins", "45 mins", "1 hour", "1 hour 1 min" */
    fun longDur(d: Duration): String {
        val total = d.toMinutes().coerceAtLeast(0)
        val h = total / 60
        val m = total % 60
        val hours = if (h == 1L) "1 hour" else "$h hours"
        val mins = if (m == 1L) "1 min" else "$m mins"
        return when {
            h == 0L -> mins
            m == 0L -> hours
            else -> "$hours $mins"
        }
    }

    private fun titleTime(t: Instant, offset: ZoneOffset?): String {
        val text = if (offset != null) TITLE_TIME.format(t.atOffset(offset)) else TITLE_TIME.format(t.atZone(ZoneId.systemDefault()))
        return text.lowercase(Locale.US)
    }

    private fun systemOffset(t: Instant): ZoneOffset = ZoneId.systemDefault().rules.getOffset(t)

    private fun time(t: Instant, offset: ZoneOffset): String = TIME.format(t.atOffset(offset))

    internal fun dur(d: Duration): String {
        val minutes = d.toMinutes()
        val h = minutes / 60
        val m = minutes % 60
        return if (h > 0) "${h}h ${"%02d".format(m)}m" else "${m}m"
    }

    private val EXERCISE_NAMES: Map<Int, String> = mapOf(
        ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT to "Workout",
        ExerciseSessionRecord.EXERCISE_TYPE_BADMINTON to "Badminton",
        ExerciseSessionRecord.EXERCISE_TYPE_BASEBALL to "Baseball",
        ExerciseSessionRecord.EXERCISE_TYPE_BASKETBALL to "Basketball",
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING to "Bike ride",
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY to "Indoor bike",
        ExerciseSessionRecord.EXERCISE_TYPE_BOOT_CAMP to "Boot camp",
        ExerciseSessionRecord.EXERCISE_TYPE_BOXING to "Boxing",
        ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS to "Calisthenics",
        ExerciseSessionRecord.EXERCISE_TYPE_CRICKET to "Cricket",
        ExerciseSessionRecord.EXERCISE_TYPE_DANCING to "Dance",
        ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL to "Elliptical",
        ExerciseSessionRecord.EXERCISE_TYPE_EXERCISE_CLASS to "Exercise class",
        ExerciseSessionRecord.EXERCISE_TYPE_GOLF to "Golf",
        ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING to "HIIT",
        ExerciseSessionRecord.EXERCISE_TYPE_HIKING to "Hike",
        ExerciseSessionRecord.EXERCISE_TYPE_MARTIAL_ARTS to "Martial arts",
        ExerciseSessionRecord.EXERCISE_TYPE_PILATES to "Pilates",
        ExerciseSessionRecord.EXERCISE_TYPE_ROCK_CLIMBING to "Climbing",
        ExerciseSessionRecord.EXERCISE_TYPE_ROWING to "Rowing",
        ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE to "Rowing machine",
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING to "Run",
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL to "Treadmill run",
        ExerciseSessionRecord.EXERCISE_TYPE_SKIING to "Skiing",
        ExerciseSessionRecord.EXERCISE_TYPE_SNOWBOARDING to "Snowboarding",
        ExerciseSessionRecord.EXERCISE_TYPE_SOCCER to "Soccer",
        ExerciseSessionRecord.EXERCISE_TYPE_SQUASH to "Squash",
        ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING to "Stair climb",
        ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE to "Stair machine",
        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING to "Strength training",
        ExerciseSessionRecord.EXERCISE_TYPE_STRETCHING to "Stretching",
        ExerciseSessionRecord.EXERCISE_TYPE_SURFING to "Surfing",
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER to "Open-water swim",
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL to "Pool swim",
        ExerciseSessionRecord.EXERCISE_TYPE_TABLE_TENNIS to "Table tennis",
        ExerciseSessionRecord.EXERCISE_TYPE_TENNIS to "Tennis",
        ExerciseSessionRecord.EXERCISE_TYPE_VOLLEYBALL to "Volleyball",
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING to "Walk",
        ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING to "Weightlifting",
        ExerciseSessionRecord.EXERCISE_TYPE_WHEELCHAIR to "Wheelchair",
        ExerciseSessionRecord.EXERCISE_TYPE_YOGA to "Yoga",
    )

    private fun exerciseName(type: Int): String = EXERCISE_NAMES[type] ?: "Workout"
}
