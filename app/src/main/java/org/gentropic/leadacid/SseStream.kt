package org.gentropic.leadacid

import java.io.InputStream
import java.util.concurrent.LinkedBlockingQueue

/**
 * An InputStream that carries a Server-Sent-Events feed (SPEC §4.2). A plugin
 * pushes events from any thread via `emit`/`comment`; the WebView's network
 * thread reads them out (blocking until the next event). Non-blocking producer:
 * a bounded queue that drops the OLDEST frame on overflow — sensor/telemetry
 * data is fine to drop under a slow consumer, and the producer must never block
 * the main/sensor thread.
 *
 * **V-1 FINDING (resolved on device):** `shouldInterceptRequest` buffers the
 * response ~2 KiB before delivering it to the page, which batches SSE events
 * into ~400 ms clumps. Constructing with `padTo > 0` appends a comment after
 * each event so every frame exceeds the buffer and flushes immediately — per-
 * event delivery at ~20 ms, 30 ms startup. Cost is trivial on a local
 * (non-network) stream. Binary firehoses still prefer port streams (§4.3).
 *
 * `onClose` fires when the WebView tears down the response (EventSource.close(),
 * navigation) — the plugin unregisters its source there.
 */
class SseStream(capacity: Int = 512, padTo: Int = 0) : InputStream() {
    private val queue = LinkedBlockingQueue<ByteArray>(capacity)
    private val eof = ByteArray(0)
    private var buf: ByteArray = ByteArray(0)
    private var pos = 0
    @Volatile private var closed = false

    private val padFrame: ByteArray? =
        if (padTo > 0) (": " + "-".repeat(padTo) + "\n\n").toByteArray(Charsets.UTF_8) else null

    var onClose: (() -> Unit)? = null

    private fun enqueue(bytes: ByteArray) {
        if (closed) return
        if (!queue.offer(bytes)) { queue.poll(); queue.offer(bytes) }   // drop oldest
    }

    /** One SSE event. `data` may be multi-line; each line gets its own `data:`. */
    fun emit(event: String?, data: String) {
        val sb = StringBuilder()
        if (event != null) sb.append("event: ").append(event).append('\n')
        for (line in data.split('\n')) sb.append("data: ").append(line).append('\n')
        sb.append('\n')
        enqueue(sb.toString().toByteArray(Charsets.UTF_8))
        padFrame?.let(::enqueue)          // flush past the interceptor's buffer
    }

    /** A `:` comment line — used for the anti-buffering preamble + heartbeats. */
    fun comment(text: String) = enqueue((": $text\n\n").toByteArray(Charsets.UTF_8))

    private fun ensure(): Boolean {
        while (pos >= buf.size) {
            if (closed && queue.isEmpty()) return false
            val next = try { queue.take() } catch (e: InterruptedException) { return false }
            if (next === eof) return false
            buf = next; pos = 0
        }
        return true
    }

    override fun read(): Int {
        if (!ensure()) return -1
        return buf[pos++].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (!ensure()) return -1
        val n = minOf(len, buf.size - pos)
        System.arraycopy(buf, pos, b, off, n); pos += n
        return n
    }

    override fun close() {
        if (closed) return
        closed = true
        queue.offer(eof)          // wake a reader blocked in take()
        onClose?.invoke()
    }
}
