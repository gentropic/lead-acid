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
      return jsonResp({ uri: 'bench://' + req.query.collection + '/' + req.query.name, name: req.query.name, bytes: body ? body.length : 0 });
    },
    'intake/stream': function () {
      var id = 'bi' + (++streamSeq);
      streams.set(id, { stop: function () { if (intakeStream === id) intakeStream = null; } });
      intakeStream = id;
      flushIntake();                 // synchronously, inside the open — like the real plugin
      return jsonResp({ stream: id });
    },
    'intake/pending': function () { return jsonResp(intakeQueue.splice(0)); },
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

  // intake: fixtures `intake: [items]` arrive when the page opens the stream;
  // `window.__bench.intake(items)` injects more later (a smoke "sharing" a file).
  // A file item needs its bytes in `__bench.files[token]` (= the fs fixtures).
  var intakeQueue = (fx.intake || []).slice(), intakeStream = null;
  function flushIntake() {
    if (!intakeStream) return;
    intakeQueue.splice(0).forEach(function (it) { push(intakeStream, 'item', JSON.stringify(it)); });
  }
  console.log('[bench] active — /native mocked, shell.present=true. Plugins: shell, fs, sensor, share, attest, intake.');
  window.__bench = { fixtures: fx, push: push, files: fx.files, intake: function (items) { intakeQueue.push.apply(intakeQueue, items); flushIntake(); } };
})();
