package org.gentropic.leadacid.intake

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.IntentCompat
import org.gentropic.leadacid.BridgeRequest
import org.gentropic.leadacid.BridgeResponse
import org.gentropic.leadacid.Plugin
import org.gentropic.leadacid.PluginFactory
import org.gentropic.leadacid.PluginHost
import org.gentropic.leadacid.PortStream

/**
 * The share sheet as INPUT (SPEC §5.1 `intake`) — share's inbound twin. Files
 * or text shared TO the instrument (ACTION_SEND / SEND_MULTIPLE / VIEW — the
 * instrument's manifest says which mime types) are queued and handed to the
 * page over the push lane.
 *
 *   GET /native/intake/stream   — open a port stream; every queued item is
 *                                 delivered as an `item` event, then live ones
 *   GET /native/intake/pending  — the queue as JSON (for pages that poll)
 *
 * An item: {kind:"file", token, name, size, mime} — the file is registered as an
 * fs token at DELIVERY time, not at intent time: a share that LAUNCHES the
 * instrument arrives in onCreate before the page exists, and the page-generation
 * lifecycle releases SAF tokens on every page start. Registering late keeps the
 * token alive for the page that asked. Or {kind:"text", text, mime}.
 */
class IntakePlugin(private val host: PluginHost) : Plugin {
    override val name = "intake"

    private class Item(val uri: Uri?, val text: String?, val mime: String?)
    private val pending = ArrayDeque<Item>()
    private var stream: PortStream? = null

    init { host.onIntent(::accept) }

    /** Called on the main thread with every intent the activity receives. */
    fun accept(intent: Intent) {
        val mime = intent.type
        val items = when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                when {
                    uri != null -> listOf(Item(uri, null, mime))
                    text != null -> listOf(Item(null, text, mime ?: "text/plain"))
                    else -> emptyList()
                }
            }
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?.map { Item(it, null, mime) } ?: emptyList()
            Intent.ACTION_VIEW -> intent.data?.let { listOf(Item(it, null, mime)) } ?: emptyList()
            else -> emptyList()
        }
        if (items.isEmpty()) return
        synchronized(pending) { pending.addAll(items) }
        deliver()
    }

    override fun handle(req: BridgeRequest): BridgeResponse = when (req.subpath) {
        "stream" -> {
            synchronized(pending) {
                stream?.close()
                stream = host.pushBus.open(onClose = { synchronized(pending) { stream = null } })
            }
            deliver()
            BridgeResponse.json(200, "OK", """{"stream":${BridgeResponse.jsonStr(stream!!.id)}}""")
        }
        "pending" -> {
            val items = synchronized(pending) { pending.toList().also { pending.clear() } }
            BridgeResponse.json(200, "OK", "[${items.joinToString(",") { itemJson(it) }}]")
        }
        else -> BridgeResponse.error(404, "Not Found", "intake/${req.subpath}")
    }

    /** Push everything queued to the open stream (no stream → it waits). */
    private fun deliver() {
        val s: PortStream
        val items: List<Item>
        synchronized(pending) {
            s = stream ?: return
            items = pending.toList(); pending.clear()
        }
        for (it in items) s.emit("item", itemJson(it))
    }

    private fun itemJson(item: Item): String {
        if (item.uri == null) {
            return """{"kind":"text","text":${BridgeResponse.jsonStr(item.text ?: "")},"mime":${BridgeResponse.jsonStr(item.mime ?: "text/plain")}}"""
        }
        val uri = item.uri
        val resolver = host.context.contentResolver
        var name = uri.lastPathSegment ?: "shared"
        var size = -1L
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { i -> c.getString(i)?.let { name = it } }
                    c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { i -> if (!c.isNull(i)) size = c.getLong(i) }
                }
            }
        }
        val mime = item.mime ?: runCatching { resolver.getType(uri) }.getOrNull() ?: "application/octet-stream"
        val token = host.registerFile(uri)
            ?: return """{"kind":"error","name":${BridgeResponse.jsonStr(name)},"error":"could not open the shared file"}"""
        return """{"kind":"file","token":${BridgeResponse.jsonStr(token)},"name":${BridgeResponse.jsonStr(name)},"size":$size,"mime":${BridgeResponse.jsonStr(mime)}}"""
    }
}

/**
 * The factory an instrument lists. The instrument's manifest decides WHAT it
 * takes in — an intent-filter on its activity for SEND / SEND_MULTIPLE / VIEW
 * with the mime types it wants (see template/src/main/AndroidManifest.xml).
 */
val Intake = PluginFactory { host -> IntakePlugin(host) }
