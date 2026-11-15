package com.cloudplay.bridge.core

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Name lookups that can't stall the game.
 *
 * A diagnostic log showed one OneDrive lookup hanging for ~9 minutes inside the phone's
 * resolver; OkHttp has no DNS timeout, and every read and download waited behind it.
 * Now: a known address is always answered at once (refreshed in the background when old),
 * the system resolver gets 4 seconds, then DNS-over-HTTPS (Cloudflare, reached by IP) is tried.
 */
object CachingDns : Dns {
    private const val FRESH_MS = 10 * 60_000L
    private const val SYSTEM_TIMEOUT_S = 4L

    private val cache = ConcurrentHashMap<String, Pair<Long, List<InetAddress>>>()
    private val refreshing = ConcurrentHashMap.newKeySet<String>()
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "cloudplay-dns").apply { isDaemon = true } }

    private val doh: DnsOverHttps by lazy {
        DnsOverHttps.Builder()
            .client(
                OkHttpClient.Builder()
                    .connectTimeout(4, TimeUnit.SECONDS)
                    .readTimeout(4, TimeUnit.SECONDS)
                    .build()
            )
            .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
            .bootstrapDnsHosts(InetAddress.getByName("1.1.1.1"), InetAddress.getByName("1.0.0.1"))
            .build()
    }

    /** Network changed: keep addresses as a fallback, but look them up again on next use. */
    fun expire() {
        for ((host, entry) in cache) cache[host] = 0L to entry.second
    }

    override fun lookup(hostname: String): List<InetAddress> {
        val entry = cache[hostname]
        if (entry != null) {
            if (System.currentTimeMillis() - entry.first > FRESH_MS && refreshing.add(hostname)) {
                pool.execute {
                    try {
                        cache[hostname] = System.currentTimeMillis() to resolve(hostname)
                    } catch (e: Exception) {
                        // Keep the old address.
                    } finally {
                        refreshing.remove(hostname)
                    }
                }
            }
            return entry.second
        }
        return resolve(hostname).also { cache[hostname] = System.currentTimeMillis() to it }
    }

    private fun resolve(hostname: String): List<InetAddress> {
        val system = pool.submit<List<InetAddress>> { Dns.SYSTEM.lookup(hostname) }
        try {
            return system.get(SYSTEM_TIMEOUT_S, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Diag.log("DNS system lookup failed for $hostname (${e.javaClass.simpleName}), trying DNS-over-HTTPS")
        }
        return try {
            doh.lookup(hostname)
        } catch (e: Exception) {
            Diag.log("DNS-over-HTTPS failed for $hostname: ${e.message}")
            throw UnknownHostException("Unable to resolve host \"$hostname\"")
        }
    }
}
