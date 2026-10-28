package com.colehydro.wear

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Complication direct log (v13, item 6): tapping the watch-face
 * complication sends a broadcast here, NOT an activity intent.
 * Logs the watch default amount (+8 oz unless changed), queues
 * it for the phone like any other tap, then refreshes the Tile
 * and the complication so the new total shows on the watch face.
 * MainActivity (quick-add screen) is still available from the
 * watch app list; the complication no longer opens it.
 */
class LogWaterReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_LOG_DEFAULT = "com.colehydro.wear.ACTION_LOG_DEFAULT"
    }

    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_LOG_DEFAULT) return
        val pending = goAsync()
        scope.launch {
            try {
                val repo = WaterRepository(context)
                val (_, _, defaultMl) = repo.snapshot()
                val tap = WaterTap(
                    id = UUID.randomUUID().toString(),
                    ml = defaultMl,
                    timestampMillis = System.currentTimeMillis()
                )
                vibrateClick(context)
                repo.addTap(tap)
                SyncSender.sendTap(context, tap)
                WaterTileService.requestTileUpdate(context)
                try {
                    ComplicationDataSourceUpdateRequester.create(
                        context,
                        ComponentName(context, HydrationComplicationService::class.java)
                    ).requestUpdateAll()
                } catch (_: Exception) { }
                // Toast must run on the main thread; post it via a
                // main-thread handler if we are still on IO here.
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    try { Toast.makeText(context, "+${mlToOz(defaultMl)} oz logged", Toast.LENGTH_SHORT).show() } catch (_: Exception) { }
                }
            } catch (_: Exception) {
            } finally {
                pending.finish()
            }
        }
    }
}
