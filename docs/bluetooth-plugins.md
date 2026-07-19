# Bluetooth plugins — `ble` and `hid`

**Design spec for the two Bluetooth capabilities (SPEC §5.1b futures).** Not built yet; this
captures the shape so implementation is mechanical when an instrument pulls them.

They share a stack (permissions, session lifecycle, pairing) but sit at opposite ends of it:

- **`ble`** — the phone as BLE **central / GATT client**: it reaches *out* to peripherals
  (a Pico, a field sensor). WebView has no Web Bluetooth, so this is the only path an artifact
  has to BLE hardware. *Driver: aupro's rig, any Pico instrument.*
- **`hid`** — the phone as an input-device **peripheral**: it presents *itself* as a Bluetooth
  keyboard / mouse / gamepad to a host. *Driver: RelayKVM, the grip.*

Both are **impossible in a browser** — that's the whole reason they justify the shell.

## Shared foundation

**Permissions (build-enforced per plugin module, §5).** Android 12+ split Bluetooth perms:

| need | permission | notes |
|---|---|---|
| scan (`ble`) | `BLUETOOTH_SCAN` + `usesPermissionFlags="neverForLocation"` | the flag avoids dragging in location perm |
| connect (`ble`, `hid` classic) | `BLUETOOTH_CONNECT` | runtime, since 12 |
| advertise (`hid` BLE/HOGP) | `BLUETOOTH_ADVERTISE` | only for the BLE-peripheral path |

Each plugin is its own Gradle module carrying only *its* Bluetooth permissions, so an app
without `ble` can't request `BLUETOOTH_SCAN` — the manifest merger makes it structural.

**Companion Device Manager (preferred for known hardware).** For a specific device — the grip,
a named Pico — `CompanionDeviceManager` association replaces scan-everything with a scoped system
dialog and grants background-presence privileges (wake when the companion appears, observe it
without broad scan/location perms). One shell-level `companion` affordance serves all hardware
plugins; use it instead of raw scanning wherever the device is known.

**Session lifecycle (§5 contract).** A GATT connection or an HID registration is a *session*
owned by the page generation. A reload releases it (auto-disconnect / unregister) unless it's
backed by a foreground service (a long-running logger). Claim conflicts → `409`. This is exactly
the SessionRegistry the SPEC specced; `ble` and `hid` are its first real users.

**Bridge lanes used (all three — a good completeness check).**
- *Control* (scan / connect / register / read / write) → **request/reply** (fetch).
- *Byte payloads* (characteristic writes, HID reports) → **body sidecar** (§4.3 port).
- *Async events* (scan results, notifications, connection state) → **push streams** (§4.2 port).

## `ble` — BLE central / GATT client

Model: **scan → connect → discover → read/write/subscribe.** Notifications (the point of most
sensor peripherals) ride the push lane.

Routes (sketch):

```
GET  /native/ble/scan?services=<uuid,…>&timeout=   → push stream of {address,name,rssi,services}
POST /native/ble/connect  {address}                → {session, mtu} (GATT connect + bond if needed)
GET  /native/ble/{session}/services                → discovered {service:{char:[uuid,props]}}
GET  /native/ble/{session}/char/{uuid}             → read (value as base64/hex)
POST /native/ble/{session}/char/{uuid}             → write (bytes via body sidecar)
GET  /native/ble/{session}/notify?chars=<uuid,…>   → push stream of {char, value} notifications
POST /native/ble/{session}/close
```

- **Binary is first-class:** characteristic values are bytes — writes ride the body sidecar,
  reads/notifications carry `ArrayBuffer` over the port (no base64 needed on the port path).
- **`lead-acid.js`** wraps it: `const dev = await shell.ble.connect(addr); dev.on(uuid, v => …);
  dev.write(uuid, bytes);` — the artifact never touches GATT ceremony.
- The Pico story: a Nordic-UART-style peripheral streaming data → `notify` push stream straight
  into the artifact.

## `hid` — the phone as an input device

**Two paths; start with classic.**

1. **Classic Bluetooth HID — `BluetoothHidDevice` (API 28+).** The clean, officially-supported
   path. Register an SDP record with a **HID report descriptor** (the byte-level spec of what the
   device *is* — keyboard, mouse, gamepad, consumer-control), then send input reports. The phone
   appears to a paired host as a Bluetooth keyboard/mouse. This is what "phone as Bluetooth
   keyboard" apps use, and it's the v1 target.
2. **BLE HID / HOGP — `BluetoothLeAdvertiser` + `BluetoothGattServer` (API 21+, chipset-permitting).**
   Advertise the HID-over-GATT service and implement the report-map characteristics. Genuinely
   possible but fiddlier and host-picky. Deferred behind the classic path; a per-device
   verification item. *(Note: `BluetoothHidDevice` is classic HID, not BLE — the catalog's
   "BLE HID via BluetoothHidDevice" is imprecise.)*

Routes (sketch, classic path):

```
POST /native/hid/register  {profile:'keyboard'|'mouse'|'gamepad'|'consumer'|{descriptor}}
                                                   → {session} (phone now presents as HID)
GET  /native/hid/state                             → push stream of {host, connected} changes
POST /native/hid/report    {reportId, data}        → send an input report (bytes via body sidecar)
POST /native/hid/unregister
```

- **Report descriptors:** ship canned ones (standard keyboard / mouse / consumer-control) plus a
  custom-descriptor escape hatch for composite devices (the grip's HID+CDC).
- **`lead-acid.js` gives ergonomic helpers** so the artifact never hand-crafts report bytes:
  `const kb = await shell.hid.keyboard(); kb.type('hello'); kb.press('Enter');`
  `const m = await shell.hid.mouse(); m.move(dx,dy); m.click('left'); m.scroll(dy);`
  The helper maps intent → the modifier/keycode or button/dxdy report bytes.
- **RelayKVM / relay vision:** phone as a software-defined input device driven by a web UI, or a
  bridge — receive commands over BLE (a NUS GATT server, i.e. `ble` central-or-peripheral in) and
  emit HID out to a host. Dual-role (BLE central + HID peripheral at once) is chipset-dependent →
  a verification item.

## Open questions / verification items

- **`BluetoothHidDevice` reliability across devices** — the API is sound but vendor stacks vary;
  smoke it on the S24+ first, note host-pairing UX (the host must pair with the phone).
- **Dual-role** (BLE central + HID peripheral simultaneously) — chipset-dependent; verify before
  designing a relay that needs both.
- **Pairing/bonding UX** — bonding is a system flow the plugin triggers and the user confirms in
  system UI; the plugin never handles the passkey.
- **Classic vs BLE for `hid`** — default classic (`BluetoothHidDevice`); only build HOGP if a host
  requires BLE specifically.
- **CompanionDeviceManager** — wire it for the known-hardware case (grip/Pico) rather than raw
  scanning, for the scoped-permission + background-presence win.

## Sequencing

Neither is next up (`share` + `attest` land first — small, wuffle-driven). When a hardware
instrument pulls Bluetooth: **`ble` first** (central is simpler, more broadly useful, and the
session/notify machinery it builds is reused by `hid`), then **`hid`** on top.
