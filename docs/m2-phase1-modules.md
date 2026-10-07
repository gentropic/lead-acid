# M2 Phase 1 — the module split + plugin registration

**Design of record for turning lead-acid from one bespoke app into a reusable jig.**

> **BUILT 2026-10-06.** Modules, factories, host, template — all as below, with the deviations
> recorded in "What landed differently" at the end. Verification: items 2–3 done (JS untouched;
> the FileProvider witness holds: template without `plugin-share` → 0 providers, with → 1).
> Item 1 (the on-device bench battery) **PASSED on the S24+ 2026-10-07** — 13/13 (ranged
> reads 61 MB/s, push stream 100 events/2 s with 0 clumps, attest on StrongBox, fs/publish,
> shell/info `0.1-m2`, keepawake) — after one real finding: the built wuffle hung on every
> body call and stream because the port was pushed at `onPageFinished` before a registry
> bundle's modules had a listener. The page pulls the port now (SPEC §4.3).
Phase 1 only: the Gradle module split, the plugin-registration API, and the manifest-merge model.
Signing / release / wuffle-app are Phase 2; the pack script + stranger-docs are Phase 3.

Nothing about the bridge protocol or the JS side (`lead-acid.js`, `bench.js`) changes — this is a
Kotlin-side re-org plus one new API. Artifacts are untouched.

## Module layout

| module | Gradle type | contents |
|---|---|---|
| **`core`** | `com.android.library` | the jig: `LeadAcidActivity` (base), the bridge (`Plugin`/`BridgeRequest`/`BridgeResponse`/`PhantomSkipStream`), dispatch + navigation lock, the WebMessagePort body sidecar + `PushBus`/`PortStream`, the §7 obligations (insets, back, file chooser, keepAwake, fullscreen, geolocation/getUserMedia callbacks), page-generation lifecycle, the `PluginHost` impl, and the **always-present** plugins `fs` + `shell`. Manifest requests **nothing** (§8). |
| **`plugin-sensor`** | library | `SensorPlugin` + `Sensor` factory + manifest (no perm — not a body sensor) |
| **`plugin-share`** | library | `SharePlugin` + `Share` factory + **its own** FileProvider + `file_paths.xml` + manifest |
| **`plugin-attest`** | library | `AttestPlugin` + `Attest` factory (StrongBox flag from the host) |
| *(future)* `plugin-ble`, `-hid`, `-usb`, `-gnss`, `-camera` | library | one per capability, each owning its permissions |
| **`app`** (the current spike) | `com.android.application` | the **dev/kitchen-sink** harness: depends on `core` + ALL plugin modules, hosts any artifact (`--es artifact`), carries the bench page. Our development + on-device-test app. Stays. |
| **`template`** | `com.android.application` | the **copy-me skeleton**: `core` + a couple plugins, one `assets/artifact.html`, the instrument config. The five-minute starting point. |

`fs` and `shell` are **core, not plugins** — `fs` (ranged mmap) is the load-bearing feature every
file artifact needs and needs no permission; `shell` is shell-level (keepAwake/info/closestream).
Everything that needs a permission or a device capability is a plugin module.

## The registration API (DECIDED: factories + a host)

An instrument declares its capabilities as a **list of plugin factories**; `core` wires each with a
`PluginHost` (its own capability surface). Minimal and auditable — the app is a subclass with a
few-line list, and the Gradle dependency + manifest merge carry the permissions.

```kotlin
// core
interface Plugin { val name: String; fun handle(req: BridgeRequest): BridgeResponse }

fun interface PluginFactory { fun create(host: PluginHost): Plugin }

interface PluginHost {
    val context: Context
    val activity: ComponentActivity                 // startActivityForResult, requestPermissions, window
    val pushBus: PushBus                             // streaming plugins
    fun onActivityResult(requestCode: Int, handler: (Int, Intent?) -> Unit)
    suspend fun requestPermission(perm: String): Boolean
}

open class LeadAcidActivity : ComponentActivity(), PluginHost {
    /** Instruments override this to declare their capabilities. */
    open val plugins: List<PluginFactory> = emptyList()
    // onCreate always registers fs + shell, then each declared factory via create(this)
}
```

```kotlin
// plugin-sensor — the module exposes one factory object
val Sensor = PluginFactory { host ->
    SensorPlugin(host.context.getSystemService(Context.SENSOR_SERVICE) as SensorManager, host.pushBus)
}

// an instrument (template / wuffle-app) — this is the WHOLE wiring:
class WuffleActivity : LeadAcidActivity() {
    override val plugins = listOf(Fs, Share, Attest)   // (Fs is core-provided; here for illustration)
}
```

- **Why not self-registration (ServiceLoader/androidx.startup)?** Too much magic — you can't read
  the app and see its capabilities. Explicit list = auditable, which is a GCU value.
- **Why not hand-construct plugins in the subclass?** Factories hide the host-wiring boilerplate
  (SensorManager, StrongBox flag, the publisher) while staying a one-line-per-plugin list.
- `PluginHost` is the shell's service surface plugins draw on. `SharePlugin` builds its
  `ACTION_SEND` via `host.activity`; `AttestPlugin` gets the StrongBox flag from the host;
  streaming plugins take `host.pushBus`. The share/file-chooser result path is
  `host.onActivityResult(reqCode, handler)` (core routes `onActivityResult` to registered handlers).

## Manifest merge = build-enforced capability

`core`'s manifest requests nothing. Each plugin module's manifest contributes only *its* elements
(permissions, the share FileProvider, a future camera `uses-feature`). The app's merged manifest =
`core` + the plugin modules it depends on. So **an app cannot ship a permission whose plugin it
didn't include** — structural, per §5.

**Proof for Phase 1 (no perm-bearing plugin is built yet):** use the share **FileProvider** as the
merge witness — a `template` *without* `plugin-share` has no `…fileprovider` in its merged
manifest; *with* it, the provider is present. That proves per-module manifest contributions merge.
The permission version of the proof lands with the first perm-bearing plugin (`gnss`→LOCATION,
`camera`→CAMERA). **Landed 2026-10-07 with `plugin-gnss`:** merged manifests show
`ACCESS_FINE_LOCATION` + `COARSE` in `app` and `wuffle` (both depend on it) and neither in
`template` (which doesn't). Build-enforced capability, proven both ways.

## Migration map (what moves, mechanically)

- `Bridge.kt`, `Push.kt`, the WebMessagePort + push-registry code, the §7 wiring, the
  page-generation lifecycle, `FilePlugin.kt`, `ShellPlugin.kt`, and `LeadAcidActivity` → **`core`**,
  with `LeadAcidActivity` gaining `PluginHost` + the `plugins` hook and losing the hard-coded
  `register(sensorPlugin/SharePlugin/AttestPlugin)` calls.
- `SensorPlugin.kt` → `plugin-sensor`; `SharePlugin.kt` + FileProvider + `file_paths.xml` (out of
  `core`'s manifest) → `plugin-share`; `AttestPlugin.kt` → `plugin-attest`. Each gains a factory.
- The current `app` keeps its `LeadAcidActivity` subclass but its `plugins` list now names the
  factory objects instead of `register(...)` calls; it depends on all plugin modules (kitchen sink).
- `settings.gradle.kts` includes the new modules; `libs.versions.toml` stays the shared catalog.

## Verification (Phase 1 "done")

1. The dev `app` still passes the full on-device bench battery (all lanes, fs/publish, sensor
   stream, share, attest) — the refactor changed structure, not behavior.
2. `wuffle-bench-smoke` + `wuffle-smoke` still green (JS side untouched).
3. The FileProvider merge witness: build `template` with/without `plugin-share`, diff the merged
   manifest.

## Explicitly OUT of Phase 1

Signing config + release build (AAB/APK) + wuffle-app = **Phase 2**. The `pack-instrument` script +
stranger-facing README + the yearly-ratchet doc = **Phase 3**. Publishing `core` as a Maven
artifact / template repo = later (in-repo modules for now).

## What landed differently (2026-10-06)

- **`PluginHost.startForResult(intent, handler)` instead of `onActivityResult(code, handler)`.**
  The host allocates the request code itself (an atomic counter from 1000) and routes the result
  to the one-shot handler, so no plugin ever sees or chooses a code. Core's own §7c file chooser
  goes through the same door.
- **`requestPermission(perm, onResult)` is callback-shaped, not `suspend`.** No plugin needs
  coroutines yet and `core`'s dependency surface stays webkit + core + activity. It refuses
  immediately when the merged manifest doesn't declare the permission — the plugin-merge gate —
  and the §7c geolocation / getUserMedia prompts now ride it instead of their own request codes.
- **`lead-acid.js` ships as a `core` asset.** A Copy task feeds the root `lead-acid.js` (still the
  single source of truth the monorepo vendors) into a generated assets dir, so every instrument
  gets `/assets/lead-acid.js` through the asset merge. The copy that lived in `app/src/main/assets`
  is gone.
- **Posture lives in `core`'s manifest.** `allowBackup="false"` + `enableOnBackInvokedCallback`
  are declared once there and inherited; an instrument's manifest holds only its label, theme and
  Activity.
- **`builtinFiles()` is a function, not a property** — it touches `filesDir`, which a property
  initializer would reach before the Context is attached. `artifactName()` likewise.
- **Factory naming settled on the terse top-level object** (`Sensor`, `Share`, `Attest`), which in
  `plugin-sensor` shadows `android.hardware.Sensor` inside its own file — imported there as
  `HwSensor`.
- The dev harness's activity is `DevActivity` (namespace `org.gentropic.leadacid.app`, applicationId
  unchanged so existing installs update in place); the template's is `InstrumentActivity`.

## Genuinely open questions

- **Activity Result API vs request codes.** We use `startActivityForResult` + request codes today
  (share, chooser). The modern `registerForActivityResult` is cleaner but must be called before
  `STARTED`, which fights lazy plugin creation. Phase 1 keeps request-codes via
  `host.onActivityResult`; revisit if it gets ugly.
- **Config location.** Instrument config (applicationId/label/icon/plugins/posture) as a Gradle
  block (simplest, Phase 1) vs a `.gcuapp` file `core` reads (portable, drives a future packager).
  Start Gradle; graduate to a file iff Phase 3's packager wants one.
- **Factory naming convention.** `val Sensor = PluginFactory { … }` (top-level object per module)
  vs `SensorPlugin.Factory`. Leaning top-level object for the terse `listOf(Sensor, Share, Attest)`.
