package com.colehydro.wear

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.time.LocalDate
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
        // Undo is local-only for v1: it removes the last tap from today's
        // total and from the pending queue if it has not synced yet.
        // If the tap already reached Health Connect, v1 does not try to
        // delete the Health Connect record — that is a v2 addition, and
        // deleting the wrong record would be worse than a stale total.
        val prefs = context.dataStore.data.first()
        val pending = readPending(prefs[WaterPrefs.PENDING_JSON])
        val last = pending.lastOrNull() ?: return null
        val newTotal = maxOf(0, (prefs[WaterPrefs.TODAY_ML] ?: 0) - last.ml)
        context.dataStore.edit {
            it[WaterPrefs.TODAY_ML] = newTotal
            it[WaterPrefs.PENDING_JSON] = Json.encodeToString(pending.dropLast(1))
        }
        return newTotal
    }

    suspend fun pending(): List<WaterTap> =
        readPending(context.dataStore.data.first()[WaterPrefs.PENDING_JSON])

    suspend fun markSynced(ids: Set<String>) {
        context.dataStore.edit { prefs ->
            val remaining = readPending(prefs[WaterPrefs.PENDING_JSON])
                .filterNot { it.id in ids }
            prefs[WaterPrefs.PENDING_JSON] = Json.encodeToString(remaining)
        }
    }

    suspend fun setDefaultMl(ml: Int) {
        context.dataStore.edit { it[WaterPrefs.DEFAULT_ML] = ml }
    }

    suspend fun setGoalMl(ml: Int) {
        context.dataStore.edit { it[WaterPrefs.GOAL_ML] = ml }
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

fun mlToOz(ml: Int): Int = (ml / 29.5735).toInt()
fun ozToMl(oz: Int): Int = (oz * 29.5735).toInt()
