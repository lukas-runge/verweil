package de.lukasrunge.verweil

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import de.lukasrunge.verweil.ui.MainActivity

/** The app's notifications: the ongoing one while tracking, and alerts when something needs the user. */
object Notifications {
    const val TRACKING_ID = 1
    private const val INTERRUPTED_ID = 2
    private const val UPLOAD_REFUSED_ID = 3

    private const val TRACKING_CHANNEL = "tracking"
    private const val ALERTS_CHANNEL = "alerts"

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(
                    TRACKING_CHANNEL,
                    context.getString(R.string.channel_tracking),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = context.getString(R.string.channel_tracking_description) },
                NotificationChannel(
                    ALERTS_CHANNEL,
                    context.getString(R.string.channel_alerts),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = context.getString(R.string.channel_alerts_description) },
            ),
        )
    }

    /** The ongoing notification of the foreground service. */
    fun tracking(context: Context, title: String, stop: PendingIntent): Notification =
        NotificationCompat.Builder(context, TRACKING_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.icon_background))
            .setContentTitle(title)
            .setContentText(context.getString(R.string.notification_tracking_text))
            .setContentIntent(openApp(context))
            .addAction(R.drawable.ic_stop, context.getString(R.string.action_stop_tracking), stop)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()

    /** Tracking should run but does not, and Android would not let it restart from the background. */
    fun trackingInterrupted(context: Context, reason: String) = alert(
        context,
        INTERRUPTED_ID,
        context.getString(R.string.notification_interrupted_title),
        reason,
    )

    fun clearTrackingInterrupted(context: Context) = NotificationManagerCompat.from(context).cancel(INTERRUPTED_ID)

    fun uploadRefused(context: Context) = alert(
        context,
        UPLOAD_REFUSED_ID,
        context.getString(R.string.notification_upload_refused_title),
        context.getString(R.string.notification_upload_refused_text),
    )

    fun clearUploadRefused(context: Context) = NotificationManagerCompat.from(context).cancel(UPLOAD_REFUSED_ID)

    @SuppressLint("MissingPermission") // areNotificationsEnabled covers the Android 13 permission.
    private fun alert(context: Context, id: Int, title: String, text: String) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        val notification = NotificationCompat.Builder(context, ALERTS_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.icon_background))
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        manager.notify(id, notification)
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )
}
