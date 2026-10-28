package com.colehydro.phone

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Periodic widget fallback (v13 fix for "widget always says 0").
 * Manual updates fire on phone adds, watch taps, and notification
 * +8s, but if any of those fail silently, or water is logged from
 * another Health Connect app, the widget would stay stale forever
 * with updatePeriodMillis=0 and no other trigger. This worker
 * re-reads Health Connect and updates every placed widget every
 * 15 minutes (WorkManager's minimum periodic interval), so the
 * worst case is 15 minutes stale, never all day.
 */
class WidgetUpdateWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        return try {
            WaterWidgetHelper.updateAll(applicationContext)
            Result.success()
        } catch (e: Exception) {
            android.util.Log.e("WidgetUpdate", "update failed", e)
            Result.retry()
        }
    }
}
