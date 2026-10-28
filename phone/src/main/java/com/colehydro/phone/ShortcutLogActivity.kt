package com.colehydro.phone

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Launcher shortcut target (v13, item 4): long-press the Water
 * Logged icon -> "+8 oz" -> this transparent activity logs 237 ml
 * to Health Connect, shows a Toast, and finishes immediately.
 * No full app UI opens. Static shortcut is defined in
 * res/xml/shortcuts.xml and wired in the manifest via
 * android.app.shortcuts meta-data on MainActivity.
 */
class ShortcutLogActivity : Activity() {
    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scope.launch {
            val wrote = try { HealthConnectWriter.writeManualMl(this@ShortcutLogActivity, 237) } catch (_: Exception) { false }
            if (wrote) {
                vibrateClick(this@ShortcutLogActivity)
                HealthConnectWriter.addToCachedTotalMl(this@ShortcutLogActivity, 237)
                WaterWidgetHelper.updateAll(this@ShortcutLogActivity)
            }
            android.os.Handler(mainLooper).post {
                try {
                    Toast.makeText(
                        this@ShortcutLogActivity,
                        if (wrote) "+8 oz logged" else "Couldn't log — open Water Logged",
                        Toast.LENGTH_SHORT
                    ).show()
                } catch (_: Exception) { }
                finish()
            }
        }
    }
}
