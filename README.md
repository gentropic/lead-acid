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

The artifacts themselves live in the [auditable monorepo](https://github.com/endarthur/auditable)
(micro, lamina, wuffle, …); this repo consumes their built HTML files.

## Status

**Spike 0 PASSED** (2026-07-19, S24+ / Android 16 / WebView 150) — ranged `fetch()` through
`shouldInterceptRequest` round-trips all range forms correctly off a `MappedByteBuffer`,
65 MB/s on sequential 256 KiB reads. Key finding: the WebView loader applies the Range to the
plugin's stream itself (see SPEC §4.1 and PLAN.md). Next: **M0**, the trench coat — micro's
single file, unmodified, rendering a block model off ranged fetch from internal storage.

## Building

Requires the Android SDK (`local.properties` → `sdk.dir`, or `ANDROID_HOME`) and a JDK 17+
(Android Studio's bundled JBR works).

```
gradlew.bat assembleDebug      # → app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

*Geoscientific Chaos Union — CC0.*
