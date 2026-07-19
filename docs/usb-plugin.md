# USB plugin — `usb`

**Design spec for USB Host / OTG serial (SPEC §5.1b futures).** Not built; specced so it's
mechanical when an instrument pulls it. Companion to `docs/bluetooth-plugins.md` — the *wired*
counterpart to `ble`/`spp`.

The phone as a USB **host**: plug a device into the USB‑C/OTG port and talk to it. WebView has no
WebUSB (desktop Chrome does; WebView doesn't), so this is another **browser‑impossible** capability
the shell unlocks. *Driver: a USB‑serial instrument (a Pico/Arduino in CDC mode, an RTK receiver,
a data logger) when one shows up — the wired sibling of the aupro/Pico story.*

## The serial-driver decision

Android exposes raw USB (`UsbManager`, `UsbDeviceConnection`, bulk/control transfers) but **no
kernel serial node** (`/dev/ttyUSB*`) without root — the app implements the serial protocol in
userspace. Two ways:

1. **Vendor `usb-serial-for-android` (recommended).** v3.11.0 (2026‑07), **MIT**, pure Java, zero
   runtime deps, actively maintained; implements FTDI / CP210x / CH340‑341 / PL2303 / CDC‑ACM.
   §1.5 says "no third‑party runtime deps beyond AndroidX" — so **vendor the source into the `usb`
   module** (like bearing.js / acorn / cm6): in‑tree, auditable, no build‑time external repo, and
   isolated to this one plugin module (an app without `usb` never sees it). This respects §1.5's
   spirit while not reinventing four chipset drivers.
2. **CDC‑ACM direct (the zero‑dep floor).** Standard USB‑serial (what a Pico/Arduino presents) is a
   documented USB class — ~200 lines to drive directly, no library. If we only ever needed CDC‑ACM
   we'd do this; the vendored library is the pragmatic choice because field adapters are commonly
   FTDI/CP210x/CH340, which each have quirks.

Start by vendoring the library; the CDC‑ACM‑direct path stays the fallback if we ever want to shed
even that.

## Permissions & attach flow — stays §8‑clean

- Manifest (usb module only): `<uses-feature android:name="android.hardware.usb.host" />`. **No
  dangerous runtime permission** — USB access is granted **per device** by a system dialog.
- Two ways in: (a) on‑demand — enumerate, then `UsbManager.requestPermission(device)` pops the
  system grant dialog; (b) **attach intent‑filter** (`device_filter.xml` matching vendor/product
  ids) so plugging a known device in notifies/launches the app — the USB analog of Companion Device
  Manager. The intent‑filter path is nicest for a dedicated instrument (plug the rig in → the app
  is ready).
- Detach is a system broadcast (`ACTION_USB_DEVICE_DETACHED`) → close the session (below).

## Routes (sketch)

```
GET  /native/usb/list                          → attached devices [{id, vid, pid, name, driver}]
POST /native/usb/open  {id, baud, dataBits,     → grants (system dialog if needed), claims the
                        stopBits, parity}          interface, sets serial params → {session}
POST /native/usb/{session}/write               → bytes out (body sidecar)
GET  /native/usb/{session}/read                → push stream of incoming bytes (port stream)
POST /native/usb/{session}/control {dtr, rts}  → DTR/RTS lines
POST /native/usb/{session}/close
```

**Model:** attach → permission → open (claim + configure) → **read as a port push stream, write via
the body sidecar.** Same three bridge lanes as every other I/O plugin: control = request/reply,
outbound bytes = body sidecar, inbound bytes = push stream (ArrayBuffers over the port — no base64).

**`lead-acid.js`** wraps it so the artifact sees a stream, not USB ceremony:

```js
const port = await shell.usb.open(dev.id, { baud: 115200 });
port.on('data', bytes => ingest(bytes));   // Uint8Array
port.write(cmd);                            // Uint8Array
```

## Session lifecycle (§5 contract)

A claimed USB device is a session owned by the page generation. It closes on: explicit
`close`, **device detach** (the broadcast), page reload, or `onPause` (background stance) — unless
a foreground service holds it (a long‑running logger). Reader loop runs on a background thread
(bulk `read()` blocks); its bytes push over the port. Claim conflict (device already open) → `409`.

## Relation to the other device plugins

`usb` is the wired sibling of `ble`/`spp`. A Pico can present as **USB CDC serial *or* USB HID**, so
`usb` pairs with the RelayKVM/grip vision (talk to a Pico over the cable). An RTK receiver may offer
USB serial as well as Bluetooth SPP → `usb` and `spp` both feed the same NMEA→mock‑location flow.
Build whichever transport the instrument in front of you actually uses.

## Open questions

- **Which chipsets to ship** — vendor all of usb‑serial‑for‑android, or trim to CDC‑ACM + FTDI +
  CP210x + CH340 (the ones seen in the field)?
- **OTG/host detection** — feature‑check `usb.host`; some phones need an externally‑powered hub for
  power‑hungry devices.
- **Permission persistence** — the per‑device grant can be remembered ("always open for this app");
  wire the intent‑filter for the remembered‑device case.
- **Vendoring mechanics** — pull the library's `src/` into `ext/`‑style vendoring with a
  DO‑NOT‑EDIT header + a `sync` note pointing at the upstream tag (the bearing.js pattern).
