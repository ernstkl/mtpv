package io.github.ernstkl.mtpv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import io.github.ernstkl.mtpv.ui.MainActivity

/**
 * Keeps the process running while a role is active, so Android does not freeze it when the app
 * leaves the screen. Without it a secondary's server stops answering and a controller stops
 * receiving page turns. The roles themselves live in [MtpvApp]; this service only holds the
 * foreground state and its notification.
 */
class KeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val role = (application as MtpvApp).role.value
        if (role == Role.NONE) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification(role), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        return START_NOT_STICKY
    }

    private fun notification(role: Role): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "MTPV running", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val text = when (role) {
            Role.CONTROLLER -> "Controller: keeps the secondaries in sync"
            else -> "Secondary: waiting for page changes from the controller"
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle("MTPV is active")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "running"
        private const val NOTIFICATION_ID = 1

        /** Starts or stops the service to match [role]. Call while the app is in the foreground. */
        fun update(context: Context, role: Role) {
            val intent = Intent(context, KeepAliveService::class.java)
            if (role == Role.NONE) context.stopService(intent) else context.startForegroundService(intent)
        }
    }
}
