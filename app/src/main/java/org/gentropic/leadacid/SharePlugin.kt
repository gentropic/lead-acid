package org.gentropic.leadacid

/**
 * Outbound share to the system share sheet (SPEC §5.1) — the Web Share API is
 * absent in WebView. The share sheet is inherently user-mediated (they pick the
 * target and confirm there), so firing the chooser is a Regular action.
 *
 *   POST /native/share?name=&mime=&text=   — bytes (body sidecar) → ACTION_SEND
 *                                            a file; or, with no body, share text
 *
 * The Activity-touching part (write cache file, FileProvider URI, chooser) is a
 * lambda so the plugin stays context-free.
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
