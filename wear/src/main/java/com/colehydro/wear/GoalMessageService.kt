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
 * Phone -> watch goal sync.
 *
 * The phone sends path "/hydro/goal" with the goal in millilitres as a
 * plain UTF-8 integer whenever the goal is saved in phone Settings.
 * This service saves it to the watch DataStore (GOAL_ML), so the Tile,
 * complication and watch quick-add all use the phone's goal instead of
 * the 64 oz scaffold default. Out-of-range values are ignored.
 * After saving, it requests a Tile and complication refresh so the
 * new goal shows without waiting for the next tap.
 */
class GoalMessageService : WearableListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != "/hydro/goal") return
        val ml = String(event.data, Charsets.UTF_8).toIntOrNull() ?: return
        if (ml !in 473..5915) return // 16–200 oz, same range as phone Settings
        scope.launch {
            WaterRepository(this@GoalMessageService).setGoalMl(ml)
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
}
