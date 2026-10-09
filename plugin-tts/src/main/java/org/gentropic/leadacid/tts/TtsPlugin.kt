package org.gentropic.leadacid.tts

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.gentropic.leadacid.BridgeRequest
import org.gentropic.leadacid.BridgeResponse
import org.gentropic.leadacid.Plugin
import org.gentropic.leadacid.PluginFactory
import org.gentropic.leadacid.PluginHost
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Text-to-speech through the device's own engine, OFFLINE VOICES ONLY. Every
 * Android voice reports whether it needs the network; this plugin lists and uses
 * only those that don't, and a language with no offline voice answers 503 rather
 * than quietly synthesizing in the cloud. No permission, no INTERNET.
 *
 *   GET  /native/tts/support          → {available, engine, offlineVoices}
 *   GET  /native/tts/voices?lang=     → [{name, locale, quality, latency}]   (offline, installed)
 *   POST /native/tts/speak?text=&lang=&voice=&rate=&pitch=&queue=flush|add
 *        (text may also be the request body, UTF-8) → {done:true, ms, voice} when it finishes speaking
 *        | {stopped:true} if stop / pause interrupted it
 *   POST /native/tts/stop             → {stopped}
 *
 *   400 {error, field}  — empty text, text over the engine's limit, a bad rate/pitch
 *   503 {error, capability:"tts.offline"} — no engine, or no offline voice for lang / voice
 *
 * speak() returns when the utterance ends, so `await shell.tts.speak(…)` sequences
 * naturally. Speech stops on onPause (a voice from a backgrounded app is a
 * surprise) and the engine is released on onDestroy, via the Activity lifecycle.
 */
class TtsPlugin(private val host: PluginHost) : Plugin {
    override val name = "tts"

    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    private val waiters = ConcurrentHashMap<String, Pair<CountDownLatch, Array<String?>>>()
    private var seq = 0

    override fun handle(req: BridgeRequest): BridgeResponse {
        if (!ensure()) return BridgeResponse.json(503, "Not Available", """{"error":"no text-to-speech engine","capability":"tts.offline"}""")
        return when (req.subpath) {
            "support" -> support()
            "voices" -> voices(req.query["lang"])
            "speak" -> if (req.method == "POST") speak(req) else BridgeResponse.error(405, "Method Not Allowed", req.method)
            "stop" -> { onUi { tts?.stop() }; finishAll("stopped"); BridgeResponse.json(200, "OK", """{"stopped":true}""") }
            else -> BridgeResponse.error(404, "Not Found", "tts/${req.subpath}")
        }
    }

    private fun onUi(block: () -> Unit) { val l = CountDownLatch(1); host.activity.runOnUiThread { try { block() } finally { l.countDown() } }; l.await(5, TimeUnit.SECONDS) }

    /** Lazy init on the main thread; waits for the engine's onInit. */
    @Synchronized private fun ensure(): Boolean {
        if (ready && tts != null) return true
        val latch = CountDownLatch(1)
        var ok = false
        host.activity.runOnUiThread {
            val engine = TextToSpeech(host.context) { status -> ok = status == TextToSpeech.SUCCESS; latch.countDown() }
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String) {}
                override fun onDone(id: String) = finish(id, "done")
                @Deprecated("") override fun onError(id: String) = finish(id, "error")
                override fun onError(id: String, code: Int) = finish(id, "error:$code")
                override fun onStop(id: String, interrupted: Boolean) = finish(id, "stopped")
            })
            tts = engine
            host.activity.lifecycle.addObserver(LifecycleEventObserver { _, e ->
                when (e) {
                    Lifecycle.Event.ON_PAUSE -> { tts?.stop(); finishAll("stopped") }
                    Lifecycle.Event.ON_DESTROY -> { tts?.shutdown(); tts = null; ready = false; finishAll("stopped") }
                    else -> {}
                }
            })
        }
        latch.await(8, TimeUnit.SECONDS)
        ready = ok
        if (!ok) { onUi { tts?.shutdown() }; tts = null }
        return ok
    }

    private fun finish(id: String, how: String) { waiters.remove(id)?.let { (l, out) -> out[0] = how; l.countDown() } }
    private fun finishAll(how: String) { for (k in waiters.keys.toList()) finish(k, how) }

    /** The voices that synthesize on the device and are installed. */
    private fun offlineVoices(): List<Voice> = try {
        tts?.voices?.filter { v -> !v.isNetworkConnectionRequired && !v.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) } ?: emptyList()
    } catch (e: Exception) { emptyList() }

    private fun matches(v: Voice, lang: String): Boolean {
        val want = Locale.forLanguageTag(lang)
        return v.locale.language.equals(want.language, true) && (want.country.isEmpty() || v.locale.country.equals(want.country, true))
    }

    private fun voiceJson(v: Voice) =
        """{"name":${BridgeResponse.jsonStr(v.name)},"locale":${BridgeResponse.jsonStr(v.locale.toLanguageTag())},"quality":${v.quality},"latency":${v.latency}}"""

    private fun support(): BridgeResponse =
        BridgeResponse.json(200, "OK", """{"available":true,"engine":${BridgeResponse.jsonStr(tts?.defaultEngine ?: "")},"offlineVoices":${offlineVoices().size}}""")

    private fun voices(lang: String?): BridgeResponse {
        val vs = offlineVoices().filter { lang == null || matches(it, lang) }.sortedWith(compareBy({ it.locale.toLanguageTag() }, { -it.quality }))
        return BridgeResponse.json(200, "OK", "[${vs.joinToString(",") { voiceJson(it) }}]")
    }

    private fun bad(field: String, msg: String) =
        BridgeResponse.json(400, "Bad Request", """{"error":${BridgeResponse.jsonStr(msg)},"field":${BridgeResponse.jsonStr(field)}}""")

    private fun speak(req: BridgeRequest): BridgeResponse {
        val text = (req.query["text"] ?: req.body?.toString(Charsets.UTF_8) ?: "").trim()
        if (text.isEmpty()) return bad("text", "nothing to say")
        if (text.length > TextToSpeech.getMaxSpeechInputLength()) return bad("text", "longer than ${TextToSpeech.getMaxSpeechInputLength()} characters")
        val rate = req.query["rate"]?.let { it.toFloatOrNull() ?: return bad("rate", "a number") }?.coerceIn(0.1f, 4f)
        val pitch = req.query["pitch"]?.let { it.toFloatOrNull() ?: return bad("pitch", "a number") }?.coerceIn(0.1f, 4f)
        val lang = req.query["lang"]?.takeIf { it.isNotBlank() } ?: Locale.getDefault().toLanguageTag()
        val offline = offlineVoices()
        val voice = req.query["voice"]?.let { n -> offline.firstOrNull { it.name == n }
            ?: return BridgeResponse.json(503, "Not Available", """{"error":"no offline voice named $n","capability":"tts.offline"}""") }
            ?: offline.filter { matches(it, lang) }.maxByOrNull { it.quality * 10 - it.latency }
            ?: return BridgeResponse.json(503, "Not Available", """{"error":"no offline voice for $lang","capability":"tts.offline","language":${BridgeResponse.jsonStr(lang)}}""")
        val id = "u" + synchronized(this) { ++seq }
        val latch = CountDownLatch(1)
        val out = arrayOfNulls<String>(1)
        waiters[id] = latch to out
        val t0 = System.currentTimeMillis()
        var queued = TextToSpeech.ERROR
        onUi {
            val e = tts ?: return@onUi
            e.voice = voice
            if (rate != null) e.setSpeechRate(rate)
            if (pitch != null) e.setPitch(pitch)
            queued = e.speak(text, if (req.query["queue"] == "add") TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH, Bundle(), id)
        }
        if (queued != TextToSpeech.SUCCESS) { waiters.remove(id); return BridgeResponse.error(500, "Speak Failed", "the engine refused the utterance") }
        latch.await(maxOf(30_000L, text.length * 400L), TimeUnit.MILLISECONDS)   // generous: ~150 wpm with room
        val how = out[0] ?: run { waiters.remove(id); "timeout" }
        return when {
            how == "done" -> BridgeResponse.json(200, "OK", """{"done":true,"ms":${System.currentTimeMillis() - t0},"voice":${BridgeResponse.jsonStr(voice.name)}}""")
            how == "stopped" -> BridgeResponse.json(200, "OK", """{"stopped":true}""")
            else -> BridgeResponse.error(500, "Speak Failed", how)
        }
    }
}

/** The factory an instrument lists: `override val plugins = listOf(Tts, …)`. No permission. */
val Tts = PluginFactory { host -> TtsPlugin(host) }
