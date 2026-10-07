package org.gentropic.leadacid.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import org.gentropic.leadacid.BridgeRequest
import org.gentropic.leadacid.BridgeResponse
import org.gentropic.leadacid.Plugin
import org.gentropic.leadacid.PluginFactory
import org.gentropic.leadacid.PluginHost
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Camera, level 1 (docs/camera-plugin.md): the module's manifest brings the
 * CAMERA permission, so an instrument's page can preview and capture with
 * getUserMedia — the shell's §7c wiring grants it once the permission is held.
 * This plugin itself only enumerates and asks:
 *
 *   GET /native/camera/list                 → [{id, facing, hasRaw, focalLengthsMm, sensorMm:[w,h], pixels:[w,h]}]
 *   GET /native/camera/permission?request=1 → {granted, canRequest}  (prompts when asked)
 *
 * Asking HERE lets an artifact get the system dialog out of the way before it
 * opens the viewfinder, instead of inside getUserMedia's own prompt. The
 * CameraX/RAW tier (native preview behind a transparent WebView, DNG) is the
 * dedicated camera app's project, not this module.
 */
class CameraPlugin(private val host: PluginHost) : Plugin {
    override val name = "camera"
    private val CAM = Manifest.permission.CAMERA

    override fun handle(req: BridgeRequest): BridgeResponse = when (req.subpath) {
        "list" -> list()
        "permission" -> permission(req.query["request"] != null)
        else -> BridgeResponse.error(404, "Not Found", "camera/${req.subpath}")
    }

    private fun declared(): Boolean = runCatching {
        host.context.packageManager.getPackageInfo(host.context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions?.contains(CAM) == true
    }.getOrDefault(false)

    private fun permission(request: Boolean): BridgeResponse {
        val granted = if (!request || host.hasPermission(CAM)) host.hasPermission(CAM) else {
            val latch = CountDownLatch(1)
            var r = false
            host.activity.runOnUiThread { host.requestPermission(CAM) { ok -> r = ok; latch.countDown() } }
            latch.await(120, TimeUnit.SECONDS)
            r
        }
        return BridgeResponse.json(200, "OK", """{"granted":$granted,"canRequest":${declared()}}""")
    }

    private fun list(): BridgeResponse {
        val cm = host.context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val items = runCatching {
            cm.cameraIdList.map { id ->
                val c = cm.getCameraCharacteristics(id)
                val facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
                    CameraMetadata.LENS_FACING_BACK -> "back"; CameraMetadata.LENS_FACING_FRONT -> "front"
                    CameraMetadata.LENS_FACING_EXTERNAL -> "external"; else -> "unknown"
                }
                val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
                val hasRaw = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW)
                val fl = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.joinToString(",") ?: ""
                val sensor = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                val px = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                """{"id":${BridgeResponse.jsonStr(id)},"facing":"$facing","hasRaw":$hasRaw,"focalLengthsMm":[$fl]""" +
                    (if (sensor != null) ""","sensorMm":[${sensor.width},${sensor.height}]""" else "") +
                    (if (px != null) ""","pixels":[${px.width},${px.height}]""" else "") + "}"
            }
        }.getOrElse { e -> return BridgeResponse.error(500, "Camera Error", e.message ?: e.javaClass.simpleName) }
        return BridgeResponse.json(200, "OK", "[${items.joinToString(",")}]")
    }
}

/** The factory an instrument lists — and the dependency that brings CAMERA into its manifest. */
val Camera = PluginFactory { host -> CameraPlugin(host) }
