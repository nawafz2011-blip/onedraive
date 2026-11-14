package com.cloudplay.bridge.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.cloudplay.bridge.R
import java.util.Locale
import kotlin.concurrent.thread

/**
 * While an emulator has a game open, shows a silent notification with live speed and
 * whether the game is waiting on CloudPlay, so it can be checked without leaving the game.
 */
object LiveNotifier {
    private const val CHANNEL = "live"
    private const val ID = 1

    @Volatile
    private var running = false

    @Synchronized
    fun start(context: Context) {
        if (running) return
        running = true
        val app = context.applicationContext
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "نشاط CloudPlay", NotificationManager.IMPORTANCE_LOW)
        )
        thread(name = "cloudplay-live", isDaemon = true) {
            var last = Stats.bytes.get()
            var idleSince = 0L
            try {
                while (true) {
                    Thread.sleep(1000)
                    val now = Stats.bytes.get()
                    val speed = now - last
                    last = now
                    if (Stats.openFiles.get() == 0) {
                        if (idleSince == 0L) idleSince = System.currentTimeMillis()
                        if (System.currentTimeMillis() - idleSince > 10_000) break
                    } else {
                        idleSince = 0L
                    }
                    manager.notify(ID, build(app, speed, now))
                }
            } catch (e: Exception) {
                // Missing notification permission or similar: nothing to show.
            } finally {
                runCatching { manager.cancel(ID) }
                running = false
            }
        }
    }

    private fun build(context: Context, speed: Long, total: Long): Notification {
        val waiting = Stats.waiting.get() > 0
        val status = if (waiting) "⏳ اللعبة تنتظر بيانات من وندرايف" else "✅ اللعبة ما تنتظر CloudPlay"
        val errors = Stats.errors.get()
        val text = buildString {
            append(status)
            append("\nالسرعة: ").append(mb(speed)).append("/ث · المحمّل: ").append(mb(total))
            if (errors > 0) append("\nأخطاء: ").append(errors)
        }
        return Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_cloud)
            .setContentTitle(status)
            .setContentText("السرعة: ${mb(speed)}/ث")
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
    }

    private fun mb(bytes: Long) = String.format(Locale.US, "%.1f ميقا", bytes / 1048576.0)
}
