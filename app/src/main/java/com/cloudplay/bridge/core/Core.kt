package com.cloudplay.bridge.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.provider.DocumentsContract
import java.io.File

object Core {
    const val PROVIDER_AUTHORITY = "com.cloudplay.bridge.documents"

    @Volatile
    private var instance: Instance? = null

    fun get(context: Context): Instance =
        instance ?: synchronized(this) {
            instance ?: Instance(context.applicationContext).also { instance = it }
        }

    fun notifyRootsChanged(context: Context) {
        context.contentResolver.notifyChange(
            DocumentsContract.buildRootsUri(PROVIDER_AUTHORITY), null
        )
    }
}

class Instance(context: Context) {
    val settings = Settings(context)
    val auth = AuthManager(context, settings)
    val graph = GraphClient(auth).also { graph ->
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        var current: Network? = connectivity.activeNetwork
        connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (network != current) graph.onNetworkChanged()
                current = network
            }

            override fun onLost(network: Network) = graph.onNetworkChanged()
        })
    }
    // Folder name tracks CHUNK_SIZE: chunks of a different size must never be mixed.
    // In filesDir, not cacheDir: Android may wipe cacheDir and lose prepared games.
    val cache = ChunkCache(File(context.filesDir, "chunks-512k"), settings).also {
        for (old in listOf("chunks", "chunks-2m", "chunks-512k")) File(context.cacheDir, old).deleteRecursively()
    }
    val preloader = Preloader(this) { context.filesDir.usableSpace }
}

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var clientId: String
        get() = prefs.getString("client_id", "") ?: ""
        set(value) = prefs.edit().putString("client_id", value).apply()

    val pinned: List<Pinned>
        get() = prefs.getStringSet("pinned", emptySet()).orEmpty().mapNotNull { Pinned.decode(it) }

    @Synchronized
    fun pin(p: Pinned) = savePinned(pinned.filter { it.key != p.key } + p)

    @Synchronized
    fun unpin(key: String) = savePinned(pinned.filter { it.key != key })

    private fun savePinned(list: List<Pinned>) =
        prefs.edit().putStringSet("pinned", list.map { it.encode() }.toSet()).apply()

    var cacheMaxGb: Int
        get() = prefs.getInt("cache_max_gb", 4)
        set(value) = prefs.edit().putInt("cache_max_gb", value.coerceIn(1, 64)).apply()
}
