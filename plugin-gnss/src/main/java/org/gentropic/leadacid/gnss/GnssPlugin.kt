package org.gentropic.leadacid.gnss

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
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
        else -> BridgeResponse.error(404, "Not Found", "gnss/${req.subpath}")
    }

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
