package com.colehydro.phone

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Volume
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.request.AggregateRequest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The ONLY Health Connect writer in the project.
 *
 * - Writes HydrationRecord: one record per drink, with volume + time.
 * - Dedupe: v1 keeps a small on-device set of already-written tap ids
 *   in SharedPreferences. A tap id that is already in the set is NOT
 *   written again, even if the watch retries the send. This is what
 *   makes the watch's queue safe.
 *   TODO: move the seen-id set to DataStore and prune ids older
 *   than 7 days in Android Studio.
 * - Reads today's total via VOLUME_TOTAL aggregation, so the phone
 *   UI can show the Health Connect truth, not just its own log.
 */
object HealthConnectWriter {

    val PERMISSIONS = setOf(
        HealthPermission.getReadPermission(HydrationRecord::class),
        HealthPermission.getWritePermission(HydrationRecord::class),
    )

    fun isAvailable(context: Context): Boolean =
        HealthConnectClient.getSdkStatus(context) ==
            HealthConnectClient.SDK_AVAILABLE

    suspend fun writeTapIfNew(
        context: Context,
        tapId: String,
        ml: Int,
        timestampMillis: Long,
    ): Boolean {
        val prefs = context.getSharedPreferences("hydro_seen", Context.MODE_PRIVATE)
        if (prefs.getBoolean(tapId, false)) return false // already written

        val client = HealthConnectClient.getOrCreate(context)
        val instant = Instant.ofEpochMilli(timestampMillis)
        val zone = ZoneId.systemDefault().rules.getOffset(instant)
        client.insertRecords(
            listOf(
                HydrationRecord(
                    startTime = instant,
                    startZoneOffset = zone,
                    endTime = instant,
                    endZoneOffset = zone,
                    volume = Volume.milliliters(ml.toDouble()),
                    metadata = Metadata.manualEntry()
                )
            )
        )
        prefs.edit().putBoolean(tapId, true).apply()
        return true
    }

    suspend fun todayTotalMl(context: Context): Int {
        return try {
            val client = HealthConnectClient.getOrCreate(context)
            val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant()
            val end = Instant.now()
            val result = client.aggregate(
                AggregateRequest(
                    metrics = setOf(HydrationRecord.VOLUME_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(start, end)
                )
            )
            val volume = result[HydrationRecord.VOLUME_TOTAL]
            volume?.inMilliliters?.toInt() ?: 0
        } catch (_: Exception) {
            0
        }
    }
}
