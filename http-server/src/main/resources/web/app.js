// Movie Assistant phone web UI (#63). Vanilla JS, no build step, no external resources.
// Auth (ADR-0002): the pairing token lives in localStorage and goes as `Authorization: Bearer`
// on every protected /api call; only the /api/events SSE stream passes it as `?token=` (EventSource
// cannot set headers; the Registros log stream is read with fetch() and the header instead). A 401 anywhere clears it and shows the PIN form again. The token is never logged.
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

  /** Resolves to {code, message} from a JSON error body; message is '' when absent. */
  function readApiError(res) {
    return res.json().then(
      function (body) {
        return {
          code: (body && body.error) || ('http_' + res.status),
          message: (body && typeof body.message === 'string') ? body.message : '',
        };
      },
      function () { return { code: 'http_' + res.status, message: '' }; }
    );
  }

  /** Spanish failure text; a 4xx shows the server's `message` (or its code when it sent none). */
  function failureText(prefix, res, err) {
    if (res.status >= 400 && res.status < 500) return prefix + ': ' + (err.message || err.code) + '.';
    return prefix + ' (error ' + res.status + ').';
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
    stopLogs();
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
    selectTab(currentTab);
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
    var actions = document.createElement('div');
    actions.className = 'row-actions';
    var subInput = document.createElement('input');
    subInput.type = 'file';
    subInput.accept = SUBTITLE_ACCEPT;
    subInput.hidden = true;
    var subButton = document.createElement('button');
    subButton.type = 'button';
    subButton.className = 'btn';
    subButton.textContent = 'SUBIR SUBTÍTULO';
    var searchButton = document.createElement('button');
    searchButton.type = 'button';
    searchButton.className = 'btn';
    searchButton.textContent = 'BUSCAR SUBTÍTULOS';
    searchButton.hidden = true;
    var delButton = document.createElement('button');
    delButton.type = 'button';
    delButton.className = 'btn btn-danger';
    delButton.textContent = 'BORRAR';
    var msg = document.createElement('p');
    msg.className = 'msg';
    msg.setAttribute('role', 'status');
    msg.setAttribute('aria-live', 'polite');
    subButton.addEventListener('click', function () { subInput.click(); });
    subInput.addEventListener('change', function () { uploadSubtitle(id, subInput, subButton, msg); });
    delButton.addEventListener('click', function () { askDelete(id, delButton, msg); });
    searchButton.addEventListener('click', function () { searchSubtitles(id, searchButton, msg); });
    actions.appendChild(searchButton);
    actions.appendChild(subButton);
    actions.appendChild(delButton);
    actions.appendChild(subInput);
    li.appendChild(name);
    li.appendChild(bar);
    li.appendChild(stats);
    li.appendChild(button);
    li.appendChild(actions);
    li.appendChild(msg);
    return {
      li: li, name: name, fill: fill, pct: p, speed: speed, size: size, button: button,
      searchButton: searchButton,
    };
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
      // Only a finished download is a library movie with subtitles to look for (#285).
      row.searchButton.hidden = t.state !== 'completed';
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

  // ---- per-row actions: subtitle upload and delete (#64) ------------------------------------

  var SUBTITLE_ACCEPT = '.srt,.ass,.ssa,.vtt';
  var SUBTITLE_EXT = /\.(srt|ass|ssa|vtt)$/i;

  function uploadSubtitle(id, input, button, msg) {
    var file = input.files && input.files[0];
    if (!file) return;
    if (!SUBTITLE_EXT.test(file.name)) {
      setMsg(msg, 'Elige un subtítulo .srt, .ass, .ssa o .vtt.', 'error');
      input.value = '';
      return;
    }
    var form = new FormData();
    form.append('torrentId', id);
    form.append('file', file, file.name);
    button.disabled = true;
    setMsg(msg, 'Subiendo subtítulo…', '');
    // No Content-Type header: the browser sets multipart/form-data with its boundary.
    api('/api/subtitles', { method: 'POST', body: form })
      .then(function (res) {
        if (res.status === 201) {
          setMsg(msg, 'Subtítulo enviado a la TV.', 'ok');
          return;
        }
        return readApiError(res).then(function (err) {
          setMsg(msg, failureText('No se pudo subir el subtítulo', res, err), 'error');
        });
      })
      .catch(function (e) {
        if (!(e instanceof Unauthorized)) setMsg(msg, 'No se pudo contactar con la TV.', 'error');
      })
      .then(function () {
        button.disabled = false;
        input.value = '';
      });
  }

  // ---- per-movie "Buscar subtítulos" (#285, ADR-0005 §5) -----------------------------------

  var SUBTITLE_SEARCH_POLL_MS = 3000;
  var SUBTITLE_SEARCH_MAX_POLLS = 40; // two minutes, then the last state stays on screen

  var SUBTITLE_LANGUAGES = { en: 'Inglés', es: 'Español' };
  var SUBTITLE_STATES = {
    pending: 'pendiente',
    searching: 'buscando',
    downloaded: 'encontrado',
    not_found: 'no encontrado',
    failed: 'error',
  };
  var SUBTITLE_SEARCH_TEXT = {
    found: ['Subtítulos encontrados', 'ok'],
    not_found: ['No se encontraron subtítulos', 'error'],
    failed: ['La búsqueda de subtítulos falló', 'error'],
    laptop_offline: ['Portátil no conectado: se buscarán cuando se conecte', 'error'],
    searching: ['Buscando subtítulos…', ''],
    no_needs: ['La TV aún no ha registrado qué subtítulos necesita esta película', ''],
  };

  /** One sentence for a SubtitleSearchDto: the overall result, then each language's state. */
  function subtitleSearchText(dto) {
    var head = SUBTITLE_SEARCH_TEXT[dto.status] || [dto.status, ''];
    var parts = (dto.languages || []).map(function (l) {
      var state = SUBTITLE_STATES[l.state] || l.state;
      if (l.state === 'downloaded' && l.variant) state += ' (' + l.variant + ')';
      return (SUBTITLE_LANGUAGES[l.language] || l.language) + ': ' + state;
    });
    return { text: head[0] + (parts.length ? ' · ' + parts.join(' · ') : '') + '.', kind: head[1] };
  }

  function showSubtitleSearch(msg, dto) {
    var shown = subtitleSearchText(dto);
    setMsg(msg, shown.text, shown.kind);
  }

  /** Asks the TV to retry the search now, then follows it until it ends or the polls run out. */
  function searchSubtitles(id, button, msg) {
    var path = '/api/library/' + encodeURIComponent(id) + '/subtitles';
    var polls = 0;
    button.disabled = true;
    setMsg(msg, 'Pidiendo a la TV que busque subtítulos…', '');
    function follow(res) {
      if (res.status !== 200) {
        return readApiError(res).then(function (err) {
          setMsg(msg, failureText('No se pudieron buscar subtítulos', res, err), 'error');
        });
      }
      return res.json().then(function (dto) {
        showSubtitleSearch(msg, dto);
        if (dto.status !== 'searching' || ++polls > SUBTITLE_SEARCH_MAX_POLLS) return;
        return new Promise(function (resolve) { setTimeout(resolve, SUBTITLE_SEARCH_POLL_MS); })
          .then(function () { return api(path); })
          .then(follow);
      });
    }
    api(path + '/search', { method: 'POST' })
      .then(follow)
      .catch(function (e) {
        if (!(e instanceof Unauthorized)) setMsg(msg, 'No se pudo contactar con la TV.', 'error');
      })
      .then(function () { button.disabled = false; });
  }

  /** Confirm dialog with the `Borrar también los archivos` checkbox; resolves to null on cancel. */
  function confirmDelete(name) {
    var dialog = $('del-dialog');
    if (!dialog || typeof dialog.showModal !== 'function') {
      // Old browsers without <dialog>: two plain confirms stand in for the checkbox.
      if (!window.confirm('¿Borrar «' + name + '» de la TV?')) return Promise.resolve(null);
      return Promise.resolve({ deleteFiles: window.confirm('¿Borrar también los archivos?') });
    }
    $('del-name').textContent = name; // textContent: names come from the network
    $('del-files').checked = false;
    return new Promise(function (resolve) {
      dialog.addEventListener('close', function onClose() {
        dialog.removeEventListener('close', onClose);
        resolve(dialog.returnValue === 'ok' ? { deleteFiles: $('del-files').checked } : null);
      });
      dialog.returnValue = '';
      dialog.showModal();
    });
  }

  function askDelete(id, button, msg) {
    var row = rows[id];
    var name = (row && row.name.textContent) || id;
    confirmDelete(name).then(function (choice) {
      if (!choice) return;
      button.disabled = true;
      setMsg(msg, 'Borrando…', '');
      var path = '/api/torrents/' + encodeURIComponent(id) + '?deleteFiles=' + (choice.deleteFiles ? 'true' : 'false');
      return api(path, { method: 'DELETE' })
        .then(function (res) {
          if (res.status === 204) {
            setMsg($('send-msg'), choice.deleteFiles ? 'Descarga y archivos borrados.' : 'Descarga borrada.', 'ok');
            refreshIfPolling();
            return;
          }
          return readApiError(res).then(function (err) {
            setMsg(msg, failureText('No se pudo borrar', res, err), 'error');
          });
        })
        .catch(function (e) {
          if (!(e instanceof Unauthorized)) setMsg(msg, 'No se pudo contactar con la TV.', 'error');
        })
        .then(function () { button.disabled = false; });
    });
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

  // ---- Registros: the TV's log stream (#273, ADR-0006 §4) -----------------------------------
  // Read with fetch() and the Authorization header (api()), never `?token=`: that exception is
  // /api/events' alone. When the stream fails or ends, GET /api/logs is polled every
  // LOG_POLL_EVERY_MS and the stream is retried now and then. The DOM holds at most
  // LOG_MAX_DOM_LINES lines; `logEntries` keeps what was received (the .txt export) up to
  // LOG_MAX_KEPT, so a long session cannot exhaust the phone's memory. The stream only runs while
  // the tab is showing.

  var LOG_MAX_DOM_LINES = 2000;
  var LOG_MAX_KEPT = 20000;
  var LOG_POLL_EVERY_MS = 3000;
  var LOG_STREAM_RETRY_AFTER_MS = 30000;
  var LOG_PAGE_LIMIT = 500;
  var LOG_FLUSH_MS = 100;

  var logEntries = [];      // oldest first: every line received at the selected level
  var logPending = [];      // received but not yet rendered (always while paused)
  var logUnseen = 0;        // lines received since the pause
  var logBootId = null;
  var logLastSeq = 0;
  var logFollowing = true;
  var logAbort = null;      // AbortController of the open stream, null when none is open
  var logPollTimer = null;
  var logFlushTimer = null;
  var logStreamFailedAt = 0;
  var logGeneration = 0;    // bumped by stopLogs(): a reader of an older generation drops its data

  function logLevel() { return $('log-level').value; }

  function pad(n, width) {
    var s = String(n);
    while (s.length < width) s = '0' + s;
    return s;
  }

  /** `HH:MM:SS.mmm`, preceded by `YYYY-MM-DD ` when [withDate] (the .txt export). */
  function logTime(ms, withDate) {
    var d = new Date(ms);
    var time = pad(d.getHours(), 2) + ':' + pad(d.getMinutes(), 2) + ':' + pad(d.getSeconds(), 2) +
      '.' + pad(d.getMilliseconds(), 3);
    if (!withDate) return time;
    return d.getFullYear() + '-' + pad(d.getMonth() + 1, 2) + '-' + pad(d.getDate(), 2) + ' ' + time;
  }

  function logText(entry, withDate) {
    if (entry.note) return entry.note;
    return logTime(entry.timeMs, withDate) + ' ' + String(entry.level).toUpperCase() + ' ' +
      entry.module + ': ' + entry.message;
  }

  function logLineNode(entry) {
    var div = document.createElement('div');
    div.className = 'log-line ' + (entry.note ? 'log-note' : 'lv-' + entry.level);
    div.textContent = logText(entry, false); // textContent: log lines are never parsed as HTML
    return div;
  }

  function keepLog(entry) {
    logEntries.push(entry);
    if (logEntries.length > LOG_MAX_KEPT) logEntries.splice(0, logEntries.length - LOG_MAX_KEPT);
    logPending.push(entry);
    // Only the newest LOG_MAX_DOM_LINES pending lines can ever be rendered.
    if (logPending.length > 2 * LOG_MAX_DOM_LINES) logPending.splice(0, logPending.length - LOG_MAX_DOM_LINES);
    if (!logFollowing) logUnseen++;
  }

  /** One LogEntryDto; a seq at or below the cursor is a line already shown (stream + poll overlap). */
  function acceptLog(entry) {
    if (!entry || typeof entry.seq !== 'number' || entry.seq <= logLastSeq) return;
    logLastSeq = entry.seq;
    keepLog(entry);
  }

  /**
   * Records [bootId]; false when the TV restarted while [since] was a cursor of the old boot, so
   * the answer skipped the new boot's first lines and has to be asked again from 0.
   */
  function acceptBoot(bootId, since) {
    if (logBootId === bootId) return true;
    var restarted = logBootId !== null;
    logBootId = bootId;
    if (!restarted) return true;
    logLastSeq = 0;
    keepLog({ note: '— La TV se ha reiniciado: los registros empiezan de nuevo —' });
    return since === 0;
  }

  function scheduleLogFlush() {
    if (!logFollowing) {
      setMsg($('log-msg'), 'En pausa · ' + logUnseen + ' líneas nuevas sin mostrar.', '');
      return;
    }
    if (!logFlushTimer) logFlushTimer = setTimeout(flushLogs, LOG_FLUSH_MS);
  }

  /** Renders the pending lines and drops the oldest rendered ones beyond LOG_MAX_DOM_LINES. */
  function flushLogs() {
    logFlushTimer = null;
    if (!logFollowing || !logPending.length) return;
    var box = $('log-lines');
    var batch = logPending.length > LOG_MAX_DOM_LINES ? logPending.slice(-LOG_MAX_DOM_LINES) : logPending;
    logPending = [];
    var fragment = document.createDocumentFragment();
    batch.forEach(function (entry) { fragment.appendChild(logLineNode(entry)); });
    box.appendChild(fragment);
    while (box.childNodes.length > LOG_MAX_DOM_LINES) box.removeChild(box.firstChild);
    box.scrollTop = box.scrollHeight;
  }

  /** One SSE frame's text -> {event, data}; null for a comment-only frame (`: ping`). */
  function parseSseFrame(text) {
    var event = 'message';
    var data = [];
    text.split('\n').forEach(function (line) {
      if (!line || line.charAt(0) === ':') return;
      var colon = line.indexOf(':');
      var field = colon < 0 ? line : line.slice(0, colon);
      var value = colon < 0 ? '' : line.slice(colon + 1).replace(/^ /, '');
      if (field === 'event') event = value;
      else if (field === 'data') data.push(value);
    });
    return data.length ? { event: event, data: data.join('\n') } : null;
  }

  function startLogs() {
    stopLogs();
    openLogStream();
  }

  function stopLogs() {
    logGeneration++;
    if (logAbort) { logAbort.abort(); logAbort = null; }
    if (logPollTimer) { clearInterval(logPollTimer); logPollTimer = null; }
  }

  function openLogStream() {
    if (typeof AbortController === 'undefined' || typeof TextDecoder === 'undefined') {
      startLogPolling();
      return;
    }
    var generation = logGeneration;
    var controller = new AbortController();
    var since = logLastSeq;
    logAbort = controller;
    api('/api/logs/stream?since=' + since + '&level=' + encodeURIComponent(logLevel()),
      { cache: 'no-store', signal: controller.signal })
      .then(function (res) {
        if (!res.ok || !res.body || typeof res.body.getReader !== 'function') throw new Error('no_stream');
        var reader = res.body.getReader();
        var decoder = new TextDecoder();
        var buffer = '';
        function pump() {
          return reader.read().then(function (chunk) {
            if (generation !== logGeneration) return;
            if (chunk.done) throw new Error('stream_ended');
            buffer += decoder.decode(chunk.value, { stream: true }).replace(/\r\n?/g, '\n');
            var frames = buffer.split('\n\n');
            buffer = frames.pop();
            for (var i = 0; i < frames.length; i++) {
              var frame = parseSseFrame(frames[i]);
              if (!frame) continue;
              if (frame.event === 'boot') {
                // The stream works: it replaces polling until it fails again.
                if (logPollTimer) { clearInterval(logPollTimer); logPollTimer = null; }
                if (logFollowing) setMsg($('log-msg'), 'En directo.', '');
                if (!acceptBoot(JSON.parse(frame.data).bootId, since)) {
                  startLogs(); // the TV restarted: ask again from seq 0
                  return;
                }
              } else if (frame.event === 'log') {
                acceptLog(JSON.parse(frame.data));
              }
            }
            scheduleLogFlush();
            return pump();
          });
        }
        return pump();
      })
      .catch(function (e) {
        if (e instanceof Unauthorized || generation !== logGeneration) return;
        if (logAbort === controller) logAbort = null;
        logStreamFailedAt = Date.now();
        setMsg($('log-msg'), 'Sin conexión en directo: consultando cada ' +
          (LOG_POLL_EVERY_MS / 1000) + ' s.', 'error');
        startLogPolling();
      });
  }

  function startLogPolling() {
    if (logPollTimer) return;
    pollLogs();
    logPollTimer = setInterval(pollLogs, LOG_POLL_EVERY_MS);
  }

  function pollLogs() {
    var generation = logGeneration;
    var since = logLastSeq;
    api('/api/logs?since=' + since + '&level=' + encodeURIComponent(logLevel()) + '&limit=' + LOG_PAGE_LIMIT,
      { cache: 'no-store' })
      .then(function (res) {
        if (!res.ok) return;
        return res.json().then(function (page) {
          if (generation !== logGeneration) return;
          if (!acceptBoot(page.bootId, since)) { pollLogs(); return; } // restarted: page from 0
          page.entries.forEach(acceptLog);
          scheduleLogFlush();
          if (page.entries.length >= LOG_PAGE_LIMIT) { pollLogs(); return; } // more backlog waiting
          // Give the stream another chance now and then; its boot frame stops polling.
          if (!logAbort && Date.now() - logStreamFailedAt > LOG_STREAM_RETRY_AFTER_MS) openLogStream();
        });
      })
      .catch(function () { /* next tick retries; a 401 already re-opened pairing */ });
  }

  /** A new level is a new stream: the lines of the old one are cleared, not filtered. */
  function changeLogLevel() {
    stopLogs();
    logEntries = [];
    logPending = [];
    logUnseen = 0;
    logBootId = null;
    logLastSeq = 0;
    $('log-lines').textContent = '';
    startLogs();
  }

  function toggleLogFollow() {
    logFollowing = !logFollowing;
    var button = $('log-follow');
    button.textContent = logFollowing ? 'PAUSAR' : 'SEGUIR';
    button.setAttribute('aria-pressed', String(!logFollowing));
    logUnseen = 0;
    if (logFollowing) {
      setMsg($('log-msg'), logAbort ? 'En directo.' : '', '');
      flushLogs();
    } else {
      setMsg($('log-msg'), 'En pausa · 0 líneas nuevas sin mostrar.', '');
    }
  }

  /** Every kept line (not only the rendered ones) as a plain-text file. */
  function downloadLogs() {
    var text = logEntries.map(function (entry) { return logText(entry, true); }).join('\n') + '\n';
    var url = URL.createObjectURL(new Blob([text], { type: 'text/plain;charset=utf-8' }));
    var link = document.createElement('a');
    link.href = url;
    link.download = 'teachermovies-registros-' + logTime(Date.now(), true).replace(/[ :.]/g, '-') + '.txt';
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    setTimeout(function () { URL.revokeObjectURL(url); }, 1000);
  }

  // ---- tabs: DESCARGAS | REGISTROS ----------------------------------------------------------

  var currentTab = 'downloads';

  function selectTab(name) {
    currentTab = name;
    var logs = name === 'logs';
    $('view-downloads').hidden = logs;
    $('view-logs').hidden = !logs;
    $('tab-downloads').setAttribute('aria-selected', String(!logs));
    $('tab-logs').setAttribute('aria-selected', String(logs));
    if (logs) startLogs(); else stopLogs();
  }

  // ---- boot ----------------------------------------------------------------------------------

  $('pair-form').addEventListener('submit', pair);
  $('send').addEventListener('click', sendMagnet);
  $('upload').addEventListener('click', function () { $('file').click(); });
  $('file').addEventListener('change', uploadTorrent);
  $('tab-downloads').addEventListener('click', function () { selectTab('downloads'); });
  $('tab-logs').addEventListener('click', function () { selectTab('logs'); });
  $('log-level').addEventListener('change', changeLogLevel);
  $('log-follow').addEventListener('click', toggleLogFollow);
  $('log-download').addEventListener('click', downloadLogs);

  checkStatus();
  setInterval(checkStatus, STATUS_EVERY_MS);

  if (token()) showApp(); else showPairing();
})();
