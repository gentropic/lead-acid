package org.gentropic.leadacid.gnss

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.location.GnssMeasurementsEvent
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.OnNmeaMessageListener
import android.os.Handler
import android.os.HandlerThread
import org.gentropic.leadacid.BridgeRequest
import org.gentropic.leadacid.BridgeResponse
import org.gentropic.leadacid.Plugin
import org.gentropic.leadacid.PluginFactory
import org.gentropic.leadacid.PluginHost
import org.gentropic.leadacid.PortStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Raw GNSS (SPEC §5.1 `gnss`, docs/gnss-plugin.md) off the platform
 * LocationManager — never Play Services, so a GMS-less phone works too. The
 * web geolocation API already works in the shell; this plugin is for what it
 * can't give: raw measurements, satellite status, NMEA, and un-fused fixes.
 *
 * Every stream rides the port push lane:
 *   GET /native/gnss/location?minMs=&provider=   → `fix`    {lat,lon,alt,acc,speed,bearing,t,provider}
 *   GET /native/gnss/status                       → `status` {fixSats, satellites:[{svid,constellation,cn0,used,elev,az}]}
 *   GET /native/gnss/raw                          → `raw`    {clock:{…}, measurements:[{…}]} (fields emitted faithfully;
 *                                                             the PPK/PPP math is the artifact's job)
 *   GET /native/gnss/nmea                         → `nmea`   {t, s}
 *   GET /native/gnss/permission?request=1         → {granted, canRequest} (prompts when asked)
 *
 * The LOGGER (a foreground service, GnssLogService — capture survives the screen
 * going off and the WebView going away; the page re-attaches via status):
 *   POST /native/gnss/log/start?what=raw,location,nmea,status&name=   → {name, startedAt} | 409 if running
 *   POST /native/gnss/log/stop                                        → {name, token, epochs, fixes, nmea, bytes}
 *                                                                       the sealed file served as fs token `token`
 *   GET  /native/gnss/log/status                                      → {running, name, token?, epochs, fixes, nmea, bytes, startedAt, stoppedAt?}
 *
 * THE FIRST DANGEROUS PERMISSION. ACCESS_FINE_LOCATION is declared by THIS
 * module's manifest; a stream-open asks the host to request it and waits (the
 * interceptor thread is ours to block) — the user answers the system dialog
 * and the stream opens, or 403 {canRequest} comes back. An instrument that
 * doesn't include this module can't even ask: the host refuses what the merged
 * manifest doesn't declare.
 */
class GnssPlugin(private val host: PluginHost) : Plugin {
    override val name = "gnss"

    private val lm = host.context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val thread = HandlerThread("leadacid-gnss").apply { start() }
    private val handler = Handler(thread.looper)

    override fun handle(req: BridgeRequest): BridgeResponse = when (req.subpath) {
        "permission" -> permission(req.query["request"] != null)
        "location", "status", "raw", "nmea" -> withPermission { open(req) }
        "log/start" -> withPermission { logStart(req) }
        "log/stop" -> logStop()
        "log/status" -> logStatus()
        else -> BridgeResponse.error(404, "Not Found", "gnss/${req.subpath}")
    }

    // ── the logger ───────────────────────────────────────────────────────────
    private fun logToken(name: String) = "gnss-log-$name"

    private fun logStart(req: BridgeRequest): BridgeResponse {
        if (req.method != "POST") return BridgeResponse.error(405, "Method Not Allowed", req.method)
        val running = GnssLogService.session?.takeIf { it.stoppedAt == 0L }
        if (running != null)
            return BridgeResponse.json(409, "Conflict", """{"error":"busy","holder":${BridgeResponse.jsonStr(running.name)},"detail":"a log is running — stop it first"}""")
        val name = (req.query["name"] ?: "gnss-" + System.currentTimeMillis()).replace(Regex("[^A-Za-z0-9._-]"), "_")
        val what = req.query["what"] ?: "raw,location"
        // the notification is a UX surface (SPEC §5); ask for it, don't require it
        if (android.os.Build.VERSION.SDK_INT >= 33 && !host.hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
            host.activity.runOnUiThread { host.requestPermission(Manifest.permission.POST_NOTIFICATIONS) { } }
        }
        val i = Intent(host.context, GnssLogService::class.java).setAction(GnssLogService.ACTION_START)
            .putExtra(GnssLogService.EXTRA_NAME, name).putExtra(GnssLogService.EXTRA_WHAT, what)
        host.context.startForegroundService(i)
        // the service sets `session` on its main-thread onStartCommand; wait briefly for it
        var s: GnssLogService.Session? = null
        for (k in 0 until 50) { s = GnssLogService.session?.takeIf { it.name == name && it.stoppedAt == 0L }; if (s != null) break; Thread.sleep(20) }
        return if (s == null) BridgeResponse.error(500, "Logger Failed", "the service did not start")
        else BridgeResponse.json(200, "OK", """{"name":${BridgeResponse.jsonStr(s.name)},"what":${BridgeResponse.jsonStr(what)},"startedAt":${s.startedAt}}""")
    }

    private fun logStop(): BridgeResponse {
        val s = GnssLogService.session?.takeIf { it.stoppedAt == 0L }
            ?: return BridgeResponse.error(404, "Not Found", "no log is running")
        host.context.startService(Intent(host.context, GnssLogService::class.java).setAction(GnssLogService.ACTION_STOP))
        for (k in 0 until 100) { if (s.stoppedAt != 0L) break; Thread.sleep(20) }
        if (s.stoppedAt == 0L) return BridgeResponse.error(500, "Logger Failed", "the service did not stop")
        val token = host.serveFile(logToken(s.name), s.file)
        return BridgeResponse.json(200, "OK", summary(s, token))
    }

    private fun logStatus(): BridgeResponse {
        val s = GnssLogService.session ?: return BridgeResponse.json(200, "OK", """{"running":false}""")
        val token = if (s.stoppedAt != 0L) host.serveFile(logToken(s.name), s.file) else null
        return BridgeResponse.json(200, "OK", summary(s, token))
    }

    private fun summary(s: GnssLogService.Session, token: String?) =
        """{"running":${s.stoppedAt == 0L},"name":${BridgeResponse.jsonStr(s.name)},"token":${if (token == null) "null" else BridgeResponse.jsonStr(token)},"what":[${s.what.joinToString(",") { BridgeResponse.jsonStr(it) }}],"epochs":${s.epochs.get()},"fixes":${s.fixes.get()},"nmea":${s.nmea.get()},"bytes":${s.bytes},"lastFix":${BridgeResponse.jsonStr(s.lastFix)},"startedAt":${s.startedAt},"stoppedAt":${if (s.stoppedAt == 0L) "null" else s.stoppedAt.toString()}}"""

    private val FINE = Manifest.permission.ACCESS_FINE_LOCATION

    private fun permission(request: Boolean): BridgeResponse {
        val granted = if (request) askBlocking() else host.hasPermission(FINE)
        return BridgeResponse.json(200, "OK", """{"granted":$granted,"canRequest":${declared()}}""")
    }

    private fun declared(): Boolean = runCatching {
        host.context.packageManager.getPackageInfo(host.context.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
            .requestedPermissions?.contains(FINE) == true
    }.getOrDefault(false)

    /** Ask on the main thread, wait here (up to two minutes for the dialog). */
    private fun askBlocking(): Boolean {
        if (host.hasPermission(FINE)) return true
        val latch = CountDownLatch(1)
        var result = false
        host.activity.runOnUiThread { host.requestPermission(FINE) { ok -> result = ok; latch.countDown() } }
        latch.await(120, TimeUnit.SECONDS)
        return result
    }

    private inline fun withPermission(body: () -> BridgeResponse): BridgeResponse =
        if (askBlocking()) body()
        else BridgeResponse.json(403, "Forbidden", """{"error":"permission","detail":"ACCESS_FINE_LOCATION denied","canRequest":${declared()}}""")

    @SuppressLint("MissingPermission")   // checked by withPermission
    private fun open(req: BridgeRequest): BridgeResponse {
        lateinit var stream: PortStream
        when (req.subpath) {
            "location" -> {
                val minMs = req.query["minMs"]?.toLongOrNull()?.coerceAtLeast(0) ?: 1000L
                val provider = req.query["provider"]?.takeIf { lm.allProviders.contains(it) } ?: LocationManager.GPS_PROVIDER
                val listener = object : LocationListener {
                    override fun onLocationChanged(l: Location) = stream.emit("fix", fixJson(l))
                    @Deprecated("Deprecated in Java") override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) {}
                    override fun onProviderEnabled(p: String) = stream.emit("provider", """{"provider":${BridgeResponse.jsonStr(p)},"enabled":true}""")
                    override fun onProviderDisabled(p: String) = stream.emit("provider", """{"provider":${BridgeResponse.jsonStr(p)},"enabled":false}""")
                }
                stream = host.pushBus.open(onClose = { lm.removeUpdates(listener) })
                lm.requestLocationUpdates(provider, minMs, 0f, listener, thread.looper)
                // the last known fix right away, so a page has something before the first update
                runCatching { lm.getLastKnownLocation(provider) }.getOrNull()?.let { stream.emit("fix", fixJson(it, last = true)) }
            }
            "status" -> {
                val cb = object : GnssStatus.Callback() {
                    override fun onSatelliteStatusChanged(s: GnssStatus) = stream.emit("status", statusJson(s))
                    override fun onFirstFix(ttffMillis: Int) = stream.emit("firstfix", """{"ttffMs":$ttffMillis}""")
                    override fun onStarted() = stream.emit("engine", """{"on":true}""")
                    override fun onStopped() = stream.emit("engine", """{"on":false}""")
                }
                stream = host.pushBus.open(onClose = { lm.unregisterGnssStatusCallback(cb) })
                @Suppress("DEPRECATION") lm.registerGnssStatusCallback(cb, handler)
            }
            "raw" -> {
                val cb = object : GnssMeasurementsEvent.Callback() {
                    override fun onGnssMeasurementsReceived(e: GnssMeasurementsEvent) = stream.emit("raw", rawJson(e))
                    @Deprecated("Deprecated in Java") override fun onStatusChanged(status: Int) = stream.emit("rawstatus", """{"status":$status}""")
                }
                stream = host.pushBus.open(onClose = { lm.unregisterGnssMeasurementsCallback(cb) })
                @Suppress("DEPRECATION") lm.registerGnssMeasurementsCallback(cb, handler)
            }
            "nmea" -> {
                val l = OnNmeaMessageListener { s, t -> stream.emit("nmea", """{"t":$t,"s":${BridgeResponse.jsonStr(s)}}""") }
                stream = host.pushBus.open(onClose = { lm.removeNmeaListener(l) })
                @Suppress("DEPRECATION") lm.addNmeaListener(l, handler)
            }
        }
        return BridgeResponse.json(200, "OK", """{"stream":${BridgeResponse.jsonStr(stream.id)}}""")
    }

    private fun num(d: Double) = if (d.isNaN() || d.isInfinite()) "null" else d.toString()
    private fun num(f: Float) = num(f.toDouble())

    private fun fixJson(l: Location, last: Boolean = false): String = buildString {
        append("""{"lat":${l.latitude},"lon":${l.longitude}""")
        append(""","alt":${if (l.hasAltitude()) num(l.altitude) else "null"}""")
        append(""","acc":${if (l.hasAccuracy()) num(l.accuracy) else "null"}""")
        append(""","speed":${if (l.hasSpeed()) num(l.speed) else "null"}""")
        append(""","bearing":${if (l.hasBearing()) num(l.bearing) else "null"}""")
        append(""","t":${l.time},"provider":${BridgeResponse.jsonStr(l.provider ?: "")},"last":$last}""")
    }

    private fun constellation(c: Int) = when (c) {
        GnssStatus.CONSTELLATION_GPS -> "gps"; GnssStatus.CONSTELLATION_GLONASS -> "glonass"
        GnssStatus.CONSTELLATION_GALILEO -> "galileo"; GnssStatus.CONSTELLATION_BEIDOU -> "beidou"
        GnssStatus.CONSTELLATION_QZSS -> "qzss"; GnssStatus.CONSTELLATION_SBAS -> "sbas"
        GnssStatus.CONSTELLATION_IRNSS -> "irnss"; else -> "unknown"
    }

    private fun statusJson(s: GnssStatus): String {
        var used = 0
        val sats = (0 until s.satelliteCount).joinToString(",") { i ->
            if (s.usedInFix(i)) used++
            """{"svid":${s.getSvid(i)},"constellation":"${constellation(s.getConstellationType(i))}","cn0":${num(s.getCn0DbHz(i))},"used":${s.usedInFix(i)},"elev":${num(s.getElevationDegrees(i))},"az":${num(s.getAzimuthDegrees(i))}""" +
                (if (s.hasCarrierFrequencyHz(i)) ""","freq":${num(s.getCarrierFrequencyHz(i))}}""" else "}")
        }
        return """{"fixSats":$used,"satellites":[$sats]}"""
    }

    private fun rawJson(e: GnssMeasurementsEvent): String {
        val c = e.clock
        val clock = buildString {
            append("""{"timeNanos":${c.timeNanos}""")
            if (c.hasFullBiasNanos()) append(""","fullBiasNanos":${c.fullBiasNanos}""")
            if (c.hasBiasNanos()) append(""","biasNanos":${num(c.biasNanos)}""")
            if (c.hasBiasUncertaintyNanos()) append(""","biasUncertaintyNanos":${num(c.biasUncertaintyNanos)}""")
            if (c.hasDriftNanosPerSecond()) append(""","driftNanosPerSecond":${num(c.driftNanosPerSecond)}""")
            if (c.hasLeapSecond()) append(""","leapSecond":${c.leapSecond}""")
            append(""","hwClockDiscontinuityCount":${c.hardwareClockDiscontinuityCount}}""")
        }
        val ms = e.measurements.joinToString(",") { m ->
            buildString {
                append("""{"svid":${m.svid},"constellation":"${constellation(m.constellationType)}","timeOffsetNanos":${num(m.timeOffsetNanos)},"state":${m.state}""")
                append(""","receivedSvTimeNanos":${m.receivedSvTimeNanos},"receivedSvTimeUncertaintyNanos":${m.receivedSvTimeUncertaintyNanos}""")
                append(""","cn0":${num(m.cn0DbHz)},"pseudorangeRateMps":${num(m.pseudorangeRateMetersPerSecond)},"pseudorangeRateUncertaintyMps":${num(m.pseudorangeRateUncertaintyMetersPerSecond)}""")
                append(""","adrState":${m.accumulatedDeltaRangeState},"adrMeters":${num(m.accumulatedDeltaRangeMeters)},"adrUncertaintyMeters":${num(m.accumulatedDeltaRangeUncertaintyMeters)}""")
                if (m.hasCarrierFrequencyHz()) append(""","carrierFrequencyHz":${num(m.carrierFrequencyHz)}""")
                append(""","multipath":${m.multipathIndicator}""")
                if (m.hasSnrInDb()) append(""","snrDb":${num(m.snrInDb)}""")
                if (m.hasAutomaticGainControlLevelDb()) append(""","agcDb":${num(m.automaticGainControlLevelDb)}""")
                append("}")
            }
        }
        return """{"clock":$clock,"measurements":[$ms]}"""
    }
}

/** The factory an instrument lists — and the dependency that brings FINE location into its manifest. */
val Gnss = PluginFactory { host -> GnssPlugin(host) }
