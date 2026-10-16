package dev.kapil.healthcal

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.aggregate.AggregationResultGroupedByPeriod
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import kotlin.reflect.KClass

/** Reads sleep and exercise sessions, and daily steps and calories, from Health Connect on this device. */
object HealthReader {
    val SLEEP = HealthPermission.getReadPermission(SleepSessionRecord::class)
    val EXERCISE = HealthPermission.getReadPermission(ExerciseSessionRecord::class)
    const val BACKGROUND = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"
    const val HISTORY = "android.permission.health.READ_HEALTH_DATA_HISTORY"

    // For the home-screen widget
    val STEPS = HealthPermission.getReadPermission(StepsRecord::class)
    val CALORIES = HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class)

    val ALL_PERMISSIONS: Set<String> = setOf(SLEEP, EXERCISE, BACKGROUND, HISTORY, STEPS, CALORIES)

    fun sdkStatus(context: Context): Int = HealthConnectClient.getSdkStatus(context)

    suspend fun grantedPermissions(context: Context): Set<String> =
        HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions()

    suspend fun readSleep(context: Context, from: Instant, to: Instant): List<SleepSessionRecord> =
        readAll(context, SleepSessionRecord::class, from, to)

    suspend fun readExercise(context: Context, from: Instant, to: Instant): List<ExerciseSessionRecord> =
        readAll(context, ExerciseSessionRecord::class, from, to)

    /** Steps per local day, [first] to [last] inclusive. Days with no data are absent. */
    suspend fun dailySteps(context: Context, first: LocalDate, last: LocalDate): Map<LocalDate, Long> {
        val out = HashMap<LocalDate, Long>()
        for (g in dailyGroups(context, StepsRecord.COUNT_TOTAL, first, last)) {
            g.result[StepsRecord.COUNT_TOTAL]?.let { out[g.startTime.toLocalDate()] = it }
        }
        return out
    }

    /** Total calories burned per local day, [first] to [last] inclusive. */
    suspend fun dailyCalories(context: Context, first: LocalDate, last: LocalDate): Map<LocalDate, Long> {
        val out = HashMap<LocalDate, Long>()
        for (g in dailyGroups(context, TotalCaloriesBurnedRecord.ENERGY_TOTAL, first, last)) {
            g.result[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.let { out[g.startTime.toLocalDate()] = it.inKilocalories.toLong() }
        }
        return out
    }

    /** One metric per request, so a missing permission for one does not hide the other. */
    private suspend fun dailyGroups(
        context: Context,
        metric: AggregateMetric<*>,
        first: LocalDate,
        last: LocalDate,
    ): List<AggregationResultGroupedByPeriod> =
        HealthConnectClient.getOrCreate(context).aggregateGroupByPeriod(
            AggregateGroupByPeriodRequest(
                metrics = setOf(metric),
                timeRangeFilter = TimeRangeFilter.between(first.atStartOfDay(), last.plusDays(1).atStartOfDay()),
                timeRangeSlicer = Period.ofDays(1),
            )
        )

    private suspend fun <T : Record> readAll(
        context: Context,
        type: KClass<T>,
        from: Instant,
        to: Instant,
    ): List<T> {
        val client = HealthConnectClient.getOrCreate(context)
        val out = ArrayList<T>()
        var token: String? = null
        do {
            val response = client.readRecords(
                ReadRecordsRequest(
                    type,
                    TimeRangeFilter.between(from, to),
                    pageToken = token,
                )
            )
            out.addAll(response.records)
            token = response.pageToken
        } while (!token.isNullOrEmpty())
        return out
    }
}
