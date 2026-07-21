# lead-acid studio — build a signed instrument APK in the browser

**Design note for a browser tool that turns a base APK + an artifact into a signed, installable
instrument — no Gradle, no Android Studio, and with the signing key on hardware the user holds.**
Downstream of M2 (which produces the base APKs). Records the argument so the eventual spec doesn't
rediscover it.

## The idea

An APK is a ZIP with a signature block. So a new instrument doesn't need a *compile* — it needs a
pre-built shell with the artifact swapped in and a fresh signature. The studio does exactly that,
entirely client-side:

1. **Pick a base APK.** The M2 Gradle pipeline pre-builds a small catalog of bases, **one per
   plugin-set** — so the merged manifest already carries exactly the right permissions and the
   studio never has to add or strip any. You choose the base whose capabilities match your artifact.
2. **Swap the artifact.** `fflate` replaces `assets/artifact.html` (+ `lead-acid.js`, config) in the
   ZIP. Trivial — it's a ZIP entry.
3. **Zip-align + re-sign** (APK Signature Scheme v2/v3): SHA-256 digests over the content, signed
   with the user's key. Signing is a standard RSA/ECDSA op — doable in-browser, but see §"the key
   lives on hardware" for why the signature comes from a YubiKey via a plugin.
4. **Out comes a signed, installable APK.** No toolchain.

**The identity frontier.** Swapping the artifact is easy; changing the app's *identity* is the
fiddly part. Label and icon are resource-table edits (moderate). The **package-name rename** is
baked into the binary manifest (AXML) *and* `resources.arsc` — real surgery. So **v1 studio: an
instrument inherits its base's identity**; distinct package names are a v2 with proper AXML/arsc
patching. The core value — artifact swap + sign — needs none of that.

## Studio-as-instrument (the recursion)

The studio is itself a **lead-acid instrument**: a Switchboard artifact that does the ZIP/digest
work in JS (`fflate` + WebCrypto), plus a native **`yksign` plugin** for the signature. We build
lead-acid *with* lead-acid. (A pure *browser* studio could do the ZIP part, but driving a hardware
key from a browser means reimplementing the smartcard/CCID protocol over WebUSB — painful; the
app path is clean.)

## The key lives on hardware — GCU holds nothing

Signing is a **`yksign` plugin** (`attest`'s external-hardware cousin; sits by `nfc`/`usb`):

- The user generates an RSA-2048 or EC P-256 key **on a YubiKey** (PIV applet) — the private key
  never leaves the device. (Any PIV smartcard works; YubiKey is the reference.)
- The studio computes the APK digest and hands it to `yksign`, which drives the YubiKey via
  **YubiKit for Android** over **NFC tap or USB-C**. PIV does raw signing over the digest — exactly
  what APK v2/v3 needs (Android accepts ECDSA). Touch/PIN gates each signature.
- **Zero key custody.** GCU only ever sends a digest and receives a signature. Not the key, not a
  copy, not escrow. The strongest possible "we don't hold the user's key."

**Right door: PIV, not WebAuthn.** WebAuthn signs a fixed auth structure (challenge + authenticator
data), not an arbitrary digest — wrong shape for code signing. PIV is the raw-signing door.

## Google developer verification — fingerprint, not custody

The Sept-2026 verification requirement is **compatible with hardware-sovereign signing**, because
it registers a *public fingerprint*, not the key:

- **Register** your package name + your signing key's **SHA-256 fingerprint** under a verified
  developer identity. Google records the *public fingerprint* — never the private key. Every future
  build signed by that YubiKey then passes automatically.
- **Two account tiers:** the full account is a one-time **$25 + gov ID** (for GCU or a real dev);
  the **free "limited distribution" account** (no ID, no fee) covers **up to 20 devices** for
  personal/testing use — perfect for the sovereign-hobbyist signing their own instruments for their
  own phone(s). (Brazil is first wave, 2026-09-30.)
- **The custody grab is a *different* mechanism.** **Play App Signing** (Play distribution) is where
  Google holds the final key — so lead-acid **sideloads** and never touches it. Verification wanted
  *accountability* (a verified identity behind each key); the fingerprint model gives that *without*
  demanding custody. Verification is the ally, Play App Signing is the enemy — and they're separate.

**The user owns the Google relationship; the studio is the wrench.** The studio can't register *for*
the user (that needs *their* identity/account). It generates the key, produces the fingerprint,
guides them through developer-verification or the free 20-device account, and manages the signing.
The user is a self-sovereign developer; GCU isn't even a party to their identity.

## Key rotation & recovery — provision it, don't scramble

Android enforces **update continuity**: an update must be signed with the same key (or a v3-rotation
successor), or it's refused (uninstall+reinstall, data loss). A non-exportable hardware key is
maximally secure but creates a **recovery hazard**: lose the YubiKey → can't update existing installs.

- **v3 key rotation** lets you move keys via a *proof-of-rotation lineage* (the old key signs a
  statement authorizing the new key). But it needs the **old key present** to build the lineage — so
  it's for *planned* rotation, not lost-key *recovery*.
- **The fix: pre-provision a successor.** While holding primary key A, generate a **second YubiKey**
  (key B) and pre-create the **A→B lineage**, stored offline. Lose A → sign future updates with **B +
  the pre-made lineage** (Android accepts), and re-register B's fingerprint via your Google *account*
  (account ≠ key possession, so this works even with A gone). Recovery becomes a thing you set up,
  not a crisis. The studio walks the user through it at onboarding.
- Alternative for those who accept less purity: generate the key in software, back it up encrypted
  offline, *import* it to the YubiKey. Recoverable, but the key touched software once.

## Two-homes context

For every capability that runs in a **browser** — most of wuffle, since `deviceorientation` works in
mobile Chrome — **no APK is needed at all**: it's just the hosted HTML (gentropic.org/…), zero
signing, zero Google. The studio is only for the **native-capability tier** (BLE/HID/USB/raw-GNSS/
StrongBox/background). So the studio serves the subset that genuinely needs to be an app; everything
else distributes as a plain web page.

## Open questions

- **Package-rename AXML/arsc surgery** for distinct instrument identities (v2 studio).
- **v3-rotation × verification-registration interaction** — the exact re-registration flow on a
  rotation is still settling as verification rolls out (2026); verify before relying on it.
- **`yksign` scope** — PIV first (YubiKit); OpenPGP applet as an alternative door; browser-WebUSB
  path only if a device-free studio is ever wanted.
- **Base-APK catalog shape** — how many plugin-set bases the pipeline ships, and how the studio
  presents "pick your capabilities" as "pick a base."

---

*Geoscientific Chaos Union — CC0. Your key, your hardware, your name on it — our wrench.*
