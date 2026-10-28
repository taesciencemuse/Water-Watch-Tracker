package com.colehydro.phone

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Notification actions for Water Logged reminders (v12, item 1).
 *
 * - Tapping the notification itself opens MainActivity (a normal
 *   Activity PendingIntent, set as contentIntent in the helper below).
 * - The "+8 oz" action button sends ACTION_LOG_8 here. This receiver
 *   writes 8 oz straight to Health Connect, cancels the notification,
 *   and refreshes the home widget, so logging from a reminder never
 *   needs the app open.
 *
 * If Health Connect permission is missing, the write fails silently
 * here — the user can open the app from the notification tap instead.
 */
object NotificationHelper {
    const val ACTION_LOG_8 = "com.colehydro.phone.ACTION_LOG_8"
    const val EXTRA_NOTIFICATION_ID = "notification_id"

    fun openAppPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun log8PendingIntent(context: Context, notificationId: Int): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = ACTION_LOG_8
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
        }
        return PendingIntent.getBroadcast(
            context, notificationId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun buildReminderNotification(
        context: Context,
        notificationId: Int,
        title: String,
        text: String
    ): android.app.Notification {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            android.app.NotificationChannel(
                "water_reminders",
                "Water reminders",
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
        return NotificationCompat.Builder(context, "water_reminders")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openAppPendingIntent(context))
            .addAction(0, "+8 oz", log8PendingIntent(context, notificationId))
            .setAutoCancel(true)
            .build()
    }
}

class NotificationActionReceiver : BroadcastReceiver() {
    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != NotificationHelper.ACTION_LOG_8) return
        val notificationId = intent.getIntExtra(NotificationHelper.EXTRA_NOTIFICATION_ID, 1001)
        val pending = goAsync()
        scope.launch {
            try {
                HealthConnectWriter.writeManualMl(context, 237)
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.cancel(notificationId)
                WaterWidgetHelper.updateAll(context)
            } catch (_: Exception) {
            } finally {
                pending.finish()
            }
        }
    }
}
