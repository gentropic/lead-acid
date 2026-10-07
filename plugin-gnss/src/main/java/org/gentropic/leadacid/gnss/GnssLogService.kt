package org.gentropic.leadacid.gnss

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.GnssMeasurementsEvent
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.OnNmeaMessageListener
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.util.concurrent.atomic.AtomicLong

/**
 * The raw-GNSS LOGGER — lead-acid's first foreground service (SPEC §5
 * "foreground-service plugins", docs/gnss-plugin.md). Capture is dumb,
 * reliable platform-callback plumbing in Kotlin: every epoch appended as one
 * JSON line to an app-private file, with the screen off, the WebView gone,
 * whatever. The notification states the instrument's actual state
 * ("GNSS logging — 4,312 epochs · fix 3D"), never "app is running".
 *
 * One session at a time (409 on a second start). The page that comes back
 * after a reload re-attaches through `log/status`; `log/stop` seals the file
 * and serves it as an fs token for publish/share.
 */
class GnssLogService : Service() {

    companion object {
        const val CHANNEL = "leadacid-logging"
        const val NOTIF_ID = 7101
        const val ACTION_START = "org.gentropic.leadacid.gnss.LOG_START"
        const val ACTION_STOP = "org.gentropic.leadacid.gnss.LOG_STOP"
        const val EXTRA_NAME = "name"
        const val EXTRA_WHAT = "what"       // comma list: raw,nmea,location,status

        /** The one session's live state — the plugin reads it, the service writes it. */
        @Volatile var session: Session? = null

        fun logDir(ctx: Context): File = File(ctx.filesDir, "gnss-logs").apply { mkdirs() }
    }

    class Session(val name: String, val file: File, val what: Set<String>, val startedAt: Long) {
        val epochs = AtomicLong(0)        // raw epochs
        val fixes = AtomicLong(0)
        val nmea = AtomicLong(0)
        @Volatile var lastFix: String = "no fix"
        @Volatile var bytes: Long = 0
        @Volatile var stoppedAt: Long = 0
    }

    private lateinit var lm: LocationManager
    private val thread = HandlerThread("leadacid-gnss-log").apply { start() }
    private val handler = Handler(thread.looper)
    private var writer: BufferedWriter? = null
    private var sess: Session? = null
    private val closers = ArrayList<() -> Unit>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        lm = getSystemService(LOCATION_SERVICE) as LocationManager
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "lead-acid logging", NotificationManager.IMPORTANCE_LOW).apply {
                description = "A running capture (GNSS logging with the screen off)"
            },
        )
    }

    @SuppressLint("MissingPermission")   // the plugin checks FINE before starting us
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (sess != null) return START_STICKY   // already logging — ignore the duplicate
                val name = intent.getStringExtra(EXTRA_NAME) ?: "gnss"
                val what = (intent.getStringExtra(EXTRA_WHAT) ?: "raw,location").split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                val file = File(logDir(this), "$name.jsonl")
                val s = Session(name, file, what, System.currentTimeMillis())
                sess = s; session = s
                writer = BufferedWriter(FileWriter(file, false), 1 shl 16)
                write("""{"kind":"start","name":${q(name)},"what":[${what.joinToString(",") { q(it) }}],"t":${s.startedAt},"device":${q(Build.MODEL)}}""")
                startForeground(NOTIF_ID, notification(s), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
                attach(s)
            }
            ACTION_STOP -> stopLogging()
        }
        return START_STICKY
    }

    private fun attach(s: Session) {
        if ("location" in s.what) {
            val l = object : LocationListener {
                override fun onLocationChanged(loc: Location) {
                    s.fixes.incrementAndGet()
                    s.lastFix = if (loc.hasAccuracy()) String.format(java.util.Locale.ROOT, "±%.1f m", loc.accuracy) else "fix"
                    write("""{"kind":"fix","t":${loc.time},"lat":${loc.latitude},"lon":${loc.longitude},"alt":${if (loc.hasAltitude()) loc.altitude else "null"},"acc":${if (loc.hasAccuracy()) loc.accuracy else "null"},"provider":${q(loc.provider ?: "")}}""")
                    refresh(s)
                }
                @Deprecated("Deprecated in Java") override fun onStatusChanged(p: String?, st: Int, e: android.os.Bundle?) {}
                override fun onProviderEnabled(p: String) {}
                override fun onProviderDisabled(p: String) {}
            }
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, l, thread.looper)
            closers += { lm.removeUpdates(l) }
        }
        if ("raw" in s.what) {
            val cb = object : GnssMeasurementsEvent.Callback() {
                override fun onGnssMeasurementsReceived(e: GnssMeasurementsEvent) {
                    val c = e.clock
                    val ms = e.measurements.joinToString(",") { m ->
                        """{"svid":${m.svid},"c":${m.constellationType},"state":${m.state},"tOff":${m.timeOffsetNanos},"rxSv":${m.receivedSvTimeNanos},"rxUnc":${m.receivedSvTimeUncertaintyNanos},"cn0":${m.cn0DbHz},"prr":${m.pseudorangeRateMetersPerSecond},"prrUnc":${m.pseudorangeRateUncertaintyMetersPerSecond},"adrState":${m.accumulatedDeltaRangeState},"adr":${m.accumulatedDeltaRangeMeters},"adrUnc":${m.accumulatedDeltaRangeUncertaintyMeters}""" +
                            (if (m.hasCarrierFrequencyHz()) ""","freq":${m.carrierFrequencyHz}}""" else "}")
                    }
                    val clock = """{"timeNanos":${c.timeNanos}""" +
                        (if (c.hasFullBiasNanos()) ""","fullBiasNanos":${c.fullBiasNanos}""" else "") +
                        (if (c.hasBiasNanos()) ""","biasNanos":${c.biasNanos}""" else "") +
                        (if (c.hasDriftNanosPerSecond()) ""","driftNanosPerSecond":${c.driftNanosPerSecond}""" else "") +
                        ""","discontinuity":${c.hardwareClockDiscontinuityCount}}"""
                    write("""{"kind":"raw","t":${System.currentTimeMillis()},"clock":$clock,"measurements":[$ms]}""")
                    val n = s.epochs.incrementAndGet()
                    if (n % 10 == 0L) refresh(s)
                }
            }
            @Suppress("DEPRECATION") lm.registerGnssMeasurementsCallback(cb, handler)
            closers += { lm.unregisterGnssMeasurementsCallback(cb) }
        }
        if ("nmea" in s.what) {
            val l = OnNmeaMessageListener { msg, t -> s.nmea.incrementAndGet(); write("""{"kind":"nmea","t":$t,"s":${q(msg)}}""") }
            @Suppress("DEPRECATION") lm.addNmeaListener(l, handler)
            closers += { lm.removeNmeaListener(l) }
        }
        if ("status" in s.what) {
            val cb = object : GnssStatus.Callback() {
                override fun onSatelliteStatusChanged(st: GnssStatus) {
                    var used = 0
                    for (i in 0 until st.satelliteCount) if (st.usedInFix(i)) used++
                    write("""{"kind":"status","t":${System.currentTimeMillis()},"sats":${st.satelliteCount},"used":$used}""")
                }
            }
            @Suppress("DEPRECATION") lm.registerGnssStatusCallback(cb, handler)
            closers += { lm.unregisterGnssStatusCallback(cb) }
        }
    }

    private fun stopLogging() {
        val s = sess ?: run { stopSelf(); return }
        for (c in closers) runCatching { c() }
        closers.clear()
        write("""{"kind":"stop","t":${System.currentTimeMillis()},"epochs":${s.epochs.get()},"fixes":${s.fixes.get()},"nmea":${s.nmea.get()}}""")
        handler.post {
            runCatching { writer?.flush(); writer?.close() }
            writer = null
            s.bytes = s.file.length()
            s.stoppedAt = System.currentTimeMillis()
            sess = null                      // `session` stays: the plugin reads the sealed summary
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /** All writes on the log thread; bytes tracked for the status route. */
    private fun write(line: String) {
        handler.post {
            val w = writer ?: return@post
            w.write(line); w.newLine()
            sess?.let { it.bytes += line.length + 1 }
        }
    }

    private fun refresh(s: Session) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(s))
    }

    private fun notification(s: Session): Notification {
        val stop = Intent(this, GnssLogService::class.java).setAction(ACTION_STOP)
        val stopPi = android.app.PendingIntent.getService(this, 1, stop, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
        val title = "GNSS logging — ${s.epochs.get()} epochs · ${s.lastFix}"
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText("${s.name}.jsonl · ${s.bytes / 1024} KB")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stopPi).build())
            .build()
    }

    override fun onDestroy() {
        for (c in closers) runCatching { c() }
        runCatching { writer?.flush(); writer?.close() }
        thread.quitSafely()
        super.onDestroy()
    }

    private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""
}
