package com.colehydro.phone

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.metadata.Metadata
import android.health.connect.HealthConnectException
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Volume
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.request.AggregateRequest
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
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

    /**
     * Manual add from the phone main screen: writes one HydrationRecord
     * straight to Health Connect (no watch queue involved) and remembers
     * its record id so the next Undo can delete exactly that record.
     * Returns false if the write failed (e.g. permission not granted).
     */
    /** Returns null on success, or the exact exception message on failure. */
    suspend fun writeManualMlResult(context: Context, ml: Int): String? {
        return try {
            val client = HealthConnectClient.getOrCreate(context)
            val endInstant = Instant.now()
            val startInstant = endInstant.minusSeconds(1)
            val startZone = ZoneId.systemDefault().rules.getOffset(startInstant)
            val endZone = ZoneId.systemDefault().rules.getOffset(endInstant)
            val response = client.insertRecords(
                listOf(
                    HydrationRecord(
                        startTime = startInstant,
                        startZoneOffset = startZone,
                        endTime = endInstant,
                        endZoneOffset = endZone,
                        volume = Volume.milliliters(ml.toDouble()),
                        metadata = Metadata.manualEntry()
                    )
                )
            )
            val id = response.recordIdsList.firstOrNull()
            if (id != null) {
                context.getSharedPreferences("hydro_seen", Context.MODE_PRIVATE)
                    .edit().putString("last_phone_record_id", id).apply()
            }
            null
        } catch (e: Exception) {
            val code = (e as? HealthConnectException)?.errorCode
            if (code != null) "${e.javaClass.simpleName} (errorCode=$code): ${e.message}"
            else "${e.javaClass.simpleName}: ${e.message}"
        }
    }

    suspend fun writeManualMl(context: Context, ml: Int): Boolean {
        return writeManualMlResult(context, ml) == null
    }

    /** Deletes the most recent phone manual add, if there is one. */
    suspend fun undoLastPhoneAdd(context: Context): Boolean {
        return try {
            val prefs = context.getSharedPreferences("hydro_seen", Context.MODE_PRIVATE)
            val id = prefs.getString("last_phone_record_id", null) ?: return false
            val client = HealthConnectClient.getOrCreate(context)
            client.deleteRecords(
                HydrationRecord::class,
                recordIdsList = listOf(id),
                clientRecordIdsList = emptyList()
            )
            prefs.edit().remove("last_phone_record_id").apply()
            true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun writeTapIfNew(
        context: Context,
        tapId: String,
        ml: Int,
        timestampMillis: Long,
    ): Boolean {
        val prefs = context.getSharedPreferences("hydro_seen", Context.MODE_PRIVATE)
        if (prefs.getBoolean(tapId, false)) return false // already written

        val client = HealthConnectClient.getOrCreate(context)
        val endInstant = Instant.ofEpochMilli(timestampMillis)
        // HydrationRecord is an interval record: a zero-length interval
        // (start == end) is rejected by Health Connect validation, so
        // the drink spans the 1 second ending at the tap time.
        val startInstant = endInstant.minusSeconds(1)
        val startZone = ZoneId.systemDefault().rules.getOffset(startInstant)
        val endZone = ZoneId.systemDefault().rules.getOffset(endInstant)
        client.insertRecords(
            listOf(
                HydrationRecord(
                    startTime = startInstant,
                    startZoneOffset = startZone,
                    endTime = endInstant,
                    endZoneOffset = endZone,
                    volume = Volume.milliliters(ml.toDouble()),
                    // Watch tap: actively recorded on a watch, with the
                    // tap UUID as clientRecordId so Health Connect itself
                    // dedupes a retried tap, not just our seen-list.
                    metadata = Metadata.activelyRecorded(
                        device = Device(type = Device.TYPE_WATCH),
                        clientRecordId = tapId
                    )
                )
            )
        )
        prefs.edit().putBoolean(tapId, true).apply()
        return true
    }

    /** Daily goal, stored locally in this app only — Health Connect
     *  has no hydration-goal API to pull a goal from. */
    fun getGoalMl(context: Context): Int {
        return context.getSharedPreferences("hydro_prefs", Context.MODE_PRIVATE)
            .getInt("goal_ml", 1893)
    }

    fun setGoalMl(context: Context, ml: Int) {
        context.getSharedPreferences("hydro_prefs", Context.MODE_PRIVATE)
            .edit().putInt("goal_ml", ml).apply()
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

    data class HydrationEntry(val timeMillis: Long, val ml: Int)

    suspend fun readHydrationEntries(context: Context, start: Instant, end: Instant): List<HydrationEntry> {
        return try {
            val client = HealthConnectClient.getOrCreate(context)
            val response = client.readRecords(
                ReadRecordsRequest(
                    HydrationRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, end)
                )
            )
            response.records.map {
                HydrationEntry(it.endTime.toEpochMilli(), it.volume.inMilliliters.toInt())
            }.sortedBy { it.timeMillis }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Daily totals for [startDate]..[endDate] inclusive, in system zone. */
    suspend fun dailyTotals(context: Context, startDate: LocalDate, endDate: LocalDate): List<Pair<LocalDate, Int>> {
        val zone = ZoneId.systemDefault()
        val start = startDate.atStartOfDay(zone).toInstant()
        val end = endDate.plusDays(1).atStartOfDay(zone).toInstant()
        val entries = readHydrationEntries(context, start, end)
        val byDay = entries.groupBy {
            Instant.ofEpochMilli(it.timeMillis).atZone(zone).toLocalDate()
        }.mapValues { (_, list) -> list.sumOf { it.ml } }
        val out = mutableListOf<Pair<LocalDate, Int>>()
        var d = startDate
        while (!d.isAfter(endDate)) {
            out.add(d to (byDay[d] ?: 0))
            d = d.plusDays(1)
        }
        return out
    }

    /** Monthly totals for the given months, oldest first. */
    suspend fun monthlyTotals(context: Context, months: List<YearMonth>): List<Pair<YearMonth, Int>> {
        if (months.isEmpty()) return emptyList()
        val zone = ZoneId.systemDefault()
        val start = months.first().atDay(1).atStartOfDay(zone).toInstant()
        val end = months.last().plusMonths(1).atDay(1).atStartOfDay(zone).toInstant()
        val entries = readHydrationEntries(context, start, end)
        val byMonth = entries.groupBy {
            val ld = Instant.ofEpochMilli(it.timeMillis).atZone(zone).toLocalDate()
            YearMonth.of(ld.year, ld.month)
        }.mapValues { (_, list) -> list.sumOf { it.ml } }
        return months.map { it to (byMonth[it] ?: 0) }
    }

    // --- Watch: last tap received from the watch, for the Watch tab ---
    fun saveLastWatchTap(context: Context, ml: Int, timeMillis: Long) {
        context.getSharedPreferences("hydro_prefs", Context.MODE_PRIVATE).edit()
            .putLong("last_watch_tap_millis", timeMillis)
            .putInt("last_watch_tap_ml", ml).apply()
    }

    fun getLastWatchTap(context: Context): Pair<Long, Int>? {
        val prefs = context.getSharedPreferences("hydro_prefs", Context.MODE_PRIVATE)
        val millis = prefs.getLong("last_watch_tap_millis", 0L)
        return if (millis == 0L) null else millis to prefs.getInt("last_watch_tap_ml", 0)
    }

    // --- Reminders: stored prefs only; scheduling lives in ReminderWorker ---
    fun getRemindersEnabled(context: Context): Boolean =
        context.getSharedPreferences("hydro_prefs", Context.MODE_PRIVATE).getBoolean("reminders_enabled", false)

    fun setRemindersEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences("hydro_prefs", Context.MODE_PRIVATE).edit().putBoolean("reminders_enabled", enabled).apply()
    }

    fun getReminderIntervalMinutes(context: Context): Int =
        context.getSharedPreferences("hydro_prefs", Context.MODE_PRIVATE).getInt("reminder_interval_minutes", 60)

    fun setReminderIntervalMinutes(context: Context, minutes: Int) {
        context.getSharedPreferences("hydro_prefs", Context.MODE_PRIVATE).edit().putInt("reminder_interval_minutes", minutes).apply()
    }

    fun getReminderStartHour(context: Context): Int =
        context.getSharedPreferences("hydro_prefs", Context.MODE_PRIVATE).getInt("reminder_start_hour", 8)

    fun getReminderEndHour(context: Context): Int =
        context.getSharedPreferences("hydro_prefs", Context.MODE_PRIVATE).getInt("reminder_end_hour", 20)

    fun setReminderWindow(context: Context, startHour: Int, endHour: Int) {
        context.getSharedPreferences("hydro_prefs", Context.MODE_PRIVATE).edit()
            .putInt("reminder_start_hour", startHour)
            .putInt("reminder_end_hour", endHour).apply()
    }
}
