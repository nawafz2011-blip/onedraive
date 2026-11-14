package com.cloudplay.bridge.core

import java.io.File
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

    fun get(key: String, index: Long, fetch: () -> ByteArray): ByteArray {
        val target = file(key, index)
        synchronized(memory) { memory[target.name] }?.let { return it }
        readIfPresent(target)?.let { remember(target.name, it); return it }
        val lock = locks.computeIfAbsent(target.name) { Any() }
        val data = synchronized(lock) {
            readIfPresent(target) ?: fetch().also { bytes ->
                val tmp = File(dir, target.name + ".tmp")
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(target)) tmp.delete()
            }
        }
        locks.remove(target.name)
        remember(target.name, data)
        if (++writesSinceTrim >= 64) {
            writesSinceTrim = 0
            trim()
        }
        return data
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
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= max) break
            val len = f.length()
            if (f.delete()) total -= len
        }
    }

    /** Clears streamed chunks; prepared games stay. */
    @Synchronized
    fun clear() {
        synchronized(memory) { memory.clear() }
        val pinned = settings.pinned.map { prefix(it.key) }
        dir.listFiles()?.filter { f -> pinned.none { f.name.startsWith(it) } }?.forEach { it.delete() }
    }

    @Synchronized
    fun removeKey(key: String) {
        val p = prefix(key)
        synchronized(memory) { memory.keys.removeAll { it.startsWith(p) } }
        dir.listFiles { f -> f.name.startsWith(p) }?.forEach { it.delete() }
    }

    fun bytesFor(key: String): Long {
        val p = prefix(key)
        return dir.listFiles { f -> f.name.startsWith(p) }?.sumOf { it.length() } ?: 0L
    }

    private fun readIfPresent(f: File): ByteArray? {
        if (!f.exists()) return null
        return try {
            f.setLastModified(System.currentTimeMillis())
            f.readBytes()
        } catch (e: Exception) {
            null
        }
    }

    private fun prefix(key: String) = key.replace(Regex("[^A-Za-z0-9]"), "_") + "_"

    private fun file(key: String, index: Long) = File(dir, prefix(key) + index)

    companion object {
        const val CHUNK_SIZE = 512L * 1024
        private const val MEMORY_CHUNKS = 64
        const val GB = 1024L * 1024 * 1024
    }
}
