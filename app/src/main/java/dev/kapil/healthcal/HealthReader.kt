package dev.kapil.healthcal

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import kotlin.reflect.KClass

/** Reads sleep and exercise sessions from Health Connect on this device. */
object HealthReader {
    val SLEEP = HealthPermission.getReadPermission(SleepSessionRecord::class)
    val EXERCISE = HealthPermission.getReadPermission(ExerciseSessionRecord::class)
    const val BACKGROUND = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"
    const val HISTORY = "android.permission.health.READ_HEALTH_DATA_HISTORY"

    val ALL_PERMISSIONS: Set<String> = setOf(SLEEP, EXERCISE, BACKGROUND, HISTORY)

    fun sdkStatus(context: Context): Int = HealthConnectClient.getSdkStatus(context)

    suspend fun grantedPermissions(context: Context): Set<String> =
        HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions()

    suspend fun readSleep(context: Context, from: Instant, to: Instant): List<SleepSessionRecord> =
        readAll(context, SleepSessionRecord::class, from, to)

    suspend fun readExercise(context: Context, from: Instant, to: Instant): List<ExerciseSessionRecord> =
        readAll(context, ExerciseSessionRecord::class, from, to)

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
