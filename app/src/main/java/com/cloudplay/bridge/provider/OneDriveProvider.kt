package com.cloudplay.bridge.provider

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.storage.StorageManager
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import com.cloudplay.bridge.R
import com.cloudplay.bridge.core.Core
import com.cloudplay.bridge.core.DriveItem
import com.cloudplay.bridge.core.GraphClient.Companion.ROOT_ID
import com.cloudplay.bridge.core.Instance
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class OneDriveProvider : DocumentsProvider() {

    private val core: Instance by lazy { Core.get(context!!) }
    private val prefetcher = Executors.newFixedThreadPool(2)
    private val parents = ConcurrentHashMap<String, String>()
    private val items = ConcurrentHashMap<String, DriveItem>()
    private val listings = ConcurrentHashMap<String, Pair<Long, List<DriveItem>>>()

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        if (!core.auth.signedIn.value) return cursor
        cursor.newRow().apply {
            add(Root.COLUMN_ROOT_ID, "onedrive")
            add(Root.COLUMN_DOCUMENT_ID, ROOT_ID)
            add(Root.COLUMN_TITLE, "CloudPlay")
            add(Root.COLUMN_SUMMARY, "OneDrive")
            add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_IS_CHILD)
            add(Root.COLUMN_ICON, R.drawable.ic_cloud)
        }
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        if (documentId == ROOT_ID) {
            cursor.newRow().apply {
                add(Document.COLUMN_DOCUMENT_ID, ROOT_ID)
                add(Document.COLUMN_DISPLAY_NAME, "OneDrive")
                add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
                add(Document.COLUMN_FLAGS, 0)
                add(Document.COLUMN_SIZE, null)
                add(Document.COLUMN_LAST_MODIFIED, null)
            }
        } else {
            addRow(cursor, items[documentId] ?: fetchItem(documentId))
        }
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val cached = listings[parentDocumentId]
        val children = if (cached != null && System.currentTimeMillis() - cached.first < LISTING_TTL_MS) {
            cached.second
        } else {
            val fresh = try {
                core.graph.children(parentDocumentId)
            } catch (e: Exception) {
                throw FileNotFoundException("Couldn't list OneDrive folder: ${e.message}")
            }
            listings[parentDocumentId] = System.currentTimeMillis() to fresh
            fresh
        }
        for (child in children) {
            items[child.id] = child
            parents[child.id] = parentDocumentId
            addRow(cursor, child)
        }
        return cursor
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        var current = documentId
        repeat(64) {
            if (current == parentDocumentId) return true
            if (current == ROOT_ID) return false
            val parent = parents[current]
                ?: runCatching { fetchItem(current).parentId }.getOrNull()?.also { parents[current] = it }
                ?: return false
            current = parent
        }
        return false
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("CloudPlay is read-only")
        val item = fetchItem(documentId)
        if (item.isFolder) throw FileNotFoundException("${item.name} is a folder")
        val thread = HandlerThread("cloudplay-${item.name}").apply { start() }
        val storage = context!!.getSystemService(StorageManager::class.java)
        return storage.openProxyFileDescriptor(
            ParcelFileDescriptor.MODE_READ_ONLY,
            StreamingFile(core, item, prefetcher, thread),
            Handler(thread.looper),
        )
    }

    private fun fetchItem(id: String): DriveItem = try {
        core.graph.item(id).also {
            items[id] = it
            it.parentId?.let { p -> parents[id] = p }
        }
    } catch (e: Exception) {
        throw FileNotFoundException("Couldn't read OneDrive item: ${e.message}")
    }

    private fun addRow(cursor: MatrixCursor, item: DriveItem) {
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, item.id)
            add(Document.COLUMN_DISPLAY_NAME, item.name)
            add(Document.COLUMN_MIME_TYPE, mimeOf(item))
            add(Document.COLUMN_FLAGS, 0)
            add(Document.COLUMN_SIZE, if (item.isFolder) null else item.size)
            add(Document.COLUMN_LAST_MODIFIED, item.modified)
        }
    }

    private fun mimeOf(item: DriveItem): String {
        if (item.isFolder) return Document.MIME_TYPE_DIR
        val ext = item.name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: item.mimeType
            ?: "application/octet-stream"
    }

    private companion object {
        const val LISTING_TTL_MS = 60_000L
        val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY, Root.COLUMN_FLAGS, Root.COLUMN_ICON,
        )
        val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_FLAGS, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED,
        )
    }
}
