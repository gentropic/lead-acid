package org.gentropic.leadacid

import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * The bridge contract (SPEC §4.1).
 *
 * A plugin owns one path segment under /native/ and answers request/reply calls.
 * WebResourceRequest carries NO request body (Chromium crbug 155250) — so bodies
 * ride the WebMessagePort sidecar (SPEC §4.3): the artifact posts [id | bytes]
 * over the port, tags the fetch with X-LeadAcid-Body-Id, and the shell joins the
 * two, delivering `req.body`. `shell.native({body})` hides all of this.
 */
interface Plugin {
    /** Path segment after /native/, e.g. "fs". */
    val name: String
    fun handle(req: BridgeRequest): BridgeResponse
}

class BridgeRequest(
    val method: String,
    /** Path after /native/{plugin}/, no leading slash. */
    val subpath: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    /** Request body, joined from the port sidecar; null if the call carried none. */
    val body: ByteArray? = null,
) {
    /** Case-insensitive header lookup. */
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

class BridgeResponse(
    val status: Int,
    val reason: String,
    val mime: String,
    val headers: Map<String, String> = emptyMap(),
    val body: InputStream = ByteArrayInputStream(ByteArray(0)),
) {
    fun toWebResponse(): WebResourceResponse {
        val res = WebResourceResponse(mime, null, body)
        res.setStatusCodeAndReasonPhrase(status, reason)
        // no-store is the default posture (don't churn the 20 MB WebView HTTP
        // cache with slices of huge files) — a plugin's own Cache-Control wins.
        val h = HashMap(headers)
        h.putIfAbsent("Cache-Control", "no-store")
        res.responseHeaders = h
        return res
    }

    companion object {
        fun json(status: Int, reason: String, body: String, extra: Map<String, String> = emptyMap()) =
            BridgeResponse(
                status, reason, "application/json", extra,
                ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)),
            )

        fun error(status: Int, reason: String, detail: String) =
            json(status, reason, """{"error":${jsonStr(reason)},"detail":${jsonStr(detail)}}""")

        fun jsonStr(s: String): String =
            "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""
    }
}

/**
 * A stream of `size` phantom bytes: skippable in O(1), unreadable (instant EOF
 * once skipped past). Lets the loader "apply" an unsatisfiable Range without
 * erroring — used for 416 responses.
 */
class PhantomSkipStream(private var remaining: Long) : InputStream() {
    override fun read(): Int = -1
    override fun read(b: ByteArray, off: Int, len: Int): Int = -1
    override fun available(): Int = minOf(remaining, Int.MAX_VALUE.toLong()).toInt()
    override fun skip(n: Long): Long {
        val k = maxOf(0, minOf(n, remaining))
        remaining -= k
        return k
    }
}
