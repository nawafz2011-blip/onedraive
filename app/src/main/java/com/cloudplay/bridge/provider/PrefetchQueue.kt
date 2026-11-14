package com.cloudplay.bridge.provider

import java.util.concurrent.LinkedBlockingDeque

/** A read-ahead task that can tell its owner it was dropped before running. */
class PrefetchTask(private val work: () -> Unit, val onDrop: () -> Unit) : Runnable {
    override fun run() = work()
}

/**
 * Newest-first queue for read-ahead. When a game jumps around its file, the chunks near the
 * latest read matter most; requests from old positions are served last, and the oldest are
 * dropped once the queue is full instead of blocking new ones.
 */
class PrefetchQueue(capacity: Int) : LinkedBlockingDeque<Runnable>(capacity) {
    override fun offer(e: Runnable): Boolean {
        while (!offerFirst(e)) (pollLast() as? PrefetchTask)?.onDrop?.invoke()
        return true
    }
}
