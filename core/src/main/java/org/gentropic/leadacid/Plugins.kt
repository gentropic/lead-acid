package org.gentropic.leadacid

import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity

/**
 * Plugin registration (M2 Phase 1). An instrument declares its capabilities as
 * an EXPLICIT list of factories — readable in the source, auditable, no
 * ServiceLoader magic:
 *
 *     class WuffleActivity : LeadAcidActivity() {
 *         override val plugins = listOf(Sensor, Share, Attest)
 *     }
 *
 * `fs` and `shell` are core, always present. Everything that needs a permission
 * or a device capability is a plugin module; its Gradle dependency is what
 * carries its manifest fragment into the instrument (SPEC §5).
 */
fun interface PluginFactory {
    fun create(host: PluginHost): Plugin
}

/**
 * The shell's service surface a plugin draws on. Plugins stay free of their own
 * Activity references and request-code bookkeeping: the host routes
 * `onActivityResult` / permission results to the handler that asked.
 */
interface PluginHost {
    val context: Context
    /** For startActivity / startActivityForResult / the window. */
    val activity: ComponentActivity
    /** Streaming plugins open port streams here (SPEC §4.2). */
    val pushBus: PushBus

    /**
     * Launch an Intent for a result; `handler(resultCode, data)` runs on the
     * main thread when it comes back. Request codes are allocated by the host
     * (the §7c file chooser uses the same door).
     */
    fun startForResult(intent: Intent, handler: (Int, Intent?) -> Unit): Boolean

    /**
     * Ask for a runtime permission the instrument's merged manifest declares.
     * `onResult(false)` immediately if the manifest doesn't declare it — a
     * plugin can't talk its way into a permission its module didn't contribute.
     */
    fun requestPermission(perm: String, onResult: (Boolean) -> Unit)
    fun hasPermission(perm: String): Boolean

    /**
     * See every Intent the activity receives — the launch intent (replayed to
     * handlers registered during onCreate) and each onNewIntent. Main thread.
     * The share-target plugin (`intake`) lives on this.
     */
    fun onIntent(handler: (Intent) -> Unit)

    /**
     * Register a content URI (a SAF pick, a shared file) as an fs token served
     * by core's `fs` plugin (mmap-over-fd). Released on the next page start
     * like any SAF pick — register when the page asks, not when the URI arrives.
     */
    fun registerFile(uri: android.net.Uri): String?

    /**
     * Serve an app-private file a plugin produced (a sealed log) under a DURABLE
     * fs token — survives page starts, so the page that reattaches after a
     * reload can still read it. Re-registering replaces.
     */
    fun serveFile(token: String, file: java.io.File): String
}
