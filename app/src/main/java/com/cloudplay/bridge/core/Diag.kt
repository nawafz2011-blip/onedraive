package com.cloudplay.bridge.core

import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.atomic.AtomicLong

/**
 * Diagnostic log the user can send from the home screen: timings of emulator reads,
 * downloads and errors, plus a summary every second while a game is open.
 */
object Diag {
    private const val MAX_LINES = 30_000
    private val lines = ArrayDeque<String>()
    private val start = System.currentTimeMillis()

    @Volatile
    var pool: ThreadPoolExecutor? = null

    // Counters for the per-second summary.
    val reads = AtomicLong()
    val readBytes = AtomicLong()
    val misses = AtomicLong()
    val missMs = AtomicLong()
    val requests = AtomicLong()
    val requestMs = AtomicLong()

    fun log(message: String) {
        val t = (System.currentTimeMillis() - start) / 1000.0
        val line = String.format(Locale.US, "%9.2f %s", t, message)
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
    }

    private var last = LongArray(7)

    /** Called once a second while a game file is open. */
    fun summary() {
        val now = longArrayOf(
            Stats.bytes.get(), reads.get(), readBytes.get(), misses.get(), missMs.get(),
            requests.get(), requestMs.get(),
        )
        val d = LongArray(now.size) { now[it] - last[it] }
        last = now
        val p = pool
        log(
            String.format(
                Locale.US,
                "SUM dl=%.1fMB/s reads=%d readMB=%.1f misses=%d avgMissMs=%d requests=%d avgReqMs=%d " +
                    "waiting=%d open=%d busy=%d queued=%d errors=%d",
                d[0] / 1048576.0, d[1], d[2] / 1048576.0, d[3], if (d[3] > 0) d[4] / d[3] else 0,
                d[5], if (d[5] > 0) d[6] / d[5] else 0,
                Stats.waiting.get(), Stats.openFiles.get(), p?.activeCount ?: -1, p?.queue?.size ?: -1,
                Stats.errors.get(),
            )
        )
    }

    fun dump(versionName: String): String = buildString {
        append("CloudPlay ").append(versionName)
        append(" · Android ").append(Build.VERSION.RELEASE).append(" · ").append(Build.MODEL).append('\n')
        append("Saved ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())).append('\n')
        synchronized(lines) { lines.forEach { append(it).append('\n') } }
    }
}
