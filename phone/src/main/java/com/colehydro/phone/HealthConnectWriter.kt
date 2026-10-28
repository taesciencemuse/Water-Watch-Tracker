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
        // Clear-today race: a tap from before the last clear must
        // not reappear if it flushes after the clear.
        if (timestampMillis < getClearTimeMillis(context)) return false
        val prefs = context.getSharedPreferences("hydro_seen", Context.MODE_PRIVATE)
        if (prefs.contains(tapId)) return false // already written (bool or timestamp form)
        pruneSeenIds(context)

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
        // Timestamped seen entry, so pruneSeenIds can drop
        // entries older than 7 days (old boolean entries are
        // converted to "now" on first prune, then pruned 7 days
        // after upgrade).
        prefs.edit().putLong(tapId, System.currentTimeMillis()).apply()
        return true
    }

    /** Prune hydro_seen tap ids older than 7 days. A retried tap
     *  after 7 days is still protected by Health Connect's own
     *  clientRecordId dedupe, so pruning cannot double-write. */
    fun pruneSeenIds(context: Context) {
        try {
            val prefs = context.getSharedPreferences("hydro_seen", Context.MODE_PRIVATE)
            val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
            val edit = prefs.edit()
            var changed = false
            for ((key, value) in prefs.all) {
                if (key == "last_phone_record_id") continue
                when (value) {
                    is Long -> if (value < cutoff) { edit.remove(key); changed = true }
                    is Boolean -> { edit.putLong(key, System.currentTimeMillis()); changed = true }
                }
            }
            if (changed) edit.apply()
        } catch (_: Exception) { }
    }

    fun hasLastPhoneAdd(context: Context): Boolean {
        return context.getSharedPreferences("hydro_seen", Context.MODE_PRIVATE)
            .getString("last_phone_record_id", null) != null
    }

    /** Delete a Health Connect record by clientRecordId (watch tap
     *  undo path, if a future ack protocol is added). Not used by
     *  watch undo today: watch undo is pending-only, with feedback
     *  when already synced, because there is no phone ack yet. */
    suspend fun deleteByClientRecordId(context: Context, clientRecordId: String): Boolean {
        return try {
            val client = HealthConnectClient.getOrCreate(context)
            client.deleteRecords(
                HydrationRecord::class,
                recordIdsList = emptyList(),
                clientRecordIdsList = listOf(clientRecordId)
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Daily goal, stored locally in this app only — Health Connect
     *  has no hydration-goal API to pull a goal from. */
    fun getGoalMl(context: Context): Int {
        return context.getSharedPreferences("hydro_prefs", Context.MODE_PRIVATE)
            .getInt("goal_ml", 1893)
    }

    fun setClearTimeMillis(context: Context, millis: Long) {
        context.getSharedPreferences("hydro_prefs", Context.MODE_PRIVATE)
            .edit().putLong("clear_time_millis", millis).apply()
    }

    fun getClearTimeMillis(context: Context): Long {
        return context.getSharedPreferences("hydro_prefs", Context.MODE_PRIVATE)
            .getLong("clear_time_millis", 0L)
    }

    fun setGoalMl(context: Context, ml: Int) {
        context.getSharedPreferences("hydro_prefs", Context.MODE_PRIVATE)
            .edit().putInt("goal_ml", ml).apply()
    }

    suspend fun todayTotalMl(context: Context): Int {
        return todayTotalMlOrNull(context) ?: 0
    }

    /** Null on failure, so callers can tell "couldn't read" apart
     *  from a true 0. todayTotalMl() keeps the old 0-on-failure
     *  behaviour for widget / worker fallbacks that already use
     *  maxOf() with other sources. */
    suspend fun todayTotalMlOrNull(context: Context): Int? {
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
        } catch (e: Exception) {
            android.util.Log.e("HealthConnect", "todayTotalMl failed", e)
            null
        }
    }

    /** Today total via readRecords, summed (not aggregate).
     *  The History tab uses this path and it works in the app, so
     *  the widget uses it as a second opinion: if aggregate returns
     *  0 in the widget context but entries exist, entries win. */
    suspend fun todayTotalViaEntries(context: Context): Int {
        val zone = ZoneId.systemDefault()
        val start = LocalDate.now(zone).atStartOfDay(zone).toInstant()
        return readHydrationEntries(context, start, Instant.now()).sumOf { it.ml }
    }

    /** App's last known today total, for the widget fallback.
     *  refreshTotal() in the app writes this (its aggregate read
     *  works in the Activity context). The widget reads it when
     *  its own Health Connect reads come back 0, so it never
     *  shows 0 after the app has logged water today. */
    fun getCachedTotalMl(context: Context): Int {
        val prefs = context.getSharedPreferences("hydro_prefs", Context.MODE_PRIVATE)
        val today = LocalDate.now(ZoneId.systemDefault()).toString()
        return if (prefs.getString("widget_total_date", null) == today) {
            prefs.getInt("widget_total_ml", 0)
        } else 0
    }

    fun setCachedTotalMl(context: Context, ml: Int) {
        val today = LocalDate.now(ZoneId.systemDefault()).toString()
        context.getSharedPreferences("hydro_prefs", Context.MODE_PRIVATE).edit()
            .putString("widget_total_date", today)
            .putInt("widget_total_ml", ml).apply()
    }

    /** Increment today's cached total by a just-logged amount.
     *  Used by widget +8, notification +8, and watch taps, which
     *  know the amount they just wrote even if a Health Connect
     *  re-read in that background context returns 0. */
    fun addToCachedTotalMl(context: Context, ml: Int) {
        setCachedTotalMl(context, getCachedTotalMl(context) + ml)
    }

    data class HydrationEntry(val timeMillis: Long, val ml: Int)

    suspend fun readHydrationEntries(context: Context, start: Instant, end: Instant): List<HydrationEntry> {
        return readHydrationEntriesOrNull(context, start, end) ?: emptyList()
    }

    /** Null on failure (vs empty = truly no entries). Paginates
     *  with pageToken, so a 365-day streak read is not silently
     *  truncated at Health Connect's page size. */
    suspend fun readHydrationEntriesOrNull(context: Context, start: Instant, end: Instant): List<HydrationEntry>? {
        return try {
            val client = HealthConnectClient.getOrCreate(context)
            val out = mutableListOf<HydrationEntry>()
            var pageToken: String? = null
            do {
                val response = client.readRecords(
                    ReadRecordsRequest(
                        HydrationRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(start, end),
                        pageToken = pageToken
                    )
                )
                out += response.records.map {
                    HydrationEntry(it.endTime.toEpochMilli(), it.volume.inMilliliters.toInt())
                }
                pageToken = response.pageToken
            } while (!pageToken.isNullOrBlank())
            out.sortedBy { it.timeMillis }
        } catch (e: Exception) {
            android.util.Log.e("HealthConnect", "readHydrationEntries failed", e)
            null
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

    /** Last drink today, as epoch millis, or null if none yet today.
     *  Read from Health Connect records, so phone adds and synced
     *  watch taps both count. Used for the Today "Last logged" line. */
    suspend fun lastEntryTimeMillisToday(context: Context): Long? {
        return lastEntryTimeMillisTodayResult(context).getOrNull()
    }

    /** Result: failure is distinct from "no entries today" (null
     *  success), so the UI can show "couldn't read" instead of
     *  "No drinks logged today yet" on a failed read. */
    suspend fun lastEntryTimeMillisTodayResult(context: Context): Result<Long?> {
        return try {
            val zone = ZoneId.systemDefault()
            val start = LocalDate.now(zone).atStartOfDay(zone).toInstant()
            val entries = readHydrationEntriesOrNull(context, start, Instant.now())
                ?: return Result.failure(Exception("Health Connect read failed"))
            Result.success(entries.maxOfOrNull { it.timeMillis })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Streak: consecutive days hitting [goalMl], counting backwards.
     *  Today counts only if it already hit the goal; if today is still
     *  under goal, the streak is the run ending yesterday, so an
     *  unfinished today does not break it. Reads up to 365 days of
     *  records in one read, so this is one Health Connect call. */
    suspend fun streakDays(context: Context, goalMl: Int): Int {
        return streakDaysOrNull(context, goalMl) ?: 0
    }

    suspend fun streakDaysOrNull(context: Context, goalMl: Int): Int? {
        return try {
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            val entries = readHydrationEntriesOrNull(
                context,
                today.minusDays(364).atStartOfDay(zone).toInstant(),
                today.plusDays(1).atStartOfDay(zone).toInstant()
            ) ?: return null
            val byDay = entries.groupBy {
                Instant.ofEpochMilli(it.timeMillis).atZone(zone).toLocalDate()
            }.mapValues { (_, list) -> list.sumOf { it.ml } }
            var streak = 0
            var d = today
            if ((byDay[d] ?: 0) >= goalMl) {
                streak = 1
                d = d.minusDays(1)
            } else {
                d = d.minusDays(1)
            }
            while ((byDay[d] ?: 0) >= goalMl) {
                streak++
                d = d.minusDays(1)
            }
            streak
        } catch (_: Exception) {
            null
        }
    }

    /** Delete all of today's Hydration records from Health Connect.
     *  Returns how many were deleted. Also clears the phone's last-add
     *  undo pointer, so Undo can't try to delete an already-deleted
     *  record. Watch-tap dedupe (hydro_seen) is intentionally kept:
     *  if a watch tap re-flushes after a clear, dedupe stops it from
     *  reappearing in Health Connect. */
    suspend fun deleteTodayRecords(context: Context): Int {
        // Clear timestamp first: any watch tap timestamped before
        // this moment must not resurrect the cleared day when it
        // later flushes (see WatchMessageService clear check).
        setClearTimeMillis(context, Instant.now().toEpochMilli())
        setCachedTotalMl(context, 0)
        return try {
            val client = HealthConnectClient.getOrCreate(context)
            val zone = ZoneId.systemDefault()
            val start = LocalDate.now(zone).atStartOfDay(zone).toInstant()
            val response = client.readRecords(
                ReadRecordsRequest(
                    HydrationRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, Instant.now())
                )
            )
            val ids = response.records.map { it.metadata.id }.filter { it.isNotBlank() }
            if (ids.isNotEmpty()) {
                client.deleteRecords(
                    HydrationRecord::class,
                    recordIdsList = ids,
                    clientRecordIdsList = emptyList()
                )
            }
            context.getSharedPreferences("hydro_seen", Context.MODE_PRIVATE)
                .edit().remove("last_phone_record_id").apply()
            ids.size
        } catch (_: Exception) {
            -1
        }
    }
}
