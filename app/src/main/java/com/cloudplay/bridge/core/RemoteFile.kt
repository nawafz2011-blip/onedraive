package com.cloudplay.bridge.core

import com.cloudplay.bridge.core.ChunkCache.Companion.CHUNK_SIZE
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** A OneDrive file read chunk by chunk through the cache. Shared by the provider and the preloader. */
class RemoteFile(private val core: Instance, val item: DriveItem) {
    val key = keyOf(item)
    val size = item.size
    val chunkCount = (size + CHUNK_SIZE - 1) / CHUNK_SIZE

    @Volatile
    private var downloadUrl: String? = item.downloadUrl

    fun chunk(index: Long): ByteArray = core.cache.get(key, index) { download(index) }

    fun chunkLength(index: Long): Long = minOf(CHUNK_SIZE, size - index * CHUNK_SIZE)

    /**
     * Downloads [count] consecutive chunks in one request and stores each in the cache.
     * One big request is far faster than many 512 KB ones: each request waits a full
     * round trip to OneDrive before the first byte arrives.
     */
    fun fetchRun(first: Long, count: Int) {
        val last = minOf(first + count, chunkCount) - 1
        if (last < first) return
        val start = first * CHUNK_SIZE
        val data = downloadRange(start, last * CHUNK_SIZE + chunkLength(last) - 1)
        for (index in first..last) {
            val from = ((index - first) * CHUNK_SIZE).toInt()
            val to = from + chunkLength(index).toInt()
            if (to > data.size) break // short response: never cache a partial chunk
            core.cache.put(key, index, data, from, to - from)
        }
    }

    private fun download(index: Long): ByteArray {
        val start = index * CHUNK_SIZE
        return downloadRange(start, start + chunkLength(index) - 1)
    }

    private fun downloadRange(start: Long, end: Long): ByteArray {
        var lastError: Exception? = null
        // Ride out short network drops (about 10 s) instead of failing the emulator's read.
        repeat(6) { attempt ->
            try {
                val url = downloadUrl ?: refreshUrl()
                return core.graph.readRange(url, start, end).also { Stats.bytes.addAndGet(it.size.toLong()) }
            } catch (e: ExpiredLinkException) {
                downloadUrl = null
                lastError = e
            } catch (e: IOException) {
                lastError = e
                Thread.sleep(minOf(500L * (attempt + 1), 2000L))
            }
        }
        val error = lastError ?: IOException("Download failed")
        Stats.error("${item.name}: ${error.message}")
        throw error
    }

    private fun refreshUrl(): String =
        (core.graph.item(item.id).downloadUrl ?: throw IOException("No download link"))
            .also { downloadUrl = it }

    companion object {
        fun keyOf(item: DriveItem) = item.id + "_" + (item.eTag?.hashCode() ?: 0)
    }
}

/** Counters shown on the home screen so problems are visible. */
object Stats {
    val bytes = AtomicLong()
    val errors = AtomicInteger()

    @Volatile
    var lastError: String? = null
        private set

    fun error(message: String) {
        errors.incrementAndGet()
        lastError = message
    }
}
