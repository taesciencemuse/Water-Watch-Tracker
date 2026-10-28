package com.colehydro.phone

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Quick Settings Tile (v13, item 3): a tile in the notification
 * shade that logs +8 oz with one tap, no app open.
 *
 * The user adds it manually: swipe down twice, tap Edit / pencil,
 * drag the "Water +8" tile into the active tiles. Android does not
 * let an app add its own QS tile silently; on Android 13+ the app
 * could prompt via requestAddTileService, but manual add is the
 * standard path and is documented in the delivery notes.
 *
 * Label shows today's total / goal, refreshed in onStartListening
 * (when the QS panel opens) and after every tap. State is
 * INACTIVE (this is a button, not a toggle); ACTIVE would imply
 * an on/off state that does not exist here.
 */
class WaterQuickTileService : TileService() {
    private val scope = CoroutineScope(Dispatchers.IO)

    private fun oz(ml: Int) = (ml / 29.5735).roundToInt()

    private suspend fun todayTotalAndGoal(): Pair<Int, Int> {
        val agg = try { HealthConnectWriter.todayTotalMl(this) } catch (_: Exception) { 0 }
        val entries = try { HealthConnectWriter.todayTotalViaEntries(this) } catch (_: Exception) { 0 }
        val cached = try { HealthConnectWriter.getCachedTotalMl(this) } catch (_: Exception) { 0 }
        val goal = HealthConnectWriter.getGoalMl(this)
        return maxOf(agg, entries, cached) to goal
    }

    private fun refreshLabel() {
        scope.launch {
            val (total, goal) = todayTotalAndGoal()
            qsTile?.let { tile ->
                tile.label = "Water ${oz(total)} / ${oz(goal)}"
                tile.state = Tile.STATE_INACTIVE
                tile.updateTile()
            }
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        refreshLabel()
    }

    override fun onClick() {
        super.onClick()
        scope.launch {
            val wrote = try { HealthConnectWriter.writeManualMl(this@WaterQuickTileService, 237) } catch (_: Exception) { false }
            if (wrote) {
                vibrateClick(this@WaterQuickTileService)
                HealthConnectWriter.addToCachedTotalMl(this@WaterQuickTileService, 237)
                WaterWidgetHelper.updateAll(this@WaterQuickTileService)
                refreshLabel()
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    try { Toast.makeText(this@WaterQuickTileService, "+8 oz logged", Toast.LENGTH_SHORT).show() } catch (_: Exception) { }
                }
            } else {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    try { Toast.makeText(this@WaterQuickTileService, "Open Water Logged to grant Health Connect", Toast.LENGTH_SHORT).show() } catch (_: Exception) { }
                }
            }
        }
    }
}
