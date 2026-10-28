package com.colehydro.wear

import android.content.ComponentName
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Phone -> watch sync messages.
 *
 * "/hydro/goal" with the goal in millilitres as a plain UTF-8 integer
 * whenever the goal is saved in phone Settings. Saves it to the watch
 * DataStore (GOAL_ML), so the Tile, complication and watch quick-add
 * all use the phone's goal instead of the 64 oz scaffold default.
 * Out-of-range values are ignored.
 *
 * "/hydro/reset" (empty payload) from the phone's "clear today's log":
 * clears the watch's today total and its unsynced tap queue, so a
 * cleared day doesn't reappear when pending taps flush, and the watch
 * doesn't keep showing the old total until midnight.
 *
 * After either message, it requests a Tile and complication refresh
 * so the new state shows without waiting for the next tap.
 */
class GoalMessageService : WearableListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            "/hydro/goal" -> {
                val ml = String(event.data, Charsets.UTF_8).toIntOrNull() ?: return
                if (ml !in 473..5915) return // 16–200 oz, same range as phone Settings
                scope.launch {
                    WaterRepository(this@GoalMessageService).setGoalMl(ml)
                    requestUpdates()
                }
            }
            "/hydro/reset" -> {
                scope.launch {
                    WaterRepository(this@GoalMessageService).resetToday()
                    requestUpdates()
                }
            }
        }
    }

    private fun requestUpdates() {
        try {
            TileService.getUpdater(this@GoalMessageService)
                .requestUpdate(WaterTileService::class.java)
        } catch (_: Exception) {
        }
        try {
            ComplicationDataSourceUpdateRequester.create(
                this@GoalMessageService,
                ComponentName(this@GoalMessageService, HydrationComplicationService::class.java)
            ).requestUpdateAll()
        } catch (_: Exception) {
        }
    }
}
