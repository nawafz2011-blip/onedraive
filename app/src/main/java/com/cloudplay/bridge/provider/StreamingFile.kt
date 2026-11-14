package com.cloudplay.bridge.provider

import android.os.HandlerThread
import android.os.ProxyFileDescriptorCallback
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Log
import com.cloudplay.bridge.core.ChunkCache.Companion.CHUNK_SIZE
import com.cloudplay.bridge.core.DriveItem
import com.cloudplay.bridge.core.ExpiredLinkException
import com.cloudplay.bridge.core.Instance
import java.io.IOException
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException

class StreamingFile(
    private val core: Instance,
    private val item: DriveItem,
    private val prefetcher: ExecutorService,
    private val thread: HandlerThread,
) : ProxyFileDescriptorCallback() {

    private val size = item.size
    private val key = item.id + "_" + (item.eTag?.hashCode() ?: 0)
    private val inFlight = Collections.synchronizedSet(mutableSetOf<Long>())

    @Volatile
    private var downloadUrl: String? = item.downloadUrl

    private var lastIndex = -1L
    private var lastData: ByteArray? = null
    private var lastReadIndex = -2L

    override fun onGetSize(): Long = size

    override fun onRead(offset: Long, length: Int, data: ByteArray): Int {
        if (offset >= size) return 0
        val total = minOf(length.toLong(), size - offset).toInt()
        try {
            var done = 0
            var index = offset / CHUNK_SIZE
            while (done < total) {
                val pos = offset + done
                index = pos / CHUNK_SIZE
                val chunk = chunk(index)
                val within = (pos - index * CHUNK_SIZE).toInt()
                val n = minOf(total - done, chunk.size - within)
                if (n <= 0) throw IOException("Short chunk $index")
                System.arraycopy(chunk, within, data, done, n)
                done += n
            }
            // Read ahead only while the emulator reads straight through the file
            // (loading a game). Random reads (scanning headers) would just waste bandwidth.
            val sequential = index == lastReadIndex || index == lastReadIndex + 1
            lastReadIndex = index
            if (sequential) for (i in 1..READ_AHEAD) prefetch(index + i)
            return done
        } catch (e: Throwable) {
            // Any error (including OutOfMemoryError) must become EIO: an uncaught
            // throwable here kills CloudPlay, and Android then kills the emulator too.
            Log.w(TAG, "Read failed for ${item.name} at $offset", e)
            lastData = null
            lastIndex = -1L
            throw ErrnoException("onRead", OsConstants.EIO)
        }
    }

    override fun onRelease() {
        lastData = null
        thread.quitSafely()
    }

    private fun chunk(index: Long): ByteArray {
        if (index == lastIndex) lastData?.let { return it }
        val bytes = core.cache.get(key, index) { download(index) }
        lastIndex = index
        lastData = bytes
        return bytes
    }

    private fun prefetch(index: Long) {
        if (index * CHUNK_SIZE >= size || core.cache.has(key, index) || !inFlight.add(index)) return
        try {
            prefetcher.execute {
                try {
                    core.cache.get(key, index) { download(index) }
                } catch (e: Throwable) {
                    Log.d(TAG, "Prefetch failed for chunk $index", e)
                } finally {
                    inFlight.remove(index)
                }
            }
        } catch (e: RejectedExecutionException) {
            // Prefetch queue is full (e.g. emulator scanning many files); skip it.
            inFlight.remove(index)
        }
    }

    private fun download(index: Long): ByteArray {
        val start = index * CHUNK_SIZE
        val end = minOf(start + CHUNK_SIZE, size) - 1
        var lastError: Exception? = null
        repeat(3) { attempt ->
            try {
                val url = downloadUrl ?: refreshUrl()
                return core.graph.readRange(url, start, end)
            } catch (e: ExpiredLinkException) {
                downloadUrl = null
                lastError = e
            } catch (e: IOException) {
                lastError = e
                Thread.sleep(500L * (attempt + 1))
            }
        }
        throw lastError ?: IOException("Download failed")
    }

    private fun refreshUrl(): String =
        (core.graph.item(item.id).downloadUrl ?: throw IOException("No download link"))
            .also { downloadUrl = it }

    private companion object {
        const val TAG = "StreamingFile"
        const val READ_AHEAD = 6
    }
}
