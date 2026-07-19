package org.gentropic.leadacid

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.graphics.Color
import android.view.View
import android.view.WindowManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient.FileChooserParams
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import java.io.File

/**
 * The trench coat (SPEC §3). Spike-0 edition: WebView + asset loading +
 * "/native/…" interceptor + one registered plugin. An app subclasses this to
 * declare its plugin list; the spike declares FilePlugin inline.
 * (Kotlin block comments NEST — never write /native/ followed by two stars in a comment.)
 */
class LeadAcidActivity : ComponentActivity() {

    companion object {
        const val ORIGIN = "appassets.androidplatform.net"
        /** 8 MiB, byte[i] = i mod 251 — verifiable from JS at any offset. */
        const val TEST_FILE_SIZE = 8L * 1024 * 1024
        const val FILE_CHOOSER_REQ = 71
    }

    private lateinit var webView: WebView
    private val plugins = HashMap<String, Plugin>()

    /** Debug builds only: dispatch logging + page-console mirroring to logcat. */
    private val debuggable: Boolean by lazy {
        (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    private var fileChooserCallback: android.webkit.ValueCallback<Array<Uri>>? = null

    /** The fs plugin — created once so it can hold SAF-fd maps across requests. */
    private val filePlugin by lazy { FilePlugin(mapOf("spike0.bin" to File(filesDir, "spike0.bin"))) }

    /** Fullscreen (§7c): the custom view an artifact hands us via onShowCustomView. */
    private var customView: View? = null
    private var customViewCallback: android.webkit.WebChromeClient.CustomViewCallback? = null
    private var pendingGeolocation: Pair<String, GeolocationPermissions.Callback>? = null
    private var pendingPermissionRequest: PermissionRequest? = null

    private val PERM_LOCATION = 81
    private val PERM_CAMERA = 82

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        File(filesDir, "spike0.bin").also(::ensureTestFile)
        register(filePlugin)
        register(ShellPlugin(version = versionName()) { on ->
            runOnUiThread {
                if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        })

        // §7b: Back. Predictive back is declared in the manifest; this callback
        // makes the animation reflect what actually happens. Default is honest:
        // no in-page history → finish. (Artifact-intercept via shell.onBack is a
        // lead-acid.js addition — the shell would consult a cached back-state.)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })

        val assetLoader = WebViewAssetLoader.Builder()
            .setDomain(ORIGIN)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView = WebView(this)
        webView.settings.apply {
            javaScriptEnabled = true
            // WebView defaults DOM storage OFF — localStorage is literally null
            // without this, and artifacts die at boot (micro did; found by the
            // canary). A shell-core obligation, not a per-app choice.
            domStorageEnabled = true
            // SPEC §8: all file service goes through the fs plugin's routes.
            allowFileAccess = false
            allowContentAccess = false
        }
        // Debug builds: expose the WebView's DevTools socket. With
        // `adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>`
        // a desktop Playwright connects over CDP and drives the artifact
        // exactly like the committed smoke harnesses drive it in headless
        // Chromium — the on-device dev loop.
        if (debuggable) WebView.setWebContentsDebuggingEnabled(true)

        webView.webChromeClient = object : android.webkit.WebChromeClient() {
            // Page console → logcat, so test results are readable over adb.
            override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                if (debuggable) android.util.Log.i("leadacidjs", m.message())
                return true
            }

            // SPEC §7c: file inputs silently no-op without this. SAF picker;
            // ACTION_OPEN_DOCUMENT so the returned URIs are documents-provider
            // backed (persistable grants + ranged reads later).
            override fun onShowFileChooser(
                view: WebView,
                callback: android.webkit.ValueCallback<Array<Uri>>,
                params: FileChooserParams,
            ): Boolean {
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = callback
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(
                        Intent.EXTRA_ALLOW_MULTIPLE,
                        params.mode == FileChooserParams.MODE_OPEN_MULTIPLE,
                    )
                }
                return runCatching { startActivityForResult(intent, FILE_CHOOSER_REQ) }
                    .onFailure { fileChooserCallback = null }
                    .isSuccess
            }

            // §7c: fullscreen (immersive artifacts, video). Add the custom view
            // over the root; restore on hide.
            override fun onShowCustomView(view: View, cb: CustomViewCallback) {
                if (customView != null) { cb.onCustomViewHidden(); return }
                customView = view
                customViewCallback = cb
                (window.decorView as android.view.ViewGroup).addView(
                    view,
                    android.view.ViewGroup.LayoutParams(-1, -1),
                )
                webView.visibility = View.GONE
            }

            override fun onHideCustomView() {
                val v = customView ?: return
                (window.decorView as android.view.ViewGroup).removeView(v)
                customView = null
                customViewCallback?.onCustomViewHidden()
                customViewCallback = null
                webView.visibility = View.VISIBLE
            }

            // §7c: standard web geolocation. Granted iff the app holds the
            // Android permission (base manifest requests none — §8; a location
            // plugin's manifest merge is what turns this on for an app).
            override fun onGeolocationPermissionsShowPrompt(
                origin: String,
                callback: GeolocationPermissions.Callback,
            ) {
                if (hasPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)) {
                    callback.invoke(origin, true, false)
                } else if (declaresPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)) {
                    pendingGeolocation = origin to callback
                    requestPermissions(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION), PERM_LOCATION)
                } else {
                    callback.invoke(origin, false, false)   // capability absent
                }
            }

            // §7c: getUserMedia (camera preview before/without the camera plugin).
            // Grant only the camera resource, and only if the app holds CAMERA.
            override fun onPermissionRequest(request: PermissionRequest) {
                val wantsCamera = request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                if (!wantsCamera) { request.deny(); return }
                if (hasPermission(android.Manifest.permission.CAMERA)) {
                    request.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
                } else if (declaresPermission(android.Manifest.permission.CAMERA)) {
                    pendingPermissionRequest = request
                    requestPermissions(arrayOf(android.Manifest.permission.CAMERA), PERM_CAMERA)
                } else {
                    request.deny()   // capability absent
                }
            }
        }
        webView.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? {
                val url = request.url
                if (url.host != ORIGIN) return null
                val path = url.path ?: return null
                if (path.startsWith("/native/")) return dispatch(request, path)
                return assetLoader.shouldInterceptRequest(url)
            }

            // SPEC §8: navigation locked to the virtual origin; external links
            // go to the system browser.
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                if (request.url.host == ORIGIN) return false
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                return true
            }
        }
        // SPEC §7b: Android 15+ enforces edge-to-edge, and WebView does NOT
        // populate env(safe-area-inset-*) (V-3 confirmed on-device) — so the
        // shell's DEFAULT is to keep the artifact inside the system bars,
        // cutout, and keyboard. Artifacts need zero inset awareness; true
        // edge-to-edge becomes a per-artifact opt-in later (meta tag, per the
        // spec). GOTCHA: WebView ignores its own padding — pad a CONTAINER.
        webView.setBackgroundColor(Color.BLACK)
        val root = android.widget.FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        root.addView(webView)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars()
                    or WindowInsetsCompat.Type.displayCutout()
                    or WindowInsetsCompat.Type.ime(),
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        setContentView(root)
        // Dev switcher (debug builds): `adb shell am start … --es artifact micro.html`
        // loads a different asset without a rebuild. Release ignores the extra.
        val artifact = (if (debuggable) intent.getStringExtra("artifact") else null)
            ?.takeIf { it.matches(Regex("[A-Za-z0-9._-]+")) } ?: "artifact.html"
        webView.loadUrl("https://$ORIGIN/assets/$artifact")
    }

    private fun register(plugin: Plugin) {
        plugins[plugin.name] = plugin
    }

    /** /native/{plugin}/{subpath…} → PluginRegistry dispatch (SPEC §3). */
    private fun dispatch(request: WebResourceRequest, path: String): WebResourceResponse {
        val rest = path.removePrefix("/native/")
        val slash = rest.indexOf('/')
        val pluginName = if (slash < 0) rest else rest.substring(0, slash)
        val subpath = if (slash < 0) "" else rest.substring(slash + 1)

        val plugin = plugins[pluginName]
            ?: return BridgeResponse.error(404, "Not Found", "no plugin: $pluginName").toWebResponse()

        val query = HashMap<String, String>()
        val uri: Uri = request.url
        for (key in runCatching { uri.queryParameterNames }.getOrDefault(emptySet())) {
            uri.getQueryParameter(key)?.let { query[key] = it }
        }

        val req = BridgeRequest(
            method = request.method,
            subpath = subpath,
            query = query,
            headers = request.requestHeaders ?: emptyMap(),
        )
        return try {
            val res = plugin.handle(req)
            if (debuggable) android.util.Log.i(
                "leadacid",
                "${req.method} /native/$pluginName/$subpath range=${req.header("Range")} -> ${res.status} ${res.headers["Content-Range"] ?: ""}",
            )
            res.toWebResponse()
        } catch (e: Exception) {
            android.util.Log.e("leadacid", "${req.method} /native/$pluginName/$subpath threw", e)
            BridgeResponse.error(500, "Internal Error", e.message ?: e.javaClass.simpleName)
                .toWebResponse()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == FILE_CHOOSER_REQ) {
            val cb = fileChooserCallback
            fileChooserCallback = null
            val uris = if (resultCode == RESULT_OK && data != null) {
                data.clipData?.let { cd -> Array(cd.itemCount) { cd.getItemAt(it).uri } }
                    ?: data.data?.let { arrayOf(it) }
            } else null
            // Register each picked SAF document as an fs token, so the artifact
            // can also reach it via GET /native/fs/<token> (mmap-over-fd path).
            uris?.forEach { filePlugin.registerSaf(it, contentResolver) }
            cb?.onReceiveValue(uris)
            return
        }
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
    }

    private fun hasPermission(perm: String) =
        ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED

    /** Is the permission even in this app's merged manifest? (Plugin-merge gate.) */
    private fun declaresPermission(perm: String): Boolean = runCatching {
        packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions?.contains(perm) == true
    }.getOrDefault(false)

    private fun versionName(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "0"
    }.getOrDefault("0")

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        when (requestCode) {
            PERM_LOCATION -> pendingGeolocation?.let { (origin, cb) ->
                cb.invoke(origin, granted, false); pendingGeolocation = null
            }
            PERM_CAMERA -> pendingPermissionRequest?.let { req ->
                if (granted) req.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) else req.deny()
                pendingPermissionRequest = null
            }
        }
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    /** Deterministic pattern file: byte[i] = i mod 251 (largest prime < 256). */
    private fun ensureTestFile(file: File) {
        if (file.exists() && file.length() == TEST_FILE_SIZE) return
        val chunk = ByteArray(64 * 1024)
        file.outputStream().buffered().use { out ->
            var written = 0L
            while (written < TEST_FILE_SIZE) {
                for (j in chunk.indices) chunk[j] = ((written + j) % 251).toByte()
                val n = minOf(chunk.size.toLong(), TEST_FILE_SIZE - written).toInt()
                out.write(chunk, 0, n)
                written += n
            }
        }
    }
}
