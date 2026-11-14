package com.cloudplay.bridge.core

import android.content.Context
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
    val graph = GraphClient(auth)
    // Folder name tracks CHUNK_SIZE: chunks of a different size must never be mixed.
    val cache = ChunkCache(File(context.cacheDir, "chunks-512k"), settings).also {
        File(context.cacheDir, "chunks").deleteRecursively()
        File(context.cacheDir, "chunks-2m").deleteRecursively()
    }
}

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var clientId: String
        get() = prefs.getString("client_id", "") ?: ""
        set(value) = prefs.edit().putString("client_id", value).apply()

    var cacheMaxGb: Int
        get() = prefs.getInt("cache_max_gb", 4)
        set(value) = prefs.edit().putInt("cache_max_gb", value.coerceIn(1, 64)).apply()
}
