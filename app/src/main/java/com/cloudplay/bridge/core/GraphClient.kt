package com.cloudplay.bridge.core

import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.time.Instant
import java.util.concurrent.TimeUnit

data class DriveItem(
    val id: String,
    val name: String,
    val size: Long,
    val isFolder: Boolean,
    val mimeType: String?,
    val modified: Long,
    val parentId: String?,
    val eTag: String?,
    val downloadUrl: String?,
)

class ExpiredLinkException : IOException("Download link expired")

class GraphClient(private val auth: AuthManager) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        // Enough warm connections for all read-ahead threads, so each chunk skips a new TLS handshake.
        .connectionPool(ConnectionPool(32, 5, TimeUnit.MINUTES))
        .dns(CachingDns)
        .build()

    fun item(id: String): DriveItem = parse(getJson("$BASE/items/${enc(id)}"))

    fun children(id: String): List<DriveItem> {
        val result = mutableListOf<DriveItem>()
        var url: String? = "$BASE/items/${enc(id)}/children?\$top=500"
        while (url != null) {
            val json = getJson(url)
            val values = json.getJSONArray("value")
            for (i in 0 until values.length()) result += parse(values.getJSONObject(i))
            url = json.optString("@odata.nextLink").takeIf { it.isNotBlank() }
        }
        return result
    }

    fun quota(): Pair<Long, Long>? {
        val quota = getJson(BASE).optJSONObject("quota") ?: return null
        return quota.optLong("used") to quota.optLong("total")
    }

    fun readRange(downloadUrl: String, start: Long, endInclusive: Long): ByteArray {
        val request = Request.Builder()
            .url(downloadUrl)
            .header("Range", "bytes=$start-$endInclusive")
            .build()
        http.newCall(request).execute().use { response ->
            when (response.code) {
                206 -> return response.body!!.bytes()
                200 -> {
                    if (start == 0L) return response.body!!.bytes()
                    throw IOException("Server does not support partial downloads")
                }
                401, 403, 404, 410 -> throw ExpiredLinkException()
                else -> throw IOException("Download failed (HTTP ${response.code})")
            }
        }
    }

    private fun getJson(url: String, retried: Boolean = false): JSONObject {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${auth.accessToken()}")
            .build()
        http.newCall(request).execute().use { response ->
            if (response.code == 401 && !retried) {
                auth.invalidateAccessToken()
                return getJson(url, retried = true)
            }
            if (!response.isSuccessful) throw IOException("OneDrive request failed (HTTP ${response.code})")
            return JSONObject(response.body!!.string())
        }
    }

    private fun parse(json: JSONObject): DriveItem {
        val parentRef = json.optJSONObject("parentReference")
        val parentId = when {
            parentRef == null -> null
            parentRef.optString("path") == "/drive/root:" -> ROOT_ID
            else -> parentRef.optString("id").takeIf { it.isNotBlank() }
        }
        val modified = runCatching {
            Instant.parse(json.getString("lastModifiedDateTime")).toEpochMilli()
        }.getOrDefault(0L)
        return DriveItem(
            id = json.getString("id"),
            name = json.optString("name"),
            size = json.optLong("size"),
            isFolder = json.has("folder"),
            mimeType = json.optJSONObject("file")?.optString("mimeType")?.takeIf { it.isNotBlank() },
            modified = modified,
            parentId = parentId,
            eTag = json.optString("eTag").takeIf { it.isNotBlank() },
            downloadUrl = json.optString("@microsoft.graph.downloadUrl").takeIf { it.isNotBlank() },
        )
    }

    private fun enc(id: String) = URLEncoder.encode(id, "UTF-8")

    companion object {
        const val ROOT_ID = "root"
        private const val BASE = "https://graph.microsoft.com/v1.0/me/drive"
    }
}
