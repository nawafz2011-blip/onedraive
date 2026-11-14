package com.cloudplay.bridge.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Downloads a whole game into the cache and pins it there, so the emulator reads it at
 * local speed. Large games (Switch, PS2) need this: they read too much, too fast, to stream.
 */
class Preloader(private val core: Instance, private val freeSpace: () -> Long) {
    data class Progress(
        val name: String,
        val done: Long,
        val total: Long,
        val finished: Boolean = false,
        val error: String? = null,
    ) {
        val running get() = !finished && error == null
    }

    private val _state = MutableStateFlow<Progress?>(null)
    val state: StateFlow<Progress?> = _state

    @Volatile
    private var cancelled = false

    @Synchronized
    fun start(item: DriveItem) {
        if (_state.value?.running == true) return
        val file = RemoteFile(core, item)
        val pinned = Pinned(file.key, item.name, item.size)
        val missing = (0 until file.chunkCount).filterNot { core.cache.has(file.key, it) }
        val needed = missing.sumOf { file.chunkLength(it) }
        if (needed + RESERVE > freeSpace()) {
            _state.value = Progress(item.name, 0, item.size, error = "المساحة في الجوال ما تكفي. تحتاج تقريبًا ${needed / ChunkCache.GB + 1} جيقا فاضية.")
            return
        }
        core.settings.pin(pinned)
        cancelled = false
        val done = AtomicLong(item.size - needed)
        _state.value = Progress(item.name, done.get(), item.size)
        thread(name = "cloudplay-preload") {
            val queue = java.util.concurrent.ConcurrentLinkedQueue(missing)
            val failure = AtomicReference<String?>(null)
            val pool = Executors.newFixedThreadPool(WORKERS)
            repeat(WORKERS) {
                pool.execute {
                    while (!cancelled && failure.get() == null) {
                        val index = queue.poll() ?: break
                        try {
                            file.chunk(index)
                            _state.value = Progress(item.name, done.addAndGet(file.chunkLength(index)), item.size)
                        } catch (e: Throwable) {
                            failure.compareAndSet(null, "انقطع التحميل. تأكد من النت واضغط تجهيز مرة ثانية، يكمل من وين وقف.")
                        }
                    }
                }
            }
            pool.shutdown()
            pool.awaitTermination(7, TimeUnit.DAYS)
            _state.value = when {
                failure.get() != null -> Progress(item.name, done.get(), item.size, error = failure.get())
                cancelled -> null
                else -> Progress(item.name, item.size, item.size, finished = true)
            }
        }
    }

    fun cancel() {
        cancelled = true
    }

    fun remove(pinned: Pinned) {
        core.settings.unpin(pinned.key)
        core.cache.removeKey(pinned.key)
    }

    private companion object {
        const val WORKERS = 4
        const val RESERVE = 512L * 1024 * 1024
    }
}

data class Pinned(val key: String, val name: String, val size: Long) {
    fun encode() = "$key\t$size\t$name"

    companion object {
        fun decode(s: String): Pinned? {
            val parts = s.split('\t', limit = 3)
            if (parts.size != 3) return null
            return Pinned(parts[0], parts[2], parts[1].toLongOrNull() ?: return null)
        }
    }
}
