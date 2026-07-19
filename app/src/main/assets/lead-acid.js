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

  // request/reply. opts.body rides the WebMessagePort sidecar (SPEC §4.3) —
  // hidden here so callers pass {body} as if fetch carried it. (Sidecar wiring
  // lands with the first body-carrying plugin; GET/query/header calls work now.)
  const native = (path, opts) => fetch('/native/' + path, opts);
  const stream = (path) => new EventSource('/native/' + path);

  async function version() {
    if (!present) return null;
    try { return (await (await native('shell/info')).json()).version; }
    catch { return __leadacid.version(); }
  }

  const keepAwake = (on = true) =>
    native('shell/keepawake?on=' + (on ? 'true' : 'false'), { method: 'POST' });

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

  return { present, native, stream, version, keepAwake, files, fileSource, fsBackend };
})();
