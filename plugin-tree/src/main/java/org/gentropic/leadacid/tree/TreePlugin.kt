package org.gentropic.leadacid.tree

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import org.gentropic.leadacid.BridgeRequest
import org.gentropic.leadacid.BridgeResponse
import org.gentropic.leadacid.Plugin
import org.gentropic.leadacid.PluginFactory
import org.gentropic.leadacid.PluginHost
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * `tree` — a folder the user picks (Android's folder picker, ACTION_OPEN_DOCUMENT_TREE)
 * served to the page as a directory tree: list / stat / mkdir / remove / rename,
 * reads through fs tokens (mmap), writes through a part-file-then-rename writer.
 *
 * WHY: the WebView DEFINES `showDirectoryPicker` but aborts it instantly (no
 * chooser), so a project-folder app (micro) sees the API and dies on every call.
 * This plugin is the honest version: a real folder on the phone — visible in
 * Files, on an SD card, syncable — not an app-private sandbox. The pick is the
 * user's consent; the grant is persisted (`takePersistableUriPermission`), so a
 * remembered project reopens on the next launch (`restore`) until the user
 * revokes it, in which case `restore` says so instead of pretending.
 *
 * Routes (/native/tree/…; `dir` = a root id from pick/restore, `path` = a
 * relative path, '/'-separated, no '..'):
 *   GET  info                    → {ok:true}                      (presence probe)
 *   POST pick[?initial=primary:Download] → {dir,name,uri} | {cancelled:true}   (initial = where the picker opens)
 *   GET  restore?uri=            → {dir,name,uri} | 404 (permission lost / folder gone)
 *   GET  list?dir&path           → [{name,kind,size,mtime}]
 *   GET  stat?dir&path           → {name,kind,size,mtime} | 404
 *   POST mkdir?dir&path          → {ok:true}                      (intermediates created)
 *   POST remove?dir&path[&recursive=1] → {ok:true}
 *   POST rename?dir&path&name    → {ok:true}
 *   GET  token?dir&path          → {token,size,mtime,name}        (an fs token; read in ranges)
 *   POST open?dir&path           → {w}                            (truncating writer)
 *   POST write?w  (body)         → {bytes}
 *   POST close?w                 → {ok:true,size}                 (part → final name)
 *   POST abort?w                 → {ok:true}                      (part deleted)
 *
 * The writer lands bytes in `.<name>.part` beside the target and renames on
 * close — so a crash mid-write never leaves a half-written project file under
 * its real name (the File System Access API's swap-on-close, approximated).
 */
class TreePlugin(private val host: PluginHost) : Plugin {
    override val name = "tree"

    private class TreeError(val status: Int, val reason: String, val detail: String) : Exception(detail)
    private class Root(val id: String, val treeUri: Uri, val rootDocId: String, val name: String)
    private class Entry(val docId: String, val name: String, val mime: String, val size: Long, val mtime: Long) {
        val isDir get() = mime == Document.MIME_TYPE_DIR
    }
    private class Writer(val root: Root, val parentDocId: String, val finalName: String, var partUri: Uri, val out: OutputStream) {
        var bytes = 0L
    }

    private val roots = ConcurrentHashMap<String, Root>()
    private val writers = ConcurrentHashMap<String, Writer>()
    private val wSeq = AtomicInteger(0)
    private val resolver get() = host.context.contentResolver

    override fun handle(req: BridgeRequest): BridgeResponse = try {
        when (req.subpath) {
            "info" -> BridgeResponse.json(200, "OK", """{"ok":true}""")
            "pick" -> post(req) { pick(req.query["initial"]) }
            "restore" -> restore(req.query["uri"] ?: throw TreeError(400, "Bad Request", "uri required"))
            "list" -> list(req)
            "stat" -> stat(req)
            "mkdir" -> post(req) { ensureDir(rootOf(req), req.query["path"] ?: ""); ok() }
            "remove" -> post(req) { remove(req) }
            "rename" -> post(req) { rename(req) }
            "token" -> token(req)
            "open" -> post(req) { open(req) }
            "write" -> post(req) { write(req) }
            "close" -> post(req) { close(req) }
            "abort" -> post(req) { abort(req) }
            else -> BridgeResponse.error(404, "Not Found", "tree/${req.subpath}")
        }
    } catch (e: TreeError) {
        BridgeResponse.error(e.status, e.reason, e.detail)
    } catch (e: Exception) {
        android.util.Log.e("leadacid", "tree/${req.subpath} failed", e)
        BridgeResponse.error(500, "Tree Failed", e.toString())
    }

    private inline fun post(req: BridgeRequest, body: () -> BridgeResponse): BridgeResponse =
        if (req.method != "POST") BridgeResponse.error(405, "Method Not Allowed", req.method) else body()

    private fun ok() = BridgeResponse.json(200, "OK", """{"ok":true}""")
    private fun j(s: String) = BridgeResponse.jsonStr(s)
    private fun rootJson(r: Root) = """{"dir":${j(r.id)},"name":${j(r.name)},"uri":${j(r.treeUri.toString())}}"""
    private fun entryJson(e: Entry) =
        """{"name":${j(e.name)},"kind":${j(if (e.isDir) "directory" else "file")},"size":${e.size},"mtime":${e.mtime}}"""

    // ── roots ────────────────────────────────────────────────────────────────

    private fun pick(initial: String?): BridgeResponse {
        val latch = CountDownLatch(1)
        var picked: Uri? = null
        host.activity.runOnUiThread {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
                )
            // where the picker OPENS (a hint, not a constraint — the user navigates anywhere):
            // a documents docId on the primary external storage, e.g. "primary:Download"
            if (!initial.isNullOrEmpty()) runCatching {
                i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", initial))
            }
            val launched = host.startForResult(i) { code, data ->
                if (code == Activity.RESULT_OK) picked = data?.data
                latch.countDown()
            }
            if (!launched) latch.countDown()
        }
        latch.await(10, TimeUnit.MINUTES)
        val uri = picked ?: return BridgeResponse.json(200, "OK", """{"cancelled":true}""")
        runCatching {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        return BridgeResponse.json(200, "OK", rootJson(rootFor(uri)))
    }

    private fun restore(uriStr: String): BridgeResponse {
        val uri = Uri.parse(uriStr)
        val held = resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission && it.isWritePermission }
        if (!held) throw TreeError(404, "Not Found", "permission lost — pick the folder again")
        val root = rootFor(uri)
        if (displayName(root.treeUri, root.rootDocId) == null) throw TreeError(404, "Not Found", "folder gone")
        return BridgeResponse.json(200, "OK", rootJson(root))
    }

    private fun rootFor(uri: Uri): Root {
        val id = "d" + (uri.toString().hashCode().toLong() and 0xffffffffL).toString(16)
        return roots.getOrPut(id) {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            val name = displayName(uri, docId)
                ?: docId.substringAfterLast(':').substringAfterLast('/').ifEmpty { "folder" }
            Root(id, uri, docId, name)
        }
    }

    private fun rootOf(req: BridgeRequest): Root =
        roots[req.query["dir"] ?: ""] ?: throw TreeError(404, "Not Found", "unknown dir — pick or restore first")

    private fun displayName(treeUri: Uri, docId: String): String? = runCatching {
        resolver.query(docUri(treeUri, docId), arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun docUri(treeUri: Uri, docId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
    private fun docUri(root: Root, docId: String): Uri = docUri(root.treeUri, docId)

    // ── resolution ───────────────────────────────────────────────────────────

    private fun children(root: Root, docId: String): List<Entry> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(root.treeUri, docId)
        val out = ArrayList<Entry>()
        val cols = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED,
        )
        resolver.query(uri, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                out += Entry(
                    c.getString(0), c.getString(1) ?: "", c.getString(2) ?: "",
                    if (c.isNull(3)) 0L else c.getLong(3), if (c.isNull(4)) 0L else c.getLong(4),
                )
            }
        }
        return out
    }

    private fun segments(path: String): List<String> {
        val segs = path.split('/').filter { it.isNotEmpty() && it != "." }
        if (segs.any { it == ".." }) throw TreeError(400, "Bad Request", "no '..' in paths")
        return segs
    }

    /** (parentDocId, entry-or-null-when-the-leaf-is-missing). The root itself resolves to a directory entry. */
    private fun resolve(root: Root, path: String): Pair<String, Entry?> {
        val segs = segments(path)
        if (segs.isEmpty()) return root.rootDocId to Entry(root.rootDocId, root.name, Document.MIME_TYPE_DIR, 0, 0)
        var parent = root.rootDocId
        for ((i, s) in segs.withIndex()) {
            val e = children(root, parent).firstOrNull { it.name == s }
            if (i == segs.size - 1) return parent to e
            if (e == null || !e.isDir) throw TreeError(404, "Not Found", "no such directory: " + segs.subList(0, i + 1).joinToString("/"))
            parent = e.docId
        }
        throw IllegalStateException("unreachable")
    }

    private fun require(root: Root, path: String): Entry =
        resolve(root, path).second ?: throw TreeError(404, "Not Found", "no such entry: $path")

    /** Walk/create the directory chain; returns the leaf directory's docId. */
    private fun ensureDir(root: Root, path: String): String {
        var parent = root.rootDocId
        for (s in segments(path)) {
            val e = children(root, parent).firstOrNull { it.name == s }
            parent = when {
                e == null -> {
                    val created = DocumentsContract.createDocument(resolver, docUri(root, parent), Document.MIME_TYPE_DIR, s)
                        ?: throw TreeError(500, "Tree Failed", "could not create directory: $s")
                    DocumentsContract.getDocumentId(created)
                }
                e.isDir -> e.docId
                else -> throw TreeError(409, "Conflict", "a file is in the way: $s")
            }
        }
        return parent
    }

    // ── reads ────────────────────────────────────────────────────────────────

    private fun list(req: BridgeRequest): BridgeResponse {
        val root = rootOf(req)
        val e = require(root, req.query["path"] ?: "")
        if (!e.isDir) throw TreeError(400, "Bad Request", "not a directory")
        return BridgeResponse.json(200, "OK", "[" + children(root, e.docId).joinToString(",") { entryJson(it) } + "]")
    }

    private fun stat(req: BridgeRequest): BridgeResponse =
        BridgeResponse.json(200, "OK", entryJson(require(rootOf(req), req.query["path"] ?: "")))

    private fun token(req: BridgeRequest): BridgeResponse {
        val root = rootOf(req)
        val e = require(root, req.query["path"] ?: "")
        if (e.isDir) throw TreeError(400, "Bad Request", "not a file")
        val token = host.registerFile(docUri(root, e.docId)) ?: throw TreeError(500, "Tree Failed", "could not open ${e.name}")
        return BridgeResponse.json(200, "OK", """{"token":${j(token)},"size":${e.size},"mtime":${e.mtime},"name":${j(e.name)}}""")
    }

    // ── mutations ────────────────────────────────────────────────────────────

    private fun remove(req: BridgeRequest): BridgeResponse {
        val root = rootOf(req)
        val path = req.query["path"] ?: throw TreeError(400, "Bad Request", "path required")
        if (segments(path).isEmpty()) throw TreeError(400, "Bad Request", "cannot remove the root")
        val e = require(root, path)
        if (e.isDir && req.query["recursive"] != "1" && children(root, e.docId).isNotEmpty())
            throw TreeError(400, "Bad Request", "directory not empty (recursive=1 to remove it anyway)")
        if (!DocumentsContract.deleteDocument(resolver, docUri(root, e.docId))) throw TreeError(500, "Tree Failed", "delete refused")
        return ok()
    }

    private fun rename(req: BridgeRequest): BridgeResponse {
        val root = rootOf(req)
        val e = require(root, req.query["path"] ?: throw TreeError(400, "Bad Request", "path required"))
        val to = req.query["name"]?.takeIf { it.isNotEmpty() && !it.contains('/') } ?: throw TreeError(400, "Bad Request", "name required")
        DocumentsContract.renameDocument(resolver, docUri(root, e.docId), to) ?: throw TreeError(500, "Tree Failed", "rename refused")
        return ok()
    }

    private fun open(req: BridgeRequest): BridgeResponse {
        val root = rootOf(req)
        val path = req.query["path"] ?: throw TreeError(400, "Bad Request", "path required")
        val segs = segments(path)
        if (segs.isEmpty()) throw TreeError(400, "Bad Request", "path names no file")
        val finalName = segs.last()
        val parent = ensureDir(root, segs.dropLast(1).joinToString("/"))
        val partName = ".$finalName.part"
        children(root, parent).firstOrNull { it.name == partName }?.let { DocumentsContract.deleteDocument(resolver, docUri(root, it.docId)) }
        val partUri = DocumentsContract.createDocument(resolver, docUri(root, parent), "application/octet-stream", partName)
            ?: throw TreeError(500, "Tree Failed", "could not create $partName")
        val out = resolver.openOutputStream(partUri, "wt") ?: throw TreeError(500, "Tree Failed", "could not open $partName for writing")
        val id = "w" + wSeq.incrementAndGet()
        writers[id] = Writer(root, parent, finalName, partUri, out)
        return BridgeResponse.json(200, "OK", """{"w":${j(id)}}""")
    }

    private fun writerOf(req: BridgeRequest): Pair<String, Writer> {
        val id = req.query["w"] ?: throw TreeError(400, "Bad Request", "w required")
        return id to (writers[id] ?: throw TreeError(404, "Not Found", "no such writer (closed?)"))
    }

    private fun write(req: BridgeRequest): BridgeResponse {
        val (_, w) = writerOf(req)
        val body = req.body ?: ByteArray(0)
        synchronized(w) { w.out.write(body); w.bytes += body.size }
        return BridgeResponse.json(200, "OK", """{"bytes":${w.bytes}}""")
    }

    private fun close(req: BridgeRequest): BridgeResponse {
        val (id, w) = writerOf(req)
        writers.remove(id)
        synchronized(w) { w.out.flush(); w.out.close() }
        // swap: drop the old target, rename the part into its name
        children(w.root, w.parentDocId).firstOrNull { it.name == w.finalName }?.let {
            DocumentsContract.deleteDocument(resolver, docUri(w.root, it.docId))
        }
        val renamed = runCatching { DocumentsContract.renameDocument(resolver, w.partUri, w.finalName) }.getOrNull()
        if (renamed == null) {
            // a provider that won't rename: copy the part into a fresh target, then drop the part
            val target = DocumentsContract.createDocument(resolver, docUri(w.root, w.parentDocId), "application/octet-stream", w.finalName)
                ?: throw TreeError(500, "Tree Failed", "could not create ${w.finalName}")
            resolver.openInputStream(w.partUri)!!.use { inp -> resolver.openOutputStream(target, "wt")!!.use { out -> inp.copyTo(out) } }
            DocumentsContract.deleteDocument(resolver, w.partUri)
        }
        return BridgeResponse.json(200, "OK", """{"ok":true,"size":${w.bytes}}""")
    }

    private fun abort(req: BridgeRequest): BridgeResponse {
        val (id, w) = writerOf(req)
        writers.remove(id)
        runCatching { synchronized(w) { w.out.close() } }
        runCatching { DocumentsContract.deleteDocument(resolver, w.partUri) }
        return ok()
    }
}

/** The factory an instrument lists: `override val plugins = listOf(Intake, Tree)`. No permission — the pick IS the grant. */
val Tree = PluginFactory { host -> TreePlugin(host) }
