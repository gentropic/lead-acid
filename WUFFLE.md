# wuffle

**A geological compass — lead-acid's first original instrument.** *(graduated)*

wuffle is a structural-geology field compass: lay the phone on a plane (bedding,
joint, foliation) → **dip direction / dip**; sight a lineation → **trend / plunge**,
live off device orientation, plotted on a Schmidt net and published to Downloads.
It was the artifact that proved lead-acid earns its keep — the one that exists
*because* of the shell, dogfooding `keepAwake`, `fs/publish`, and `share`.

**wuffle now lives in the auditable monorepo**, not here — it graduated from a
lead-acid spike asset to a first-class single-file GCU tool:

- **Source + canonical docs:** `tools/wuffle/` in
  [auditable](https://github.com/endarthur/auditable) (`tools/wuffle/README.md`).
- **Build:** `node build.js --target=wuffle` → `wuffle.html` (bundles the canonical
  `@gcu/bearing` + the vendored `@gcu/leadacid` shim). Guarded by
  `npm run test:wuffle`.
- **Packaged here:** the built `wuffle.html` is copied into
  `app/src/main/assets/wuffle.html` (gitignored — a build input, like a future
  `micro.html`) for the APK.

It's a **two-homes** tool: the same file runs on the desktop
(gentropic.org/wuffle — stereonet + manual entry for analysis) and in this shell
(live compass). App Links make `gentropic.org/wuffle` open either the installed
app or the live site.

See `tools/wuffle/README.md` in the monorepo for the full design, build, and
roadmap.
