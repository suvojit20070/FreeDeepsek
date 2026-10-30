/*
 * Free Deepsek - chat.deepseek.com ke andar inject hone wali script.
 *
 * Response DOM se nahi padha jata. Page ki apni completion request (fetch ya XHR)
 * ko hook karke uski SSE stream ki copy read hoti hai, aur clean chunks
 * FDSBridge (Java) ko bheje jaate hain. Isse UI badalne par bhi response
 * parsing nahi tootti. Selectors sirf input, send aur toggles ke liye hain.
 */
(function () {
  'use strict';
  if (window.__fds && window.__fds.__v) return;

  var VERSION = 1;
  var COMPLETION_RE = /\/chat\/completion(\?|$|#)/i;
  // activeId: jo request bhejni hai; req: id -> {headers, sid} (chat delete ke liye); del: delete status
  var S = { activeId: null, req: {}, del: {} };
  // File upload tracking
  var U = { expected: 0, done: 0, pending: 0, failed: '', last: 0, t0: 0, statuses: [] };

  function isFileUrl(u) { return /\/file/i.test(u || ''); }

  function noteFileResponse(text) {
    U.last = Date.now();
    try {
      var found = String(text || '').match(/"status"\s*:\s*"([A-Za-z_]+)"/g) || [];
      var st = found.map(function (x) { return x.replace(/.*"([A-Za-z_]+)"$/, '$1').toUpperCase(); });
      if (st.length) U.statuses = st;
      if (st.some(function (x) { return /FAIL|ERROR/.test(x); })) {
        var mm = /"(?:msg|error_msg|message)"\s*:\s*"([^"]*)"/.exec(text);
        U.failed = (mm && mm[1]) || 'upload failed';
      }
    } catch (e) { /* ignore */ }
  }

  function trackFilePromise(promise, url) {
    var isUpload = /upload/i.test(url);
    U.pending++;
    U.last = Date.now();
    promise.then(function (res) {
      res.clone().text().then(function (t) {
        noteFileResponse(t);
        if (!res.ok) U.failed = U.failed || ('HTTP ' + res.status);
        U.pending--;
        if (isUpload) U.done++;
      }, function () { U.pending--; if (isUpload) U.done++; });
    }, function (e) { U.pending--; U.failed = 'network: ' + e; });
  }

  function headersToObject(src) {
    var h = {};
    if (!src) return h;
    try {
      if (typeof Headers !== 'undefined' && src instanceof Headers) {
        src.forEach(function (v, k) { h[k] = v; });
      } else if (Array.isArray(src)) {
        src.forEach(function (p) { h[p[0]] = p[1]; });
      } else {
        for (var k in src) if (Object.prototype.hasOwnProperty.call(src, k)) h[k] = src[k];
      }
    } catch (e) { /* ignore */ }
    return h;
  }

  function sessionFromBody(body) {
    if (typeof body !== 'string') return '';
    try { return JSON.parse(body).chat_session_id || ''; } catch (e) { return ''; }
  }

  /* ---------------------------------------------------------------- bridge */

  function call(name) {
    var b = window.FDSBridge;
    if (!b || typeof b[name] !== 'function') return;
    var args = [];
    for (var i = 1; i < arguments.length; i++) {
      args.push(arguments[i] == null ? '' : String(arguments[i]));
    }
    try { b[name].apply(b, args); } catch (e) { /* ignore */ }
  }

  function log(m) { call('log', m); }

  // Pending request id ko ek hi baar claim karo (ek request = ek stream)
  function claim() {
    var id = S.activeId;
    if (!id) return null;
    S.activeId = null;
    call('onStart', id);
    return id;
  }

  /* ------------------------------------------------------------ SSE parser */
  /*
   * DeepSeek web ke alag-alag formats handle karta hai:
   *  1. OpenAI style:  {"choices":[{"delta":{"content":"..","type":"thinking"}}]}
   *  2. JSON-patch:    {"v":{"response":{"fragments":[{"type":"THINK","content":".."}]}}}
   *                    {"p":"response/fragments/-1/content","o":"APPEND","v":".."}
   *                    {"v":".."}  (pichhle path par append)
   *                    {"p":"response","o":"BATCH","v":[{"p":"status","v":"FINISHED"}]}
   *  3. Purana patch:  {"p":"response/thinking_content","v":".."} / "response/content"
   */
  function makeParser(id) {
    var buf = '', lastPath = '', frags = [], got = false, ended = false, errMsg = '', curEvent = '', fin = false;

    function fragKind(i) {
      if (i < 0) i = frags.length + i;
      var t = String(frags[i] || 'RESPONSE').toUpperCase();
      if (t.indexOf('THINK') >= 0) return 'think';
      if (t === 'RESPONSE' || t === 'TEXT' || t === 'ANSWER') return 'text';
      return 'skip';
    }

    function emit(kind, text) {
      if (!kind || kind === 'skip' || typeof text !== 'string' || !text) return;
      got = true;
      call('onChunk', id, kind, text);
    }

    function pathKind(p) {
      if (!p) return null;
      var m = /fragments\/(-?\d+)\/content$/.exec(p);
      if (m) return fragKind(parseInt(m[1], 10));
      if (/fragments$/.test(p)) return frags.length ? fragKind(-1) : 'text';
      if (/think/i.test(p)) return 'think';
      if (/(^|\/)content$/.test(p)) return 'text';
      return null;
    }

    function addFrags(arr) {
      for (var i = 0; i < arr.length; i++) {
        var f = arr[i] || {};
        frags.push(f.type || 'RESPONSE');
        emit(fragKind(-1), f.content);
      }
    }

    function respObj(r) {
      if (!r || typeof r !== 'object') return;
      if (Array.isArray(r.fragments)) addFrags(r.fragments);
      if (typeof r.thinking_content === 'string') emit('think', r.thinking_content);
      if (typeof r.content === 'string') emit('text', r.content);
      if (r.status && /FINISHED|DONE|COMPLETE/i.test(String(r.status))) fin = true;
    }

    function handle(o, base) {
      if (!o || typeof o !== 'object') return;

      if (Array.isArray(o.choices) && o.choices.length) {
        var d = o.choices[0].delta || o.choices[0].message || {};
        if (typeof d.reasoning_content === 'string') emit('think', d.reasoning_content);
        if (typeof d.content === 'string') emit(d.type === 'thinking' ? 'think' : 'text', d.content);
        if (o.choices[0].finish_reason) fin = true;
        return;
      }

      if (typeof o.code === 'number' && o.code !== 0 && (o.msg || o.message)) {
        errMsg = String(o.msg || o.message);
        return;
      }
      if (o.error) {
        errMsg = typeof o.error === 'string' ? o.error : JSON.stringify(o.error);
        return;
      }

      var p = null;
      if (typeof o.p === 'string') {
        p = base ? base + '/' + o.p : o.p;
        lastPath = p;
      }
      var v = o.v;

      if (o.o === 'BATCH' && Array.isArray(v)) {
        var saved = lastPath;
        for (var i = 0; i < v.length; i++) handle(v[i], p || '');
        lastPath = saved;
        return;
      }

      if (typeof v === 'string') {
        var path = p || lastPath;
        if (/status$/.test(path)) { if (/FINISHED|DONE|COMPLETE/i.test(v)) fin = true; return; }
        emit(pathKind(path), v);
        return;
      }

      if (Array.isArray(v)) {
        if (p && /fragments$/.test(p)) addFrags(v);
        return;
      }

      if (v && typeof v === 'object') {
        if (v.response) respObj(v.response);
        else if (p && /response$/.test(p)) respObj(v);
      }
    }

    function line(l) {
      if (l.indexOf('event:') === 0) {
        curEvent = l.slice(6).trim();
        if (curEvent === 'finish' || curEvent === 'close') fin = true;
        return;
      }
      if (l.indexOf('data:') !== 0) return;
      var d = l.slice(5).trim();
      if (!d || d === '[DONE]') return;
      var o;
      try { o = JSON.parse(d); } catch (e) { return; }
      if (curEvent === 'error') { errMsg = errMsg || d.slice(0, 300); return; }
      handle(o, '');
    }

    return {
      feed: function (t) {
        if (!t) return;
        buf += t;
        var i;
        while ((i = buf.indexOf('\n')) >= 0) {
          var l = buf.slice(0, i).replace(/\r$/, '');
          buf = buf.slice(i + 1);
          if (!l) { curEvent = ''; continue; }
          line(l);
        }
        if (fin && !ended) this.end('stop');
      },
      raw: function (t) {
        if (!t) return;
        try { handle(JSON.parse(t), ''); } catch (e) { errMsg = errMsg || String(t).slice(0, 300); }
      },
      end: function (reason, err) {
        if (ended) return;
        ended = true;
        if (buf.trim()) { line(buf.trim()); buf = ''; }
        if (err) errMsg = errMsg || err;
        call('onEnd', id, reason || 'stop', got ? '' : errMsg);
      }
    };
  }

  /* ------------------------------------------------------------- fetch hook */

  function isEventStream(res) {
    var ct = '';
    try { ct = (res.headers && res.headers.get('content-type')) || ''; } catch (e) { /* ignore */ }
    return ct.indexOf('event-stream') >= 0;
  }

  function pump(id, body) {
    var p = makeParser(id);
    var reader = body.getReader();
    var dec = new TextDecoder('utf-8');
    (function step() {
      reader.read().then(function (r) {
        if (r.done) { p.feed(dec.decode() + '\n'); p.end('stop'); return; }
        p.feed(dec.decode(r.value, { stream: true }));
        step();
      }).catch(function (e) { p.end('error', 'stream: ' + e); });
    })();
  }

  function handleFetchResponse(id, res) {
    if (!res.ok) {
      res.clone().text().then(function (t) {
        call('onEnd', id, 'error', 'HTTP ' + res.status + ': ' + String(t || '').slice(0, 300));
      }, function () { call('onEnd', id, 'error', 'HTTP ' + res.status); });
      return;
    }
    var c = res.clone();
    if (isEventStream(res) && c.body) { pump(id, c.body); return; }
    c.text().then(function (t) {
      var p = makeParser(id);
      if (String(t).indexOf('data:') >= 0) p.feed(t + '\n'); else p.raw(t);
      p.end('stop');
    }, function (e) { call('onEnd', id, 'error', String(e)); });
  }

  var origFetch = window.fetch;
  if (origFetch && !origFetch.__fds) {
    var hooked = function (input, init) {
      var url = '', method = 'GET';
      try {
        url = typeof input === 'string' ? input : ((input && input.url) || String(input));
        method = (init && init.method) || (input && input.method) || 'GET';
      } catch (e) { /* ignore */ }

      var promise = origFetch.apply(this, arguments);
      if (U.expected && isFileUrl(url)) trackFilePromise(promise, url);
      if (!S.activeId || String(method).toUpperCase() !== 'POST') return promise;

      var reqInfo = {
        headers: headersToObject((init && init.headers) || (input && input.headers)),
        sid: sessionFromBody(init && init.body)
      };
      var id = COMPLETION_RE.test(url) ? claim() : null;
      if (id) S.req[id] = reqInfo;
      return promise.then(function (res) {
        try {
          var myId = id;
          // Endpoint ka naam badal gaya ho toh bhi: koi bhi POST jo SSE de, woh hamara hai
          if (!myId && S.activeId && isEventStream(res)) {
            myId = claim();
            if (myId) S.req[myId] = reqInfo;
          }
          if (myId) handleFetchResponse(myId, res);
        } catch (e) { log('fetch hook: ' + e); }
        return res;
      }, function (err) {
        if (id) call('onEnd', id, 'error', 'network: ' + err);
        throw err;
      });
    };
    hooked.__fds = true;
    window.fetch = hooked;
  }

  /* --------------------------------------------------------------- XHR hook */

  var XP = XMLHttpRequest.prototype;
  if (!XP.open.__fds) {
    var XO = XP.open, XS = XP.send;
    var XH = XP.setRequestHeader;
    XP.open = function (m, u) {
      this.__fdsM = m;
      this.__fdsU = String(u);
      this.__fdsH = {};
      return XO.apply(this, arguments);
    };
    XP.open.__fds = true;
    XP.setRequestHeader = function (k, v) {
      try { if (this.__fdsH) this.__fdsH[k] = v; } catch (e) { /* ignore */ }
      return XH.apply(this, arguments);
    };
    XP.send = function (body) {
      var x = this;
      if (U.expected && isFileUrl(x.__fdsU)) {
        var isUpload = /upload/i.test(x.__fdsU);
        U.pending++;
        U.last = Date.now();
        x.addEventListener('loadend', function () {
          try { noteFileResponse(x.responseText); } catch (e) { U.last = Date.now(); }
          if (x.status >= 400 || x.status === 0) U.failed = U.failed || ('HTTP ' + x.status);
          U.pending--;
          if (isUpload) U.done++;
        });
      }
      if (S.activeId && String(x.__fdsM).toUpperCase() === 'POST' && COMPLETION_RE.test(x.__fdsU || '')) {
        var id = claim(), p = makeParser(id), off = 0;
        S.req[id] = { headers: x.__fdsH || {}, sid: sessionFromBody(body) };
        var pull = function () {
          try {
            var t = x.responseText || '';
            if (t.length > off) { p.feed(t.slice(off)); off = t.length; }
          } catch (e) { /* ignore */ }
        };
        x.addEventListener('progress', pull);
        x.addEventListener('load', function () {
          pull();
          p.feed('\n');
          try {
            var t = x.responseText || '';
            if (t && t.indexOf('data:') < 0) p.raw(t);
          } catch (e) { /* ignore */ }
          if (x.status >= 400) p.end('error', 'HTTP ' + x.status); else p.end('stop');
        });
        x.addEventListener('error', function () { p.end('error', 'xhr network error'); });
        x.addEventListener('abort', function () { p.end('abort', 'aborted'); });
      }
      return XS.apply(this, arguments);
    };
  }

  /* ------------------------------------------------------------ DOM helpers */

  function visible(el) {
    if (!el) return false;
    var r = el.getBoundingClientRect();
    return (r.width > 0 && r.height > 0) || el.offsetParent !== null;
  }

  function findInput() {
    var sels = ['textarea#chat-input', 'textarea[placeholder]', 'textarea',
      '[contenteditable="true"][role="textbox"]', 'div[contenteditable="true"]'];
    var fallback = null;
    for (var s = 0; s < sels.length; s++) {
      var list = document.querySelectorAll(sels[s]);
      for (var i = 0; i < list.length; i++) {
        var el = list[i];
        if (el.disabled || el.readOnly) continue;
        if (visible(el)) return el;
        if (!fallback) fallback = el;
      }
    }
    return fallback;
  }

  function inputText(el) {
    if (!el) return '';
    return el.value != null ? String(el.value) : String(el.innerText || '');
  }

  // React-safe value set: native setter, taaki React ka value tracker change dekhe
  function setValue(el, text) {
    el.focus();
    if (el.tagName === 'TEXTAREA' || el.tagName === 'INPUT') {
      var proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
      var desc = Object.getOwnPropertyDescriptor(proto, 'value');
      if (desc && desc.set) desc.set.call(el, text); else el.value = text;
      el.dispatchEvent(new Event('input', { bubbles: true }));
      el.dispatchEvent(new Event('change', { bubbles: true }));
    } else {
      document.execCommand('selectAll', false, null);
      document.execCommand('insertText', false, text);
      el.dispatchEvent(new InputEvent('input', { bubbles: true, data: text, inputType: 'insertText' }));
    }
    return inputText(el).length > 0;
  }

  function key(el, type) {
    var e = new KeyboardEvent(type, { key: 'Enter', code: 'Enter', bubbles: true, cancelable: true });
    try {
      Object.defineProperty(e, 'keyCode', { get: function () { return 13; } });
      Object.defineProperty(e, 'which', { get: function () { return 13; } });
    } catch (x) { /* ignore */ }
    el.dispatchEvent(e);
  }

  function pressEnter(el) {
    key(el, 'keydown');
    key(el, 'keypress');
    key(el, 'keyup');
  }

  function clickEl(el) {
    var opts = { bubbles: true, cancelable: true, view: window };
    var P = typeof PointerEvent === 'function' ? PointerEvent : MouseEvent;
    try {
      el.dispatchEvent(new P('pointerdown', opts));
      el.dispatchEvent(new MouseEvent('mousedown', opts));
      el.dispatchEvent(new P('pointerup', opts));
      el.dispatchEvent(new MouseEvent('mouseup', opts));
    } catch (e) { /* ignore */ }
    el.click();
  }

  // Input ke baad aane wala aakhri icon-only button = Send
  function findSend(input) {
    if (!input) return null;
    var n = input;
    for (var lvl = 0; lvl < 8; lvl++) {
      n = n.parentElement;
      if (!n) break;
      var list = n.querySelectorAll('button,[role="button"]');
      var best = null;
      for (var i = 0; i < list.length; i++) {
        var b = list[i];
        if ((b.innerText || '').trim().length > 0) continue;
        if (!b.querySelector('svg')) continue;
        if (!(input.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING)) continue;
        best = b;
      }
      if (best) return best;
    }
    return null;
  }

  function isDisabled(b) {
    if (b.disabled || b.getAttribute('aria-disabled') === 'true') return true;
    var cls = b.className;
    if (cls && typeof cls === 'object' && cls.baseVal != null) cls = cls.baseVal;
    return /disabled/i.test(String(cls || ''));
  }

  function clickSendIfPending() {
    var input = findInput();
    if (!input) return 'noinput';
    // Text abhi bhi box mein hai = message gaya nahi. Khaali hai toh mat dabao
    // (warna "Stop" button dab jayega).
    if (!inputText(input).trim()) return 'already-sent';
    var b = findSend(input);
    if (!b) return 'nosend';
    clickEl(b);
    return 'clicked';
  }

  /* ------------------------------------------------------- DeepThink/Search */

  var THINK_WORDS = ['deepthink', 'deep think', '深度思考'];
  var SEARCH_WORDS = ['search', '联网搜索', '智能搜索'];

  function findToggle(words, input) {
    var n = input;
    for (var lvl = 0; lvl < 8 && n; lvl++) {
      n = n.parentElement;
      if (!n) break;
      var list = n.querySelectorAll('button,[role="button"]');
      for (var i = 0; i < list.length; i++) {
        var t = (list[i].innerText || '').trim().toLowerCase();
        if (!t || t.length > 40) continue;
        for (var w = 0; w < words.length; w++) {
          if (t.indexOf(words[w]) >= 0) return list[i];
        }
      }
    }
    return null;
  }

  function isOn(b) {
    var a = b.getAttribute('aria-pressed') || b.getAttribute('aria-checked') ||
      b.getAttribute('data-state') || b.getAttribute('data-checked');
    if (a) return a === 'true' || a === 'on' || a === 'checked' || a === 'active';
    var cls = b.className;
    if (cls && typeof cls === 'object' && cls.baseVal != null) cls = cls.baseVal;
    return /(selected|active|checked|--on\b|is-on)/i.test(String(cls || ''));
  }

  function setModes(think, search) {
    var input = findInput();
    var res = {};
    var jobs = [[THINK_WORDS, !!think, 'think'], [SEARCH_WORDS, !!search, 'search']];
    for (var i = 0; i < jobs.length; i++) {
      var b = input ? findToggle(jobs[i][0], input) : null;
      if (!b) { res[jobs[i][2]] = 'missing'; continue; }
      if (isOn(b) !== jobs[i][1]) { clickEl(b); res[jobs[i][2]] = 'toggled'; }
      else res[jobs[i][2]] = 'ok';
    }
    return JSON.stringify(res);
  }

  /* ---------------------------------------------------------------- public */

  function ready() {
    var path = location.pathname || '';
    if (location.hostname.indexOf('deepseek.com') < 0) return 'other';
    if (/sign_in|sign_up|login|register/i.test(path)) return 'login';
    if (findInput()) return 'ready';
    return 'loading';
  }

  function lastAnswer() {
    var sels = ['.ds-markdown', '[class*="ds-markdown"]', '[class*="markdown"]'];
    for (var s = 0; s < sels.length; s++) {
      var l = document.querySelectorAll(sels[s]);
      if (l.length) return String(l[l.length - 1].innerText || '').trim();
    }
    return '';
  }

  window.__fds = {
    __v: VERSION,
    ready: ready,
    setModes: setModes,
    lastAnswer: lastAnswer,
    /* list = [{name, mime, data(base64)}] -> page ke file input mein daalo */
    attach: function (list) {
      var inputs = document.querySelectorAll('input[type="file"]');
      if (!inputs.length) return 'nofileinput';
      var dt = new DataTransfer();
      for (var i = 0; i < list.length; i++) {
        var f = list[i];
        var bin = atob(String(f.data || '').replace(/\s+/g, ''));
        var arr = new Uint8Array(bin.length);
        for (var j = 0; j < bin.length; j++) arr[j] = bin.charCodeAt(j);
        dt.items.add(new File([arr], f.name || ('file' + (i + 1)), { type: f.mime || 'application/octet-stream' }));
      }
      U.expected = list.length;
      U.done = 0;
      U.pending = 0;
      U.failed = '';
      U.last = 0;
      U.t0 = Date.now();
      U.statuses = [];
      var inp = inputs[0];
      inp.files = dt.files;
      inp.dispatchEvent(new Event('input', { bubbles: true }));
      inp.dispatchEvent(new Event('change', { bubbles: true }));
      return 'ok';
    },
    uploadState: function () {
      if (U.failed) return 'failed:' + U.failed;
      if (!U.last) return (Date.now() - U.t0 > 15000) ? 'unseen' : 'waiting:0/' + U.expected;
      var busy = U.statuses.some(function (s) { return /PENDING|PARSING|UPLOADING|PROCESSING|WAIT/.test(s); });
      if (U.done >= U.expected && U.pending <= 0 && !busy && Date.now() - U.last > 1200) return 'ready';
      return 'waiting:' + U.done + '/' + U.expected + (U.statuses.length ? ' ' + U.statuses.join(',') : '');
    },
    /* Response ke baad chat DeepSeek account se delete (page ki apni auth headers ke saath) */
    deleteChat: function (id) {
      var r = S.req[id] || {};
      delete S.req[id];
      var sid = r.sid;
      if (!sid) {
        var m = /\/chat\/s\/([0-9a-zA-Z-]{8,})/.exec(location.pathname);
        if (m) sid = m[1];
      }
      if (!sid) { S.del[id] = 'fail:no session id'; return 'nosession'; }
      var h = {}, src = r.headers || {};
      for (var k in src) {
        var lk = k.toLowerCase();
        if (lk.indexOf('pow') >= 0 || lk === 'content-length' || lk === 'accept') continue;
        h[k] = src[k];
      }
      var hasAuth = Object.keys(h).some(function (k) { return k.toLowerCase() === 'authorization'; });
      if (!hasAuth) {
        try {
          var t = JSON.parse(localStorage.getItem('userToken') || 'null');
          var tok = t && (t.value || t);
          if (typeof tok === 'string' && tok) h['authorization'] = 'Bearer ' + tok;
        } catch (e) { /* ignore */ }
      }
      h['content-type'] = 'application/json';
      S.del[id] = 'pending';
      origFetch.call(window, location.origin + '/api/v0/chat_session/delete', {
        method: 'POST', headers: h, credentials: 'include',
        body: JSON.stringify({ chat_session_id: sid })
      }).then(function (res) {
        return res.text().then(function (t) {
          var ok = res.ok;
          try { var j = JSON.parse(t); if (typeof j.code === 'number' && j.code !== 0) ok = false; } catch (e) { /* ignore */ }
          S.del[id] = ok ? 'ok' : 'fail:HTTP ' + res.status + ' ' + String(t).slice(0, 150);
        });
      }).catch(function (e) { S.del[id] = 'fail:' + e; });
      return 'started';
    },
    deleteState: function (id) { return S.del[id] || 'none'; },
    /* Khaali home page (naya chat) par hain? Pre-warm check */
    isFresh: function () {
      if (ready() !== 'ready') return 'no';
      if (/\/s\//.test(location.pathname)) return 'no';
      var input = findInput();
      return (input && !inputText(input).trim()) ? 'yes' : 'no';
    },
    /* Text daalo aur request id arm karo (bhejna submit() se) */
    fill: function (id, text) {
      var input = findInput();
      if (!input) return 'noinput';
      if (!setValue(input, text)) return 'setfail';
      U.expected = 0;
      S.activeId = id;
      return 'ok';
    },
    /* Pehle Send button click (DeepSeek synthetic Enter ignore karta hai), Enter sirf backup */
    submit: function (mode) {
      var input = findInput();
      if (!input) return 'noinput';
      if (mode !== 'enter') {
        var btn = findSend(input);
        if (btn && !isDisabled(btn)) { clickEl(btn); return 'clicked'; }
      }
      pressEnter(input);
      return 'enter';
    },
    send: function (id, text) {
      var r = this.fill(id, text);
      if (r === 'ok') this.submit();
      return r;
    },
    clickSend: clickSendIfPending,
    cancel: function () { S.activeId = null; return 'ok'; },
    stop: function () {
      // Generating ke time send button ki jagah stop button hota hai
      var input = findInput();
      if (input && !inputText(input).trim()) {
        var b = findSend(input);
        if (b) { clickEl(b); return 'stopped'; }
      }
      return 'none';
    }
  };

  log('inject v' + VERSION + ' @ ' + location.href);
})();
