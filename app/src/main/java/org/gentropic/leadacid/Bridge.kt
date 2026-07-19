package org.gentropic.leadacid

import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.ByteBuffer

/**
 * The bridge contract (SPEC §4.1).
 *
 * A plugin owns one path segment under /native/ and answers request/reply calls.
 * Note: WebResourceRequest carries NO request body (long-standing Chromium
 * limitation, crbug 155250) — `handle` receives method + subpath + query +
 * headers only. Spike 0 records this; body-carrying calls are a design item
 * for M0 (candidates: WebMessagePort per SPEC §4.3, or body-in-header for
 * small JSON).
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
        res.responseHeaders = HashMap(headers) + mapOf("Cache-Control" to "no-store")
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

/** Streams a ByteBuffer slice without copying it whole — the mmap serving path. */
class ByteBufferInputStream(private val buf: ByteBuffer) : InputStream() {
    override fun read(): Int =
        if (buf.hasRemaining()) buf.get().toInt() and 0xFF else -1

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (!buf.hasRemaining()) return -1
        val n = minOf(len, buf.remaining())
        buf.get(b, off, n)
        return n
    }

    override fun available(): Int = buf.remaining()

    /** O(1) — the loader may skip() gigabytes to apply a Range; never read-through. */
    override fun skip(n: Long): Long {
        if (n <= 0) return 0
        val k = minOf(n, buf.remaining().toLong())
        buf.position(buf.position() + k.toInt())
        return k
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
