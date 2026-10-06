# lead-acid

**An Android shell for single-file web artifacts.**

> Heavy. Ancient. Completely understood. Starts your truck every morning.

lead-acid carries one GCU single-file HTML artifact into an Android APK and negotiates its
access to the parts of the phone Android keeps behind the counter: radios, raw sensors, the
filesystem, the NPU. It adds no capability to the artifact — the WebView is full Chromium.
The shell is a jig: a `WebViewAssetLoader` serving the artifact from a virtual origin, plus a
`/native/**` interceptor dispatching to permission-gated Kotlin plugins over plain
`fetch`/`EventSource`. The load-bearing trick is ranged reads over `mmap` — what lets a phone
stream a multi-gigabyte local file a browser tab can't hold.

- **`SPEC.md`** — the design of record (architecture, bridge, plugin catalog, postures).
- **`PLAN.md`** — the execution plan (Spike 0 → M0 trench coat → M1 wuffle → template/bench).
- **`WUFFLE.md`** — the first instrument: a geological compass with a live Schmidt net.

The artifacts themselves live in the [auditable monorepo](https://github.com/endarthur/auditable)
(micro, lamina, wuffle, …); this repo consumes their built HTML files.

## Status

**Spike 0 PASSED** (2026-07-19, S24+ / Android 16 / WebView 150) — ranged `fetch()` through
`shouldInterceptRequest` round-trips all range forms correctly off a `MappedByteBuffer`,
65 MB/s on sequential 256 KiB reads. Key finding: the WebView loader applies the Range to the
plugin's stream itself (see SPEC §4.1 and PLAN.md).

**M0 + M1 DONE** (2026-07-19/21, all on the S24+): micro's single file renders a real 324k-block
model off the SAF picker; the `fs` plugin serves ranged reads off windowed mmaps (~30× `Blob.slice`
on scattered reads) and publishes to MediaStore; all three bridge lanes (fetch spine, port body
sidecar, port push) are proven; plugins `sensor` / `share` / `attest` work; wuffle, the compass,
graduated to the monorepo; `bench.js` runs the same shim on the desktop with no APK.

**M2 Phase 1 DONE** (2026-10-06): lead-acid is a **jig**, not one app. `core` is an Android
library (the activity base, the bridge, the port lanes, `fs` + `shell`, and `lead-acid.js` as a
merged asset); each capability is its own `plugin-*` library module owning its permissions and
providers; an instrument is a `LeadAcidActivity` subclass with a list of plugin factories. The
merged-manifest witness holds: a template without `plugin-share` has no FileProvider, with it it
does.

**M2 Phase 2 DONE** (2026-10-06): release signing from a gitignored `keystore.properties`
(unsigned output without one, v2+v3 schemes with one — verified with apksigner), `bundleRelease`
AABs, and **wuffle as an instrument** (`wuffle/`): its artifact syncs from the monorepo build at
build time, it declares no plugin modules (core covers it), and its merged manifest carries no
permission. See `docs/signing.md`.

**M2 Phase 3 DONE** (2026-10-06): `pack-instrument.mjs` stamps a named instrument out of the
template in one command, and this README is the stranger's recipe. **M2 is complete.** Still
owed on-device: the bench battery re-run after the module split (no phone was attached).

## Building

Requires the Android SDK (`local.properties` → `sdk.dir`, or `ANDROID_HOME`) and a JDK 17+
(Android Studio's bundled JBR works).

```
gradlew.bat assembleDebug      # every module; the apps land at
                               #   app/build/outputs/apk/debug/app-debug.apk            (dev harness)
                               #   template/build/outputs/apk/debug/template-debug.apk
                               #   wuffle/build/outputs/apk/debug/wuffle-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
gradlew.bat :wuffle:assembleRelease :wuffle:bundleRelease   # signed iff keystore.properties — docs/signing.md
```

## Make an instrument (the five-minute recipe)

You have a single-file HTML tool. You want it on a phone with the parts of Android a browser
tab can't reach. Three commands:

```
# 1. stamp a module out of the template — id, label, the plugins it needs, the artifact
node pack-instrument.mjs compass --id org.example.compass --label "Compass" \
     --plugins sensor,share --artifact path/to/compass.html

# 2. build it (debug for your own phone; release + bundle per docs/signing.md)
gradlew.bat :compass:assembleDebug

# 3. install it
adb install -r compass/build/outputs/apk/debug/compass-debug.apk
```

What the stamp does: copies `template/`, sets the namespace + `applicationId`, the label, writes
the plugin list into BOTH places that must agree (the Gradle dependencies and the activity's
factory list), drops your artifact at `src/main/assets/artifact.html`, and adds the module to
`settings.gradle.kts`. Re-running it on the same name regenerates those files in place and keeps
your artifact unless you pass `--artifact` again. Available plugins: `sensor`, `share`,
`attest`; none is also fine. Permissions follow the plugins — the stamped manifest requests
nothing itself.

Your artifact doesn't need to know about the shell. If it wants native features, it imports
`./lead-acid.js` (served next to it by core) and feature-detects `shell.present`; the same file
keeps working in a desktop browser with those features simply absent, and `bench.js` mocks the
native side for desktop development (SPEC §4.7).

To swap the icon, replace `src/main/res/drawable/ic_launcher_foreground.xml` (an adaptive-icon
foreground: 108 dp canvas, keep content inside the central 72 dp) and the background colour in
`res/values/colors.xml`.

**The yearly ratchet.** Google requires `targetSdk` to track the current platform within about a
year of each release. It lives in ONE place — `gradle/libs.versions.toml`, `targetSdk` (and
`compileSdk` alongside) — so every instrument built from this repo moves together with a
one-line change. Rebuild, re-run the bench on a device of that version, ship.

## Layout

| module | kind | what |
|---|---|---|
| `core` | library | the jig: `LeadAcidActivity` (subclass it), the bridge, the WebMessagePort lanes, the §7 shell obligations, the always-present `fs` + `shell` plugins, and `lead-acid.js` served as `/assets/lead-acid.js`. Its manifest requests **nothing**. |
| `plugin-sensor` | library | streaming sensors over the push lane (no permission) |
| `plugin-share` | library | the system share sheet — owns the FileProvider + `file_paths.xml` |
| `plugin-attest` | library | hardware-backed signing in the AndroidKeyStore (StrongBox where available) |
| `app` | application | the **dev / kitchen-sink harness**: core + every plugin, hosts any artifact (`--es artifact micro.html` on debug builds), carries the bench page |
| `template` | application | the **copy-me instrument**: core + one plugin + a placeholder artifact — the five-minute starting point |
| `wuffle` | application | the geological compass as an instrument — core + `plugin-sensor` (v2 reads the fused rotation vector + compass accuracy); its `artifact.html` syncs from the monorepo's built `wuffle.html` (`gradle.properties` → `leadacid.artifactsDir`) |

An instrument is the whole wiring:

```kotlin
class WuffleActivity : LeadAcidActivity() {
    override val plugins = listOf(Sensor, Share, Attest)
}
```

Each factory comes from a plugin module the instrument's `build.gradle.kts` depends on — the
dependency is what carries that plugin's permissions into the merged manifest, so an app cannot
ship a permission whose plugin it didn't include (SPEC §5).

---

*Geoscientific Chaos Union — CC0.*
