// lead-acid.js — the optional shell module (SPEC §4.5).
//
// One vanilla-JS file an artifact may inline or ignore. Feature-detected:
// the SAME artifact runs on desktop (shell.present === false, native features
// simply absent) and inside lead-acid. No build step, no dependency.
//
// The fast-path story (SPEC §4.1, measured): reads through the fs plugin are
// mmap-over-fd — ~11.5× per read, ~30× on scattered small reads — vs the
// browser's Blob.slice on a SAF document. So for NON-SEALED artifacts (Sealed
// ones can't fetch at all under connect-src 'none'), /native/fs is the default
// read path: mount `shell.fsBackend(Backend)` into @gcu/vfs and every
// readRange rides the mmap path with zero artifact changes.

export const shell = (() => {
  const present = typeof __leadacid !== 'undefined';

  // The body sidecar (SPEC §4.3): the shell posts one WebMessagePort to the
  // page as `__leadacid_port`. We post [id(12 ascii) | body] as an ArrayBuffer,
  // then fetch with the id in a header; the shell joins them into req.body.
  // portReady is a PROMISE so a body-carrying call before the port arrives just
  // awaits it — no race regardless of when the module loads vs onPageFinished.
  let resolvePort;
  const portReady = present ? new Promise((r) => { resolvePort = r; }) : Promise.resolve(null);
  // Push streams (SPEC §4.2): the shell posts {s:id, e:event, d:data} over the
  // SAME port (shell→page); no interceptor, no buffering, no padding. Routed by
  // stream id to per-stream handlers.
  const pushStreams = new Map();   // id → { handlers: Map<event,Set>, onclose }
  if (present) {
    window.addEventListener('message', (e) => {
      if (e.data === '__leadacid_port' && e.ports && e.ports[0]) {
        const port = e.ports[0];
        port.onmessage = (ev) => {
          let m; try { m = typeof ev.data === 'string' ? JSON.parse(ev.data) : ev.data; } catch { return; }
          if (!m || !m.s) return;
          const st = pushStreams.get(m.s); if (!st) return;
          if (m.close) { pushStreams.delete(m.s); st.onclose && st.onclose(); return; }
          const set = st.handlers.get(m.e); if (set) for (const cb of set) cb(m.d);
          const any = st.handlers.get('*'); if (any) for (const cb of any) cb(m.e, m.d);
        };
        resolvePort(port);
      }
    });
  }
  let bodySeq = 0;
  function newBodyId() {
    // 12 ascii chars, unique per call
    const s = (Date.now().toString(36) + (bodySeq++).toString(36) + '00000000000').slice(0, 12);
    return s;
  }
  function sendBody(port, id, bytes) {
    const u8 = bytes instanceof Uint8Array ? bytes
      : bytes instanceof ArrayBuffer ? new Uint8Array(bytes)
        : new TextEncoder().encode(typeof bytes === 'string' ? bytes : JSON.stringify(bytes));
    const buf = new Uint8Array(12 + u8.length);
    for (let i = 0; i < 12; i++) buf[i] = id.charCodeAt(i);
    buf.set(u8, 12);
    port.postMessage(buf.buffer, [buf.buffer]);
  }

  // request/reply. opts.body rides the port sidecar — hidden here so callers
  // pass {body} as if fetch carried it. Bodyless calls are plain fetch.
  async function native(path, opts) {
    if (opts && opts.body != null) {
      const port = await portReady;
      if (port) {
        const id = newBodyId();
        const { body, headers, ...rest } = opts;
        // Post the body FIRST so the shell has it (or is about to) when the
        // tagged fetch lands; the shell's awaitBody() tolerates either order.
        sendBody(port, id, body);
        return fetch('/native/' + path, {
          ...rest,
          headers: { ...(headers || {}), 'X-LeadAcid-Body-Id': id },
        });
      }
    }
    return fetch('/native/' + path, opts);
  }

  // Open a push stream over the port (SPEC §4.2). Returns { on(event, cb),
  // onClose(cb), close() }. The shell pushes events with no buffering — unlike
  // SSE through the interceptor, which batches ~2 KiB (V-1), so no EventSource.
  async function stream(path, opts) {
    if (!present) throw new Error('no shell — streams are a native feature');
    await portReady;
    const sep = path.includes('?') ? '&' : '?';
    const res = await native(path + sep + 'transport=port', opts);
    if (!res.ok) throw new Error('stream open failed: ' + res.status);
    const id = (await res.json()).stream;
    const handlers = new Map();
    const st = { handlers, onclose: null };
    pushStreams.set(id, st);
    const api = {
      on(event, cb) { let s = handlers.get(event); if (!s) handlers.set(event, s = new Set()); s.add(cb); return api; },
      onClose(cb) { st.onclose = cb; return api; },
      close() { if (pushStreams.delete(id)) native('shell/closestream?id=' + encodeURIComponent(id)); },
    };
    return api;
  }

  async function version() {
    if (!present) return null;
    try { return (await (await native('shell/info')).json()).version; }
    catch { return __leadacid.version(); }
  }

  const keepAwake = (on = true) =>
    native('shell/keepawake?on=' + (on ? 'true' : 'false'), { method: 'POST' });

  // Publish a finished output into a public collection (Downloads/Pictures/
  // Documents) — survives uninstall, visible to other apps. Body via §4.3 port.
  async function publish(name, bytes, { collection = 'Downloads', mime = 'application/octet-stream' } = {}) {
    const q = `?name=${encodeURIComponent(name)}&collection=${encodeURIComponent(collection)}&mime=${encodeURIComponent(mime)}`;
    const r = await native('fs/publish' + q, { method: 'POST', body: bytes });
    if (!r.ok) throw new Error('publish failed: ' + r.status);
    return r.json();   // { uri, name, bytes }
  }

  // Hand a file (or text) to the system share sheet. The chooser is the user's
  // confirmation — it's not a silent send.
  async function share(name, bytes, { mime = 'application/octet-stream', text } = {}) {
    let q = `?name=${encodeURIComponent(name)}&mime=${encodeURIComponent(mime)}`;
    if (text) q += `&text=${encodeURIComponent(text)}`;
    return (await native('share' + q, { method: 'POST', body: bytes })).ok;
  }
  async function shareText(text) {
    return (await native('share?mime=text/plain&text=' + encodeURIComponent(text), { method: 'POST' })).ok;
  }

  // Hardware-backed signing (StrongBox/TEE). `sign` returns a raw P-256 sig that
  // verifies with WebCrypto ECDSA/P-256/SHA-256 directly.
  const attest = {
    async sign(bytes) {
      const r = await native('attest/sign', { method: 'POST', body: bytes });
      if (!r.ok) throw new Error('attest failed: ' + r.status);
      return r.json();   // { alg, sig, pub, hash, security }
    },
    async keyinfo() { return (await native('attest/keyinfo')).json(); },
  };

  // Registered fs tokens (SAF picks + built-ins): [{token, size}]
  async function files() {
    try { return await (await native('fs/list')).json(); }
    catch { return []; }
  }

  // A duck-typed SOURCE over one fs token — the shape lamina's cursor and
  // micro's providers consume directly (readRange(off,len) → Uint8Array).
  function fileSource(token, size) {
    const url = 'fs/' + encodeURIComponent(token);
    return {
      token, size,
      rangeReadable: true,
      async readRange(offset, length) {
        const r = await native(url, { headers: { Range: `bytes=${offset}-${offset + length - 1}` } });
        return new Uint8Array(await r.arrayBuffer());
      },
      async arrayBuffer() { return (await native(url)).arrayBuffer(); },
    };
  }

  // A @gcu/vfs Backend over /native/fs, path === token. Pass the artifact's own
  // Backend base class (lead-acid ships no vfs) — returns a subclass whose
  // readRange rides the mmap fast path and whose rangeReadable is true, so
  // @gcu/vfs consumers seek instead of full-reading. THE non-Sealed default.
  function fsBackend(Backend) {
    return class LeadAcidFsBackend extends Backend {
      async _size(token) {
        const list = await files();
        return (list.find(f => f.token === token) || {}).size ?? 0;
      }
      async stat(p) {
        const token = p.replace(/^\/+/, '');
        return { type: 'file', size: await this._size(token), _binary: true };
      }
      async readRange(p, offset, length) {
        return fileSource(p.replace(/^\/+/, '')).readRange(offset, length);
      }
      async readFile(p) {
        const token = p.replace(/^\/+/, '');
        return new Uint8Array(await (await native('fs/' + encodeURIComponent(token))).arrayBuffer());
      }
      get rangeReadable() { return true; }   // ← the fast-path flag consumers check
      get readonly() { return true; }         // fs plugin serves reads; writes = fs/publish
    };
  }


  // Fused orientation off the sensor plugin's rotation vector (SPEC §5.1) —
  // the survey-grade path, as opposed to deviceorientationabsolute which
  // WebView derives from the same sensor but hides the accuracy. Readings
  // arrive in the W3C deviceorientation convention so an artifact feeds them
  // to whatever already consumed the event (bearing's compass.*). `accuracy`
  // is Android's SensorManager.SENSOR_STATUS_*: 3 high · 2 medium · 1 low ·
  // 0 unreliable (figure-8 to recalibrate) · -1 no contact.
  async function orientation({ rateHz = 30, source = 'rotation' } = {}) {
    const s = await stream(`sensor/stream?types=${encodeURIComponent(source)}&rateHz=${rateHz}`);
    const readers = new Set(), accs = new Set();
    let accuracy = null;
    s.on(source, (d) => {
      const o = orientationFromRotationVector(d.v);
      if (!o) return;
      if (d.acc !== accuracy) { accuracy = d.acc; for (const cb of accs) cb(accuracy); }
      o.absolute = source !== 'game_rotation';
      o.accuracy = accuracy;
      o.t = d.t;
      for (const cb of readers) cb(o);
    });
    s.on('accuracy', (d) => { accuracy = d.acc; for (const cb of accs) cb(accuracy); });
    const api = {
      on(cb) { readers.add(cb); return api; },
      onAccuracy(cb) { accs.add(cb); return api; },
      onClose(cb) { s.onClose(cb); return api; },
      close() { s.close(); },
    };
    return api;
  }

  return { present, native, stream, version, keepAwake, publish, share, shareText, attest, files, fileSource, fsBackend, orientation, orientationFromRotationVector };
})();

/**
 * Android rotation vector (x, y, z[, w] = axis·sin θ/2, cos θ/2) → the W3C
 * deviceorientation triple { alpha, beta, gamma } in degrees, with the spec's
 * ranges: alpha [0, 360), beta [-180, 180), gamma [-90, 90). Both describe the
 * same thing — the rotation taking device axes (x right, y top, z out of the
 * screen) to the Earth frame (x East, y North, z Up) — so this is the quaternion
 * → matrix → Z-X'-Y'' Tait-Bryan decomposition the spec defines, with the
 * face-down half handled by the sign of R22 (= cos β cos γ, and |γ| < 90°).
 * Pure; exported for tests. Returns null for a degenerate vector.
 */
export function orientationFromRotationVector(v) {
  if (!v || v.length < 3) return null;
  let [x, y, z] = v;
  let w = v.length > 3 ? v[3] : Math.sqrt(Math.max(0, 1 - x * x - y * y - z * z));
  const n = Math.hypot(x, y, z, w);
  if (!(n > 0)) return null;
  x /= n; y /= n; z /= n; w /= n;
  // SensorManager.getRotationMatrixFromVector — device → world (ENU), row-major.
  const r01 = 2 * x * y - 2 * z * w;
  const r11 = 1 - 2 * x * x - 2 * z * z;
  const r20 = 2 * x * z - 2 * y * w;
  const r21 = 2 * y * z + 2 * x * w;
  const r22 = 1 - 2 * x * x - 2 * y * y;
  const D = 180 / Math.PI;
  let alpha, beta, gamma;
  if (r22 >= 0) {            // screen up: cos β ≥ 0
    alpha = Math.atan2(-r01, r11);
    beta = Math.asin(Math.max(-1, Math.min(1, r21)));
    gamma = Math.atan2(-r20, r22);
  } else {                   // screen down: cos β < 0 → fold β past ±90°
    alpha = Math.atan2(r01, -r11);
    beta = Math.PI - Math.asin(Math.max(-1, Math.min(1, r21)));
    gamma = Math.atan2(r20, -r22);
  }
  alpha *= D; beta *= D; gamma *= D;
  alpha = ((alpha % 360) + 360) % 360;
  if (beta >= 180) beta -= 360;
  if (beta < -180) beta += 360;
  if (gamma >= 90) gamma -= 180;      // keep the spec's half-open [-90, 90)
  if (gamma < -90) gamma += 180;
  return { alpha, beta, gamma };
}
