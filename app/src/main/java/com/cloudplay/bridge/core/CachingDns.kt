package com.cloudplay.bridge.core

import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap

/**
 * Remembers OneDrive's addresses. Read-ahead opens many connections at once, and phones
 * sometimes fail those bursts of lookups ("Unable to resolve host"), which failed reads and
 * crashed the emulator. Lookups are done once per host at a time and reused; if a lookup
 * fails, the last known address is used.
 */
object CachingDns : Dns {
    private const val TTL_MS = 10 * 60_000L
    private val cache = ConcurrentHashMap<String, Pair<Long, List<InetAddress>>>()
    private val locks = ConcurrentHashMap<String, Any>()

    fun clear() = cache.clear()

    override fun lookup(hostname: String): List<InetAddress> {
        fresh(hostname)?.let { return it }
        synchronized(locks.computeIfAbsent(hostname) { Any() }) {
            fresh(hostname)?.let { return it }
            var lastError: UnknownHostException? = null
            repeat(3) { attempt ->
                try {
                    return Dns.SYSTEM.lookup(hostname).also {
                        cache[hostname] = System.currentTimeMillis() to it
                    }
                } catch (e: UnknownHostException) {
                    lastError = e
                    cache[hostname]?.let { (_, stale) -> return stale }
                    Thread.sleep(400L * (attempt + 1))
                }
            }
            throw lastError ?: UnknownHostException(hostname)
        }
    }

    private fun fresh(hostname: String): List<InetAddress>? =
        cache[hostname]?.takeIf { System.currentTimeMillis() - it.first < TTL_MS }?.second
}
