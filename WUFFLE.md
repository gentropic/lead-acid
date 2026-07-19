# wuffle

**A geological compass — the first original lead-acid instrument.**

> A wuffle throws its planes and lines straight onto a stereonet. The name puns
> on the *Wulff net*, but orientation data belongs on an **equal-area (Schmidt)**
> net — so that's the default.

wuffle is a structural-geology field compass/clinometer: lay the phone on a
plane (bedding, joint, foliation) and read **dip direction / dip**; sight a
lineation and read **trend / plunge**. Each measurement lands live on a Schmidt
net and appends to a log you can publish to the phone's Downloads as CSV.

It is the M1 instrument of [lead-acid](./SPEC.md) — the first artifact that
exists *because* of the shell rather than a port of an existing web tool. It
dogfoods the whole stack in one screen: device orientation (§7c), `fs/publish`
+ the WebMessagePort body sidecar, `keepAwake`, insets, and back.

Status: **spike** — validated on a Galaxy S24+ (physical tilt confirmed:
flat → dip 0°, vertical → dip 90°, dip direction tracks compass). Lives today as
a lead-acid asset (`app/src/main/assets/wuffle.html` + a vendored `bearing.js`);
it graduates to a proper `tools/wuffle` monorepo build (see Roadmap).

## What it does

- **Two measurement modes** — *plane* (bedding/joint → dip direction/dip) and
  *line* (lineation → trend/plunge), a compact segmented toggle in the header.
- **Live Schmidt net** — the current reading previews as a dashed great circle
  (plane) or point (line) that tracks the phone in real time; measured planes
  are solid orange great circles, lineations are blue points.
- **Measure** — a floating action button freezes the current reading into the
  log (with a haptic tick).
- **Publish** — writes `wuffle-log.csv` to Downloads via `fs/publish` (survives
  uninstall, opens in any app). Columns: `type, dip_dir_or_trend,
  dip_or_plunge, strike, timestamp`.
- **keepAwake** — the screen stays lit while measuring (field ergonomics).
- **Magnetic north** — no declination correction yet (a v2 item; see Roadmap).

## How it works

**Orientation → geology.** `deviceorientationabsolute` (compass-referenced
alpha; `deviceorientation` fallback) feeds `@gcu/bearing`'s
`compass.planeFromDeviceOrientation(alpha, beta, gamma) → [dipDir, dip]` and
`lineFromDeviceOrientation(…) → [trend, plunge]`. bearing already had these — the
phone-tilt-to-attitude math is the library's, not wuffle's.

**The stereonet is `@gcu/bearing`'s `Stereonet`** (`projection: 'equal-area'`).
Planes plot via `sn.plane(dipDir, dip)`, lines via `sn.line(trend, plunge)`.

**Smoothness — the load-bearing UI decision.** bearing's `render()` re-projects
the *entire* graticule plus every measured plane (180 points each) on every
call. Driving that off every orientation event was choppy. The fix separates two
layers:

- **Static layer** — the graticule + all measured planes/lines. `sn.render()`
  runs *only* when the measurement set changes (measure / clear).
- **Live layer** — a dedicated overlay `<path>` + dot appended to the SVG,
  updated per frame via `requestAnimationFrame`: one `setAttribute('d', …)`, no
  `sn.render()`. The trace is computed with bearing's own projection
  (`conversions.planeToDcos` + `curves.greatCircle` + `sn._projectCurve`) so the
  ghost lands exactly where a measured plane would. The overlay survives
  `render()` (bearing updates specific elements, never wipes the SVG; it's
  re-appended after a static redraw to stay on top).

Result: the expensive net redraw happens only on measure/clear; moving the phone
updates a single path at 60 fps.

**Net-centric layout.** A circle inscribed in a square leaves the corners empty,
so the net *is* the interface: the reading sits in its top-left dead corner, the
projection caption top-right, the mode toggle in the header, and measure floats
as a screen-corner FAB (thumb-reachable, never over the net's data quadrant; the
log scrolls under it).

## Build & run (spike)

wuffle is currently a lead-acid asset. It runs inside lead-acid because the
`appassets` origin is a secure context, which `deviceorientation` requires.

```
# from the lead-acid repo, with a device on adb:
gradlew.bat assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n org.gentropic.leadacid.spike/org.gentropic.leadacid.LeadAcidActivity --es artifact wuffle.html
```

`bearing.js` in the assets is a vendored copy of `@gcu/bearing`'s built ESM
(source of truth: `gentropic/bearing.js`). A debug hook `window.__wuffle.plot(d1,
d2, mode)` plots without a physical tilt (for on-device testing over CDP).

## Roadmap

- **Graduate to `tools/wuffle`** — a real monorepo build (bundled bearing,
  Switchboard styling, a `.gcupkg`), developed against `bench.js` on desktop,
  packaged into lead-acid for the field. This is where wuffle stops being a
  spike asset.
- **WMM declination** — bundle a World Magnetic Model coefficient set so
  true-vs-magnetic north is correct offline (`planeFromDeviceOrientation` already
  takes `{ declination }`). Magnetic-only is the v0 stance.
- **Stereonet export / share** — render the net to an image and hand it to the
  system share sheet (`share`), so a session's plot travels, not just the CSV.
- **Reload a log** (`intake`) — import a previous `wuffle-log.csv` and re-plot
  it, so a net accumulates across sessions.
- **Fabric analysis** — bearing has contouring + statistics; density contours
  and mean-vector/eigen stats over a session are a natural next tier.
- **Calibration UX** — surface magnetometer calibration state (the figure-8
  dance) once a raw-sensor plugin lands.

## Credits

Geology + stereonet engine: **[@gcu/bearing](https://github.com/endarthur/bearing.js)**.
Shell: [lead-acid](./SPEC.md). GCU — CC0.
