# Camera plugin — `camera`

**Design spec for CameraX capture (SPEC §5.1, committed).** Not built; specced now because the
*preview* path has a real subtlety worth pinning down. *Drivers: wuffle v3 (an outcrop photo
stapled to a measurement); a dedicated RAW/manual camera app.*

## Two levels — know which you need

1. **Basic preview via `getUserMedia`** — already works in the shell (the §7c `onPermissionRequest`
   wiring + `CAMERA` perm). A `<video>` element, canvas-grab for a JPEG. **No plugin needed.** Good
   enough for "show the camera, grab a frame." Can't do RAW, manual exposure, or lens selection.
2. **The `camera` plugin (CameraX/Camera2)** — RAW/DNG, manual ISO/exposure/focus, multi-lens,
   RAW14. This is what a real field camera wants, and it's what this doc specs.

CameraX (`androidx.camera.*`) is the implementation — it's AndroidX, so no third-party-dep question.

## The preview problem (the subtle bit)

CameraX renders preview to a native `Surface`, **not** into the web page. So how does the artifact
*see* the preview while drawing its own UI (capture button, ISO slider, framing guides)? The clean
answer is the **transparent-WebView composite** (same trick as the AR design-note):

- The plugin renders CameraX preview onto a `SurfaceView`/`PreviewView` placed **behind** the
  WebView.
- The WebView is made transparent (`setBackgroundColor(TRANSPARENT)`, a supported config) so the
  artifact's UI floats over the live preview.
- The artifact controls capture over `/native/camera/*`; the preview is native (full frame rate, no
  bridge in the pixel path).

This is a **shell-level view-layering** arrangement the Activity manages (SurfaceView z-order below
a transparent WebView; touch routing). It's the main verification item. Streaming preview *frames*
to the page over the port is the lazy alternative — laggy, wasteful — use only for a thumbnail.

## Routes (sketch)

```
GET  /native/camera/list                    → [{id, facing, hasRaw, focalLengths, raw14}]
POST /native/camera/preview {cameraId}       → start preview to the native surface (behind the WebView)
GET  /native/camera/params?cameraId=         → capabilities {isoRange, exposureNsRange, formats:[jpeg,raw,raw14]}
POST /native/camera/capture {iso, exposureNs, → capture a still; returns the image BYTES (response
                             focusDist, format}   body — large reads are unbuffered) or publishes to fs
POST /native/camera/stop
GET  /native/camera/state                    → push stream of preview/AF state
```

- **Capture bytes ride the response body**, not a push/sidecar: a DNG is 10–50 MB, and *response*
  InputStreams are unbuffered (that's the read direction we proved in Spike 0) — the plugin holds
  the captured bytes and serves them, or writes to a temp file and streams it. Or `capture` writes
  straight through `fs/publish` and returns the `content://` URI. Either way the artifact gets the
  DNG and can `share`/`publish` it.
- Control (list/params/capture-trigger) = request/reply; capture params (small JSON) via body
  sidecar or query; state = push stream. Preview = native surface (not a bridge lane).

## Permissions

`CAMERA` (runtime, dangerous — the module manifest declares it, requested at `preview`/`capture`;
the Activity's request flow is wired). No storage permission — captures go out via `fs/publish`
(scoped MediaStore) or `share`.

## Subtleties / verification items

- **The transparent-WebView preview composite** — verify SurfaceView-behind + transparent WebView
  renders and routes touch correctly on the S24+ (the one real unknown).
- **RAW → DNG:** `DngCreator` builds a DNG from RAW sensor data + capture result metadata (the
  color matrices, noise profile). **RAW14** (`ImageFormat.RAW14`, Android 17) on compatible sensors
  — capture at the deepest bit depth the hardware admits.
- **Camera contention:** the plugin owns the camera; don't mix with a concurrent `getUserMedia`
  preview (both want the device).
- **Multi-lens:** enumerate physical cameras (wide / ultrawide / tele) and let the artifact pick.
- **wuffle v3 flow:** `preview` → frame the outcrop → `capture` (JPEG is fine for a field photo) →
  attach the image to the current measurement's log entry, `share`/`publish` alongside the CSV.

## Lanes used

Request/reply (control) + response-body (large capture) + push stream (state) + native surface
(preview). The first plugin to lean on the **response-body** path for big output — the read
direction we've had since Spike 0.
