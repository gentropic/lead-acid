package org.gentropic.leadacid

/**
 * Native → page push over the WebMessagePort (SPEC §4.2/§4.3). The shell
 * implements [PushBus]; a plugin opens a [PortStream] and emits events, which
 * the shell posts over the port as `{s:id, e:event, d:data}` messages. Unlike
 * SSE through `shouldInterceptRequest`, port messages are NOT buffered — no
 * ~2 KiB batching, no padding, direct delivery.
 */
interface PushBus {
    /** Open a stream; `onClose` runs when the page closes it or the page reloads. */
    fun open(onClose: () -> Unit): PortStream
}

interface PortStream {
    val id: String
    /** Push one event. `data` is a raw JSON string (becomes the message's `d`). */
    fun emit(event: String, data: String)
    /** Shell-initiated close (page reload / pause). */
    fun close()
}
