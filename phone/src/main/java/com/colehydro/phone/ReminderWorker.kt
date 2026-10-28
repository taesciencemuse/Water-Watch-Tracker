package com.colehydro.phone

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
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

        val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            "water_reminders",
            "Water reminders",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        manager.createNotificationChannel(channel)
        val notification = NotificationCompat.Builder(ctx, "water_reminders")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Time for water")
            .setContentText("Take a sip — you're working toward today's goal.")
            .setAutoCancel(true)
            .build()
        manager.notify(1001, notification)
        return Result.success()
    }
}
