package com.colehydro.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.view.Gravity
import android.widget.Toast
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

    // Error Toast only (Oct 30, Cole: success Toasts removed).
    // The Oct 29 "bottom Toast" attempt did not work in practice
    // — on this watch the Toasts still landed in the middle, not
    // at the bottom, despite BOTTOM gravity — and they are not
    // needed: a successful tap updates the complication
    // immediately, which is the feedback. A failed tap still
    // needs a Toast, otherwise it would fail silently.
    private fun showBottomToast(context: Context, text: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                val toast = Toast.makeText(context, text, Toast.LENGTH_SHORT)
                val density = context.resources.displayMetrics.density
                toast.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, (24 * density).toInt())
                toast.show()
            } catch (_: Exception) { }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_LOG_DEFAULT) return
        val pending = goAsync()
        scope.launch {
            try {
                // ReceiverCallNotAllowedException fix (Oct 28, v19):
                // onReceive's context is a restricted Receiver context
                // that cannot bind to services. TileService.getUpdater
                // and the Wearable clients both bind, so passing the
                // receiver context made the post-log Tile refresh throw
                // AFTER addTap had already succeeded — hence "total
                // moves, but Couldn't log: ReceiverCallNotAllowed..."
                // Use applicationContext for everything downstream.
                // Refresh failures are also now non-fatal: they must
                // never masquerade as a log failure.
                val appContext = context.applicationContext
                val repo = WaterRepository(appContext)
                val (_, _, defaultMl) = repo.snapshot()
                val tap = WaterTap(
                    id = UUID.randomUUID().toString(),
                    ml = defaultMl,
                    timestampMillis = System.currentTimeMillis()
                )
                vibrateClick(appContext)
                repo.addTap(tap)
                if (SyncSender.sendTap(appContext, tap)) repo.markSynced(setOf(tap.id))
                try {
                    WaterTileService.requestTileUpdate(appContext)
                } catch (_: Exception) { }
                HydrationComplicationService.requestAllUpdates(appContext)
                // No success Toast: the complication update above
                // is the feedback (Oct 30, Cole).
            } catch (e: Exception) {
                showBottomToast(context, "Couldn't log: ${e.javaClass.simpleName}")
            } finally {
                pending.finish()
            }
        }
    }
}
