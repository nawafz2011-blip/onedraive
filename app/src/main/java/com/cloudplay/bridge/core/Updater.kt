package com.cloudplay.bridge.core

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.cloudplay.bridge.BuildConfig
import com.cloudplay.bridge.ui.UpdateReceiver
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Downloads the newest CloudPlay.apk from this repo's `latest` release and installs it over the app. */
object Updater {
    private const val BASE = "https://github.com/${BuildConfig.UPDATE_REPO}/releases/download/latest"

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** versionCode of the newest published build. */
    fun latestVersion(): Int {
        val request = Request.Builder().url("$BASE/version.txt?t=${System.currentTimeMillis()}").build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            return response.body!!.string().trim().toIntOrNull() ?: throw IOException("Bad version file")
        }
    }

    fun download(context: Context, onProgress: (Float) -> Unit): File {
        val target = File(context.cacheDir, "update.apk")
        val request = Request.Builder().url("$BASE/CloudPlay.apk").build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body!!
            val total = body.contentLength()
            body.byteStream().use { input ->
                target.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        if (total > 0) onProgress(done.toFloat() / total)
                    }
                }
            }
        }
        return target
    }

    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("CloudPlay.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val callback = PendingIntent.getBroadcast(
                context, sessionId, Intent(context, UpdateReceiver::class.java), flags
            )
            session.commit(callback.intentSender)
        }
    }
}
