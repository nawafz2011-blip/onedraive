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
import com.cloudplay.bridge.core.Diag
import com.cloudplay.bridge.core.DriveItem
import com.cloudplay.bridge.core.GraphClient.Companion.ROOT_ID
import com.cloudplay.bridge.core.Instance
import com.cloudplay.bridge.core.LiveNotifier
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class OneDriveProvider : DocumentsProvider() {

    private val core: Instance by lazy { Core.get(context!!) }
    // Bounded queue: when an emulator scans a whole folder, read-ahead requests would
    // otherwise pile up without limit and compete with the reads it is waiting on.
    private val prefetcher = ThreadPoolExecutor(4, 4, 0L, TimeUnit.MILLISECONDS, PrefetchQueue(256))
        .also { Diag.pool = it }
    private val parents = ConcurrentHashMap<String, String>()
    private val items = ConcurrentHashMap<String, DriveItem>()
    private val itemTimes = ConcurrentHashMap<String, Long>()
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
            // FLAG_LOCAL_ONLY: some emulators (e.g. the PS2 one) open their folder picker with
            // EXTRA_LOCAL_ONLY, which hides every root without it. Data is served from the
            // on-device cache, so CloudPlay behaves like local storage to them.
            add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_IS_CHILD or Root.FLAG_LOCAL_ONLY)
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
            val id = realId(documentId)
            addRow(cursor, items[id] ?: fetchItem(id), documentId)
        }
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val parentDocumentId = realId(parentDocumentId)
        // Asked to list a file (emulators probing paths): it has no children.
        if (items[parentDocumentId]?.isFolder == false) return cursor
        val children = childrenOf(parentDocumentId)
        Diag.log("LIST $parentDocumentId: ${children.size} items: " +
            children.take(8).joinToString { (if (it.isFolder) "[" + it.name + "]" else it.name).take(40) })
        for (child in children) {
            // Empty files (failed or unfinished uploads) crash emulators that try to parse them.
            if (!child.isFolder && child.size == 0L) continue
            remember(child)
            parents[child.id] = parentDocumentId
            addRow(cursor, child, docIdOf(child, parentDocumentId))
        }
        return cursor
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val parentDocumentId = realId(parentDocumentId)
        var current = realId(documentId)
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
        // Some emulators ask for "rw" on game images they only ever read. Serve those
        // read-only too (writes fail on the descriptor); refuse write-only opens.
        if ('r' !in mode) {
            Diag.log("REFUSED open mode=$mode id=$documentId")
            throw FileNotFoundException("CloudPlay is read-only")
        }
        if (mode != "r") Diag.log("OPEN as read-only (asked mode=$mode)")
        // Reuse the item from the folder listing (its download link stays valid for a while,
        // and an expired one is refreshed on first read) instead of a network round trip per open.
        val id = realId(documentId)
        val fresh = (itemTimes[id] ?: 0L) > System.currentTimeMillis() - ITEM_TTL_MS
        val item = items[id]?.takeIf { fresh && it.downloadUrl != null } ?: fetchItem(id)
        if (item.isFolder) throw FileNotFoundException("${item.name} is a folder")
        val thread = HandlerThread("cloudplay-${item.name}").apply { start() }
        LiveNotifier.start(context!!)
        com.cloudplay.bridge.service.StreamService.start(context!!)
        Diag.log("OPEN ${item.name.take(40)} ${item.size / 1048576}MB")
        val storage = context!!.getSystemService(StorageManager::class.java)
        return try {
            storage.openProxyFileDescriptor(
                ParcelFileDescriptor.MODE_READ_ONLY,
                StreamingFile(core, item, prefetcher, thread),
                Handler(thread.looper),
            )
        } catch (e: Exception) {
            // Anything other than FileNotFoundException is re-thrown inside the
            // emulator as a RuntimeException and can crash it.
            thread.quitSafely()
            throw FileNotFoundException("Couldn't open ${item.name}: ${e.message}")
        }
    }

    private fun fetchItem(id: String): DriveItem = try {
        offMain { core.graph.item(id) }.also {
            remember(it)
            it.parentId?.let { p -> parents[id] = p }
        }
    } catch (e: Exception) {
        Diag.log("ERR item $id: ${e.javaClass.simpleName} ${e.message}")
        throw FileNotFoundException("Couldn't read OneDrive item: ${e.message}")
    }

    /**
     * Some callers reach the provider on CloudPlay's main thread, where Android forbids network
     * access (NetworkOnMainThreadException in the log). Run the request on a worker and wait.
     */
    private fun <T> offMain(block: () -> T): T =
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            prefetcher.submit(java.util.concurrent.Callable { block() }).get(20, TimeUnit.SECONDS)
        } else {
            block()
        }

    /**
     * File document IDs end with the file name ("<OneDrive id>/Crazy Taxi.iso"), like the
     * system's own storage provider. The PS2 emulator's folder scan decides what is a game
     * from the end of the document URI, so bare OneDrive IDs made it skip every file.
     * Anything after the first '/' is ignored on the way in; folder IDs stay bare.
     */
    private fun docIdOf(item: DriveItem, parentId: String) =
        if (item.isFolder) item.id else "$parentId/${item.name}"

    /**
     * Folder IDs are bare OneDrive IDs. File IDs are "<folder id>/<name>" (more segments are
     * accepted, like a relative path), so emulators that take the part before the last '/' as
     * the containing folder, or build a sibling's path from it (.cue next to .bin), get it right.
     */
    private fun realId(documentId: String): String {
        val parts = documentId.split('/')
        if (parts.size == 1) return documentId
        // 1.130 handed out "<file id>/<name>"; emulators may still have those saved.
        if (parts.size == 2 && items[parts[0]]?.isFolder == false) return parts[0]
        var current = parts[0]
        for (name in parts.drop(1)) {
            if (name.isEmpty()) continue
            val kids = try {
                childrenOf(current)
            } catch (e: FileNotFoundException) {
                // Not a folder: an old "<file id>/<name>" ID.
                if (current == parts[0] && parts.size == 2) return parts[0] else throw e
            }
            val hit = kids.firstOrNull { it.name == name }
                ?: kids.firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?: run {
                    Diag.log("ERR no '$name' in $current")
                    throw FileNotFoundException("$name not found")
                }
            remember(hit)
            parents[hit.id] = current
            current = hit.id
        }
        return current
    }

    private fun childrenOf(folderId: String): List<DriveItem> {
        val cached = listings[folderId]
        if (cached != null && System.currentTimeMillis() - cached.first < LISTING_TTL_MS) return cached.second
        val fresh = try {
            offMain { core.graph.children(folderId) }
        } catch (e: Exception) {
            Diag.log("ERR list $folderId: ${e.javaClass.simpleName} ${e.message}")
            throw FileNotFoundException("Couldn't list OneDrive folder: ${e.message}")
        }
        listings[folderId] = System.currentTimeMillis() to fresh
        return fresh
    }

    private fun remember(item: DriveItem) {
        items[item.id] = item
        itemTimes[item.id] = System.currentTimeMillis()
    }

    private fun addRow(cursor: MatrixCursor, item: DriveItem, documentId: String) {
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, documentId)
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
        const val LISTING_TTL_MS = 20_000L
        const val ITEM_TTL_MS = 30 * 60_000L
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
