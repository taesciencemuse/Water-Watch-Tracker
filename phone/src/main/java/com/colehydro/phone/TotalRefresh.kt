package com.colehydro.phone

import android.content.Context
import android.content.Intent

/**
 * Live refresh (Oct 30): background writers (notification +8, watch
 * taps, widget taps, launcher shortcut) call [send] after a successful
 * Health Connect write. MainActivity registers for this broadcast
 * while started and refreshes its total on receipt, and also on
 * resume — so an open app no longer sits at a stale total until the
 * user navigates to another tab. The broadcast is restricted to this
 * app's package, so no other app can trigger it.
 */
object TotalRefresh {
    const val ACTION = "com.colehydro.phone.ACTION_REFRESH_TOTAL"

    fun send(context: Context) {
        try {
            context.sendBroadcast(Intent(ACTION).setPackage(context.packageName))
        } catch (_: Exception) {
            // Refresh is best-effort: the write already succeeded.
        }
    }
}
