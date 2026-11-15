package com.cloudplay.bridge.core

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.ConcurrentHashMap

class ChunkCache(private val dir: File, private val settings: Settings) {
    private val locks = ConcurrentHashMap<String, Any>()
    // Recently used chunks kept in RAM so jumping between a few spots of a file
    // doesn't re-read them from storage every time.
    private val memory = object : LinkedHashMap<String, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?) = size > MEMORY_CHUNKS
    }
    @Volatile
    private var writesSinceTrim = 0

    init {
        dir.mkdirs()
        dir.listFiles { f -> f.name.endsWith(".tmp") }?.forEach { it.delete() }
    }

    fun has(key: String, index: Long) = file(key, index).exists()

    // Open chunk files, so a small read of a cached chunk is one positional read
    // instead of loading the whole 512 KB chunk.
    private val channels = object : LinkedHashMap<String, FileChannel>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FileChannel>?): Boolean {
            if (size <= OPEN_CHANNELS) return false
            runCatching { eldest?.value?.close() }
            return true
        }
    }

    /**
     * Copies [length] bytes at [within] of a cached chunk into [dest]. Returns the bytes copied,
     * or -1 if the chunk isn't cached. Reads only what was asked, from RAM or disk.
     */
    fun readInto(key: String, index: Long, within: Int, dest: ByteArray, destOffset: Int, length: Int): Int {
        val name = prefix(key) + index
        synchronized(memory) { memory[name] }?.let { chunk ->
            val n = minOf(length, chunk.size - within)
            if (n <= 0) return -1
            System.arraycopy(chunk, within, dest, destOffset, n)
            return n
        }
        val channel = synchronized(channels) {
            channels[name] ?: run {
                val f = File(dir, name)
                if (!f.exists()) return -1
                val now = System.currentTimeMillis()
                if (now - f.lastModified() > TOUCH_INTERVAL_MS) f.setLastModified(now)
                try {
                    RandomAccessFile(f, "r").channel.also { channels[name] = it }
                } catch (e: Exception) {
                    return -1
                }
            }
        }
        return try {
            val buffer = ByteBuffer.wrap(dest, destOffset, length)
            var done = 0
            while (buffer.hasRemaining()) {
                val n = channel.read(buffer, (within + done).toLong())
                if (n <= 0) break
                done += n
            }
            if (done > 0) done else -1
        } catch (e: Exception) {
            synchronized(channels) { channels.remove(name) }
            -1
        }
    }

    private fun closeChannels(match: (String) -> Boolean) = synchronized(channels) {
        val it = channels.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (match(e.key)) {
                runCatching { e.value.close() }
                it.remove()
            }
        }
    }

    fun get(key: String, index: Long, fetch: () -> ByteArray): ByteArray {
        val target = file(key, index)
        synchronized(memory) { memory[target.name] }?.let { return it }
        readIfPresent(target)?.let { remember(target.name, it); return it }
        val lock = locks.computeIfAbsent(target.name) { Any() }
        val data = synchronized(lock) {
            readIfPresent(target) ?: fetch().also { write(target, it) }
        }
        locks.remove(target.name)
        remember(target.name, data)
        afterWrite()
        return data
    }

    /** Stores a chunk fetched ahead of time (no-op if it's already cached). */
    fun put(key: String, index: Long, data: ByteArray, offset: Int = 0, length: Int = data.size) {
        val target = file(key, index)
        if (target.exists()) return
        val lock = locks.computeIfAbsent(target.name) { Any() }
        synchronized(lock) { if (!target.exists()) write(target, data, offset, length) }
        locks.remove(target.name)
        afterWrite()
    }

    private fun write(target: File, bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
        val tmp = File(dir, target.name + "." + Thread.currentThread().id + ".tmp")
        tmp.outputStream().use { it.write(bytes, offset, length) }
        if (!tmp.renameTo(target)) tmp.delete()
    }

    private fun afterWrite() {
        if (++writesSinceTrim >= 64) {
            writesSinceTrim = 0
            trim()
        }
    }

    private fun remember(name: String, data: ByteArray) {
        synchronized(memory) { memory[name] = data }
    }

    fun usedBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    fun maxBytes(): Long = settings.cacheMaxGb.toLong() * GB

    @Synchronized
    fun trim() {
        val pinned = settings.pinned.map { prefix(it.key) }
        val all = dir.listFiles()?.filter { !it.name.endsWith(".tmp") } ?: return
        // Prepared (pinned) games are never evicted; only streamed chunks count toward the limit.
        val files = all.filter { f -> pinned.none { f.name.startsWith(it) } }
        var total = files.sumOf { it.length() }
        val max = maxBytes()
        if (total <= max) return
        for (f in files.map { it to it.lastModified() }.sortedBy { it.second }.map { it.first }) {
            if (total <= max) break
            closeChannels { it == f.name }
            val len = f.length()
            if (f.delete()) total -= len
        }
    }

    /** Clears streamed chunks; prepared games stay. */
    @Synchronized
    fun clear() {
        synchronized(memory) { memory.clear() }
        closeChannels { true }
        val pinned = settings.pinned.map { prefix(it.key) }
        dir.listFiles()?.filter { f -> pinned.none { f.name.startsWith(it) } }?.forEach { it.delete() }
    }

    @Synchronized
    fun removeKey(key: String) {
        val p = prefix(key)
        synchronized(memory) { memory.keys.removeAll { it.startsWith(p) } }
        closeChannels { it.startsWith(p) }
        dir.listFiles { f -> f.name.startsWith(p) }?.forEach { it.delete() }
    }

    fun bytesFor(key: String): Long {
        val p = prefix(key)
        return dir.listFiles { f -> f.name.startsWith(p) }?.sumOf { it.length() } ?: 0L
    }

    private fun readIfPresent(f: File): ByteArray? {
        if (!f.exists()) return null
        return try {
            // Touching the date is a disk write; do it at most every 10 minutes per chunk,
            // which is plenty for least-recently-used eviction.
            val now = System.currentTimeMillis()
            if (now - f.lastModified() > TOUCH_INTERVAL_MS) f.setLastModified(now)
            f.readBytes()
        } catch (e: Exception) {
            null
        }
    }

    private val prefixes = ConcurrentHashMap<String, String>()

    private fun prefix(key: String) =
        prefixes.getOrPut(key) { key.replace(UNSAFE, "_") + "_" }

    private fun file(key: String, index: Long) = File(dir, prefix(key) + index)

    companion object {
        const val CHUNK_SIZE = 512L * 1024
        private val UNSAFE = Regex("[^A-Za-z0-9]")
        private const val TOUCH_INTERVAL_MS = 10 * 60_000L
        private const val OPEN_CHANNELS = 128
        private const val MEMORY_CHUNKS = 64
        const val GB = 1024L * 1024 * 1024
    }
}
