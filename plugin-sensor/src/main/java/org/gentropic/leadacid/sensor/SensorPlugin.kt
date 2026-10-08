package org.gentropic.leadacid.sensor

import android.content.Context
import org.gentropic.leadacid.BridgeRequest
import org.gentropic.leadacid.BridgeResponse
import org.gentropic.leadacid.Plugin
import org.gentropic.leadacid.PluginFactory
import org.gentropic.leadacid.PortStream
import org.gentropic.leadacid.PushBus

import android.hardware.Sensor as HwSensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread

/**
 * Streaming sensor readings (SPEC §5.1) — the first PUSH-lane plugin. Events
 * flow over the WebMessagePort (via [PushBus]), NOT SSE: `shouldInterceptRequest`
 * buffers SSE ~2 KiB (V-1), and padding around that is silly; the port delivers
 * directly with no buffering.
 *
 * Routes:
 *   GET /native/sensor/list                       — available sensors
 *   GET /native/sensor/stream?types=a,b&rateHz=N  — opens a port stream; returns
 *                                                   {stream:id}. Events arrive on
 *                                                   the port as {s:id, e:type, d:{t,v,acc}}
 *
 * No runtime permission (rotation/magnetic/accel/gravity aren't "body sensors"),
 * so the base manifest stays permission-free (§8). Callbacks run on a background
 * HandlerThread.
 */
class SensorPlugin(
    private val sensorManager: SensorManager,
    private val pushBus: PushBus,
) : Plugin {
    override val name = "sensor"

    private val thread = HandlerThread("leadacid-sensor").apply { start() }
    private val handler = Handler(thread.looper)

    private val byName = linkedMapOf(
        "rotation" to HwSensor.TYPE_ROTATION_VECTOR,               // fused orientation (best compass)
        "geomag_rotation" to HwSensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR, // mag+accel, no gyro drift
        "game_rotation" to HwSensor.TYPE_GAME_ROTATION_VECTOR,
        "magnetic" to HwSensor.TYPE_MAGNETIC_FIELD,
        "magnetic_uncal" to HwSensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED, // raw, for calibration state
        "accel" to HwSensor.TYPE_ACCELEROMETER,
        "gravity" to HwSensor.TYPE_GRAVITY,
        // ep's @sensor sources (SPEC-pocket §4.3): pressure has no web API at
        // all, gyro and light do but only behind flags — the shell is the
        // honest path for all three. One-line additions, no permission.
        "gyro" to HwSensor.TYPE_GYROSCOPE,
        "pressure" to HwSensor.TYPE_PRESSURE,
        "light" to HwSensor.TYPE_LIGHT,
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

        lateinit var stream: PortStream
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val n = nameByType[e.sensor.type] ?: return
                val v = e.values.joinToString(",") { it.toString() }
                stream.emit(n, """{"t":${e.timestamp},"v":[$v],"acc":${e.accuracy}}""")
            }
            override fun onAccuracyChanged(s: HwSensor, acc: Int) {
                val n = nameByType[s.type] ?: return
                stream.emit("accuracy", """{"sensor":${BridgeResponse.jsonStr(n)},"acc":$acc}""")
            }
        }
        stream = pushBus.open(onClose = { sensorManager.unregisterListener(listener) })
        for (s in sensors) sensorManager.registerListener(listener, s, rateUs, handler)

        return BridgeResponse.json(200, "OK", """{"stream":${BridgeResponse.jsonStr(stream.id)}}""")
    }
}

/**
 * The factory an instrument lists: `override val plugins = listOf(Sensor, …)`.
 * (It shadows android.hardware.Sensor in this file — hence the HwSensor alias above.)
 */
val Sensor = PluginFactory { host ->
    SensorPlugin(host.context.getSystemService(Context.SENSOR_SERVICE) as SensorManager, host.pushBus)
}
