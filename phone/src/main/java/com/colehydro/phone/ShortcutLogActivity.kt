package com.colehydro.phone

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Launcher shortcut target (v13, item 4): long-press the Water
 * Logged icon -> "+8 oz" -> this activity logs 237 ml to Health
 * Connect, shows a Toast, and finishes.
 *
 * Flash fix (Oct 30, Cole approved): this used to be a translucent
 * activity that stayed alive while the write ran, and tapping the
 * shortcut flashed a brief black band across the top of the screen
 * (caught on video Oct 30). It is now a NoDisplay activity with no
 * window at all, so there is nothing to flash: onCreate launches
 * the write on an independent IO scope using the application
 * context, then finishes immediately. The Toast and the widget /
 * live-refresh updates use the application context too, so they
 * still land after the activity is gone. Static shortcut is defined
 * in res/xml/shortcuts.xml and wired in the manifest via
 * android.app.shortcuts meta-data on MainActivity.
 */
class ShortcutLogActivity : Activity() {
    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appContext = applicationContext
        scope.launch {
            val wrote = try { HealthConnectWriter.writeManualMl(appContext, 237) } catch (_: Exception) { false }
            if (wrote) {
                vibrateClick(appContext)
                HealthConnectWriter.addToCachedTotalMl(appContext, 237)
                WaterWidgetHelper.updateAll(appContext)
                // Live refresh (Oct 30): an open phone app re-reads now.
                TotalRefresh.send(appContext)
            }
            Handler(Looper.getMainLooper()).post {
                try {
                    Toast.makeText(
                        appContext,
                        if (wrote) "+8 oz logged" else "Couldn't log — open Water Logged",
                        Toast.LENGTH_SHORT
                    ).show()
                } catch (_: Exception) { }
            }
        }
        // NoDisplay activities must finish in onCreate; the write
        // above runs on an independent scope, not the activity.
        finish()
    }
}
