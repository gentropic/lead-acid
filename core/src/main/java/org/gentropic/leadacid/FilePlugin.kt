package org.gentropic.leadacid

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Ranged file serving off mmap (SPEC §4.1 — "the load-bearing feature").
 * Measured 11.5× per read / ~30× scattered vs Blob.slice on the same SAF doc.
 *
 * Routes:
 *   GET /native/fs/{token}   — full (200) or `Range: bytes=…` slice (206/416)
 *   GET /native/fs/list      — registered tokens + sizes
 *   ANY /native/fs/echo      — interceptor diagnostic (what crosses the bridge)
 *
 * Files serve through a WINDOW of 256 MiB segment maps created on demand, so a
 * file larger than the 2 GiB MappedByteBuffer cap serves fine — only the touched
 * segments map, and each map is < 2 GiB. SAF documents keep their fd open for the
 * source's lifetime; `releaseSaf` closes them (page-generation lifecycle).
 */
/**
 * A streaming publish target (fs/publish, fs/publish/open): bytes go to `out`,
 * `finish` makes the row visible (IS_PENDING → 0) and returns the ACTUAL display
 * name (MediaStore de-duplicates a collision as "name (1).ext"), `abort` drops it.
 */
class PublishSink(val uri: String, val out: java.io.OutputStream, val finish: () -> String?, val abort: () -> Unit)

class FilePlugin(
    files: Map<String, java.io.File>,
    /** name, collection, mime → an open streaming target in a public collection, or null. */
    private val opener: ((String, String, String) -> PublishSink?)? = null,
) : Plugin {
    override val name = "fs"

    private val sources = HashMap<String, Source>()
    private val builtins = HashSet<String>()

    init {
        for ((token, file) in files) {
            val ch = RandomAccessFile(file, "r").channel
            sources[token] = Source(ch, file.length(), ch)
            builtins += token
        }
    }

    /** A file served through lazily-mapped fixed-size segments (no 2 GiB cap). */
    private class Source(
        private val channel: FileChannel,
        val size: Long,
        private val closeable: Closeable?,
    ) {
        val segSize = 256L * 1024 * 1024              // < 2 GiB Int cap, generous stride
        private val segs = HashMap<Int, MappedByteBuffer>()

        @Synchronized
        fun segment(i: Int): MappedByteBuffer = segs.getOrPut(i) {
            val off = i.toLong() * segSize
            channel.map(FileChannel.MapMode.READ_ONLY, off, minOf(segSize, size - off))
        }

        @Synchronized
        fun release() {
            segs.clear()
            runCatching { channel.close() }
            runCatching { closeable?.close() }
        }
    }

    /**
     * A stream over [pos, end) that spans segment boundaries, with O(1) skip —
     * the loader-applies-range contract (SPIKE FINDING): we hand the stream from
     * position 0 capped at end, and WebView's loader skips `start` itself.
     */
    private class WindowedInputStream(private val src: Source, private var pos: Long, private val end: Long) : InputStream() {
        override fun read(): Int {
            if (pos >= end) return -1
            val seg = src.segment((pos / src.segSize).toInt())
            val b = seg.get((pos % src.segSize).toInt()).toInt() and 0xFF
            pos++
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (pos >= end) return -1
            val n = minOf(len.toLong(), end - pos).toInt()
            var done = 0
            while (done < n) {
                val segIdx = (pos / src.segSize).toInt()
                val segOff = (pos % src.segSize).toInt()
                val chunk = minOf((n - done).toLong(), src.segSize - segOff).toInt()
                val dup = src.segment(segIdx).duplicate().apply { position(segOff) }
                dup.get(b, off + done, chunk)
                done += chunk; pos += chunk
            }
            return n
        }

        override fun skip(n: Long): Long {
            val step = maxOf(0, minOf(n, end - pos))
            pos += step
            return step
        }

        override fun available(): Int = minOf(end - pos, Int.MAX_VALUE.toLong()).toInt()
    }

    /** Register a SAF document by URI: keep its fd open, map segments on demand. */
    @Synchronized
    fun registerSaf(uri: Uri, resolver: ContentResolver): String? {
        val token = tokenFor(uri)
        if (sources.containsKey(token)) return token
        return try {
            val pfd: ParcelFileDescriptor = resolver.openFileDescriptor(uri, "r")!!
            val fis = java.io.FileInputStream(pfd.fileDescriptor)   // retained for lazy mapping
            sources[token] = Source(fis.channel, pfd.statSize, Closeable { fis.close(); pfd.close() })
            token
        } catch (e: Exception) {
            android.util.Log.e("leadacid", "registerSaf failed for $uri", e)
            null
        }
    }

    /**
     * Serve an app-private file under a durable token (survives page starts —
     * a plugin's finished output, e.g. a sealed GNSS log). Re-registering a
     * token replaces it (the file may have grown since).
     */
    @Synchronized
    fun registerBuiltin(token: String, file: java.io.File): String {
        sources.remove(token)?.release()
        val ch = RandomAccessFile(file, "r").channel
        sources[token] = Source(ch, file.length(), ch)
        builtins += token
        return token
    }

    /** Page-generation lifecycle: drop all SAF sources (keep built-ins). */
    @Synchronized
    fun releaseSaf() {
        val drop = sources.keys.filter { it !in builtins }
        for (t in drop) { sources.remove(t)?.release() }
    }

    private fun tokenFor(uri: Uri): String {
        val name = (uri.lastPathSegment ?: "doc").substringAfterLast('/')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
        return "saf-${(uri.toString().hashCode().toLong() and 0xffffffffL).toString(16)}-$name"
    }

    override fun handle(req: BridgeRequest): BridgeResponse {
        if (req.subpath == "echo") return echo(req)
        if (req.subpath == "list") return list()
        if (req.subpath == "publish") return publish(req)
        if (req.subpath.startsWith("publish/")) return publishStream(req)

        val src = synchronized(this) { sources[req.subpath] }
            ?: return BridgeResponse.error(404, "Not Found", "unknown token: ${req.subpath}")
        if (req.method != "GET" && req.method != "HEAD")
            return BridgeResponse.error(405, "Method Not Allowed", req.method)

        val total = src.size
        val rangeHeader = req.header("Range")
        val base = mapOf("Accept-Ranges" to "bytes")

        if (rangeHeader == null) {
            return BridgeResponse(
                200, "OK", "application/octet-stream",
                base + mapOf("Content-Length" to total.toString()),
                WindowedInputStream(src, 0, total),
            )
        }

        val range = parseRange(rangeHeader, total)
        if (range == null) {
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
        // The loader skips `start` off this stream itself; hand it 0..end.
        return BridgeResponse(
            206, "Partial Content", "application/octet-stream",
            base + mapOf(
                "Content-Range" to "bytes $start-$endIncl/$total",
                "Content-Length" to len.toString(),
            ),
            WindowedInputStream(src, 0, endIncl + 1),
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
            left.isEmpty() -> {
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

    /**
     * POST /native/fs/publish?name=&collection=&mime= — insert a finished output
     * into a public MediaStore collection (survives uninstall, visible to other
     * apps). The body rides the §4.3 port sidecar. SPEC §5.1 fs.publish.
     */
    private fun publish(req: BridgeRequest): BridgeResponse {
        if (req.method != "POST")
            return BridgeResponse.error(405, "Method Not Allowed", req.method)
        val bytes = req.body
            ?: return BridgeResponse.error(400, "Bad Request", "no body (port sidecar delivered nothing)")
        val open = opener
            ?: return BridgeResponse.error(503, "Not Available", "no publisher on this host")
        val name = req.query["name"] ?: "leadacid-output"
        val collection = req.query["collection"] ?: "Downloads"
        val mime = req.query["mime"] ?: "application/octet-stream"
        val sink = open(name, collection, mime)
            ?: return BridgeResponse.error(500, "Publish Failed", "MediaStore insert returned null")
        val actual = try { sink.out.use { it.write(bytes) }; sink.finish() ?: name } catch (e: Exception) {
            runCatching { sink.abort() }
            return BridgeResponse.error(500, "Publish Failed", e.toString())
        }
        return BridgeResponse.json(
            200, "OK",
            """{"uri":${BridgeResponse.jsonStr(sink.uri)},"name":${BridgeResponse.jsonStr(actual)},"bytes":${bytes.size}}""",
        )
    }

    // ── streaming publish: open → write… → close (the row is PENDING until close) ──
    // A multi-GB export never sits in page memory: the artifact pipes a stream in
    // ≥4 MiB requests; abort drops the pending row so nothing half-written shows.
    private class PubWriter(val sink: PublishSink, val name: String) { var bytes = 0L }
    private val pubWriters = java.util.concurrent.ConcurrentHashMap<String, PubWriter>()
    private val pubSeq = java.util.concurrent.atomic.AtomicInteger(0)

    private fun publishStream(req: BridgeRequest): BridgeResponse {
        if (req.method != "POST") return BridgeResponse.error(405, "Method Not Allowed", req.method)
        val op = req.subpath.removePrefix("publish/")
        if (op == "open") {
            val open = opener ?: return BridgeResponse.error(503, "Not Available", "no publisher on this host")
            val name = req.query["name"] ?: "leadacid-output"
            val sink = open(name, req.query["collection"] ?: "Downloads", req.query["mime"] ?: "application/octet-stream")
                ?: return BridgeResponse.error(500, "Publish Failed", "MediaStore insert returned null")
            val id = "p" + pubSeq.incrementAndGet()
            pubWriters[id] = PubWriter(sink, name)
            return BridgeResponse.json(200, "OK", """{"w":${BridgeResponse.jsonStr(id)},"uri":${BridgeResponse.jsonStr(sink.uri)}}""")
        }
        val id = req.query["w"] ?: return BridgeResponse.error(400, "Bad Request", "w required")
        val w = pubWriters[id] ?: return BridgeResponse.error(404, "Not Found", "no such writer (closed?)")
        return when (op) {
            "write" -> {
                val b = req.body ?: ByteArray(0)
                synchronized(w) { w.sink.out.write(b); w.bytes += b.size }
                BridgeResponse.json(200, "OK", """{"bytes":${w.bytes}}""")
            }
            "close" -> {
                pubWriters.remove(id)
                val actual = try { synchronized(w) { w.sink.out.flush(); w.sink.out.close() }; w.sink.finish() ?: w.name } catch (e: Exception) {
                    runCatching { w.sink.abort() }
                    return BridgeResponse.error(500, "Publish Failed", e.toString())
                }
                BridgeResponse.json(200, "OK", """{"uri":${BridgeResponse.jsonStr(w.sink.uri)},"name":${BridgeResponse.jsonStr(actual)},"bytes":${w.bytes}}""")
            }
            "abort" -> {
                pubWriters.remove(id)
                runCatching { synchronized(w) { w.sink.out.close() } }
                runCatching { w.sink.abort() }
                BridgeResponse.json(200, "OK", """{"ok":true}""")
            }
            else -> BridgeResponse.error(404, "Not Found", "fs/publish/$op")
        }
    }

    @Synchronized
    private fun list(): BridgeResponse {
        val items = sources.entries.joinToString(",") {
            """{"token":${BridgeResponse.jsonStr(it.key)},"size":${it.value.size}}"""
        }
        return BridgeResponse.json(200, "OK", "[$items]")
    }

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
