package com.colehydro.wear

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Haptics for watch taps (v13, expressive buttons + direct log).
 * VIBRATE is a normal permission, no runtime prompt needed.
 * EFFECT_CLICK (API 29+) is the standard short UI tick; older
 * watches fall back to a 20 ms one-shot. All calls are guarded
 * so a missing vibrator can never crash a log.
 */
fun vibrateClick(context: Context) {
    try {
        val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        if (vibrator == null || !vibrator.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= 29) {
            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(20)
        }
    } catch (_: Exception) { }
}
