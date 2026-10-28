package com.colehydro.wear

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.time.LocalDate
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Single source of truth on the watch.
 *
 * - Totals are per local calendar day. A new day starts at 0.
 * - Every tap is stored in [pendingQueue] until the phone confirms it.
 *   The queue entry id is the dedupe key: if a send retries, the phone
 *   must not write the same id twice to Health Connect.
 * - Units are millilitres internally. The UI converts to oz.
 *   1 US fl oz = 29.5735 ml. Default tap = 8 oz = 237 ml.
 */
private val Context.dataStore by preferencesDataStore("water")

@Serializable
data class WaterTap(
    val id: String,
    val ml: Int,
    val timestampMillis: Long,
)

object WaterPrefs {
    val DEFAULT_ML = intPreferencesKey("default_ml")
    val GOAL_ML = intPreferencesKey("goal_ml")
    val TODAY_DATE = stringPreferencesKey("today_date")
    val TODAY_ML = intPreferencesKey("today_ml")
    val PENDING_JSON = stringPreferencesKey("pending_json")
    val LAST_SYNCED_ID = stringPreferencesKey("last_synced_id")
    val LAST_SYNCED_ML = intPreferencesKey("last_synced_ml")
    val HC_TOTAL_ML = intPreferencesKey("hc_total_ml")
    val HC_TOTAL_DATE = stringPreferencesKey("hc_total_date")
}

class WaterRepository(private val context: Context) {

    companion object {
        const val DEFAULT_TAP_ML = 237 // 8 oz
        const val DEFAULT_GOAL_ML = 1893 // 64 oz
    }

    private fun today(): String = LocalDate.now().toString()

    /** Roll the day over, then return (todayMl, goalMl, defaultMl). */
    suspend fun snapshot(): Triple<Int, Int, Int> {
        val prefs = context.dataStore.data.first()
        if (prefs[WaterPrefs.TODAY_DATE] != today()) {
            context.dataStore.edit {
                it[WaterPrefs.TODAY_DATE] = today()
                it[WaterPrefs.TODAY_ML] = 0
                // Pending queue is NOT cleared on rollover: yesterday's
                // unsynced taps still need to reach Health Connect.
            }
        }
        val fresh = context.dataStore.data.first()
        return Triple(
            fresh[WaterPrefs.TODAY_ML] ?: 0,
            fresh[WaterPrefs.GOAL_ML] ?: DEFAULT_GOAL_ML,
            fresh[WaterPrefs.DEFAULT_ML] ?: DEFAULT_TAP_ML,
        )
    }

    suspend fun addTap(tap: WaterTap): Int {
        val (todayMl, _, _) = snapshot()
        val newTotal = todayMl + tap.ml
        context.dataStore.edit { prefs ->
            prefs[WaterPrefs.TODAY_ML] = newTotal
            val pending = readPending(prefs[WaterPrefs.PENDING_JSON]) + tap
            prefs[WaterPrefs.PENDING_JSON] = Json.encodeToString(pending)
        }
        return newTotal
    }

    suspend fun undoLastTap(): Int? {
        // Kept for Tile callers: returns the new watch total only
        // when a pending tap was actually removed, null otherwise
        // (already synced / nothing to undo) so callers can show
        // feedback instead of silently dropping the total.
        return when (undoLastTapResult()) {
            UndoResult.PendingRemoved -> context.dataStore.data.first()[WaterPrefs.TODAY_ML] ?: 0
            else -> null
        }
    }

    suspend fun pending(): List<WaterTap> =
        readPending(context.dataStore.data.first()[WaterPrefs.PENDING_JSON])

    suspend fun markSynced(ids: Set<String>) {
        context.dataStore.edit { prefs ->
            val pending = readPending(prefs[WaterPrefs.PENDING_JSON])
            val remaining = pending.filterNot { it.id in ids }
            prefs[WaterPrefs.PENDING_JSON] = Json.encodeToString(remaining)
            // Last synced, in pending order, for undo feedback: if the
            // last tap already synced, undo cannot remove it from the
            // watch alone (see undoLastTapResult).
            val lastSynced = pending.lastOrNull { it.id in ids }
            if (lastSynced != null) {
                prefs[WaterPrefs.LAST_SYNCED_ID] = lastSynced.id
                prefs[WaterPrefs.LAST_SYNCED_ML] = lastSynced.ml
            }
        }
    }

    /** Undo result: PendingRemoved = a pending tap was removed;
     *  AlreadySynced = no pending tap, last tap already reached the
     *  phone, so the watch must NOT silently drop its total;
     *  NothingToUndo = no pending and no synced tap on record. */
    enum class UndoResult { PendingRemoved, AlreadySynced, NothingToUndo }

    suspend fun undoLastTapResult(): UndoResult {
        val prefs = context.dataStore.data.first()
        val pending = readPending(prefs[WaterPrefs.PENDING_JSON])
        val last = pending.lastOrNull()
        if (last != null) {
            val newTotal = maxOf(0, (prefs[WaterPrefs.TODAY_ML] ?: 0) - last.ml)
            context.dataStore.edit {
                it[WaterPrefs.TODAY_ML] = newTotal
                it[WaterPrefs.PENDING_JSON] = Json.encodeToString(pending.dropLast(1))
            }
            return UndoResult.PendingRemoved
        }
        return if (prefs[WaterPrefs.LAST_SYNCED_ID] != null) UndoResult.AlreadySynced
        else UndoResult.NothingToUndo
    }

    /** HC total sent by the phone (see /hydro/total). Displayed
     *  today total = max(watch taps total, HC total + pending ml),
     *  so phone adds appear on the watch without double-counting
     *  pending taps or going backwards on a stale HC total. */
    suspend fun setHcTotalMl(ml: Int) {
        context.dataStore.edit {
            it[WaterPrefs.HC_TOTAL_ML] = ml
            it[WaterPrefs.HC_TOTAL_DATE] = today()
        }
    }

    suspend fun displayedTodayMl(): Int {
        val prefs = context.dataStore.data.first()
        val watchTotal = prefs[WaterPrefs.TODAY_ML] ?: 0
        val pendingMl = readPending(prefs[WaterPrefs.PENDING_JSON]).sumOf { it.ml }
        val hcTotal = if (prefs[WaterPrefs.HC_TOTAL_DATE] == today()) {
            prefs[WaterPrefs.HC_TOTAL_ML] ?: 0
        } else 0
        return maxOf(watchTotal, hcTotal + pendingMl)
    }

    suspend fun displayedSnapshot(): Triple<Int, Int, Int> {
        val (watchTotal, goalMl, defaultMl) = snapshot()
        val displayed = displayedTodayMl()
        // snapshot() already rolled the day over; displayedTodayMl
        // re-reads the same day, so displayed >= watchTotal here.
        return Triple(displayed, goalMl, defaultMl)
    }

    suspend fun setDefaultMl(ml: Int) {
        context.dataStore.edit { it[WaterPrefs.DEFAULT_ML] = ml }
    }

    suspend fun setGoalMl(ml: Int) {
        context.dataStore.edit { it[WaterPrefs.GOAL_ML] = ml }
    }

    /** Clear today's total and the unsynced tap queue, for a phone
     *  "clear today's log" that also resets the watch. Used only by
     *  the /hydro/reset message; it does not touch the goal. */
    suspend fun resetToday() {
        context.dataStore.edit {
            it[WaterPrefs.TODAY_DATE] = today()
            it[WaterPrefs.TODAY_ML] = 0
            it[WaterPrefs.PENDING_JSON] = Json.encodeToString(emptyList<WaterTap>())
            it.remove(WaterPrefs.LAST_SYNCED_ID)
            it.remove(WaterPrefs.LAST_SYNCED_ML)
            it[WaterPrefs.HC_TOTAL_ML] = 0
            it[WaterPrefs.HC_TOTAL_DATE] = today()
        }
    }

    private fun readPending(json: String?): List<WaterTap> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            Json.decodeFromString(json)
        } catch (_: Exception) {
            emptyList()
        }
    }
}

fun mlToOz(ml: Int): Int = (ml / 29.5735).roundToInt()
fun ozToMl(oz: Int): Int = (oz * 29.5735).roundToInt()
