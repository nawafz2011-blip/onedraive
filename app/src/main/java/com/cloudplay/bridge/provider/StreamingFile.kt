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
import java.util.BitSet
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

    // Chunks whose read-ahead request is actually downloading (not just queued).
    private val downloading = Collections.synchronizedSet(mutableSetOf<Long>())

    private var lastIndex = -1L
    private var lastData: ByteArray? = null
    private var lastReadIndex = -2L
    private var streak = 0

    // Where the emulator is reading now; read-ahead for spots it has left is skipped.
    @Volatile
    private var position = 0L

    // Chunks known to be on disk, so hot paths skip a file-system check per chunk.
    private val known = BitSet()


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
            // Read far ahead only once the emulator has been reading straight through the file
            // for a few chunks. Games that read many small files scattered through the image
            // (Zelda, resource packs) jump constantly; a big window at every jump floods the
            // connection with data nobody reads and the chunk the game needs arrives last.
            if (index != lastReadIndex) {
                streak = if (index == lastReadIndex + 1) streak + 1 else 0
                lastReadIndex = index
                position = index
                prefetchWindow(index + 1, if (streak >= SEQUENTIAL_AFTER) READ_AHEAD else JUMP_AHEAD)
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
        val blocking = !isCached(index)
        if (blocking) Stats.waiting.incrementAndGet()
        try {
            return load(index)
        } finally {
            if (blocking) Stats.waiting.decrementAndGet()
        }
    }

    private fun load(index: Long): ByteArray {
        // Already downloading in a read-ahead request: it lands within moments, so wait for it.
        // A request that is only queued may sit behind others, so fetch the chunk right away.
        val deadline = System.currentTimeMillis() + 3_000
        while (index in downloading && !core.cache.has(remote.key, index) && System.currentTimeMillis() < deadline) {
            Thread.sleep(15)
        }
        val bytes = remote.chunk(index)
        known.set(index.toInt())
        lastIndex = index
        lastData = bytes
        return bytes
    }

    /** Queues the missing chunks of [from, from + count) as runs of up to [RUN] chunks (4 MB) per request. */
    private fun prefetchWindow(from: Long, count: Int) {
        val end = minOf(from + count, remote.chunkCount)
        var i = from
        val runs = mutableListOf<List<Long>>()
        while (i < end) {
            if (i in inFlight || isCached(i)) {
                i++
                continue
            }
            val run = mutableListOf<Long>()
            while (i < end && run.size < RUN && !isCached(i) && inFlight.add(i)) {
                run += i
                i++
            }
            if (run.isEmpty()) i++ else runs += run
        }
        // The queue serves newest first, so submit the farthest run first and the nearest last.
        runs.asReversed().forEach { submit(it) }
    }

    private fun isCached(index: Long): Boolean {
        val i = index.toInt()
        if (known.get(i)) return true
        return core.cache.has(remote.key, index).also { if (it) known.set(i) }
    }

    private fun submit(run: List<Long>) {
        val release = { run.forEach { inFlight.remove(it) } }
        try {
            prefetcher.execute(PrefetchTask(work = work@{
                // The emulator has moved on: this data is no longer the next thing it needs.
                if (run.last() < position || run.first() > position + READ_AHEAD) {
                    release()
                    return@work
                }
                downloading.addAll(run)
                try {
                    remote.fetchRun(run.first(), run.size)
                } catch (e: Throwable) {
                    Log.d(TAG, "Prefetch failed for chunks ${run.first()}+${run.size}", e)
                } finally {
                    downloading.removeAll(run.toSet())
                    release()
                }
            }, onDrop = release))
        } catch (e: RejectedExecutionException) {
            release()
        }
    }

    private companion object {
        const val TAG = "StreamingFile"
        const val READ_AHEAD = 64
        const val RUN = 8
        const val JUMP_AHEAD = 4
        const val SEQUENTIAL_AFTER = 3
    }
}
