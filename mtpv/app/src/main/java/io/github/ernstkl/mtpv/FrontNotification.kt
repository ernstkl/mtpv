package io.github.ernstkl.mtpv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import io.github.ernstkl.mtpv.ui.MainActivity

/**
 * "Tap to show" notification when MTPV came (back) to the front on another screen of the setup.
 * Android lets no app start an activity from the background (the foreground service does not help),
 * so a heads-up notification is as far as it goes; the tap brings MTPV to the front.
 */
object FrontNotification {
    private const val CHANNEL = "front"
    private const val NOTIFICATION_ID = 2
    private const val TIMEOUT_MS = 10 * 60_000L

    /** Set on the notification's intent: coming to the front this way wakes no other screen. */
    const val EXTRA_FROM_NOTIFICATION = "io.github.ernstkl.mtpv.FROM_FRONT_NOTIFICATION"

    fun show(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Show MTPV", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "When MTPV comes to the front on another screen"
            }
        )
        val open = PendingIntent.getActivity(
            context, 1, Intent(context, MainActivity::class.java).putExtra(EXTRA_FROM_NOTIFICATION, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle("MTPV: tap to show")
            .setContentText("MTPV came to the front on another screen")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setTimeoutAfter(TIMEOUT_MS)
            .setCategory(Notification.CATEGORY_REMINDER)
            .build()
        nm.notify(NOTIFICATION_ID, n)
    }

    /** MTPV is on screen anyway. */
    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }
}
