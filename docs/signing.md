# Signing & release builds (M2 Phase 2)

**The keystore never enters the repo** (SPEC §6). Every application module reads an optional
`keystore.properties` at the repo root (gitignored, with `*.jks` / `*.keystore`) and, when it is
present, signs `release` with it. When it is absent, `assembleRelease` still succeeds and
produces an **unsigned** APK (`…-release-unsigned.apk`) that installs nowhere — by design:
a release build without a key is not a mistake you can ship.

```
# keystore.properties — four lines, paths relative to the repo root
storeFile=release.jks
storePassword=…
keyAlias=wuffle
keyPassword=…
```

## Making a key

A software keystore is the "I want backups" holder in the studio note's spectrum
(`docs/lead-acid-studio.md`) — fine for a start, back the file up offline:

```
keytool -genkeypair -v -keystore release.jks -alias wuffle \
        -keyalg EC -groupname secp256r1 -validity 36500
```

EC P-256 is what Android's APK Signature Scheme v3 accepts and what a YubiKey PIV or a phone's
StrongBox can also hold later — so moving the key to hardware (the studio's `yksign` /
`attest`-pointed-at-an-APK path) is a key *rotation*, not a re-architecture. Android enforces
**update continuity**: an update must be signed with the same key or a v3-rotation successor, so
pre-provision a successor lineage before you depend on a non-exportable key (studio note,
"Key rotation & recovery").

## Building

```
gradlew.bat :wuffle:assembleDebug      # dev install
gradlew.bat :wuffle:assembleRelease    # → wuffle/build/outputs/apk/release/wuffle-release.apk (signed iff keystore.properties)
gradlew.bat :wuffle:bundleRelease      # → wuffle/build/outputs/bundle/release/wuffle-release.aab (Play)
```

`:template:` builds the same way. The dev harness (`:app:`) has no release signing on purpose.

Verified 2026-10-06 with a throwaway EC P-256 key (generated outside the repo, deleted after):
`apksigner verify` → **v3** scheme, one signer, EC/256 (v2 reports false: with minSdk 29 ≥ 28
AGP signs v3 only, since every supported device verifies v3 — v2 would be dead weight). v3 is
enabled explicitly in the signing config because AGP leaves it off by default, and v3 is the one
that carries a rotation lineage. **Windows gotcha:** `storeFile` must be a path Java understands
(`C:/…` or relative to the repo root), not a Git-Bash `/c/…` path — the latter fails the build
with a bare "BUILD FAILED" before the signing task even names the file.

## Where the key sits relative to distribution

- **Sideload / your own devices:** your key, your fingerprint, nothing else. (Google's developer
  verification, when it applies, registers the *public fingerprint* — never the key.)
- **Play:** the organization developer account (the Ltd has its D-U-N-S number as of
  2026-10-06) accepts an `.aab` — but Play App Signing then holds the *final* signing key and
  yours becomes an upload key. That is the audience fork the studio note names; both branches
  can coexist per instrument.
