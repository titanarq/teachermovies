// Movie Assistant phone web UI (#63). Vanilla JS, no build step, no external resources.
// Auth (ADR-0002): the pairing token lives in localStorage and goes as `Authorization: Bearer`
// on every protected /api call; only the SSE stream passes it as `?token=` (EventSource cannot set
// headers). A 401 anywhere clears it and shows the PIN form again. The token is never logged.
'use strict';

(function () {
  var TOKEN_KEY = 'teachermovies.token';
  var STATUS_EVERY_MS = 5000;
  var POLL_EVERY_MS = 3000;
  var SSE_RETRY_AFTER_MS = 30000;

  var $ = function (id) { return document.getElementById(id); };

  // ---- token storage -------------------------------------------------------------------------

  function getToken() {
    try { return localStorage.getItem(TOKEN_KEY); } catch (e) { return null; }
  }

  function setToken(token) {
    try { localStorage.setItem(TOKEN_KEY, token); } catch (e) { /* private mode: page-only */ }
    memoryToken = token;
  }

  function clearToken() {
    try { localStorage.removeItem(TOKEN_KEY); } catch (e) { /* nothing stored */ }
    memoryToken = null;
  }

  var memoryToken = getToken();
  function token() { return getToken() || memoryToken; }

  // ---- HTTP helpers --------------------------------------------------------------------------

  /** Thrown after a 401: the caller just stops, the PIN form is already showing. */
  function Unauthorized() { this.name = 'Unauthorized'; }

  function readError(res) {
    return res.json().then(
      function (body) { return (body && body.error) || ('http_' + res.status); },
      function () { return 'http_' + res.status; }
    );
  }

  /** fetch() to a protected /api route with the bearer token; a 401 re-opens pairing. */
  function api(path, options) {
    options = options || {};
    var headers = options.headers || {};
    headers['Authorization'] = 'Bearer ' + token();
    options.headers = headers;
    return fetch(path, options).then(function (res) {
      if (res.status === 401) {
        onUnauthorized();
        throw new Unauthorized();
      }
      return res;
    });
  }

  // ---- connection indicator ------------------------------------------------------------------

  function setConnected(on) {
    $('conn').className = 'conn ' + (on ? 'conn-on' : 'conn-off');
    $('conn-text').textContent = on ? 'conectada' : 'desconectada';
  }

  function checkStatus() {
    fetch('/api/status', { cache: 'no-store' })
      .then(function (res) { setConnected(res.ok); })
      .catch(function () { setConnected(false); });
  }

  // ---- pairing -------------------------------------------------------------------------------

  var PAIR_ERRORS = {
    wrong_pin: 'PIN incorrecto.',
    too_many_attempts: 'Demasiados intentos. Espera un minuto.',
    bad_request: 'Introduce las 6 cifras del PIN.',
  };

  function showPairing(message) {
    stopLive();
    $('app').hidden = true;
    $('pair').hidden = false;
    setMsg($('pair-msg'), message || '', message ? 'error' : '');
    $('pin').value = '';
    $('pin').focus();
  }

  function showApp() {
    $('pair').hidden = true;
    $('app').hidden = false;
    startLive();
  }

  function onUnauthorized() {
    clearToken();
    showPairing('La TV ya no reconoce este móvil. Vuelve a emparejar.');
  }

  function deviceName() {
    var ua = navigator.userAgent || '';
    var m = ua.match(/\(([^;)]+)/);
    return (m ? m[1] : 'Navegador').slice(0, 64);
  }

  function pair(event) {
    event.preventDefault();
    var pin = $('pin').value.replace(/\D/g, '');
    var button = event.target.querySelector('button');
    button.disabled = true;
    setMsg($('pair-msg'), 'Emparejando…', '');
    fetch('/api/pair', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ pin: pin, deviceName: deviceName() }),
    })
      .then(function (res) {
        if (res.ok) return res.json().then(function (body) { return { token: body.token }; });
        return readError(res).then(function (code) { return { error: code }; });
      })
      .then(function (result) {
        if (result.token) {
          setToken(result.token);
          setMsg($('pair-msg'), '', '');
          showApp();
        } else {
          setMsg($('pair-msg'), PAIR_ERRORS[result.error] || 'No se pudo emparejar.', 'error');
        }
      })
      .catch(function () { setMsg($('pair-msg'), 'No se pudo contactar con la TV.', 'error'); })
      .then(function () { button.disabled = false; });
  }

  // ---- sending magnets and .torrent files ----------------------------------------------------

  var ADD_ERRORS = {
    invalid_magnet: 'Eso no es un magnet válido.',
    invalid_torrent: 'El archivo no es un .torrent válido.',
    already_exists: 'Ese torrent ya está en la TV.',
    too_large: 'El archivo es demasiado grande (máx. 10 MB).',
    bad_request: 'Petición no válida.',
  };

  function setMsg(el, text, kind) {
    el.textContent = text;
    el.className = 'msg' + (kind ? ' ' + kind : '');
  }

  function handleAdd(promise, onSuccess) {
    var msg = $('send-msg');
    setMsg(msg, 'Enviando…', '');
    return promise
      .then(function (res) {
        if (res.status === 201) {
          setMsg(msg, 'Enviado a la TV.', 'ok');
          if (onSuccess) onSuccess();
          refreshIfPolling();
          return;
        }
        return readError(res).then(function (code) {
          setMsg(msg, ADD_ERRORS[code] || 'La TV no pudo añadirlo (' + code + ').', 'error');
        });
      })
      .catch(function (e) {
        if (!(e instanceof Unauthorized)) setMsg(msg, 'No se pudo contactar con la TV.', 'error');
      });
  }

  function sendMagnet() {
    var magnet = $('magnet').value.trim();
    if (!magnet) {
      setMsg($('send-msg'), 'Pega primero un magnet.', 'error');
      return;
    }
    var button = $('send');
    button.disabled = true;
    handleAdd(
      api('/api/torrents/magnet', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ magnet: magnet }),
      }),
      function () { $('magnet').value = ''; }
    ).then(function () { button.disabled = false; });
  }

  function uploadTorrent() {
    var input = $('file');
    var file = input.files && input.files[0];
    if (!file) return;
    var form = new FormData();
    form.append('torrent', file, file.name);
    var button = $('upload');
    button.disabled = true;
    // No Content-Type header: the browser sets multipart/form-data with its boundary.
    handleAdd(api('/api/torrents/file', { method: 'POST', body: form })).then(function () {
      button.disabled = false;
      input.value = '';
    });
  }

  // ---- download list -------------------------------------------------------------------------

  function mb(bytesPerSecond) { return (bytesPerSecond / 1e6).toFixed(1) + ' MB/s'; }
  function gb(bytes) { return (bytes / 1e9).toFixed(1); }
  function pct(progress) { return Math.round(progress) + ' %'; }

  var rows = {};

  function buildRow(id) {
    var li = document.createElement('li');
    li.className = 'item';
    var name = document.createElement('div');
    name.className = 'name';
    var bar = document.createElement('div');
    bar.className = 'bar';
    var fill = document.createElement('div');
    fill.className = 'fill';
    bar.appendChild(fill);
    var stats = document.createElement('div');
    stats.className = 'stats';
    var p = document.createElement('span');
    var speed = document.createElement('span');
    var size = document.createElement('span');
    stats.appendChild(p);
    stats.appendChild(speed);
    stats.appendChild(size);
    var button = document.createElement('button');
    button.type = 'button';
    button.className = 'btn';
    button.addEventListener('click', function () { toggle(id, button); });
    li.appendChild(name);
    li.appendChild(bar);
    li.appendChild(stats);
    li.appendChild(button);
    return { li: li, name: name, fill: fill, pct: p, speed: speed, size: size, button: button };
  }

  function render(torrents) {
    var list = $('list');
    var seen = {};
    torrents.forEach(function (t) {
      seen[t.id] = true;
      var row = rows[t.id] || (rows[t.id] = buildRow(t.id));
      // textContent only: torrent names come from the network and are never parsed as HTML.
      row.name.textContent = t.name || t.id;
      row.fill.style.width = Math.max(0, Math.min(100, t.progress)) + '%';
      row.pct.textContent = pct(t.progress);
      row.speed.textContent = mb(t.downloadSpeed);
      row.size.textContent = gb(t.downloadedBytes) + ' / ' + gb(t.totalBytes) + ' GB';
      var paused = t.state === 'paused';
      row.button.dataset.action = paused ? 'resume' : 'pause';
      row.button.textContent = paused ? 'REANUDAR' : 'PAUSAR';
      row.button.hidden = t.state === 'completed';
      list.appendChild(row.li); // keeps server order; moving an existing node is cheap
    });
    Object.keys(rows).forEach(function (id) {
      if (!seen[id]) {
        list.removeChild(rows[id].li);
        delete rows[id];
      }
    });
    $('empty').hidden = torrents.length > 0;
  }

  function toggle(id, button) {
    var action = button.dataset.action;
    button.disabled = true;
    api('/api/torrents/' + encodeURIComponent(id) + '/' + action, { method: 'POST' })
      .then(function (res) {
        if (res.status === 204) {
          refreshIfPolling();
          return;
        }
        return readError(res).then(function (code) {
          setMsg($('send-msg'), 'No se pudo ' + (action === 'pause' ? 'pausar' : 'reanudar') +
            ' (' + code + ').', 'error');
        });
      })
      .catch(function (e) {
        if (!(e instanceof Unauthorized)) setMsg($('send-msg'), 'No se pudo contactar con la TV.', 'error');
      })
      .then(function () { button.disabled = false; });
  }

  // ---- live updates: SSE, falling back to polling -------------------------------------------

  var source = null;
  var pollTimer = null;
  var sseFailedAt = 0;

  function startLive() {
    stopLive();
    openSse();
  }

  function stopLive() {
    if (source) { source.close(); source = null; }
    if (pollTimer) { clearInterval(pollTimer); pollTimer = null; }
  }

  function openSse() {
    if (typeof EventSource === 'undefined') { startPolling(); return; }
    var es = new EventSource('/api/events?token=' + encodeURIComponent(token()));
    source = es;
    es.addEventListener('torrents', function (e) {
      if (pollTimer) { clearInterval(pollTimer); pollTimer = null; }
      try { render(JSON.parse(e.data)); } catch (err) { /* malformed frame: wait for the next */ }
    });
    es.onerror = function () {
      // A failed stream (401, network, proxy) -> poll instead. Polling also detects a 401.
      es.close();
      if (source === es) source = null;
      sseFailedAt = Date.now();
      startPolling();
    };
  }

  function startPolling() {
    if (pollTimer) return;
    poll();
    pollTimer = setInterval(poll, POLL_EVERY_MS);
  }

  function poll() {
    api('/api/torrents', { cache: 'no-store' })
      .then(function (res) {
        if (!res.ok) return;
        return res.json().then(function (torrents) {
          render(torrents);
          // Give SSE another chance now and then; its first event stops polling.
          if (!source && Date.now() - sseFailedAt > SSE_RETRY_AFTER_MS) openSse();
        });
      })
      .catch(function () { /* next tick retries; the indicator reports the connection */ });
  }

  function refreshIfPolling() {
    if (pollTimer) poll();
  }

  // ---- boot ----------------------------------------------------------------------------------

  $('pair-form').addEventListener('submit', pair);
  $('send').addEventListener('click', sendMagnet);
  $('upload').addEventListener('click', function () { $('file').click(); });
  $('file').addEventListener('change', uploadTorrent);

  checkStatus();
  setInterval(checkStatus, STATUS_EVERY_MS);

  if (token()) showApp(); else showPairing();
})();
