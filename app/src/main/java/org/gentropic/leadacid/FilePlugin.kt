package org.gentropic.leadacid

import java.io.File
import java.io.RandomAccessFile
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Ranged file serving off mmap (SPEC §4.1 — "the load-bearing feature").
 *
 * Routes:
 *   GET /native/fs/{token}          — full file (200) or `Range: bytes=…` slice (206/416)
 *   ANY /native/fs/echo             — spike diagnostic: reports what the interceptor saw
 *                                     (method + headers), proving what does/doesn't cross
 *
 * Spike version: tokens are a fixed map handed in by the activity. The real
 * plugin adds SAF publishing and token issuance (SPEC §5.1).
 */
class FilePlugin(private val files: Map<String, File>) : Plugin {
    override val name = "fs"

    private val mapped = HashMap<String, MappedByteBuffer>()

    @Synchronized
    private fun mmap(token: String, file: File): MappedByteBuffer =
        mapped.getOrPut(token) {
            RandomAccessFile(file, "r").use { raf ->
                raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length())
            }
        }

    override fun handle(req: BridgeRequest): BridgeResponse {
        if (req.subpath == "echo") return echo(req)

        val file = files[req.subpath]
            ?: return BridgeResponse.error(404, "Not Found", "unknown token: ${req.subpath}")
        if (req.method != "GET" && req.method != "HEAD")
            return BridgeResponse.error(405, "Method Not Allowed", req.method)

        val total = file.length()
        val buf = mmap(req.subpath, file)
        val rangeHeader = req.header("Range")
        val base = mapOf("Accept-Ranges" to "bytes")

        // Spike diagnostic: ?mode=full serves the WHOLE stream as 200 even for
        // ranged requests — testing whether AndroidStreamReaderURLLoader applies
        // the Range itself by skipping our InputStream.
        if (rangeHeader == null || req.query["mode"] == "full") {
            val slice = buf.duplicate().apply { position(0); limit(total.toInt()) }
            return BridgeResponse(
                200, "OK", "application/octet-stream",
                base + mapOf("Content-Length" to total.toString()),
                ByteBufferInputStream(slice.slice()),
            )
        }

        val range = parseRange(rangeHeader, total)
        if (range == null) {
            // The loader still applies the request's Range to OUR stream — give
            // it a phantom big enough to skip the requested start, so it reaches
            // EOF instead of erroring (short stream → net error → "Failed to fetch").
            val reqStart = rangeHeader.substringAfter("bytes=").substringBefore('-')
                .trim().toLongOrNull() ?: total
            return BridgeResponse(
                416, "Range Not Satisfiable", "application/json",
                base + mapOf("Content-Range" to "bytes */$total"),
                PhantomSkipStream(maxOf(total, reqStart + 1)),
            )
        }

        val (start, endIncl) = range
        val len = endIncl - start + 1
        // SPIKE FINDING (S24+, WebView 150): AndroidStreamReaderURLLoader applies
        // the request Range to the app's InputStream ITSELF — it skips `start`
        // bytes (and resolves suffixes via available()), but never truncates the
        // end. Contract: hand it the stream from position 0 CAPPED at end+1;
        // do NOT pre-skip, or the loader's own skip runs off the end.
        val slice = buf.duplicate().apply { position(0); limit((endIncl + 1).toInt()) }
        return BridgeResponse(
            206, "Partial Content", "application/octet-stream",
            base + mapOf(
                "Content-Range" to "bytes $start-$endIncl/$total",
                "Content-Length" to len.toString(),
            ),
            ByteBufferInputStream(slice.slice()),
        )
    }

    /** Single-range `bytes=a-b` | `bytes=a-` | `bytes=-n`. Multi-range → null (416). */
    private fun parseRange(header: String, total: Long): Pair<Long, Long>? {
        val spec = header.trim().removePrefix("bytes=").trim()
        if (spec.isEmpty() || spec.contains(',')) return null
        val dash = spec.indexOf('-')
        if (dash < 0) return null
        val left = spec.substring(0, dash).trim()
        val right = spec.substring(dash + 1).trim()
        return when {
            left.isEmpty() -> { // suffix: last n bytes
                val n = right.toLongOrNull() ?: return null
                if (n <= 0) return null
                val start = maxOf(0, total - n)
                if (start >= total) null else Pair(start, total - 1)
            }
            else -> {
                val start = left.toLongOrNull() ?: return null
                val end = if (right.isEmpty()) total - 1 else right.toLongOrNull() ?: return null
                if (start >= total || end < start) null else Pair(start, minOf(end, total - 1))
            }
        }
    }

    /** What did the interceptor actually see? (POST bodies are expected to be absent.) */
    private fun echo(req: BridgeRequest): BridgeResponse {
        val headersJson = req.headers.entries.joinToString(",") {
            "${BridgeResponse.jsonStr(it.key)}:${BridgeResponse.jsonStr(it.value)}"
        }
        return BridgeResponse.json(
            200, "OK",
            """{"method":${BridgeResponse.jsonStr(req.method)},"headers":{$headersJson},"bodyObservable":false}""",
        )
    }
}
