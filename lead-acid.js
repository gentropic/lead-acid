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

  return { present, native, stream, version, keepAwake, publish, share, shareText, attest, files, fileSource, fsBackend };
})();
