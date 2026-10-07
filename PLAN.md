# lead-acid — build plan & first app

**A distillation of `SPEC.md` into an execution plan, with two revisions from the 2026-07
review.**

> The spec is the design of record. This is the *how we actually build it, and what rides
> first* companion. Where the two disagree, the two revisions below win; everything else
> defers to the spec.

Status: **ACTIVE** — Spike 0 in progress (2026-07-19).

---

## 0. The two revisions

1. **Sealed is orthogonal to the shell** (§2 below). The DRAFT rests the bridge's trust
   model on the artifact being Sealed (spec §8: *"the bridge trusts its caller because
   there is only one… safe because the only JS that runs is the bundled artifact"*). That
   over-couples. The shell should be a neutral capability jig that is safe *by
   construction*, hosting Sealed **and** network-opt-in artifacts alike. Sealed becomes a
   per-artifact **posture**, the default, not an architectural precondition.

2. **The first app is a compass, not aupro** (§3 below). aupro is the *reason* lead-acid
   earns its keep, but it's a big first instrument (packer + `.aupro` format + expectations
   query + a field UI). The shakedown cruise should be a small, domain-fit, single-plugin
   app that proves the whole pipeline end to end in a weekend: **wuffle**, a geological
   compass/clinometer. reflex is the wrong first app — it automates the *phone*
   (accessibility, device-admin, the "radioactive tier"), conceptually heavy and orthogonal
   to proving the shell.

---

## 1. Thesis (one paragraph)

lead-acid carries one GCU single-file HTML artifact into an Android APK and negotiates its
access to the parts of the phone Android keeps behind the counter. It adds no capability to
the artifact — the WebView is full Chromium; everything the artifact does on desktop it
already does here. The shell is a jig: a `WebViewAssetLoader` serving the artifact from a
virtual origin, plus a `/native/**` interceptor dispatching to permission-gated Kotlin
plugins over plain `fetch`/`EventSource`. The one load-bearing trick is **ranged reads over
`mmap`** (`Range` header → `206` off a `MappedByteBuffer`), which is what lets a phone stream
a multi-gigabyte local file a browser can't hold. Kotlin core stays a few hundred auditable
lines; each native concern is one small plugin that lands with its instrument.

## 2. Revision 1 — Sealed is orthogonal

**Already orthogonal (leave as-is):** the network posture. Spec §1.7/§8 already make INTERNET
opt-in per artifact — the default manifest requests nothing. Good.

**The coupling to break:** §8's reasoning that the *bridge* is safe *because* the artifact is
Sealed. Re-derive the bridge's safety from two properties that hold regardless of posture:

- **Navigation is locked to the virtual origin, always.** Any nav off `appassets` is
  cancelled; external links go to the system browser via intent. No third-party JS is ever
  loaded into the WebView — so "there is only one caller" holds by *navigation lock*, not by
  Sealed-ness. This is the real guarantee, and it survives a networked artifact that
  `fetch()`es *data* (never scripts).
- **Plugins are the security boundary.** Each plugin gates its own access (token-scoped `fs`,
  permission-merged manifest, `403`/`503`/`500` conventions). The bridge grants no blanket
  trust; it routes.

**Sealed becomes a manifest-declared mode**, enforced by the shell as a template:

| Posture | INTERNET perm | Data-safety form | Who |
|---|---|---|---|
| `sealed` (default) | absent | all "No" | micro, lamina, wuffle, aupro |
| `networked` (opt-in) | present + documented | states the egress honestly | the auditable notebook (`install`/`load`), a tile-fetching map |

The shell's job is to expose the posture honestly (permissions match registered plugins;
the data-safety answers follow the manifest), **not** to require Sealed. Concrete payoff:
lead-acid can then host the *whole* shelf — the ascetic tools **and** the notebook — instead
of only the puritan half. The `attest` chain and the no-network governance pitch stay exactly
as strong for the artifacts that choose `sealed`; they just stop being a precondition for the
shell to be sound.

*One rule of the DRAFT's §8 stays verbatim regardless of posture:* file access settings off,
all file service through the deliberate `fs` routes; the one-method JS interface
(`__leadacid.version()`) frozen.

## 3. Revision 2 — the first app is wuffle

**wuffle** — a geological compass/clinometer. Point the phone at a plane (bedding, joint,
foliation) → strike / dip / dip-direction; sight a lineation → trend / plunge. Log each
measurement with timestamp, location, and a note; export to a stereonet, CSV, or the shared
package format. (Name: the **Wulff net**, softened — the pun lands where the app exports,
since a wuffle throws its planes and lines straight onto a stereonet. *clino* was the obvious
descriptive name but it's already a geo-compass on the stores. Department has final say, per
house style.)

**Why it's the correct shakedown cruise:**

- **Domain-fit.** It's the user's actual job — a structural-geology field instrument.
- **Smallest possible plugin surface.** v0 needs *no new native plugin*: device orientation
  comes free from the WebView's `deviceorientation`/`deviceorientationabsolute` events
  (subject to the §7c `onPermissionRequest` + sensor-permission wiring the shell owns
  anyway). The only native touch is **`fs`-write** (append the measurement log to a real
  file, read it back) — which exercises the M0 `FilePlugin` in its *write* direction, the
  natural complement to micro's read direction.
- **Sealed-trivially.** No network; posture `sealed`; data-safety all "No". A textbook
  demonstration of Revision 1's default posture.
- **The lead-acid value is the whole point of it.** A compass in a mobile browser mostly
  works — but a *field* compass wants: a launcher icon, offline-in-a-canyon, `keepAwake`, and
  a persistent local log you keep (not a download). Those are exactly what the shell adds
  over the bare web app. wuffle justifies lead-acid without needing an exotic plugin.
- **It grows into the plugin model as its first real customer.** v2 wants a native
  **`sensor`** plugin (raw uncalibrated magnetometer, high-rate rotation vector, magnetic
  declination from a bundled WMM coefficient set) for survey-grade accuracy — which is
  precisely spec §5.1's `sensor` milestone, now motivated by a shipping instrument instead of
  a test harness. v3 wants **`camera`** (a photo of the outcrop stapled to the measurement)
  and **`gnss`**/geolocation (accurate station coordinates). Each lands when wuffle asks.

**Recombination inventory (new code ≈ the orientation UI + the log):**

- `@gcu/bearing` (vendored orientation math — dip/dip-dir/strike/trend/plunge, already in the
  monorepo) does the geology.
- `@gcu/stereonet` + sadpan render the export (log → stereonet plot; a real workflow, not a
  toy).
- `@gcu/yaml` for the log/export document; Switchboard for the UI.
- The measurement-log format wants to be a small readable document that aupro's `.aupro`
  packer can later ingest — write it in the shared spirit, don't over-design it yet.

## 4. Build plan

Recast around **what Claude writes in a session** vs **what needs your gradle+device loop** —
because the code is cheap and the verification is the tax. The whole thesis hangs on one
experiment; do it first.

### Spike 0 — the one experiment that validates or kills the thesis

*(~an afternoon on your machine; I drive the Kotlin.)* A bare gradle project:
`WebViewAssetLoader` serving a one-page artifact + a minimal `FilePlugin` that parses a
`Range` header and returns `206 Partial Content` off a `MappedByteBuffer` slice. **Acceptance:
a JS `fetch(url, { headers: { Range: 'bytes=0-1023' } })` through the interceptor comes back
`206` with the correct 1024 bytes on a real S24+.** If it works, lead-acid is real and
everything below is downstream plumbing. If the WebView collapses the range (buffers whole /
strips `206`), we learn it here and fall to the documented soft-joint (spec §4.3
`WebMessagePort`) before writing anything else. This is spec Q-2 + the ranged-interceptor risk,
isolated to a day.

**SPIKE 0 RESULT (2026-07-19, S24+ / Android 16 / WebView 150): PASSED — the thesis holds.**
All range forms round-trip correctly (full 200, explicit/suffix/open-ended 206 with correct
bytes and Content-Range, 416 past-EOF), 62 MB/s over 32 sequential 256 KiB ranged reads
(latency-bound; concurrent reads pipeline). Findings that bind the M0 design:

1. **The loader applies the Range itself.** `AndroidStreamReaderURLLoader` (the machinery
   behind intercepted responses) parses the request's `Range` header and skips `start` bytes
   off the app's InputStream (resolving suffixes via `available()`), but never truncates the
   end and passes the app's status/headers through untouched. **Contract: serve the stream
   from position 0 capped at end+1; never pre-skip; implement O(1) `skip()`/`available()`.**
   Pre-sliced streams fail with net errors when the loader's skip runs off the end (this is
   why "some ranges failed" on the first run — every nonzero-start range died).
2. **416 needs a phantom stream** — the loader skips the requested start regardless of
   status, so an empty 416 body errors; serve `PhantomSkipStream(start+1)` (O(1) skip,
   instant EOF) so the loader lands at EOF and delivers the 416 cleanly.
3. **Request bodies never cross `shouldInterceptRequest`** (crbug 155250) — POST arrives
   with method + headers only. Body-carrying calls ride SPEC §4.3's WebMessagePort
   (ArrayBuffer-native, androidx.webkit ≥ 1.9), hidden behind `shell.native()`; "no base64,
   ever" survives. Custom request headers DO cross (verified).
4. **Environment:** WebGL2 true, **WebGPU true** (Q-1 answered on WebView 150), Content-Length
   on intercepted responses is ignored by the renderer (set it truthfully anyway).

**SAF RANDOM-ACCESS PROBE (2026-07-19, S24+): O(1) — resolves the /native/fs-vs-Blob fork.**
`Blob.slice(off, off+64KiB)` on a SAF `ACTION_OPEN_DOCUMENT` File is flat in the offset
(47–80 ms at 0/128/256/384/511 MiB into a 512 MiB file; far read fastest; bytes correct
everywhere). So **micro/lamina open big files off phone storage UNMODIFIED and Sealed — no
`/native/fs` needed for correctness.** BUT: each SAF slice costs ~50–80 ms (content-provider
round-trip latency), and scattered small reads managed only **1.2 MB/s** — vs the mmap ranged
path's **65 MB/s** (Spike 0). So `/native/fs` is a **~50× latency optimization**, not a
capability. Rule: few-hundred-MB → SAF (artifact untouched); multi-GB latency-sensitive
streaming (micro's LOD-read pattern) → wire `/native/fs`. The fs plugin thus graduates from
"maybe needed for M0" to "the perf path, wired when a model is big enough to feel it."

**mmap-over-SAF-fd MEASURED (2026-07-19, S24+): 11.5× per read, ~30× scattered — /native/fs
is worth building.** Head-to-head on the SAME 512 MiB SAF document: `Blob.slice` ~52 ms/64KiB
(0.6 MB/s scattered) vs the fs plugin's mmap-over-fd ~4.5 ms/64KiB (17.9 MB/s scattered). The
scattered figure is micro's real LOD-read pattern → ~30× there. Caveat: SAF-fd mmap (18 MB/s
scattered) is itself ~3.5× slower than internal-storage mmap (65 MB/s, Spike 0) — the
content-provider fd has its own overhead — but still crushes Blob.slice. **So the fs plugin's
SERVING half is now built and validated** (`registerSaf` mmaps the SAF fd at pick time; the
`list` route; ranged serving). What remains for real micro use is the INTEGRATION, still
reasonably deferred until a model is big enough to feel 52 ms/read: (1) the CSP tension —
micro Sealed `connect-src 'none'` blocks the fetch, so micro needs the feature-detected
`shell.native()`/`@gcu/vfs`-adapter branch (SPEC §4.5), or a posture decision; (2) windowed
maps past the 2 GiB MappedByteBuffer cap; (3) token lifecycle (release on page generation).

### M0 — the trench coat *(I write the Kotlin; you build+run)*

`LeadAcidActivity` + asset loader + interceptor + `PluginRegistry` + the ranged `FilePlugin`
(mmap, token-scoped). Navigation lock + file-access-off from §8. **Acceptance (spec's, kept):
micro's existing single file, unmodified, renders the 2M-block example at the 250k budget on an
S24+, reading the cloud via ranged fetch from internal storage.** micro is the shell's
acceptance test — nothing to build but the shell.

### M1 — wuffle, the first original artifact *(replaces the spec's "sensor plugin" milestone)*

Ship a real app. Needs: the shell + `fs`-write + the §7c WebChromeClient wiring for
`deviceorientation` (permission prompt) + `shell.keepAwake` (`FLAG_KEEP_SCREEN_ON`) + the §7b
back/inset contracts + `viewport-fit=cover`. The artifact (`tools/wuffle` in the monorepo → a
Sealed `wuffle.html`) is `@gcu/bearing` + a log + a stereonet export. **Acceptance: measure a
plane against a known orientation, log it, reopen the app offline, export a stereonet.** This
dogfoods the entire pipeline — shell, `fs`-write, posture, back/insets, the five-minute
packaging recipe — behind something the user would actually carry.

### M2 — template, bench & docs *(mostly bounded work I can produce)*

> **Phase 1 (module split + plugin registration) BUILT 2026-10-06** — see
> `docs/m2-phase1-modules.md`. **Phase 2 (signing + release + the wuffle app) BUILT the same
> day** — `docs/signing.md`, `wuffle/`. **Phase 3 (pack-instrument.mjs + the README recipe +
> the ratchet note) BUILT the same day.** M2 is complete bar the on-device bench re-run.

The five-minute recipe for a stranger (`template/` app project, one-commit
`targetSdkVersion` catalog entry for the yearly ratchet). `lead-acid.js` (feature-detect +
`native`/`stream`/`onBack`/`keepAwake`) and `bench.js` (desktop `/native/**` fetch shim with
fixtures, so instruments develop with zero APK rebuilds). Error conventions §4.6, back/inset
§7b, edge-to-edge §7b implemented. The one real risk in this band is **SSE flush through the
interceptor (spec V-1)** — not needed by wuffle v0 (no streaming), so it can wait for the first
streaming instrument.

### M3+ — the shelf grows, each plugin with its instrument

- **`sensor`** (raw mag + rotation-vector + declination) → wuffle v2 precision. *(First real
  streaming plugin; carries the V-1 verification.)*
- **`camera`** + **geolocation**/`gnss` → wuffle v3 (outcrop photo + station coords) ✓ 2026-10-07
  (camera level 1, gnss, the FGS logger, the gnsslog instrument). **Camera level 2 — NEXT when
  picked up: the COMPOSITE SPIKE first.** One-file experiment in the dev harness: a CameraX
  `PreviewView` BEHIND a transparent WebView, the artifact's capture button floating over the
  live preview, touch routed; verified by screenshot + tap on the S24+. If it holds, the rest of
  `docs/camera-plugin.md` (routes, manual exposure, RAW→DNG, multi-lens, big captures via the
  response body) is known work; if not, the design needs a different preview answer before any
  of that is worth writing. Parked 2026-10-07 by priority (no driving instrument yet), not blocked.
- **file associations** (§6 VIEW intent-filter) → tapping a cloud opens micro.
- **`intake`/`share`/`attest`/`ble`/`spp`** → land with **aupro** (the packer + `.aupro` +
  the rig instrument), the milestone all of this was really for.
- **workers** (`WorkerHost`, spec M4) → when a background-digest instrument (weir-shaped)
  wants JS with the screen off.

## 5. The repo & the artifact split

- **`gentropic/lead-acid`** — its own sibling repo, downstream of the monorepo, matching the
  `../micro` / `../lamina` / `../numen` pattern. The toolchain boundary is the reason: Kotlin
  / Gradle / Android Studio vs. the monorepo's pure-JS "no build step on the web side"
  constraint. This doc seeds its `SPEC.md`; the DRAFT's plugin catalog (§5.1) becomes its
  roadmap.
- **The artifacts stay in the monorepo.** `tools/wuffle` → a Sealed `wuffle.html` built like
  micro/lamina; lead-acid *consumes* the built HTML (drops it in `template/app/src/main/
  assets/`). Same "shell consumes build outputs" relationship micro/lamina have with the
  Pages repos. wuffle needs no lead-acid to develop — it runs against `bench.js` on the
  desktop and only meets the shell to become an APK.
- **Two homes, one file.** Via App Links (§6), `gentropic.org/wuffle` opens the installed app
  or the live single-file site — one URL, two interchangeable referents.

## 6. Open questions

Inherited from the spec, still live: Q-1 WebGPU-in-WebView (WebGL2 is the floor; check at
Spike 0), Q-2 SAF-vs-app-private + mmap-over-SAF (partly answered by Spike 0), Q-4 `minSdk`
(wherever androidx.webkit's needed features begin, no nostalgia).

New to this plan:

- **wuffle's declination data.** Bundle a World Magnetic Model coefficient set (small, offline)
  so true-vs-magnetic north is correct without a network call — or ship magnetic-north-only
  v0 and add declination with the `sensor` plugin. Instinct: magnetic-only v0, WMM at v2.
- **The measurement-log format.** A small readable `@gcu/yaml` document now, written so
  aupro's packer can ingest it later — but don't design the `.aupro` package format yet
  (that's aupro's spec, and it "will outlive both tools; write it first" — *there*, not here).
- **wuffle calibration UX.** The figure-8 magnetometer dance is a real field ritual; decide
  whether v0 surfaces calibration state (needs the raw sensor → v2) or trusts the WebView's
  fused orientation (v0).

---

*Geoscientific Chaos Union — CC0. Heavy, ancient, completely understood; starts your truck
every morning.*
