package com.cloudplay.bridge.core

import java.io.File
import java.util.concurrent.ConcurrentHashMap

class ChunkCache(private val dir: File, private val settings: Settings) {
    private val locks = ConcurrentHashMap<String, Any>()
    @Volatile
    private var writesSinceTrim = 0

    init {
        dir.mkdirs()
        dir.listFiles { f -> f.name.endsWith(".tmp") }?.forEach { it.delete() }
    }

    fun has(key: String, index: Long) = file(key, index).exists()

    fun get(key: String, index: Long, fetch: () -> ByteArray): ByteArray {
        val target = file(key, index)
        readIfPresent(target)?.let { return it }
        val lock = locks.computeIfAbsent(target.name) { Any() }
        val data = synchronized(lock) {
            readIfPresent(target) ?: fetch().also { bytes ->
                val tmp = File(dir, target.name + ".tmp")
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(target)) tmp.delete()
            }
        }
        locks.remove(target.name)
        if (++writesSinceTrim >= 8) {
            writesSinceTrim = 0
            trim()
        }
        return data
    }

    fun usedBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    fun maxBytes(): Long = settings.cacheMaxGb.toLong() * GB

    @Synchronized
    fun trim() {
        val files = dir.listFiles()?.filter { !it.name.endsWith(".tmp") } ?: return
        var total = files.sumOf { it.length() }
        val max = maxBytes()
        if (total <= max) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= max) break
            val len = f.length()
            if (f.delete()) total -= len
        }
    }

    @Synchronized
    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
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

    private fun file(key: String, index: Long) =
        File(dir, key.replace(Regex("[^A-Za-z0-9]"), "_") + "_" + index)

    companion object {
        const val CHUNK_SIZE = 4L * 1024 * 1024
        const val GB = 1024L * 1024 * 1024
    }
}
