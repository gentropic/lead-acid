package org.gentropic.leadacid.share

import android.content.Intent
import androidx.core.content.FileProvider
import org.gentropic.leadacid.BridgeRequest
import org.gentropic.leadacid.BridgeResponse
import org.gentropic.leadacid.Plugin
import org.gentropic.leadacid.PluginFactory
import org.gentropic.leadacid.PluginHost
import java.io.File

/**
 * Outbound share to the system share sheet (SPEC §5.1) — the Web Share API is
 * absent in WebView. The share sheet is inherently user-mediated (they pick the
 * target and confirm there), so firing the chooser is a Regular action.
 *
 *   POST /native/share?name=&mime=&text=   — bytes (body sidecar) → ACTION_SEND
 *                                            a file; or, with no body, share text
 *
 * The Activity-touching part (write cache file, FileProvider URI, chooser) is a
 * lambda so the plugin stays context-free; [Share] binds it to the host.
 */
class SharePlugin(
    /** name, mime, text?, bytes? → launched the chooser? */
    private val share: (String, String, String?, ByteArray?) -> Boolean,
) : Plugin {
    override val name = "share"

    override fun handle(req: BridgeRequest): BridgeResponse {
        if (req.method != "POST")
            return BridgeResponse.error(405, "Method Not Allowed", req.method)
        val name = req.query["name"] ?: "shared"
        val mime = req.query["mime"] ?: "application/octet-stream"
        val text = req.query["text"]
        if (req.body == null && text == null)
            return BridgeResponse.error(400, "Bad Request", "nothing to share (no body, no text)")
        return if (share(name, mime, text, req.body)) BridgeResponse.json(200, "OK", """{"ok":true}""")
        else BridgeResponse.error(500, "Share Failed", "could not launch the share sheet")
    }
}

/**
 * The factory an instrument lists. The FileProvider authority is
 * `<applicationId>.fileprovider` — declared by THIS module's manifest, so an
 * instrument without plugin-share has no provider at all.
 */
val Share = PluginFactory { host -> SharePlugin(shareOut(host)) }

private fun shareOut(host: PluginHost): (String, String, String?, ByteArray?) -> Boolean =
    { name, mime, text, bytes ->
        val ctx = host.context
        try {
            val send = Intent(Intent.ACTION_SEND)
            if (text != null) send.putExtra(Intent.EXTRA_TEXT, text)
            if (bytes != null) {
                val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
                val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "shared" }
                val f = File(dir, safe).apply { writeBytes(bytes) }
                val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
                send.type = mime
                send.putExtra(Intent.EXTRA_STREAM, uri)
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                send.type = "text/plain"
            }
            host.activity.startActivity(Intent.createChooser(send, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: Exception) {
            android.util.Log.e("leadacid", "shareOut failed", e); false
        }
    }
