package com.cloudplay.bridge.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.cloudplay.bridge.R
import com.cloudplay.bridge.core.Diag
import com.cloudplay.bridge.ui.MainActivity

/**
 * Keeps CloudPlay's process running at foreground priority.
 *
 * A diagnostic log showed the whole process stopping for 217 s while an emulator was
 * mid-load: Android's cached-app freezer froze CloudPlay (it was "in the background"),
 * and the emulator's reads, which reach us through the file bridge rather than binder,
 * waited until something woke it. A foreground service is never frozen.
 */
class StreamService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "CloudPlay شغّال", NotificationManager.IMPORTANCE_MIN)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_cloud)
            .setContentTitle("CloudPlay جاهز")
            .setContentText("يخلي ألعاب وندرايف تشتغل بدون توقف")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(ID, notification)
            }
            Diag.log("SERVICE foreground")
        } catch (e: Exception) {
            Diag.log("SERVICE startForeground failed: ${e.javaClass.simpleName} ${e.message}")
            stopSelf()
        }
        return START_STICKY
    }

    companion object {
        private const val CHANNEL = "service"
        private const val ID = 2

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, StreamService::class.java))
            } catch (e: Exception) {
                // Not allowed from the background right now; the next app open retries.
                Diag.log("SERVICE start refused: ${e.javaClass.simpleName}")
            }
        }
    }
}

/** Starts the service after a reboot, so it's running before any emulator opens a game. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            StreamService.start(context)
        }
    }
}
