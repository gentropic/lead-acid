// bench.js — the desktop dev bench (SPEC §4.7).
//
// A classic <script> an instrument loads in DEV only. When the page URL carries
// `?bench`, it installs a MOCK of the native layer so the artifact runs
// shell-style ON THE DESKTOP — no APK, no device: `shell.present` becomes true
// and `/native/**` is answered with canned fixtures. It mocks BELOW lead-acid.js
// (fetch + the WebMessagePort), so the shim's real code paths (feature detect,
// body sidecar, push streams) are exercised unchanged.
//
// Self-gating and self-contained (no imports); strip it at build (it does
// nothing without `?bench`, but keep the built artifact clean). Override the
// canned data before load via `window.__benchFixtures = { … }`.
(function () {
  'use strict';
  if (!/[?&]bench\b/.test(location.search)) return;

  var fx = Object.assign({
    version: 'bench',
    sensorRateHz: 30,
    files: {},               // token → Uint8Array (fs fixtures)
    fix: { lat: -23.52, lon: -46.19, alt: 760, acc: 5.0 },   // gnss/location fixture
    locationGranted: true,   // flip to false to exercise the 403 path
  }, window.__benchFixtures || {});

  // ── feature detection: the artifact now thinks it's in the shell ──────────
  window.__leadacid = { version: function () { return fx.version; } };

  // ── the mock WebMessagePort (body sidecar page→shell + push shell→page) ────
  var chan = new MessageChannel();
  var shellSide = chan.port1, pageSide = chan.port2;
  var bodyWaiters = new Map();                 // id → resolve(bytes)
  shellSide.onmessage = function (e) {         // page → shell: [id(12) | body]
    var buf = new Uint8Array(e.data);
    if (buf.length < 12) return;
    var id = new TextDecoder().decode(buf.slice(0, 12));
    var w = bodyWaiters.get(id);
    if (w) { bodyWaiters.delete(id); w(buf.slice(12)); }
  };
  shellSide.start && shellSide.start();
  // The page PULLS the port (GET /native/shell/port) once lead-acid.js has its
  // listener — same as the real shell, so delivery can't race module
  // evaluation however the artifact is bundled. Nothing is pushed unasked.
  function deliverPort() {
    window.dispatchEvent(new MessageEvent('message', { data: '__leadacid_port', ports: [pageSide] }));
  }

  // The real shell splices the plugin's JSON into the message as an OBJECT
  // (`"d":$data`), so the page sees m.d parsed — never a string. Match it.
  function push(sid, event, dataJson) { shellSide.postMessage(JSON.stringify({ s: sid, e: event, d: typeof dataJson === 'string' ? JSON.parse(dataJson) : dataJson })); }
  function pushClose(sid) { shellSide.postMessage(JSON.stringify({ s: sid, close: true })); }
  function awaitBody(id) {
    return new Promise(function (res) {
      bodyWaiters.set(id, res);
      setTimeout(function () { if (bodyWaiters.has(id)) { bodyWaiters.delete(id); res(new Uint8Array(0)); } }, 3000);
    });
  }

  var enc = new TextEncoder(), b64 = function (buf) {
    var u = new Uint8Array(buf), s = ''; for (var i = 0; i < u.length; i++) s += String.fromCharCode(u[i]); return btoa(s);
  };
  function jsonResp(obj, status) {
    return new Response(JSON.stringify(obj), { status: status || 200, headers: { 'Content-Type': 'application/json' } });
  }

  // ── a real software signing key (bench attest verifies with WebCrypto) ─────
  var _key = null;
  function benchKey() {
    if (_key) return _key;
    _key = crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, false, ['sign'])
      .then(function (kp) {
        return crypto.subtle.exportKey('spki', kp.publicKey).then(function (spki) {
          return { priv: kp.privateKey, pubB64: b64(spki) };
        });
      });
    return _key;
  }

  // ── mock handlers. (req = {query, headers}, body = Uint8Array|null) ────────
  var streams = new Map(); var streamSeq = 0;
  var published = [], shared = [];                      // what the page handed out (the smokes read these)
  // tree: fx.tree = { name, children: { <name>: {kind:'directory',children} | {kind:'file',bytes,mtime} } }
  var treeRoot = fx.tree || { name: 'bench-folder', children: {} }; treeRoot.kind = 'directory'; treeRoot.children = treeRoot.children || {};
  var treeWriters = {}, tSeq = 0;
  function treeUri() { return 'bench://tree/' + treeRoot.name; }
  function treeRootJson() { return { dir: 'dbench', name: treeRoot.name, uri: treeUri() }; }
  function tNotFound(p) { return jsonResp({ error: 'Not Found', detail: 'no such entry: ' + p }, 404); }
  function tEntry(name, n) { return { name: name, kind: n.kind, size: n.kind === 'file' ? n.bytes.length : 0, mtime: n.mtime || 0 }; }
  function tResolve(path) {
    var segs = path.split('/').filter(Boolean); var node = treeRoot, parent = null, name = '';
    for (var i = 0; i < segs.length; i++) {
      if (node.kind !== 'directory') return { parent: null, node: null, name: segs[i] };
      parent = node; name = segs[i]; node = node.children[name] || null;
      if (!node) return { parent: i === segs.length - 1 ? parent : null, node: null, name: name };
    }
    return { parent: parent, node: node, name: name };
  }
  function tEnsureDir(path) {
    var node = treeRoot;
    path.split('/').filter(Boolean).forEach(function (s) {
      if (!node.children[s]) node.children[s] = { kind: 'directory', children: {} };
      node = node.children[s]; if (node.kind !== 'directory') throw new Error('a file is in the way: ' + s);
    });
    return node;
  }
  var routes = {
    'shell/info': function () { return jsonResp({ present: true, version: fx.version }); },
    'shell/keepawake': function () { return jsonResp({ ok: true }); },
    'shell/port': function () { deliverPort(); return jsonResp({ ok: true }); },
    'shell/closestream': function (req) {
      var id = req.query.id, s = streams.get(id);
      if (s) { s.stop(); streams.delete(id); pushClose(id); }
      return jsonResp({ ok: true });
    },
    'fs/list': function () {
      return jsonResp(Object.keys(fx.files).map(function (t) { return { token: t, size: fx.files[t].length }; }));
    },
    'fs/publish': function (req, body) {
      console.log('[bench] fs/publish', req.query.name, (body ? body.length : 0) + 'B →', req.query.collection);
      published.push({ name: req.query.name, collection: req.query.collection, mime: req.query.mime, bytes: body ? body.slice() : new Uint8Array(0) });
      return jsonResp({ uri: 'bench://' + req.query.collection + '/' + req.query.name, name: req.query.name, bytes: body ? body.length : 0 });
    },
    // ── tree: an in-memory folder the mocked picker hands out (fx.tree) ──
    'tree/info': function () { return jsonResp({ ok: true }); },
    'tree/pick': function () { return fx.treePick === false ? jsonResp({ cancelled: true }) : jsonResp(treeRootJson()); },
    'tree/restore': function (req) { return req.query.uri === treeUri() ? jsonResp(treeRootJson()) : jsonResp({ error: 'Not Found', detail: 'permission lost — pick the folder again' }, 404); },
    'tree/list': function (req) {
      var r = tResolve(req.query.path || ''); if (!r.node) return tNotFound(req.query.path);
      if (r.node.kind !== 'directory') return jsonResp({ error: 'Bad Request', detail: 'not a directory' }, 400);
      return jsonResp(Object.keys(r.node.children).map(function (k) { return tEntry(k, r.node.children[k]); }));
    },
    'tree/stat': function (req) { var r = tResolve(req.query.path || ''); return r.node ? jsonResp(tEntry(r.name || treeRoot.name, r.node)) : tNotFound(req.query.path); },
    'tree/mkdir': function (req) { tEnsureDir(req.query.path || ''); return jsonResp({ ok: true }); },
    'tree/remove': function (req) {
      var r = tResolve(req.query.path || ''); if (!r.node || !r.parent) return tNotFound(req.query.path);
      if (r.node.kind === 'directory' && req.query.recursive !== '1' && Object.keys(r.node.children).length) return jsonResp({ error: 'Bad Request', detail: 'directory not empty' }, 400);
      delete r.parent.children[r.name]; return jsonResp({ ok: true });
    },
    'tree/rename': function (req) {
      var r = tResolve(req.query.path || ''); if (!r.node || !r.parent) return tNotFound(req.query.path);
      r.parent.children[req.query.name] = r.node; delete r.parent.children[r.name]; return jsonResp({ ok: true });
    },
    'tree/token': function (req) {
      var r = tResolve(req.query.path || ''); if (!r.node) return tNotFound(req.query.path);
      if (r.node.kind !== 'file') return jsonResp({ error: 'Bad Request', detail: 'not a file' }, 400);
      var token = 'tree:' + req.query.path; fx.files[token] = r.node.bytes;
      return jsonResp({ token: token, size: r.node.bytes.length, mtime: r.node.mtime || 0, name: r.name });
    },
    'tree/open': function (req) {
      var segs = (req.query.path || '').split('/').filter(Boolean); if (!segs.length) return jsonResp({ error: 'Bad Request', detail: 'path names no file' }, 400);
      var dir = tEnsureDir(segs.slice(0, -1).join('/'));
      var id = 'w' + (++tSeq); treeWriters[id] = { dir: dir, name: segs[segs.length - 1], parts: [], n: 0 };
      return jsonResp({ w: id });
    },
    'tree/write': function (req, body) { var w = treeWriters[req.query.w]; if (!w) return jsonResp({ error: 'Not Found', detail: 'no such writer' }, 404); if (body && body.length) { w.parts.push(body.slice()); w.n += body.length; } return jsonResp({ bytes: w.n }); },
    'tree/close': function (req) {
      var w = treeWriters[req.query.w]; if (!w) return jsonResp({ error: 'Not Found', detail: 'no such writer' }, 404); delete treeWriters[req.query.w];
      var out = new Uint8Array(w.n), at = 0; w.parts.forEach(function (p) { out.set(p, at); at += p.length; });
      w.dir.children[w.name] = { kind: 'file', bytes: out, mtime: Date.now() }; return jsonResp({ ok: true, size: w.n });
    },
    'tree/abort': function (req) { delete treeWriters[req.query.w]; return jsonResp({ ok: true }); },
    'intake/stream': function () {
      var id = 'bi' + (++streamSeq);
      streams.set(id, { stop: function () { if (intakeStream === id) intakeStream = null; } });
      intakeStream = id;
      flushIntake();                 // synchronously, inside the open — like the real plugin
      return jsonResp({ stream: id });
    },
    'intake/pending': function () { return jsonResp(intakeQueue.splice(0)); },
    'gnss/permission': function () { return jsonResp({ granted: fx.locationGranted, canRequest: true }); },
    'gnss/log/start': function (req) {
      if (logSession && !logSession.stoppedAt) return new Response(JSON.stringify({ error: 'busy', holder: logSession.name }), { status: 409, headers: { 'Content-Type': 'application/json' } });
      var name = req.query.name || ('gnss-' + Date.now());
      logSession = { name: name, what: (req.query.what || 'raw,location').split(','), epochs: 0, fixes: 0, nmea: 0, bytes: 0, startedAt: Date.now(), stoppedAt: 0, lines: ['{"kind":"start","name":"' + name + '"}'] };
      logSession.iv = setInterval(function () {
        logSession.epochs++; logSession.lines.push('{"kind":"raw","t":' + Date.now() + ',"measurements":[]}');
        if (logSession.epochs % 2 === 0) { logSession.fixes++; logSession.lines.push('{"kind":"fix","t":' + Date.now() + ',"lat":' + fx.fix.lat + ',"lon":' + fx.fix.lon + '}'); }
        logSession.bytes = logSession.lines.join('\n').length + 1;
      }, 100);
      return jsonResp({ name: name, what: req.query.what || 'raw,location', startedAt: logSession.startedAt });
    },
    'gnss/log/stop': function () {
      if (!logSession || logSession.stoppedAt) return new Response(JSON.stringify({ error: 'Not Found', detail: 'no log is running' }), { status: 404, headers: { 'Content-Type': 'application/json' } });
      clearInterval(logSession.iv); logSession.stoppedAt = Date.now();
      logSession.lines.push('{"kind":"stop","epochs":' + logSession.epochs + '}');
      var token = 'gnss-log-' + logSession.name;
      fx.files[token] = new TextEncoder().encode(logSession.lines.join('\n') + '\n');
      logSession.bytes = fx.files[token].length; logSession.token = token;
      return jsonResp(logSummary());
    },
    'gnss/log/status': function () { return jsonResp(logSession ? logSummary() : { running: false }); },
    'camera/list': function () { return jsonResp([{ id: '0', facing: 'back', hasRaw: true, focalLengthsMm: [6.5], sensorMm: [9.8, 7.3], pixels: [4000, 3000] }]); },
    'camera/permission': function () { return jsonResp({ granted: true, canRequest: true }); },
    'gnss/location': function (req) {
      if (!fx.locationGranted) return new Response(JSON.stringify({ error: 'permission', detail: 'denied', canRequest: true }), { status: 403, headers: { 'Content-Type': 'application/json' } });
      var id = 'bg' + (++streamSeq), minMs = +(req.query.minMs || 1000);
      var iv = setInterval(function () {
        push(id, 'fix', JSON.stringify({ lat: fx.fix.lat + (Math.random() - 0.5) * 1e-5, lon: fx.fix.lon + (Math.random() - 0.5) * 1e-5, alt: fx.fix.alt, acc: fx.fix.acc, speed: 0, bearing: null, t: Date.now(), provider: 'bench', last: false }));
      }, Math.max(100, minMs));
      streams.set(id, { stop: function () { clearInterval(iv); } });
      setTimeout(function () { push(id, 'fix', JSON.stringify(Object.assign({ speed: 0, bearing: null, t: Date.now(), provider: 'bench', last: true }, fx.fix))); }, 0);
      return jsonResp({ stream: id });
    },
    'gnss/status': function () {
      if (!fx.locationGranted) return new Response(JSON.stringify({ error: 'permission', detail: 'denied', canRequest: true }), { status: 403, headers: { 'Content-Type': 'application/json' } });
      var id = 'bg' + (++streamSeq);
      var iv = setInterval(function () {
        push(id, 'status', JSON.stringify({ fixSats: 7, satellites: [1, 3, 8, 14, 22, 27, 31].map(function (sv) { return { svid: sv, constellation: 'gps', cn0: 30 + (sv % 7), used: true, elev: 20 + sv, az: sv * 11 }; }) }));
      }, 1000);
      streams.set(id, { stop: function () { clearInterval(iv); } });
      return jsonResp({ stream: id });
    },
    'sensor/list': function () { return jsonResp([{ name: 'rotation', vendor: 'bench', resolution: 0 }]); },
    'sensor/stream': function (req) {
      var id = 'bs' + (++streamSeq);
      var rate = +(req.query.rateHz || fx.sensorRateHz), t0 = performance.now();
      var iv = setInterval(function () {
        var t = (performance.now() - t0) / 1000;
        // a slowly-rotating synthetic rotation-vector [x,y,z,w]
        var az = (t * 0.3) % (2 * Math.PI), tilt = 0.3 + 0.2 * Math.sin(t);
        var v = [Math.sin(tilt / 2) * Math.cos(az), Math.sin(tilt / 2) * Math.sin(az), 0, Math.cos(tilt / 2)];
        push(id, 'rotation', JSON.stringify({ t: Date.now() * 1e6, v: v, acc: 3 }));
      }, 1000 / rate);
      streams.set(id, { stop: function () { clearInterval(iv); } });
      return jsonResp({ stream: id });
    },
    'share': function (req, body) {
      console.log('[bench] share', req.query.name || '(text)', body ? body.length + 'B' : req.query.text);
      shared.push({ name: req.query.name, mime: req.query.mime, text: req.query.text, bytes: body ? body.slice() : null });
      return jsonResp({ ok: true });
    },
    'attest/keyinfo': function () {
      return benchKey().then(function (k) { return jsonResp({ alg: 'ECDSA-P256-SHA256', pub: k.pubB64, security: 'bench-software' }); });
    },
    'attest/sign': function (req, body) {
      return benchKey().then(function (k) {
        // WebCrypto ECDSA already yields raw r‖s (IEEE P1363) — same as the real
        // plugin's DER→raw output; verifies with WebCrypto directly.
        return Promise.all([
          crypto.subtle.sign({ name: 'ECDSA', hash: 'SHA-256' }, k.priv, body),
          crypto.subtle.digest('SHA-256', body),
        ]).then(function (r) {
          return jsonResp({ alg: 'ECDSA-P256-SHA256', sig: b64(r[0]), pub: k.pubB64, hash: b64(r[1]), security: 'bench-software' });
        });
      });
    },
  };

  // fs ranged read is dynamic (token in the path): /native/fs/<token>
  function fsRead(token, rangeHeader) {
    var buf = fx.files[token];
    if (!buf) return jsonResp({ error: 'Not Found', detail: token }, 404);
    var total = buf.length;
    if (!rangeHeader) return new Response(buf, { status: 200, headers: { 'Accept-Ranges': 'bytes', 'Content-Length': '' + total } });
    var m = /bytes=(\d*)-(\d*)/.exec(rangeHeader) || [];
    var start = m[1] === '' ? Math.max(0, total - (+m[2])) : +m[1];
    var end = m[1] === '' ? total - 1 : (m[2] === '' ? total - 1 : Math.min(+m[2], total - 1));
    if (start >= total || end < start) return new Response(null, { status: 416, headers: { 'Content-Range': 'bytes */' + total } });
    return new Response(buf.slice(start, end + 1), {
      status: 206, headers: { 'Accept-Ranges': 'bytes', 'Content-Range': 'bytes ' + start + '-' + end + '/' + total, 'Content-Length': '' + (end - start + 1) },
    });
  }

  // ── intercept fetch('/native/**') ─────────────────────────────────────────
  var realFetch = window.fetch.bind(window);
  window.fetch = function (input, init) {
    var url = typeof input === 'string' ? input : input.url;
    var u;
    try { u = new URL(url, location.href); } catch (e) { return realFetch(input, init); }
    if (!u.pathname.startsWith('/native/')) return realFetch(input, init);

    var pathAndSub = u.pathname.slice('/native/'.length);
    var query = {}; u.searchParams.forEach(function (v, k) { query[k] = v; });
    var headers = (init && init.headers) || {};
    var req = { query: query, headers: headers };

    // body sidecar: the shim posted the body over the port, tagged with this id
    var bodyId = headers['X-LeadAcid-Body-Id'] || headers['x-leadacid-body-id'];
    var bodyP = bodyId ? awaitBody(bodyId) : Promise.resolve(null);

    return bodyP.then(function (body) {
      // dynamic fs read: fs/<token> (but not fs/list, fs/publish, fs/echo)
      var seg = pathAndSub.split('/');
      if (seg[0] === 'fs' && seg[1] && !routes['fs/' + seg[1]] && seg[1] !== 'echo') {
        return fsRead(decodeURIComponent(seg[1]), headers['Range'] || headers['range']);
      }
      var h = routes[pathAndSub];
      if (!h) return jsonResp({ error: 'Not Found', detail: pathAndSub }, 404);
      return Promise.resolve(h(req, body));
    });
  };

  var logSession = null;
  function logSummary() {
    var s = logSession;
    return { running: !s.stoppedAt, name: s.name, token: s.token || null, what: s.what, epochs: s.epochs, fixes: s.fixes, nmea: s.nmea, bytes: s.bytes, lastFix: s.fixes ? '±5 m' : 'no fix', startedAt: s.startedAt, stoppedAt: s.stoppedAt || null };
  }
  // intake: fixtures `intake: [items]` arrive when the page opens the stream;
  // `window.__bench.intake(items)` injects more later (a smoke "sharing" a file).
  // A file item needs its bytes in `__bench.files[token]` (= the fs fixtures).
  var intakeQueue = (fx.intake || []).slice(), intakeStream = null;
  function flushIntake() {
    if (!intakeStream) return;
    intakeQueue.splice(0).forEach(function (it) { push(intakeStream, 'item', JSON.stringify(it)); });
  }
  console.log('[bench] active — /native mocked, shell.present=true. Plugins: shell, fs, sensor, share, attest, intake, gnss, camera, tree.');
  // pause(): what the real shell's onPause does — stop + close EVERY open push
  // stream and tell the page (a share reaching a running singleTask instrument
  // pauses it first; the intake stream must come back by itself, SPEC §4.5).
  function pause() {
    streams.forEach(function (s, id) { s.stop(); pushClose(id); });
    streams.clear();
  }
  window.__bench = { fixtures: fx, push: push, files: fx.files, pause: pause, published: published, shared: shared, tree: treeRoot, intake: function (items) { intakeQueue.push.apply(intakeQueue, items); flushIntake(); } };
})();
