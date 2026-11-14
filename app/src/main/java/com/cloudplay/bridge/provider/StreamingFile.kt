package com.cloudplay.bridge.provider

import android.os.HandlerThread
import android.os.ProxyFileDescriptorCallback
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Log
import com.cloudplay.bridge.core.ChunkCache.Companion.CHUNK_SIZE
import com.cloudplay.bridge.core.DriveItem
import com.cloudplay.bridge.core.Instance
import com.cloudplay.bridge.core.RemoteFile
import com.cloudplay.bridge.core.Stats
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

    private val remote = RemoteFile(core, item).also { Stats.openFiles.incrementAndGet() }
    private val size = item.size
    private val inFlight = Collections.synchronizedSet(mutableSetOf<Long>())

    private var lastIndex = -1L
    private var lastData: ByteArray? = null
    private var lastReadIndex = -2L
    private var reads = 0

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
            // Read far ahead while the emulator reads straight through the file (loading a
            // game); only a little after a jump, so scanning headers doesn't waste bandwidth.
            // Once the file has been read many times it's a running game, not a folder scan:
            // games read lots of small pieces scattered through the file (e.g. resource packs),
            // so fetch a wide window after every read instead of waiting on each piece.
            reads++
            if (index != lastReadIndex) {
                val sequential = index in lastReadIndex - 1..lastReadIndex + 2
                lastReadIndex = index
                val ahead = if (sequential || reads > PLAYING_AFTER_READS) READ_AHEAD else JUMP_AHEAD
                prefetchWindow(index + 1, ahead)
            }
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
        Stats.openFiles.decrementAndGet()
        lastData = null
        thread.quitSafely()
    }

    private fun chunk(index: Long): ByteArray {
        if (index == lastIndex) lastData?.let { return it }
        val blocking = !core.cache.has(remote.key, index)
        if (blocking) Stats.waiting.incrementAndGet()
        try {
            return load(index)
        } finally {
            if (blocking) Stats.waiting.decrementAndGet()
        }
    }

    private fun load(index: Long): ByteArray {
        // Already coming in a read-ahead request: wait for it instead of downloading it twice.
        val deadline = System.currentTimeMillis() + 15_000
        while (index in inFlight && !core.cache.has(remote.key, index) && System.currentTimeMillis() < deadline) {
            Thread.sleep(15)
        }
        val bytes = remote.chunk(index)
        lastIndex = index
        lastData = bytes
        return bytes
    }

    /** Queues the missing chunks of [from, from + count) as runs of up to [RUN] chunks (8 MB) per request. */
    private fun prefetchWindow(from: Long, count: Int) {
        val end = minOf(from + count, remote.chunkCount)
        var i = from
        val runs = mutableListOf<List<Long>>()
        while (i < end) {
            if (i in inFlight || core.cache.has(remote.key, i)) {
                i++
                continue
            }
            val run = mutableListOf<Long>()
            while (i < end && run.size < RUN && !core.cache.has(remote.key, i) && inFlight.add(i)) {
                run += i
                i++
            }
            if (run.isEmpty()) i++ else runs += run
        }
        // The queue serves newest first, so submit the farthest run first and the nearest last.
        runs.asReversed().forEach { submit(it) }
    }

    private fun submit(run: List<Long>) {
        val release = { run.forEach { inFlight.remove(it) } }
        try {
            prefetcher.execute(PrefetchTask(work = {
                try {
                    remote.fetchRun(run.first(), run.size)
                } catch (e: Throwable) {
                    Log.d(TAG, "Prefetch failed for chunks ${run.first()}+${run.size}", e)
                } finally {
                    release()
                }
            }, onDrop = release))
        } catch (e: RejectedExecutionException) {
            release()
        }
    }

    private companion object {
        const val TAG = "StreamingFile"
        const val READ_AHEAD = 128
        const val RUN = 16
        const val JUMP_AHEAD = 2
        const val PLAYING_AFTER_READS = 50
    }
}
