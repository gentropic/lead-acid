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
permission. See `docs/signing.md`. Next: **Phase 3** — the pack script + stranger-facing docs.

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

## Layout

| module | kind | what |
|---|---|---|
| `core` | library | the jig: `LeadAcidActivity` (subclass it), the bridge, the WebMessagePort lanes, the §7 shell obligations, the always-present `fs` + `shell` plugins, and `lead-acid.js` served as `/assets/lead-acid.js`. Its manifest requests **nothing**. |
| `plugin-sensor` | library | streaming sensors over the push lane (no permission) |
| `plugin-share` | library | the system share sheet — owns the FileProvider + `file_paths.xml` |
| `plugin-attest` | library | hardware-backed signing in the AndroidKeyStore (StrongBox where available) |
| `app` | application | the **dev / kitchen-sink harness**: core + every plugin, hosts any artifact (`--es artifact micro.html` on debug builds), carries the bench page |
| `template` | application | the **copy-me instrument**: core + one plugin + a placeholder artifact — the five-minute starting point |
| `wuffle` | application | the geological compass as an instrument — core only; its `artifact.html` syncs from the monorepo's built `wuffle.html` (`gradle.properties` → `leadacid.artifactsDir`) |

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
