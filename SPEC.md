# @gcu/lead-acid

**An Android shell for single-file web artifacts.**

> Heavy. Ancient. Completely understood. Starts your truck every morning.

`lead-acid` carries a GCU single-file HTML artifact (シングルファイルデプロイ) into an Android APK, and negotiates its access to the parts of the phone that Android keeps behind the counter: radios, raw sensors, the filesystem, the NPU. It adds no capability to the artifact itself — the WebView is full Chromium, and everything the artifact could do on desktop it can already do here. lead-acid is a jig, not a framework: a fixture that holds a single-file artifact in place while Android is applied to it.

Status: **v0** — implementation started (Spike 0, 2026-07-19). This spec is the design of
record; `PLAN.md` is the execution companion, and where the two disagree, PLAN.md's two
revisions win (Sealed is a per-artifact posture, not the bridge's trust precondition; the
first instrument is wuffle, not aupro).

---

## 1. Design constraints

1. **The artifact is sovereign.** The web artifact must remain a single self-contained HTML file that runs unmodified in any desktop browser. lead-acid must never require the artifact to be built, bundled, transpiled, or otherwise aware of the shell beyond one optional, feature-detected vanilla JS module.
2. **Vanilla JS on the web side.** No TypeScript, no build step, no node_modules on the JS side of the bridge. Type rigor lives in Kotlin, which is typed whether anyone likes it or not. JSDoc annotations are permitted for editor hints.
3. **Kotlin core stays small and readable.** Target: the shell core (activity, asset loading, bridge routing) fits in a few hundred lines that a stranger can audit in one sitting. Auditable forever.
4. **One plugin per native concern.** Each native capability (sensors, BLE HID, raw GNSS, camera, USB host, NPU inference…) is one Kotlin class implementing one small interface, registered by name. No plugin depends on another plugin.
5. **Zero third-party runtime dependencies** beyond AndroidX (`androidx.webkit` is unavoidable and acceptable; it is the platform, not a dependency in the GCU sense). No Capacitor, no Cordova, no RN.
6. **Bridge over HTTP idioms only.** The web side talks to native exclusively through `fetch()` and `EventSource` against a virtual origin. No `addJavascriptInterface` globals in v1 (see §4.4 for the one narrow escape hatch).
7. **Offline forever.** No network permission unless a specific artifact requires it. The default manifest requests nothing.

## 2. Non-goals

- Not a cross-platform framework. Android only. (iOS is a different battery chemistry and a different decade of pain.)
- Not a renderer. Rendering stays in the WebView; if an artifact outgrows WebView graphics, that is a separate native project, not a lead-acid feature.
- Not a UI toolkit. The artifact brings its own UI (Switchboard, presumably).
- Not a store-publishing pipeline. lead-acid produces a signed APK/AAB; what happens to it afterwards is between you and the Ltd.

## 3. Architecture

```
┌─────────────────────────────────────────────────┐
│ APK                                             │
│  ┌───────────────────────────────────────────┐  │
│  │ LeadAcidActivity (Kotlin)                 │  │
│  │  ├─ WebView (full Chromium)               │  │
│  │  │   └─ assets/artifact.html  ◄── the app │  │
│  │  ├─ Interceptor  (WebViewAssetLoader +    │  │
│  │  │                shouldInterceptRequest) │  │
│  │  └─ PluginRegistry                        │  │
│  │       ├─ FilePlugin      /native/fs/…     │  │
│  │       ├─ SensorPlugin    /native/sensor/… │  │
│  │       ├─ GnssPlugin      /native/gnss/…   │  │
│  │       └─ …one class per concern           │  │
│  └───────────────────────────────────────────┘  │
└─────────────────────────────────────────────────┘
```

- **`LeadAcidActivity`** — a base activity: creates the WebView, enables the required settings, installs the interceptor, loads `https://appassets.androidplatform.net/assets/artifact.html`. Handles configuration changes gracefully (see §7). An app subclasses it only to declare its plugin list.
- **Interceptor** — routes requests on the virtual origin: `/assets/**` is served from APK assets via `WebViewAssetLoader`; `/native/**` is dispatched to the `PluginRegistry`. Everything else falls through to normal WebView behavior (which, absent the INTERNET permission, is nothing).
- **`PluginRegistry`** — maps the first path segment after `/native/` to a plugin instance. Dispatch is: method + path + query + body in, `WebResourceResponse` out.

## 4. The bridge

Three lanes, each doing the one thing it is good at:

| lane | direction | carries | good at |
|---|---|---|---|
| `fetch` (+ port body sidecar, §4.3) | request/reply | routing, statuses, streamed responses of any size | the spine — every call goes through it |
| `EventSource` (§4.2) | native → JS push | low-rate JSON events | auto-reconnect, `Last-Event-ID`, zero ceremony |
| `WebMessagePort` (§4.3) | both | ArrayBuffers | request bodies; high-rate binary push |

Request bodies never cross `shouldInterceptRequest` (verified, §4.1), and SSE is a text
protocol — so bodies and binary firehoses ride the port. Every stream-shaped plugin declares
which push lane it uses; "JSON at human rates → SSE, binary at machine rates → port" is the
assignment rule.

### 4.1 Request/reply — `fetch`

Web side:

```js
const res = await fetch('/native/gnss/status');
const status = await res.json();

const dng = await fetch('/native/camera/capture', {
  method: 'POST',
  body: JSON.stringify({ iso: 800, exposureNs: 4000000 })
});
const bytes = await dng.arrayBuffer();
```

Native side, the plugin contract:

```kotlin
interface Plugin {
  val name: String                       // path segment, e.g. "gnss"
  fun handle(req: BridgeRequest): BridgeResponse
}
```

`BridgeRequest` carries method, subpath, query params, and headers. **Request bodies never
cross `shouldInterceptRequest`** (Chromium crbug 155250, verified at Spike 0) — body-carrying
calls ride the WebMessagePort lane (§4.3), hidden behind `lead-acid.js`'s `shell.native()` so
the artifact-facing API is unchanged. `BridgeResponse` carries status, MIME type, headers, and
an `InputStream` — meaning responses can be arbitrarily large and *streamed*, never buffered
whole. Binary crosses the bridge as binary. No base64, ever.

**Byte ranges are first-class.** The interceptor honors `Range` headers against plugin-served
files (and asset files), returning `206 Partial Content`. This is the load-bearing feature: it
is what lets micro `fetch()` sparse LOD reads directly out of a multi-gigabyte point cloud that
a `FilePlugin` is serving off internal storage — ideally via `mmap` (`MappedByteBuffer`),
letting the kernel page cache do the work.

*Verified contract (Spike 0, S24+ / WebView 150):* WebView's `AndroidStreamReaderURLLoader`
applies the request's Range to the plugin's InputStream **itself** — it skips `start` bytes
(resolving suffix ranges via `available()`), never truncates the end, and passes the plugin's
status/headers through untouched. A plugin therefore serves the stream **from position 0
capped at end+1**, never pre-skipped, with O(1) `skip()`/`available()`; it still computes the
`206`/`Content-Range` headers itself. Unsatisfiable ranges answer `416` with a phantom
skippable stream (the loader applies the skip regardless of status; a short stream turns into
a net error). Full detail and the debugging arc: PLAN.md, Spike 0 findings.

### 4.2 Push — Server-Sent Events

Web side:

```js
const gnss = new EventSource('/native/gnss/stream');
gnss.addEventListener('raw', e => ingest(JSON.parse(e.data)));
```

**The push lane is the WebMessagePort, not SSE.** A plugin opens a `PortStream` via the shell's
`PushBus` and `emit(event, data)`s; the shell posts `{s:id, e:event, d:data}` over the SAME port
that carries the body sidecar (§4.3), other direction (shell→page) — the two never collide.
`lead-acid.js`'s `shell.stream(path)` opens it (`GET …?transport=port` → `{stream:id}`) and
returns `{ on(event, cb), onClose(cb), close() }`; the page routes port messages by stream id.

**Why not SSE (V-1 RESOLVED, S24+ / WebView 150):** `shouldInterceptRequest` buffers the
response **~2 KiB** before delivering, batching SSE events into ~400 ms clumps (measured: big
gaps every ~20 events). Padding each event past the buffer *works* (per-event, ~20 ms), but
shipping ~2 KiB of junk per event as a load-bearing mechanism is silly. The port has no
interceptor in the path — **direct delivery, no buffering, no padding** (measured identical: 99
events/2 s, 20 ms mean, 24 ms max, zero clumps). So SSE is dropped; the port is the one push
lane, JSON events and binary firehoses alike. Reference: the `sensor` plugin
(`GET /native/sensor/stream?types=…&rateHz=…` → a port stream). Lifecycle: the stream's `onClose`
runs on page-reload/pause (§5), and `GET /native/shell/closestream?id=` closes one on demand.
Trade vs SSE: no built-in auto-reconnect — fine, the stream is local and only "drops" on reload,
where the page re-opens it anyway.

### 4.3 The port — bodies and binary streams

`WebMessagePort` with ArrayBuffer payloads (androidx.webkit ≥ 1.9.0). Originally "the third
pipe that exists only if needed"; Spike 0 promoted it — request bodies never cross the
interceptor, and more than half the catalog's calls carry one (`fs/publish`, `nn/run`,
`print`, `widget/state`, `worker/schedule`, …). Two uses:

- **Body sidecar (uniform, all sizes) — BUILT + validated 2026-07-19.** `shell.native(path,
  {body})` sends `[id(12 ascii) | body]` over the port as an ArrayBuffer, then issues the fetch
  carrying the id in `X-LeadAcid-Body-Id`; the shell's port callback stashes the bytes by id and
  the interceptor thread awaits them (a `ConcurrentHashMap<id, CompletableFuture>` that tolerates
  either arrival order, 5 s timeout). The fetch remains the spine — routing, status codes, §4.6
  error semantics all unchanged — and the artifact never sees the seam. One mechanism for a
  200-byte JSON and a 40 MB DNG alike: no base64, no size cliff, binary crosses as binary. The
  port is delivered on the page's request (`GET /native/shell/port`, see below) via `WebViewCompat.postWebMessage`; `lead-acid.js` holds
  it as a **promise** so a body call before the port arrives just awaits it (no load-order race).
  First user: `fs/publish` — verified byte-exact end to end (JS Uint8Array → port → MediaStore →
  Downloads).
- **Binary push streams.** High-rate native→JS data (UNPROCESSED audio frames,
  SensorDirectChannel IMU batches) streams as ArrayBuffers over a port channel the plugin
  opens; SSE stays for low-rate JSON events (§4 assignment rule).

### 4.4 Escape hatch (documented, discouraged)

A single `addJavascriptInterface` object named `__leadacid` exposing exactly one method: `version()`. It exists so an artifact can synchronously detect the shell before its first fetch. Nothing else will ever be added to it.

### 4.5 The web module — `lead-acid.js`

One optional vanilla JS file (~50–100 lines) an artifact may inline or ignore:

```js
export const shell = {
  present: typeof __leadacid !== 'undefined',
  version: () => shell.present ? __leadacid.version() : null,
  native: (path, opts) => fetch('/native/' + path, opts),
  stream: (path) => new EventSource('/native/' + path),
};
```

**The page PULLS the port (2026-10-07, found on device).** The shell no longer posts the
WebMessagePort at `onPageFinished`: a bundle whose modules evaluate after the load event (the
monorepo's registry/blob-URL build — built wuffle, micro) has no listener yet at that moment, the
port message is dropped, and every body-carrying call and push stream hangs forever. Now
`lead-acid.js` asks for it (`GET /native/shell/port`) right after registering its listener; the
shell creates a fresh channel per request and the newest is the live one on both sides. Delivery
is order-independent by construction. The desktop bench serves the same route and pushes nothing
unasked, so the bench smoke proves the pull through the real shim.

**Push-lane details learned on device (2026-10-07, `intake`).** (1) A plugin may emit INSIDE
the request that opens its stream — `intake` delivers its queue at once — so the first port
messages can reach the page before `stream()` has learned the id. `lead-acid.js` holds messages
for an unknown id (bounded) and replays them once the id registers, after the caller's `.on()`
calls. (2) `onPause` closes every push stream and now TELLS the page (a `close` message per
stream), so a page's `onClose` is how it reopens on resume — wuffle reopens its fused and gnss
streams that way, by choice (a sensor stream is a battery decision the page owns). **`intake` is
different and `lead-acid.js` reopens it ITSELF**, unconditionally: a share that reaches a RUNNING
singleTask instrument pauses it briefly first (onPause → onNewIntent → onResume), the plugin
keeps the item queued until a stream is open, and a page that didn't reopen would leave the
shared file in that queue forever (found on device with micro: the first intent opened a 17 MB
model, the second sat in `intake/pending`). `shell.intake()` returns `{ on, close }` and never a
close notification — the close is the shim's business. The bench's `__bench.pause()` reproduces
the shell's onPause for a desktop guard (`test/micro-shell-smoke.mjs` §3). (3) Shared URIs are registered as fs tokens at DELIVERY
time (when the page asks), never at intent time: a share that launches the instrument arrives
in onCreate, before any page exists, and the page-generation lifecycle would release the token
on the first page start.

**Posture A — a Sealed artifact inside the shell (`shellConnect`, 2026-10-07, micro).** A Sealed
artifact ships `connect-src 'none'`, which also forbids `fetch('/native/…')` — the whole bridge.
Rather than ship a second, unsealed build of the same file, the instrument's activity opts in with
`override val shellConnect = true` and the shell rewrites that ONE directive to `connect-src
'self'` in the HTML it serves (a `PathHandler` wrapper over the asset loader; the file on disk is
untouched, its seal still verifies). The bound holds because the app declares no INTERNET
permission: `'self'` is `https://appassets.androidplatform.net`, and the only thing answering
there is the shell. Micro is the driver: `fileBlob(token, size, name)` in `lead-acid.js` makes an
fs token Blob-shaped (slice / arrayBuffer / text / stream over ranged `/native/fs/<token>` reads,
4 MiB chunks), an intake item carries `.blob`, and micro's `openBlob` takes it exactly as a dropped
File — the 17 MB block model opens never-resident, 324 k blocks in 3.8 s on the S24+ from a cold
VIEW intent. The desktop guard is `test/micro-shell-smoke.mjs` (the built file must STILL say
`connect-src 'none'`: opening it is the shell's serve-time act, never the artifact's).

**The way OUT, and project folders (2026-10-07).** A WebView has no DownloadListener here (by
design) and a `blob:` `<a download>` goes nowhere; worse, `showSaveFilePicker` and
`showDirectoryPicker` are DEFINED in the WebView and abort instantly — the worst kind of
present, since feature detection says yes. So inside the shell an artifact's exports go through
ONE door: `shell.publish` to Downloads (durable, visible to Files and other apps) with the share
sheet offered from the status bar (micro's `deliverBlob`, lamina's `deliverBytes`). And project
folders come from the **`tree` plugin**: `shell.tree.pick()` opens the system folder picker and
returns a File System Access-SHAPED directory handle (getFileHandle / getDirectoryHandle /
removeEntry / entries / resolve / getFile → a ranged `fileBlob` / createWritable → a real
`WritableStream` so `blob.stream().pipeTo(w)` works); handles survive a structured clone
(IndexedDB) and `shell.tree.rehydrate(obj)` brings one back, re-validating the persisted grant.
**Hand-offs (2026-10-08):** `shell.intent.list()` → which allowlisted hand-offs an installed app can take; `shell.intent.send('clock.timer', { seconds: 1800, message: 'core shed' })` starts the other app (calendar, clock, map, dialer, sms / mail, contacts) and resolves `{ launched: true }` — nothing more comes back; a bad field, an unknown name or a missing handler THROW with `{ status, field | capability }`. The page never names an action, a scheme or an extra.
An artifact decides by the SHELL's answer (`shell.tree.probe()`), never by the API's presence. `pick({ initial: 'primary:Download' })` says where the picker OPENS (a hint; micro starts in Downloads — the user navigates anywhere). **Streaming publish (same day):** `shell.publishStream(name, { collection, mime })` returns a real `WritableStream` (the shim's ONE `RemoteWritable`, shared with the tree writer) over `fs/publish/open → write… → close|abort`: the MediaStore row is inserted PENDING, bytes flow to its OutputStream in ≥4 MiB requests, close flips IS_PENDING and returns `{ uri, name, bytes }` with the name MediaStore ACTUALLY gave it (a collision becomes `name (1).ext`), abort deletes the row. `shell.share(name, null, { uri })` shares a published file BY REFERENCE — the bytes are never re-sent. micro's `deliverBlob` pipes `blob.stream()` straight in (48 MiB in 0.45 s on the S24+, zero heap growth); lamina's export dialog streams its sink (324 k rows in 2 s). The whole-bytes `shell.publish` stays for small things and is implemented on the same sink. The CSP rewrite (`shellConnect`) targets the `<meta>` tag only — an artifact's prose may mention the directive first (lamina's header comment did, and the first rewrite hit it).
micro saves and reopens a whole project through it unchanged (`test/micro-shell-smoke.mjs` §5);
lamina is the second posture-A instrument on the same recipe (`test/lamina-shell-smoke.mjs`).

Feature-detection means the same artifact runs on desktop (where `shell.present` is false and native features simply don't appear) and in lead-acid. One file, two homes. `shell.native()` hides the §4.3 body sidecar entirely — callers pass `{body}` as if fetch carried it. For GCU artifacts specifically, `lead-acid.js` also offers the `fs` plugin as a **`@gcu/vfs` backend adapter** (`shell.fsBackend(Backend)` — a `readRange`/`rangeReadable`-true subclass), so Works/notebook artifacts mount phone storage with zero new concepts.

**BUILT + validated (2026-07-19).** `lead-acid.js` ships: `present`, `native`, `stream`,
`version`, `keepAwake`, `files`, `fileSource(token)` (a duck-typed `readRange` source lamina/
micro consume directly), `fsBackend(Backend)` (the `@gcu/vfs` adapter), and — since 2026-10-06 —
`orientation({rateHz})`: the `sensor` plugin's fused rotation vector delivered as W3C
`{alpha, beta, gamma, absolute, accuracy}` readings (`orientationFromRotationVector` is the pure,
exported conversion), so an artifact that consumed `deviceorientation` feeds the same code and
gains the compass accuracy the event hides. On device: all pass,
the adapter's `readRange` rides the mmap path (14.3× per read). **The non-Sealed default:** since
Sealed artifacts can't fetch under `connect-src 'none'`, `/native/fs` is the default *read* path
for **non-Sealed** GCU artifacts — mount `shell.fsBackend` and every `readRange` gets the mmap
speedup with zero artifact changes. (A transparent `Blob.prototype.slice` patch is a documented
future option for non-`@gcu/vfs` artifacts; the adapter is the shipping default.)

### 4.6 Error conventions

One scheme, shell-wide, so instruments never invent their own:

- **`404`** — unknown route or plugin not registered in this app.
- **`403`** — the person refused an Android permission. **There is no shell-wide `perm/request` route** (an earlier draft promised one; it was never built and is not the pattern). A plugin that needs a runtime permission **asks inline**: the first call that needs it posts the system prompt to the UI thread (`host.activity.runOnUiThread { host.requestPermission(P) { … } }`) and blocks the bridge thread on a latch until the person answers (gnss waits up to two minutes) — the request either proceeds from that same call or answers `403 {error, detail, canRequest}`. `canRequest` is "this app's merged manifest declares the permission" (an instrument without the plugin's module can't even ask), not "not permanently denied"; Android does not say "don't ask again" reliably, so the artifact distinguishes the two by whether the prompt appeared. Each such plugin also exposes `GET /native/<plugin>/permission` → `{granted, canRequest}`, and `?request=1` to ask before the first real call (gnss's shape) — so a page can grey a button out or ask at a moment of its choosing.
- **`503`** — capability absent on this device (no UWB radio, no barometer, no StrongBox, no app that handles an intent). Body `{error, capability}` — written with `BridgeResponse.json`; the `BridgeResponse.error` helper produces `{error, detail}`, so plugins that name a capability build the body themselves (intent does). Not an error state to retry; a fact to design around.
- **`409`** — session/claim conflict (device already claimed, single-session plugin busy). Body `{error, holder}` where feasible. Distinct from `503`: the capability exists, someone has it.
- **`500`** — plugin threw. Body `{error, detail}`; `detail` is the exception message, never a stack trace in release builds.

"This phone can't" (`503`), "this app can't ask" (`403`, `canRequest: false`), and "the person said no" (`403` after the prompt) are different UX situations, and the bridge must keep them distinguishable; "not asked yet" never reaches the page as an error, because asking is part of the call.

### 4.7 The bench — developing without the shell

The missing quarter of artifact sovereignty: instruments must be developable in a desktop browser with zero APK rebuilds. `@gcu/lead-acid` ships `bench.js` — a vanilla JS fetch/EventSource shim (or service worker) that mocks `/native/**` with canned fixtures: recorded sensor streams, sample DNGs, a fake GNSS log replaying at wall-clock speed. An instrument's repo carries its fixtures next to its artifact. The loop becomes: develop against the bench on the desktop, deploy through the trench coat, and only debug on-device what is genuinely device-shaped.

## 5. Plugin conventions

- **Naming:** plugin name = path segment = Kotlin class `XxxPlugin`. Routes are documented in each plugin's header comment in OpenAPI-ish shorthand. Route grammar: `GET /noun` reads state, `POST /verb` acts, `/stream` suffixes push (lane per §4), session routes live under `/session/`.
- **Permissions are build-enforced, not promised:** each plugin is a tiny Gradle library module carrying its own manifest fragment; Android's manifest merger then makes it *structurally impossible* for an app to ship a permission whose plugin it doesn't depend on. An app with no GNSS module cannot request location. This is hygiene, Play-review strategy (permissions always match visible purpose), and the @gcu/seal instinct: the capability claim is a build fact.
- **Composition is the artifact's job.** No plugin ever calls another plugin. gnss+spp (external RTK), camera+attest (signed capture), intake+share+attest (aupro) — all composed in the artifact's JS, native side stays a flat registry of independent concerns.
- **Sessions have one contract, in shell core.** State a plugin holds open belongs to a *page generation*. **As built:** `onPageStarted` (a load or reload) closes every push stream (`closeAllPushStreams` — sensor, gnss, intake and every other stream die with the page that opened them), releases the page's SAF picks and drops pending bodies; `onPause` closes push streams too (intake reopens its own, §4.5). Work backed by a foreground service survives and the new page **re-attaches** (gnss's logger: `log/status` after a reload; the §5 background stance made into a rule). Single-holder plugins answer `409 {error, holder}` on a second claim (gnss `log/start`). **Not built yet:** the general `SessionRegistry` for claims (BLE/USB devices, camera preview held open) — its first users are the bluetooth plugins (`docs/bluetooth-plugins.md`); until then a plugin keeps its one session itself and hangs it off a `PortStream`, which the generation already closes.
- **Lifecycle:** plugins receive `onCreate/onResume/onPause/onDestroy` forwarding, and may request a **foreground service** context for long-running work (GNSS logging with the screen off). The foreground-service plumbing is shell-provided; a plugin opts in with one flag. **Badge work is Kotlin; JS only computes, never tends.** Service loops are platform-callback plumbing (dumb, reliable capture) written natively — there is no JS-mode service. A plugin's service *may* evaluate a pure worker function per-batch where a transform genuinely earns it (capture in Kotlin, digest through the isolate every N seconds), keeping the function fixture-testable; that is a per-plugin decision, never a shell mode. The service notification is a UX surface: state the instrument's actual state ("GNSS logging — 4,312 epochs, fix: RTK float"), not "app is running."
- **Threading:** `handle()` is called off the main thread (the WebView's request interceptor thread, which a plugin may block — the inline permission ask does). Main-thread-only APIs (`startActivity`, the permission prompt, `SpeechRecognizer`, camera) go through `host.activity.runOnUiThread { … }`, with a latch when the answer is needed before returning (camera, gnss, intent). There is no `onMain {}` helper; an earlier draft named one.
- **Background stance:** the WebView never runs in the background. Background *work* is Kotlin's job (foreground-service plugins, WorkManager); the artifact reattaches to ongoing native work on reopen (SSE reconnect, state catch-up). Persistent offscreen WebViews fight Android's process and power management and lose — lead-acid does not field that fight.

### 5.1 The committed set — plugins with a named driving instrument

Each lands *with* its instrument (the M3+ model), never speculatively; the driver column is
the build trigger. A row moves up from §5.1b only when an instrument names it.

| Plugin | Driver | Routes (sketch) | Native surface |
|---|---|---|---|
| `fs` | micro (M0) → every file-consuming artifact | `GET /native/fs/{token}` (ranged), `POST /native/fs/publish` | SAF picker + mmap'd serving; `publish` inserts finished outputs (DNGs, logs, upscales) into public MediaStore collections (Photos, Downloads) so they survive uninstall — app-private storage dies with the app |
| `sensor` | wuffle v2 (survey-grade orientation) | `GET /native/sensor/stream?types=…` | SensorManager, uncalibrated streams. For sustained high-rate capture (200–800Hz IMU, vibration/hammer-seismics experiments), **`SensorDirectChannel`**: HAL writes samples into a shared hardware buffer, bypassing per-event callback overhead — the difference between phone-as-toy and phone-as-data-logger at those rates. High-rate batches push on the port lane (§4) |
| `gnss` | wuffle v3 → the raw GNSS logger | `stream`, `status`, `log/start`, `log/stop` | GnssMeasurement raw observations — full design in `docs/gnss-plugin.md` (platform LocationManager not GMS; first dangerous perm; first foreground-service logger; WMM declination path for wuffle) |
| `ble` | aupro's rig hardware; any Pico instrument | `scan`, `connect`, `gatt/…`, `stream` | BLE central (GATT client). Needed because **Android WebView has no Web Bluetooth** — artifacts that talk to BLE instruments (a Pico, field hardware) reach them through this plugin, not the browser API |
| `spp` | aupro (external RTK receivers) | `connect`, `stream`, `mockloc/start` | Bluetooth Classic serial to external RTK receivers + system mock location provider |
| `camera` | wuffle v3 (outcrop photo) → the camera app | `preview` (stream), `capture`, `params` | CameraX: RAW, manual, multi-lens, RAW14 — full design in `docs/camera-plugin.md` (the transparent-WebView native-preview composite; capture bytes ride the response body; getUserMedia is the no-plugin basic path) |
| `attest` | the first data-producing instrument — land early (the starred sleeper) | `POST /native/attest/sign`, `keyinfo` | Hardware keystore (StrongBox/TEE) signing of capture hashes — cryptographic provenance for field data |
| `tree` | micro (project folders) | `POST pick`, `GET restore/list/stat/token`, `POST mkdir/remove/rename/open/write/close/abort` | A folder the user picks (ACTION_OPEN_DOCUMENT_TREE, grant persisted) as a directory tree. `lead-acid.js` serves it as File System Access-shaped handles (`shell.tree.pick/restore/rehydrate`), so a project-folder artifact runs unchanged. Exists because the WebView DEFINES `showDirectoryPicker` and aborts it instantly |
| `intake` | aupro (the packer's front door) | (manifest share target → `stream`) | Android share sheet as input: pipe shared files/text into an artifact |
| `intent` | golem (calendar / timer / alarm cards); any instrument's "open on a map" or "share by message" | `GET list`, `POST /native/intent/<name>` | **BUILT 2026-10-08.** Allowlisted hand-offs: `calendar.insert`, `clock.timer`, `clock.alarm`, `map.view`, `dial`, `sendto` (sms / mail), `contact.insert` — the page names one and gives typed fields, the shell builds the intent and starts the other app where the person confirms; `400 {field}` on a bad field, `404` off the list, `503 {capability}` when no app handles it. One install-time permission (`SET_ALARM`), the `<queries>` block, no INTERNET. `shell.intent.list()` / `shell.intent.send(name, fields)`; the bench validates and records every send (`__bench.intents`). Spec: `auditable/spec_inbox/lead-acid_and_friends/intent-plugin-spec.md` |
| `share` | every instrument's export gesture | `POST /native/share` | Outbound twin of `intake`: hand a file or text to the system share sheet (Web Share API is absent in WebView). The natural "export this log" gesture |
| `notify` | background workers (M4) | `POST /native/notify` | Post/cancel notifications outside service contexts ("post-processing finished") — the Notifications web API is absent in WebView |
| `mcast` | jormungandr (`../jormungandr` — the phone joins the supervisor's subnet pub/sub) | `send`, `stream`, `status` | UDP multicast on the local subnet (MulticastSocket + MulticastLock) — the ATAK chemistry: subnet-as-rendezvous, ~80 lines. Primary field-sync carrier; see the field-carriers spec |

**`ble` + `hid` have a full design doc:** `docs/bluetooth-plugins.md` (permissions, session
lifecycle, routes, the classic-vs-BLE-HID decision, the RelayKVM/phone-as-keyboard vision). Not
built — `share`/`attest` land first — but specced so implementation is mechanical.

### 5.1b The futures catalog — sketches with no driver yet: don't build

Kept for their design content, not as a plan.

| Plugin | Routes (sketch) | Native surface |
|---|---|---|
| `hid` | `POST /native/hid/report`, `stream` (state) | BLE HID peripheral via `BluetoothHidDevice` (RelayKVM: phone as controller+bridge, or as software-defined relay — NUS GATT server in, HID out; dual-peripheral operation is a per-device verification item) |
| `nn` | `POST /native/nn/{model}/run` | LiteRT, best-available delegate (NPU→GPU→CPU). SDK 37+: NPU access (including the LiteRT NPU delegate) requires declaring `FEATURE_NEURAL_PROCESSING_UNIT` in the manifest — joins the plugin's permission merge |
| `usb` | enumerate, claim, transfer | USB host / OTG serial — full design in `docs/usb-plugin.md` (vendor usb-serial-for-android v3.11.0 MIT into the module; per-device permission, no dangerous runtime perm; read = port push stream, write = body sidecar). Wired sibling of `ble`/`spp` |
| `grip` | `stream` (inputs), `POST display` | Composite HID+CDC device (see grip project) |
| `audio` | `stream?src=unprocessed`, `record` | AudioRecord UNPROCESSED source, exact sample timestamps (hammer seismics, acoustic experiments). Frames push on the port lane (§4) — binary at machine rate is not SSE's job |
| `nfc` | `stream` (tag taps), `write` | Tap-to-log sample chain-of-custody: tag ID + GPS + timestamp |
| `cell` | `snapshot`, `stream` | CellInfo tower IDs and signal strengths — RF coverage mapping |
| `ranging` | `sessions`, `stream` | Wi-Fi RTT + UWB behind one interface, GNSS-denied positioning |
| `haptics` | `POST /native/haptics/play` | Vibration composition API: virtual detents, capture thunk |
| `baro` | `stream`, `snapshot` | Barometer: relative elevation, barometric leveling, pressure-stamped logs |
| `aware` | `publish`, `subscribe`, `stream`, `socket/{peer}` | Wi-Fi Aware (NAN): infrastructure-free discovery + IPv6 link-local data in the true void. Hardware-gated (`503` when absent), Android-only in practice — queued behind `mcast`, allowed to never happen. See the field-carriers spec |
| `meshtastic` | `send`, `stream`, `nodes`, `status` | LoRa mesh at ridge distance via the official Meshtastic app's `IMeshService` AIDL binding (the ATAK-plugin pattern); GCU traffic on registered private portnums. App-level dependency, `503` without it. Beacon/record bandwidth only — see the field-carriers spec §3b |
| `print` | `POST /native/print` | Android print framework: log sheets and custody forms onto site printers |
| `torch` | `POST /native/torch` | Flashlight control, intensity where supported (fill light) |
| `sms` | `POST /native/sms/send` | Data-over-SMS telemetry for dead zones. **Sideload-only forever** — Play treats SMS permissions as radioactive |
| `widget` | `POST /native/widget/state` | Home-screen App Widget as dumb terminal: artifact posts JSON state, Kotlin maps it onto a RemoteViews template. Last state persisted (SharedPreferences) and re-rendered on system `onUpdate`, so the widget survives process death with no WebView kept alive — the shoulder-LCD pattern, home-screen edition |
| `tts` | `POST /native/tts/speak` | System text-to-speech engine (offline, no services dependency). Speech *recognition* stays unbuilt: it routes through Google services and fails the collects-nothing posture |
| `tile` | `POST /native/tile/state` | Quick Settings tile (`TileService`): instrument toggle in the notification shade, rendered from persisted state like the widget; taps route to the plugin's service. The fifth shoulder-LCD surface, and the most instrument-shaped one Android offers |
| `thermal` | `GET /native/thermal/headroom`, `stream` | `ThermalManager` headroom/throttling status + ADPF performance hints — lets the *artifact* drop the LOD budget or pause an `nn` batch before the OS throttles uglier |
| `biometric` | `POST /native/biometric/prompt`, `keyinfo` | `BiometricPrompt` — unlock-to-act. **Pairs with `attest`:** a keystore key bound to biometric auth signs only after a fingerprint/face check → *unlock-to-sign* provenance. The one non-exotic capacitor-parity gap actually worth building. |
| `sqlite` | `POST /native/sqlite/exec`, `query` (batch), `POST /native/sqlite/tx` | Native SQLite (+ optional SpatiaLite/R*Tree) for a **relational/indexed/spatial** local store — a data-collection instrument (records app, aupro collector), NOT the columnar-streaming path (`fs` already serves block models/clouds). **Caveat:** native-over-the-bridge has a per-query latency tax vs in-process wasm SQLite; wins on big queries + heavy writes + spatial indexes, so the API is batch/cursor-first. Build when a collection instrument drives it. |
| `securestore` | `GET/POST /native/securestore/{key}` | `EncryptedSharedPreferences` (keystore-wrapped) key-value for small secrets/tokens a networked artifact needs. `attest`-adjacent; not for bulk data (that's `fs`). |
| `badge` | `POST /native/badge` | App-icon notification badge count (the sixth shoulder-LCD surface). Trivial. |
| `clipboard` | `GET/POST /native/clipboard` | System clipboard read/write. Trivial; `403` on read where the OS gates paste. |
| `device` | `GET /native/device` | Static device facts (model, api level, screen, sensors present, StrongBox) for capability-shaping. Read-only, no perm. |
| `net` | `GET /native/net/status`, `stream` | Connectivity status (`ConnectivityManager`) — only for **networked** artifacts that need to know they're offline. Absent by default (the Sealed posture has no network to report). |
| `screen` | `POST /native/screen/brightness` | Force brightness (sun readability) + orientation lock; the field companion to `keepAwake`. |

**Capacitor-parity audit (2026-07):** the popular Capacitor ecosystem sorts into (a) the same device
capabilities already above, (b) things the artifact does itself (SQLite-via-wasm, dialogs, toast,
maps, preferences — the artifact brings its UI + storage), and (c) a large set we deliberately
**refuse** on posture grounds — OAuth/social login, remote push (needs an FCM server), the Firebase
suite (analytics/auth/crashlytics), speech *recognition* (Google-server-routed), IAP/payments,
contacts/calendar/health. The genuine unplanned remainder was small and non-exotic (the rows above).
lead-acid also has depth Capacitor lacks in the field-instrument niche: `attest` provenance, `hid`
peripheral, raw `gnss`, `meshtastic`, the streaming mmap `fs`.

**Route additions to committed plugins (not new plugins):**
- `fs`: a **directory / tree pick** (`ACTION_OPEN_DOCUMENT_TREE`) — micro's project folders need a
  whole-folder pick, and `onShowFileChooser` only does files. `GET /native/fs/picktree` → a token
  scoping ranged reads/writes under the granted tree.
- `camera`: a **video** capture mode + on-device **OCR/QR** (ML Kit, no network — photograph a
  core-box label → text; scan a sample tag; the visual sibling of `nfc` tap-to-log; combines with `nn`).
- `sensor`: extend the type map with proximity / ambient-light / pressure / temp / humidity / step —
  all `SensorManager` sensors, a one-line-each addition.

**Design-log note — Companion Device Manager.** For instruments with dedicated hardware (the grip, RelayKVM's Pico): CDM association replaces the generic scan-everything flow with a system dialog scoped to the device, and grants background presence privileges — wake when the companion appears, observe its connection without broad scan/location permissions, exemptions from some background-start restrictions. "Grip clicked on → camera instrument ready." One shell-level `companion` affordance serves all hardware projects.

**Design-log note — satellite messaging.** Android 15+ carries genuine satellite connectivity APIs, currently carrier- and device-gated, messaging-shaped. The legitimate dead-zone story for field instruments (the `sms` row's cursed cousin, uncursed). Not buildable-on-demand yet — watch this space. (Brazil status mid-2026: hardware ready on ordinary LTE modems, Anatel sandbox open, no operator contract signed.)

**Design-log note — Handoff / Continue On (Android 17).** Apps declare resumable state, synchronized via CompanionDeviceManager, surfaced as a handoff suggestion on nearby devices — with **app-to-web fallback** when the native app is absent on the receiving end. Nearly custom-built for single-file artifacts: a micro session on the phone hands off to gentropic.org/micro on the tablet. The App Links "one URL, two referents" idea promoted to live state transfer. A shell-level affordance candidate once SDK 37 is the target.

**Design-log note — AppFunctions / Android MCP (Android 17).** The platform now lets apps contribute capabilities as orchestratable tools for Android MCP (on-device Model Context Protocol). This is the expose-your-levers pattern of the automation-broadcast note, arriving as a first-class platform citizen — assistant-oriented rather than automation-oriented, but the same shape. When the broadcast convention is implemented, evaluate whether AppFunctions can be a second frontend onto the same allowlisted plugin dispatch; reflex's conductor role gains a system-level sibling to interoperate with.

**SDK-37 ratchet tax (recorded for the yearly bump):** local network access is deny-by-default (new `ACCESS_LOCAL_NETWORK` permission — anything talking to a homelab or HA webhook must declare it); large-screen resizability opt-outs are gone (lead-acid complies by design); background-audio and BAL hardening may touch `audio` and reflex-adjacent intent patterns; and Advanced Protection Mode, when a user opts in, blocks unknown-source installs — sideload-only instruments (`sms`) are invisible to those users.

**Design-log note — hybrid AR.** WebXR is absent from WebView and cannot be grafted on (compositor-level browser integration). For coarse AR — compass overlays, strike/dip readouts, labels on an outcrop — a viable hybrid: an `ar` plugin runs the ARCore session and streams 6DoF pose + camera intrinsics over SSE (~30Hz); the camera preview renders as a native Surface behind a transparent WebView (`setBackgroundColor(TRANSPARENT)`, a supported configuration); the artifact draws its WebGL overlay aligned via the poses. Bridge latency makes tightly-anchored geometry wobble — acceptable for instrument-grade annotation, not for point-clouds-pinned-to-the-outcrop, which is a separate native (GL/Filament, not Vulkan) project with lead-acid nowhere in it.

**Design-log note — automation broadcasts (deferred past v1).** Instruments are exactly what Tasker/MacroDroid users want to orchestrate ("start GNSS log on arriving at site"). The sprawl-resistant design: one generic broadcast receiver mapping a documented intent (`extra: route=gnss/log/start`) onto the same plugin dispatch the artifact uses — anything the artifact can trigger, an automation can trigger, one codepath, zero per-plugin surface. Outbound, plugins may broadcast state changes (`LOG_STARTED`) through an equally generic emitter. The A-Bus instinct applied to Android's intent system. Deferred because compatibility gravity is real: once documented, route names in broadcasts are API forever. The planned first-party conductor for these levers is **reflex** (`docs/reflex.md`) — a restrained automation app holding the trigger permissions exactly once so instruments never do. Its founding argument: the stalkerware-shaped tier (accessibility, notification/screen reading, input injection) is *severable*, and what remains — scheduling, geofencing, plain broadcasts, firing intents — is rich AND Play-clean by construction. The automation app Google actually approves, because it provably can't watch you.

**Starred sleeper — `attest`.** Every other row produces a new measurement; `attest` produces a new *kind of claim*: field data that carries a hardware-backed signature from the moment of capture. Signed-at-capture DNGs, GNSS logs, and custody records upgrade auditable-forever from a philosophy to a verifiable chain. Disproportionate value for one Kotlin file; should land early once instruments start producing data worth signing.

**Design-log note — MIDI as grip fallback.** Android ships a full MIDI stack (USB and BLE MIDI). If the grip's composite HID+CDC design ever proves annoying in practice, MIDI CC is a 40-year-old, universally supported protocol whose entire purpose is knobs and buttons, with SysEx available for the display downlink. Not a plugin; a contingency for the grip project's design log.

### 5.2 Workers — background JS without a WebView

The WebView never runs in the background (§5, background stance). But some instruments legitimately want periodic *JS* computation while closed — weir digesting feeds hourly, GNSS post-processing overnight. lead-acid answers with **workers**: JS evaluated in `androidx.javascriptengine` (`JavaScriptSandbox`/`JavaScriptIsolate`) — the system WebView's own V8 in a sandboxed process, no DOM, no rendering, no bundled runtime. Same engine the artifact runs on; nothing new in the APK. Node, Deno, and bundled QuickJS are all rejected on GCU grounds (ecosystem gravity, no Android story, and a second engine to maintain forever, respectively).

**Workers are pure functions, by force and by creed.** The sandbox has no host-function bridge — no `fetch`, no callbacks into Kotlin. The interaction model is: Kotlin gathers inputs, evaluates the worker, receives outputs. Effects (HTTP, file writes, widget updates, notifications) belong to Kotlin plugins on either side of the evaluation. Consequences, all welcome:

- A worker is a single vanilla JS file exporting one pure function: bytes/JSON in, bytes/JSON out.
- Every worker is testable in any browser console and deterministic by construction — it *cannot* touch the world.
- Artifact and worker can share code: same file, feature-detected, runs in the page and in the sandbox.

**Machinery:** a `WorkerHost` in the shell core owns WorkManager scheduling and sandbox lifecycle. The dance per wake: gather inputs via plugins → evaluate worker in an isolate → apply outputs via plugins → die. The artifact registers work via `POST /native/worker/schedule` (worker name, cadence, input/output plugin bindings declared as data, not code).

**Verification item V-2:** isolate capabilities (ArrayBuffer passing, WASM, max heap) vary by WebView version — `WorkerHost` must feature-check at runtime. Graceful floor: a declined worker is deferred to "next time the artifact opens," where the same pure function runs in the page instead. Degradation without divergence.

### 5.3 The two-tier processing contract

**Interactive is the default and free.** Artifact open, full V8, full DOM and graphics, every plugin, the human as control flow: `intake` → artifact → `share`. Most transforms on the shelf are seconds long and benefit from eyes on them; they should never pay background-mode costs.

**Background is opt-in and priced.** A flow may offer "finish in background" only if it accepts all of:

1. The computation is a **pure worker function** (checkpointable, if resuming a half-done run matters) *or* was **already native** in a plugin's service (e.g. `nn` inference — the artifact was only ever the dashboard).
2. Progress is observable **only through the service notification** — the shoulder-LCD is the pipeline's entire UI until reopened.
3. Effects on completion are limited to **declared plugin routes** (`fs/publish`, `notify`, `widget`), stated in the dispatch, not decided mid-run.
4. The run **tolerates death**: WorkManager and the OS make no promises interactive mode never had to think about.

Completion is uniform: `notify` fires; tapping reopens the artifact onto the finished result. The asymmetry is deliberate — nobody wanders into background processing accidentally, and nobody pays its constraints for a ten-second interactive flow. The question "does this need to run with the screen off?" is the entire decision procedure.

## 6. Packaging & build

- **Layout:** `@gcu/lead-acid` repo contains `core/` (the Kotlin library module), `template/` (a minimal app project consuming it), and `lead-acid.js`.
- **The recipe:** drop `artifact.html` into `template/app/src/main/assets/`, set `applicationId`, icon, and plugin list, run `gradlew bundleRelease`. Target output: artifact → installable APK in under five minutes of human attention.
- **Yearly ratchet:** Google raises the required `targetSdkVersion` annually; the template pins it in **one** version-catalog entry so bumping the entire shelf is one commit per year.
- **Signing:** documented once in the template README; keystore never enters any repo.
- **AAB for Play, APK for gentropic.org.** Both from the same build.
- **File associations:** the template includes a commented VIEW intent-filter recipe so an instrument can register for its file types (e.g., tapping a point cloud in a file manager opens micro); the received content URI is handed to the `fs` plugin for ranged serving.
- **Launcher affordances:** template recipes for static app shortcuts (long-press icon → "New log", "Resume last cloud") and verified App Links, so `gentropic.org/<instrument>` opens the installed app or the live single-file site — one URL, two interchangeable referents of the same artifact.

## 7. Desktop-windowing manners

Android's AOSP desktop windowing (connected displays, DeX-successor) costs nothing to support and the shelf should be clean for it from day one:

- `resizableActivity="true"`, no locked orientation in the shell (an artifact may request one via a meta tag the shell reads — the camera app legitimately wants landscape).
- Survive resize and density change without reload: the WebView handles this natively; the shell must simply not recreate it on configuration change (`android:configChanges` handled, state preserved).
- Don't intercept hover, right-click, scroll wheel, or keyboard shortcuts — Chromium already speaks desktop.

## 7b. Shell UX obligations

Two things every wrapped web app gets wrong unless the shell decides them once:

- **Back.** Convention: if the artifact defines `shell.onBack` (via `lead-acid.js`), the shell calls it; a `true` return means "handled" (the artifact popped a panel, closed a dialog), `false` means the shell finishes the activity. No `onBack` defined → back exits, immediately and honestly. Predictive back (Android 14+) is declared in the template manifest; the shell wires `OnBackPressedCallback` accordingly so the system animation reflects what will actually happen.
- **Edge-to-edge and insets.** Android 15 enforces edge-to-edge; content draws under the status bar and gesture areas. **V-3 RESOLVED (S24+ / WebView 150): the WebView does NOT populate `env(safe-area-inset-*)` — the values are 0** even edge-to-edge. So the shell's DEFAULT is to keep the artifact inside the insets itself: an inset listener pads a container view (systemBars + displayCutout + ime — the keyboard rides the same mechanism, giving adjust-resize for free). **Gotcha: WebView ignores its own padding — the padding must go on a wrapping container.** Artifacts need zero inset awareness by default; true edge-to-edge (artifact draws under the bars and manages `--la-inset-*` CSS variables the shell injects) becomes a per-artifact meta-tag opt-in when a design wants it. Switchboard layouts should still treat insets as first-class — the punch-hole camera is not a rendering surface.

## 7c. WebChromeClient wiring obligations

Web APIs that exist in WebView but silently no-op unless LeadAcidActivity implements the host callbacks. All of these are shell core, not plugins; forgetting any produces the classic wrapped-app bug where a feature that works in Chrome does nothing in the app:

- **`getUserMedia`** — `onPermissionRequest` answered + camera/mic manifest permissions merged when an artifact declares it needs them. (The camera *preview* path before/without the `camera` plugin.)
- **`navigator.geolocation`** — `onGeolocationPermissionsShowPrompt` + the location permission dance. Artifacts get standard web geolocation for free once wired.
- **`<input type=file>`** — `onShowFileChooser` + SAF picker intent, or file inputs silently do nothing.
- **Fullscreen** — `onShowCustomView`/`onHideCustomView`, for video and for artifacts going immersive.
- **Screen wake lock** — the web API is unreliable in WebView; the shell honors a `shell.keepAwake(true|false)` call (or meta tag) via `FLAG_KEEP_SCREEN_ON`. Field instruments need this constantly.
- **Picture-in-Picture** — `POST /native/pip/enter` shrinks the activity to the system PiP tile; the WebView keeps rendering inside it. PiP receives no touch input (only declared `RemoteAction` buttons), so artifacts render a compact glanceable view when the shell exposes `pip: true` — the shoulder-LCD pattern again (grip OLED, widget, notification, PiP tile: Switchboard should define one canonical "instrument at a glance" layout and reuse it across all four surfaces). The `SYSTEM_ALERT_WINDOW` overlay is explicitly rejected: permission-gated, review-hostile, impolite.

## 8. Security posture

Stated rather than assumed:

- **No INTERNET permission by default.** The default template manifest requests nothing; an artifact that genuinely needs network opts in explicitly and documents why.
- **Navigation is locked to the virtual origin.** Any navigation attempt off `appassets.androidplatform.net` is cancelled; external links are handed to the system browser via intent. The WebView never renders third-party content, full stop.
- **File-access settings off.** `allowFileAccess`, `allowContentAccess`, and friends are disabled; all file service goes through the `fs` plugin's deliberate, ranged, token-scoped routes.
- **The JS interface surface is one method** (`__leadacid.version()`, §4.4) and is contractually frozen.
- **The bridge trusts its caller because there is only one.** Any JS in the WebView can call `/native/**`; this is safe because the only JS that ever runs is the bundled artifact — a property the above rules exist to defend. If any rule above is relaxed for some future app, the bridge's trust model must be reconsidered in the same commit.
- **Auto Backup off.** `android:allowBackup="false"` in the template: the default would ship app data to the user's Google account backup, quietly falsifying "transmits nothing." Instruments that want durability publish through `fs/publish` instead — explicit, local, user-visible.

## 9. Store compliance posture

Baked into the template so every instrument inherits it:

- Data-safety form answers: all "No" (no collection, no sharing, no transmission). Any plugin that would change an answer must scream about it in its README header.
- Privacy policy: the GCU standard one-paragraph text ("collects nothing, transmits nothing, has no server to send anything to"), hosted at a stable gentropic.org URL, linked from every listing.
- Permissions match purpose (enforced structurally by §5's per-plugin permission merging).
- Organization developer account (via the Ltd) — exempt from the personal-account 12-tester/14-day closed-testing requirement.
- Offline-functional and app-like by construction, which is the practical defense against "minimum functionality" rejections of web-wrapped apps.

## 10. Milestones

- **M0 — the trench coat.** `LeadAcidActivity` + asset loading + interceptor + ranged `FilePlugin`. Acceptance: **micro's existing single file, unmodified, renders the 2M-block-model example at the 250k budget on an S24+, reading the cloud file via ranged fetch from internal storage.** This is the entire proof of concept and roughly an afternoon-to-a-weekend of work.
- **M1 — push.** `SseStream` helper + `sensor` plugin + V-1 verification. Acceptance: a test artifact plots live accelerometer data via `EventSource`, screen-off logging survives via foreground service.
- **M2 — template, bench & docs.** The five-minute recipe works for a stranger. `lead-acid.js` and `bench.js` published; error conventions (§4.6) and back/inset contracts (§7b) implemented. Yearly-ratchet mechanism documented.
- **M3+ — instruments.** Plugins land with their instruments: `hid` with RelayKVM, `gnss` with the raw logger, `camera` with the camera app, `grip` with the hardware. Each is its own project consuming lead-acid, not part of it.
- **M4 — workers.** `WorkerHost` + V-2 capability checks + `worker/schedule` route. Acceptance: a weir-style digest worker runs on WorkManager cadence with the artifact closed, posts a summary to the `widget` plugin, and the identical worker file produces identical output when run in the open artifact.

## 11. Open questions

- **Q-1:** WebGPU availability in Android WebView — check at M0 against the current WebView release; record findings. WebGL2 remains the assumed floor.
- **Q-2:** SAF vs. app-private storage for large point clouds — SAF is the polite answer, mmap over SAF-obtained descriptors needs verification on real devices.
- **Q-3:** Whether `Last-Event-ID` reconnection semantics are worth implementing per-plugin or shell-wide.
- **Q-4:** Minimum supported Android version. Instinct: `minSdk` wherever androidx.webkit's needed features begin, and not one API level lower out of nostalgia. Lead-acid is a chemistry, not a museum.

---

*Geoscientific Chaos Union — CC0. No dependencies were harmed, or indeed acquired, in the making of this specification.*
