# GNSS plugin — `gnss`

**Design spec for raw GNSS (SPEC §5.1, committed).** Specced while the design was fresh because
raw GNSS is genuinely fiddly. **BUILT 2026-10-07 (`plugin-gnss`): the four push streams —
`location` / `status` / `raw` / `nmea` — plus `permission`, exactly as sketched below; the
stream-open blocks the interceptor thread on the host's permission request (the system dialog
answers it; a refusal → 403 `{canRequest}`); handler-based `LocationManager` callbacks on a
HandlerThread (the executor overloads need API 30/31, minSdk is 29). Verified on the S24+: a real
GPS fix reaches wuffle v3, which stamps each measurement with lat/lon/accuracy. The WMM
declination module is `@gcu/wmm` in the monorepo (wuffle reads true north). **The LOGGER is BUILT
too (same day): `GnssLogService`, lead-acid's first foreground service (type `location`; the
typed FGS permission + POST_NOTIFICATIONS ride in this module's manifest with the `<service>`),
`log/start?what=raw,location,nmea,status&name=` (409 while one runs) / `log/stop` / `log/status`;
one JSON line per event into an app-private `<name>.jsonl`, the notification states the real
state ("GNSS logging — 64 epochs · ±0.6 m", with a Stop action), `stop` seals the file and serves
it as a durable fs token for publish/share, `status` is how a reloaded page re-attaches. Measured
on the S24+: 15 s with the screen OFF → epochs 65 → 82 (≈1 Hz raw, no duty-cycling gap on this
device), fixes 53 → 70; the sealed file had 82 raw epochs with real per-SV measurements.** *Drivers: the raw-GNSS logger instrument (**`gnsslog`, built 2026-10-07** — `tools/gnsslog` in the monorepo, `gnsslog/` here: sky plot + C/N0 from the status stream, start/stop/re-attach/publish/share of a capture; on the S24+ 24 satellites across four constellations, a 12 s screen-off capture sealed at 111 KB and published); wuffle v3 (station
coordinates + the path to declination).*

Ordinary web geolocation (`navigator.geolocation`) already works in the shell (the §7c
`onGeolocationPermissionsShowPrompt` wiring). The **plugin's** reason to exist is the stuff the web
API can't give: **raw `GnssMeasurement` observations** (pseudorange, carrier phase / ADR, per-SV
state), satellite status (C/N0, constellations), and raw NMEA — the inputs to post-processing,
PPK, and quality assessment.

## Platform, not Play Services

Use the platform **`LocationManager`** GNSS APIs, **not** `FusedLocationProviderClient` (that's a
Google Play Services dependency — GCU avoids GMS coupling so the app works on GMS-less devices):

- `registerGnssMeasurementsCallback` → raw `GnssMeasurementsEvent` (API 24+).
- `registerGnssStatusCallback` → `GnssStatus` (satellites, C/N0, used-in-fix).
- `addNmeaListener` → raw NMEA sentences.
- `requestLocationUpdates(GPS_PROVIDER, …)` → raw location fixes.

## Permissions — the first dangerous one

`ACCESS_FINE_LOCATION` (runtime, in the `gnss` module manifest) — raw measurements require FINE.
This is the first plugin needing a *dangerous* runtime permission (fs/sensor/share/attest needed
none); it exercises the request flow already wired in the Activity. Request at stream-open; a
denial → `403` with `canRequest`. `ACCESS_BACKGROUND_LOCATION` only if a background logger must run
with the app fully closed (avoid — the foreground-service logger below keeps it foreground-ish).

## Routes (sketch)

```
GET  /native/gnss/status                    → push stream {satellites:[{svid,constellation,cn0,used}], fixSats}
GET  /native/gnss/location?minMs=           → push stream {lat,lon,alt,acc,speed,bearing,t} fixes
GET  /native/gnss/raw                        → push stream raw {clock, measurements:[{svid,state,pseudorangeRate,adr,cn0,…}]}
GET  /native/gnss/nmea                       → push stream of raw NMEA sentence strings
POST /native/gnss/log/start {what:[raw,nmea,location], name}  → foreground-service logger → fs file
POST /native/gnss/log/stop
```

**All the data rides the port push lane** (§4.2) — no body sidecar (gnss is read-mostly; log config
is small query/JSON). Control (log start/stop) = request/reply.

## The logger = first foreground-service plugin  *(built — see the status above)*

`log/start` runs a **foreground service** so raw logging survives the screen going off (the SPEC's
foreground-service-plugin plumbing, first real user). The service holds the GNSS callbacks, appends
to a file, shows a truthful notification ("GNSS logging — 4,312 epochs, fix: 3D"), and survives the
WebView reattaching (the artifact reconnects to the ongoing log, §5). Stop flushes + the file is
`fs/publish`-able.

## Subtleties / verification items

- **Duty cycling.** Android power-saves by duty-cycling GNSS; continuous raw measurements need the
  right request (full-tracking / disable duty cycling where the device allows). Verify on the S24+.
- **Raw measurement decoding is the artifact's job.** Emit the raw fields (state flags, ADR, clock
  bias) faithfully; PPK/PPP math lives in the artifact (or a vendored geodesy lib), not the plugin.
- **Declination path (the wuffle tie-in).** Position → **WMM** (World Magnetic Model) evaluation →
  magnetic declination → wuffle corrects magnetic→true north. WMM is a small pure computation over a
  bundled coefficient set — its own tiny module (vendor or implement), fed by `gnss/location`. This
  is how wuffle graduates from "magnetic north" to true north offline.
- **wuffle v3 coordinates:** wuffle opens `gnss/location`, stamps each measurement with lat/lon +
  accuracy → every reading is georeferenced.

## Lanes used

Control = request/reply; status/location/raw/nmea = port push streams; captured log file =
`fs`/`fs.publish`. No new bridge machinery — it's all lanes we've built.
