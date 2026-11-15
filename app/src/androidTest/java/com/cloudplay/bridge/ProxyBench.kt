package com.cloudplay.bridge

import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.system.Os
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Random
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Measures how fast the platform's proxy file descriptor bridge (what CloudPlay hands to
 * emulators) can deliver data that is already in memory, versus a plain local file.
 * Results go to logcat under the BENCH tag.
 */
@RunWith(AndroidJUnit4::class)
class ProxyBench {
    private val size = 256L * 1024 * 1024
    private val pattern = ByteArray(512 * 1024) { it.toByte() }
    private val requests = AtomicInteger()
    private val maxRequest = AtomicInteger()
    private val requested = AtomicLong()

    private fun log(msg: String) = Log.i("BENCH", msg)

    @Test
    fun bench() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val storage = ctx.getSystemService(StorageManager::class.java)
        val thread = HandlerThread("bench").apply { start() }
        val callback = object : ProxyFileDescriptorCallback() {
            override fun onGetSize() = size
            override fun onRead(offset: Long, length: Int, data: ByteArray): Int {
                requests.incrementAndGet()
                requested.addAndGet(length.toLong())
                maxRequest.accumulateAndGet(length) { a, b -> maxOf(a, b) }
                val n = minOf(length.toLong(), size - offset).toInt()
                var done = 0
                while (done < n) {
                    val w = ((offset + done) % pattern.size).toInt()
                    val c = minOf(n - done, pattern.size - w)
                    System.arraycopy(pattern, w, data, done, c)
                    done += c
                }
                return n
            }
            override fun onRelease() {}
        }
        val proxy = storage.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, callback, Handler(thread.looper))

        val local = File(ctx.cacheDir, "bench.bin")
        local.outputStream().use { out -> repeat((size / pattern.size).toInt()) { out.write(pattern) } }
        val localFd = ParcelFileDescriptor.open(local, ParcelFileDescriptor.MODE_READ_ONLY)

        for ((name, fd) in listOf("proxy" to proxy, "local" to localFd)) {
            for (buf in listOf(4 * 1024, 64 * 1024, 1024 * 1024)) {
                reset()
                val mb = sequential(fd, buf)
                log("$name SEQ buf=${buf / 1024}KB ${"%.1f".format(mb)} MB/s requests=${requests.get()} maxReq=${maxRequest.get()} avgReq=${avg()}")
            }
            for (buf in listOf(4 * 1024, 64 * 1024)) {
                reset()
                val ops = random(fd, buf)
                log("$name RAND buf=${buf / 1024}KB ${"%.0f".format(ops)} reads/s requests=${requests.get()} maxReq=${maxRequest.get()}")
            }
        }
        proxy.close()
        localFd.close()
        thread.quitSafely()
        local.delete()
    }

    private fun reset() {
        requests.set(0); maxRequest.set(0); requested.set(0)
    }

    private fun avg() = if (requests.get() > 0) requested.get() / requests.get() else 0

    private fun sequential(fd: ParcelFileDescriptor, buf: Int): Double {
        val bytes = ByteArray(buf)
        val total = minOf(size, 128L * 1024 * 1024)
        val t0 = System.nanoTime()
        var off = 0L
        while (off < total) {
            val n = Os.pread(fd.fileDescriptor, bytes, 0, buf, off)
            if (n <= 0) break
            off += n
        }
        val s = (System.nanoTime() - t0) / 1e9
        return off / 1048576.0 / s
    }

    private fun random(fd: ParcelFileDescriptor, buf: Int): Double {
        val bytes = ByteArray(buf)
        val rnd = Random(1)
        val count = 3000
        val t0 = System.nanoTime()
        repeat(count) {
            val off = (rnd.nextLong() and Long.MAX_VALUE) % (size - buf)
            Os.pread(fd.fileDescriptor, bytes, 0, buf, off)
        }
        val s = (System.nanoTime() - t0) / 1e9
        return count / s
    }
}
