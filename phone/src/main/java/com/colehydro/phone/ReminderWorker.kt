package com.colehydro.phone

import android.app.NotificationManager
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.time.LocalTime

/**
 * Periodic water reminder. Only posts inside the user's reminder window
 * (default 8am–8pm); outside the window it does nothing and returns success
 * so WorkManager keeps the schedule. Scheduling is driven from the
 * Reminders tab: enabled -> unique periodic work, disabled -> cancelled.
 */
class ReminderWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (!HealthConnectWriter.getRemindersEnabled(ctx)) return Result.success()
        val hour = LocalTime.now().hour
        val start = HealthConnectWriter.getReminderStartHour(ctx)
        val end = HealthConnectWriter.getReminderEndHour(ctx)
        if (hour < start || hour >= end) return Result.success()

        // v12 item 2: don't nag once today's goal is already met.
        // todayTotalMl returns 0 on a read failure, so a failed read
        // does NOT suppress the reminder — it posts as before.
        if (HealthConnectWriter.todayTotalMl(ctx) >= HealthConnectWriter.getGoalMl(ctx)) return Result.success()

        val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(
            1001,
            NotificationHelper.buildReminderNotification(
                ctx, 1001,
                "Time for water",
                "Take a sip — you're working toward today's goal."
            )
        )
        return Result.success()
    }
}
