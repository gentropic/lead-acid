package org.gentropic.leadacid

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Streaming sensor readings (SPEC §5.1) — the first PUSH-lane plugin, and the
 * V-1 test (does SSE flush per-event through shouldInterceptRequest?).
 *
 * Routes:
 *   GET /native/sensor/list                     — available sensors
 *   GET /native/sensor/stream?types=a,b&rateHz= — SSE feed; one event per reading
 *
 * No runtime permission needed for these types (rotation/magnetic/accel/gravity
 * are not "body sensors"), so the base manifest stays permission-free (§8).
 * Callbacks run on a background HandlerThread — never the main thread.
 */
class SensorPlugin(private val sensorManager: SensorManager) : Plugin {
    override val name = "sensor"

    private val thread = HandlerThread("leadacid-sensor").apply { start() }
    private val handler = Handler(thread.looper)
    private val streams = CopyOnWriteArrayList<SseStream>()

    private val byName = linkedMapOf(
        "rotation" to Sensor.TYPE_ROTATION_VECTOR,               // fused orientation (best compass)
        "geomag_rotation" to Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR, // mag+accel, no gyro drift
        "game_rotation" to Sensor.TYPE_GAME_ROTATION_VECTOR,
        "magnetic" to Sensor.TYPE_MAGNETIC_FIELD,
        "magnetic_uncal" to Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED, // raw, for calibration state
        "accel" to Sensor.TYPE_ACCELEROMETER,
        "gravity" to Sensor.TYPE_GRAVITY,
    )
    private val nameByType = byName.entries.associate { (k, v) -> v to k }

    override fun handle(req: BridgeRequest): BridgeResponse = when (req.subpath) {
        "list" -> list()
        "stream" -> stream(req)
        else -> BridgeResponse.error(404, "Not Found", "sensor/${req.subpath}")
    }

    private fun list(): BridgeResponse {
        val items = byName.entries.mapNotNull { (n, t) ->
            sensorManager.getDefaultSensor(t)?.let { s ->
                """{"name":${BridgeResponse.jsonStr(n)},"vendor":${BridgeResponse.jsonStr(s.vendor)},"resolution":${s.resolution}}"""
            }
        }
        return BridgeResponse.json(200, "OK", "[${items.joinToString(",")}]")
    }

    private fun stream(req: BridgeRequest): BridgeResponse {
        val names = (req.query["types"] ?: "rotation").split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val sensors = names.mapNotNull { byName[it]?.let(sensorManager::getDefaultSensor) }
        if (sensors.isEmpty())
            return BridgeResponse.error(503, "Not Available", "no sensors for: $names")

        val rateUs = req.query["rateHz"]?.toIntOrNull()?.takeIf { it > 0 }?.let { 1_000_000 / it }
            ?: SensorManager.SENSOR_DELAY_GAME
        // padTo defeats the interceptor's ~2 KiB SSE buffer (V-1); see SseStream.
        val pad = (req.query["pad"]?.toIntOrNull() ?: 2200).coerceAtLeast(0)

        val sse = SseStream(padTo = pad)
        sse.comment("lead-acid sensor stream")

        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val n = nameByType[e.sensor.type] ?: return
                val v = e.values.joinToString(",") { it.toString() }
                sse.emit(n, """{"t":${e.timestamp},"v":[$v],"acc":${e.accuracy}}""")
            }
            override fun onAccuracyChanged(s: Sensor, acc: Int) {
                val n = nameByType[s.type] ?: return
                sse.emit("accuracy", """{"sensor":${BridgeResponse.jsonStr(n)},"acc":$acc}""")
            }
        }
        for (s in sensors) sensorManager.registerListener(listener, s, rateUs, handler)
        streams.add(sse)
        sse.onClose = {
            sensorManager.unregisterListener(listener)
            streams.remove(sse)
        }

        return BridgeResponse(
            200, "OK", "text/event-stream",
            mapOf(
                "Content-Type" to "text/event-stream",
                "Cache-Control" to "no-store",
                "X-Accel-Buffering" to "no",
            ),
            sse,
        )
    }

    /** Page-generation / pause lifecycle: tear down every open stream. */
    fun releaseAll() {
        for (s in streams) s.close()   // close() fires onClose → unregisterListener
        streams.clear()
    }
}
