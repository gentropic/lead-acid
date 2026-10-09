package org.gentropic.leadacid.speech

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import org.gentropic.leadacid.BridgeRequest
import org.gentropic.leadacid.BridgeResponse
import org.gentropic.leadacid.Plugin
import org.gentropic.leadacid.PluginFactory
import org.gentropic.leadacid.PluginHost
import org.gentropic.leadacid.PortStream
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * On-device speech recognition (spec: speech-plugin-spec.md). The phone listens,
 * the device's OWN recognizer turns speech into text, the text is pushed to the
 * page. The page never sees audio; this plugin never holds audio (the recognizer
 * service captures it). ONLY `createOnDeviceSpeechRecognizer` (API 31+, on-device
 * by contract or it fails) — never the networked recognizer, never a fallback.
 *
 *   GET  /native/speech/support?lang=     → {onDevice, sdk, language:{tag, installed}, installed?, downloadable?}
 *   GET  /native/speech/permission[?request=1] → {granted, canRequest}
 *   GET  /native/speech/stream?lang=&partial=1&continuous=0&bias=a,b&format=quality&rms=0 → {stream}
 *        events: ready {lang, onDevice, bias} · speech {on} · rms {db} · partial {text}
 *                result {alternatives:[{text, confidence}]} · error {code, reason} · end {}
 *   POST /native/speech/stop               → finish the utterance (closing the stream discards)
 *
 *   403 {error, detail, canRequest}  — RECORD_AUDIO refused (asked INLINE on the first stream/permission?request=1)
 *   503 {error, capability:"speech.ondevice"} — no on-device recognizer, or no pack for `lang` (API 33+)
 *   409 {error, holder}              — a stream is already open (one listener per page)
 *
 * SpeechRecognizer is created and driven on the main thread; handle() runs off it.
 * The stream is a PortStream, so a page reload or onPause closes it, which cancels.
 */
class SpeechPlugin(private val host: PluginHost) : Plugin {
    override val name = "speech"

    private class Session(val stream: PortStream, var recognizer: SpeechRecognizer?, val intent: Intent, val continuous: Boolean) {
        @Volatile var closed = false
    }
    @Volatile private var current: Session? = null

    override fun handle(req: BridgeRequest): BridgeResponse = when (req.subpath) {
        "support" -> support(req.query["lang"] ?: Locale.getDefault().toLanguageTag())
        "permission" -> permission(req.query["request"] != null)
        "stream" -> withPermission { stream(req) }
        "stop" -> stop()
        else -> BridgeResponse.error(404, "Not Found", "speech/${req.subpath}")
    }

    // ── permission: asked inline (the shipped gnss / camera pattern) ─────────────
    private fun declared(): Boolean = runCatching {
        host.context.packageManager.getPackageInfo(host.context.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
            .requestedPermissions?.contains(MIC) == true
    }.getOrDefault(false)

    private fun askBlocking(): Boolean {
        if (host.hasPermission(MIC)) return true
        val latch = CountDownLatch(1)
        var result = false
        host.activity.runOnUiThread { host.requestPermission(MIC) { ok -> result = ok; latch.countDown() } }
        latch.await(120, TimeUnit.SECONDS)
        return result
    }

    private fun permission(request: Boolean): BridgeResponse {
        val granted = if (request) askBlocking() else host.hasPermission(MIC)
        return BridgeResponse.json(200, "OK", """{"granted":$granted,"canRequest":${declared()}}""")
    }

    private inline fun withPermission(body: () -> BridgeResponse): BridgeResponse =
        if (askBlocking()) body()
        else BridgeResponse.json(403, "Forbidden", """{"error":"permission","detail":"RECORD_AUDIO denied","canRequest":${declared()}}""")

    // ── main-thread helper with a latch ──────────────────────────────────────────
    private fun <T> onUi(timeoutMs: Long = 5000, block: (done: (T) -> Unit) -> Unit): T? {
        val latch = CountDownLatch(1)
        var out: T? = null
        host.activity.runOnUiThread {
            try { block { v -> out = v; latch.countDown() } } catch (e: Throwable) { latch.countDown() }
        }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return out
    }

    private fun onDeviceAvailable(): Boolean =
        Build.VERSION.SDK_INT >= 31 && (onUi<Boolean> { it(SpeechRecognizer.isOnDeviceRecognitionAvailable(host.context)) } ?: false)

    /** API 33+: the on-device recognizer's installed / supported languages; null below or on failure. */
    private fun recognitionSupport(lang: String): RecognitionSupport? {
        if (Build.VERSION.SDK_INT < 33) return null
        return onUi<RecognitionSupport?>(6000) { done ->
            val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(host.context)
            r.checkRecognitionSupport(recognizeIntent(lang, partial = false, bias = emptyList(), format = null), host.context.mainExecutor,
                object : RecognitionSupportCallback {
                    override fun onSupportResult(s: RecognitionSupport) { r.destroy(); done(s) }
                    override fun onError(error: Int) { r.destroy(); done(null) }
                })
        }
    }

    private fun langMatches(list: List<String>, lang: String): Boolean =
        list.any { it.equals(lang, true) || it.substringBefore('-').equals(lang, true) || it.replace('_', '-').equals(lang, true) }

    // ── support ─────────────────────────────────────────────────────────────────
    private fun support(lang: String): BridgeResponse {
        val sdk = Build.VERSION.SDK_INT
        val onDevice = onDeviceAvailable()
        val sb = StringBuilder("""{"onDevice":$onDevice,"sdk":$sdk""")
        val s = if (onDevice) recognitionSupport(lang) else null
        if (s != null) {
            val installed = s.installedOnDeviceLanguages
            val downloadable = s.supportedOnDeviceLanguages.filter { d -> installed.none { it.equals(d, true) } }
            val exact = installed.firstOrNull { it.equals(lang, true) }
            val sibling = installed.firstOrNull { it.substringBefore('-').equals(lang.substringBefore('-'), true) }
            val uses = exact ?: sibling
            sb.append(""","language":{"tag":${BridgeResponse.jsonStr(lang)},"installed":${exact != null},"usable":${uses != null}${if (uses != null && exact == null) ""","uses":${BridgeResponse.jsonStr(uses)}""" else ""}}""")
            sb.append(""","installed":[${installed.joinToString(",") { BridgeResponse.jsonStr(it) }}]""")
            sb.append(""","downloadable":[${downloadable.joinToString(",") { BridgeResponse.jsonStr(it) }}]""")
        } else {
            sb.append(""","language":{"tag":${BridgeResponse.jsonStr(lang)},"installed":null}""")
        }
        sb.append('}')
        return BridgeResponse.json(200, "OK", sb.toString())
    }

    // ── stream ──────────────────────────────────────────────────────────────────
    private fun recognizeIntent(lang: String, partial: Boolean, bias: List<String>, format: String?): Intent {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, partial)
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        if (Build.VERSION.SDK_INT >= 33) {
            if (bias.isNotEmpty()) i.putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(bias))
            if (format != null) i.putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING,
                if (format == "latency") RecognizerIntent.FORMATTING_OPTIMIZE_LATENCY else RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY)
        }
        return i
    }

    private fun stream(req: BridgeRequest): BridgeResponse {
        current?.let { return BridgeResponse.json(409, "Conflict", """{"error":"busy","holder":${BridgeResponse.jsonStr(it.stream.id)}}""") }
        if (!onDeviceAvailable()) return BridgeResponse.json(503, "Not Available",
            """{"error":"no on-device recognizer on this phone","capability":"speech.ondevice"}""")
        val asked = req.query["lang"]?.takeIf { it.isNotBlank() } ?: Locale.getDefault().toLanguageTag()
        var lang = asked
        // the exact pack, else an installed pack of the SAME language (en-US asked, en-CA
        // installed — found on the S24+, whose recognizer shipped with en-CA only); `ready`
        // reports the pack used and what was asked. A different language never substitutes.
        recognitionSupport(asked)?.let { s ->
            val inst = s.installedOnDeviceLanguages
            val exact = inst.firstOrNull { it.equals(asked, true) || it.replace('_', '-').equals(asked, true) }
            val base = asked.substringBefore('-')
            val sibling = inst.firstOrNull { it.substringBefore('-').equals(base, true) }
            lang = exact ?: sibling ?: return BridgeResponse.json(503, "Not Available",
                """{"error":"no on-device language pack for $asked","capability":"speech.ondevice","language":${BridgeResponse.jsonStr(asked)},"downloadable":${langMatches(s.supportedOnDeviceLanguages, asked)}}""")
        }
        val partial = req.query["partial"] != "0"
        val continuous = req.query["continuous"] == "1"
        val rms = req.query["rms"] == "1"
        val rawBias = (req.query["bias"] ?: "").split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val bias = rawBias.take(100).map { it.take(60) }
        val truncated = rawBias.size > 100 || rawBias.any { it.length > 60 }
        val format = req.query["format"] ?: "quality"
        val intent = recognizeIntent(lang, partial, bias, format)

        lateinit var session: Session
        val stream = host.pushBus.open(onClose = {
            session.closed = true
            host.activity.runOnUiThread { session.recognizer?.let { it.cancel(); it.destroy() }; session.recognizer = null }
            if (current === session) current = null
        })
        session = Session(stream, null, intent, continuous)
        current = session

        var lastRms = 0L
        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) =
                stream.emit("ready", """{"lang":${BridgeResponse.jsonStr(lang)}${if (lang != asked) ""","asked":${BridgeResponse.jsonStr(asked)}""" else ""},"onDevice":true,"bias":${bias.size}${if (truncated) ""","biasTruncated":true""" else ""}}""")
            override fun onBeginningOfSpeech() = stream.emit("speech", """{"on":true}""")
            override fun onEndOfSpeech() = stream.emit("speech", """{"on":false}""")
            override fun onRmsChanged(db: Float) {
                if (!rms) return
                val now = System.currentTimeMillis()
                if (now - lastRms < 100) return
                lastRms = now
                stream.emit("rms", """{"db":$db}""")
            }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(b: Bundle?) {
                val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
                stream.emit("partial", """{"text":${BridgeResponse.jsonStr(t)}}""")
            }
            override fun onResults(b: Bundle?) {
                val texts = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: arrayListOf()
                // an engine that does not score fills 0 for every alternative (the S24+'s does) —
                // that is "not scored", not "zero confidence": report null (spec Q-2)
                val conf = b?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)?.takeIf { arr -> arr.any { it > 0f } }
                val alts = texts.mapIndexed { i, t ->
                    val c = conf?.getOrNull(i)?.takeIf { it >= 0f }
                    """{"text":${BridgeResponse.jsonStr(t)},"confidence":${c ?: "null"}}"""
                }
                stream.emit("result", """{"alternatives":[${alts.joinToString(",")}]}""")
                finishUtterance()
            }
            override fun onError(code: Int) {
                stream.emit("error", """{"code":${BridgeResponse.jsonStr(errorName(code))},"reason":$code}""")
                finishUtterance()
            }
            private fun finishUtterance() {
                stream.emit("end", "{}")
                if (session.closed) return
                if (continuous) session.recognizer?.startListening(intent)      // re-arm: a new `ready` follows
                else stream.close()
            }
        }
        val started = onUi<Boolean> { done ->
            val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(host.context)
            r.setRecognitionListener(listener)
            session.recognizer = r
            r.startListening(intent)
            done(true)
        } ?: false
        if (!started) {
            current = null
            stream.close()
            return BridgeResponse.error(500, "Speech Failed", "could not start the on-device recognizer")
        }
        return BridgeResponse.json(200, "OK", """{"stream":${BridgeResponse.jsonStr(stream.id)}}""")
    }

    private fun stop(): BridgeResponse {
        val s = current ?: return BridgeResponse.json(200, "OK", """{"stopped":false}""")
        host.activity.runOnUiThread { s.recognizer?.stopListening() }
        return BridgeResponse.json(200, "OK", """{"stopped":true}""")
    }

    companion object {
        private const val MIC = Manifest.permission.RECORD_AUDIO
        private fun errorName(code: Int) = when (code) {
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network_timeout"
            SpeechRecognizer.ERROR_NETWORK -> "network"
            SpeechRecognizer.ERROR_AUDIO -> "audio"
            SpeechRecognizer.ERROR_SERVER -> "server"
            SpeechRecognizer.ERROR_CLIENT -> "client"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "speech_timeout"
            SpeechRecognizer.ERROR_NO_MATCH -> "no_match"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "recognizer_busy"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "insufficient_permissions"
            10 -> "too_many_requests"
            11 -> "server_disconnected"
            12 -> "language_not_supported"
            13 -> "language_unavailable"
            14 -> "cannot_check_support"
            15 -> "cannot_listen_to_download_events"
            else -> "unknown"
        }
    }
}

/** The factory an instrument lists: `override val plugins = listOf(Speech, …)`. RECORD_AUDIO + the recognizer <queries> come with this module. */
val Speech = PluginFactory { host -> SpeechPlugin(host) }
