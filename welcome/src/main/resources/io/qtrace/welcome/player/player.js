/*
 * qTrace provisioning player (docs/architecture/loader.md § 17).
 *
 *   Java → JS  the URL fragment: #<base64url JSON> = { host, content, state, goto }
 *              content: { title, who?, org?, slides: [...] }
 *              state:   { network: {name: ok}, device: {...}, install: {...} }
 *              goto:    { id, seq } — shown once per seq
 *              (read on load and on every hashchange: WebEngine.executeScript crashes QuPath 0.7)
 *   JS → Java  alert('qtrace:' + {action, arg})  caught by PlayerWindow, whitelisted in PlayerBridge
 *   Preview    qtracePlayer.load / emit / goto   used when opened in a plain browser
 *
 * Slide: { id, eyebrow?, title, text, image?, visual?, hidden?, module?, actions: [{ label, action, url?, primary? }] }
 *        module: { name, label?, version, icon, features: [..3], installing?, news? } — the module a
 *        'module' visual presents: its panel button and three points (news: what a version brings)
 * A hidden slide is not in the track: it is only reached by goto (e.g. the trunk's 'ready' after
 * Continue) and is shown as a final screen, without the bottom bar.
 * Drawn visuals: network, entry, device, install, module, report, done. Opened without QuPath (plain browser),
 * the player plays trunk.json and shows what QuPath would do.
 */
(function () {
  'use strict';

  var BROWSER_PREVIEW = {
    'sign-in': 'QuPath opens qtrace.ca to sign in, then waits for your certificate',
    'invite': 'QuPath opens qtrace.ca to create your account with this invitation, then waits for your certificate',
    'continue': 'QuPath closes this window: qTrace already records your work',
    'quit': 'QuPath quits; reopen it to finish',
    'open-url': 'QuPath opens this page in your browser',
    'open-player': 'QuPath opens the Player',
    'issue-report': 'QuPath opens Bug or Feature Request, which creates an issue for the team',
    'unlock-key': 'QuPath opens the passphrase dialog',
    'network-check': 'QuPath checks qtrace.ca and github.com',
    'close': 'QuPath closes this window: you can start working'
  };

  var $ = function (id) { return document.getElementById(id); };
  var content = null, cur = 0, seen = {};
  var state = { network: {}, device: { state: 'idle' }, install: { state: 'idle' }, modules: { state: 'idle' } };
  var networkAsked = false;

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"]/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c];
    });
  }

  // ── Talking to QuPath ─────────────────────────────────────────────────────
  function run(action, arg) {
    if (window.__qtraceHost) {
      window.alert('qtrace:' + JSON.stringify({ action: action, arg: arg == null ? '' : String(arg) }));
    } else {
      toast(BROWSER_PREVIEW[action] || action);
      simulate(action, arg);
    }
  }

  var toastT = 0;
  function toast(msg) {
    var t = $('toast');
    t.textContent = msg;
    t.classList.add('on');
    clearTimeout(toastT);
    toastT = setTimeout(function () { t.classList.remove('on'); }, 2800);
  }

  // ── Visuals ───────────────────────────────────────────────────────────────
  function networkHTML() {
    return '<div class="checks">' + ['qtrace.ca', 'github.com'].map(function (name) {
      var r = state.network[name];
      var cls = r == null ? 'wait' : r ? 'ok' : 'ko';
      var st = r == null ? 'checking…' : r ? 'reachable' : 'blocked: ask your IT team';
      return '<div class="check ' + cls + '"><span class="dot">' + (r == null ? '' : r ? '✓' : '!') +
        '</span><code>' + name + '</code><span class="st">' + st + '</span></div>';
    }).join('') + '</div>';
  }

  function entryHTML() {
    return '<div class="entry">' +
      '<form class="door" data-form="invite">' +
      '<label for="invite" class="lbl">Invitation code</label>' +
      '<input id="invite" name="invite" class="codein" placeholder="XXXX-XXXX-XXXX" autocomplete="off" spellcheck="false" maxlength="14">' +
      '<button type="submit" class="btn primary">Continue with this code</button>' +
      '<span class="hint" id="invite-hint">In the email from the qTrace team.</span></form>' +
      '<a class="other" data-act="sign-in" role="button" tabindex="0">I already have a qtrace.ca account →</a></div>';
  }

  function deviceHTML() {
    var d = state.device;
    var line = {
      idle: 'Enter your invitation code, or use your account, to start.',
      starting: 'Contacting qtrace.ca…',
      waiting: 'Waiting for you on qtrace.ca…',
      approved: 'Approved: your certificate is in QuPath.',
      failed: d.message || 'Not connected.'
    }[d.state] || '';
    var cls = d.state === 'approved' ? 'ok' : d.state === 'failed' ? 'ko' : 'wait';
    var retry = d.state === 'failed' ? '<button type="button" class="btn primary" data-act="goto-account">Try again</button>' : '';
    var reopen = d.state === 'waiting' ? '<button type="button" class="btn" data-act="open-url" data-url="' + esc(d.url || '') + '">Open the page again</button>' : '';
    return '<div class="device"><div class="code">' + esc(d.code || '····-····') + '</div>' +
      '<div class="flow"><b>QuPath</b><span>→</span><span>qtrace.ca</span><span>→</span><b>Authorize QuPath</b></div>' +
      '<div class="check ' + cls + '" style="width:100%;max-width:340px"><span class="dot">' +
      (d.state === 'approved' ? '✓' : d.state === 'failed' ? '!' : '') + '</span><span>' + esc(line) + '</span></div>' +
      (retry || reopen ? '<div class="actions">' + retry + reopen + '</div>' : '') + '</div>';
  }

  function installHTML() {
    var i = state.install;
    var rows = (i.modules || [{ name: 'qTrace Compliance' }, { name: 'Your welcome' }]).map(function (m) {
      var ok = i.state === 'done' || m.done;
      return '<div class="check ' + (ok ? 'ok' : 'wait') + '"><span class="dot">' + (ok ? '✓' : '') +
        '</span><span>' + esc(m.name) + '</span><span class="st">' + esc(m.version || '') + '</span></div>';
    }).join('');
    var pct = i.state === 'done' ? 100 : i.state === 'running' ? 55 : 0;
    return '<div class="device"><div class="checks">' +
      '<div class="check ' + (i.state === 'idle' ? 'wait' : 'ok') + '"><span class="dot">' + (i.state === 'idle' ? '' : '✓') +
      '</span><span>Certificate saved</span></div>' + rows +
      '</div><div class="progress"><i style="width:' + pct + '%"></i></div></div>';
  }

  // The icon of a module's panel button, by id — same 24-unit drawings as the QuPath panel
  // (QTracePanel.iconReplay / iconVersions, each module's .icon) and the BackOffice (ExtensionIcon.tsx).
  var S = 'fill="none" stroke="currentColor" stroke-linecap="round" stroke-linejoin="round"';
  var MODULE_ICONS = {
    player: '<path d="M6.6 5.4A8 8 0 1 0 15.3 18.4" ' + S + ' stroke-width="2.2"/>' +
      '<polygon points="13.4,4.1 16.7,5.6 15.7,9" fill="currentColor"/><polygon points="9.2,9.1 9.2,15.8 14.9,12.45" fill="currentColor"/>',
    versiongraph: '<line x1="7" y1="7.6" x2="7" y2="16.6" ' + S + ' stroke-width="1.7"/><path d="M17 10.4C17 14.6 7 12.2 7 16.6" ' + S + ' stroke-width="1.7"/>' +
      '<circle cx="7" cy="5.2" r="2.4" ' + S + ' stroke-width="1.7"/><circle cx="7" cy="19" r="2.4" ' + S + ' stroke-width="1.7"/><circle cx="17" cy="8" r="2.4" ' + S + ' stroke-width="1.7"/>',
    cohort: '<circle cx="7" cy="7" r="4.2" ' + S + ' stroke-width="1.6"/><circle cx="17" cy="7" r="4.2" ' + S + ' stroke-width="1.6"/>' +
      '<circle cx="7" cy="17" r="4.2" ' + S + ' stroke-width="1.6"/><circle cx="17" cy="17" r="4.2" ' + S + ' stroke-width="1.6"/>' +
      '<polyline points="5.2,7 6.5,8.4 8.9,5.6" ' + S + ' stroke-width="1.5"/><circle cx="17" cy="17" r="1.9" fill="currentColor"/>',
    library: '<path d="M12 8C9 6 5.5 6 3.5 7.2V17.6C5.5 16.4 9 16.4 12 18.4" ' + S + ' stroke-width="1.7"/>' +
      '<path d="M12 8C15 6 18.5 6 20.5 7.2V17.6C18.5 16.4 15 16.4 12 18.4" ' + S + ' stroke-width="1.7"/><line x1="12" y1="8" x2="12" y2="18.4" ' + S + ' stroke-width="1.7"/>',
    workfloweditor: '<line x1="6" y1="7.6" x2="6" y2="16.4" ' + S + ' stroke-width="1.7"/><circle cx="6" cy="5.2" r="2.4" ' + S + ' stroke-width="1.7"/>' +
      '<circle cx="6" cy="18.8" r="2.4" ' + S + ' stroke-width="1.7"/><polygon points="12.2,17.8 13.2,14.2 19.4,8 22,10.6 15.8,16.8" ' + S + ' stroke-width="1.6"/>' +
      '<line x1="17.9" y1="9.5" x2="20.5" y2="12.1" ' + S + ' stroke-width="1.4"/>',
    security: '<path d="M12 3L19 6V11C19 15.4 16 19 12 21C8 19 5 15.4 5 11V6Z" ' + S + ' stroke-width="1.7"/>' +
      '<circle cx="12" cy="10.4" r="1.9" ' + S + ' stroke-width="1.5"/><line x1="12" y1="12.3" x2="12" y2="15.4" ' + S + ' stroke-width="1.5"/>',
    compliance: '<path d="M12 3L19 6V11C19 15.4 16 19 12 21C8 19 5 15.4 5 11V6Z" ' + S + ' stroke-width="1.7"/>' +
      '<polyline points="8.8,11.8 11.2,14.2 15.4,9.4" ' + S + ' stroke-width="1.8"/>',
    training: '<circle cx="12" cy="10" r="4" ' + S + ' stroke-width="1.6"/><path d="M10.4 15H13.6M10.9 17.2H13.1" ' + S + ' stroke-width="1.5"/>' +
      '<path d="M12 3.2V4.6M5.6 10H7M17 10H18.4M7.4 5.4L8.4 6.4M16.6 5.4L15.6 6.4" ' + S + ' stroke-width="1.4"/>',
    upload: '<path d="M7 17.3A4 4 0 0 1 6.5 9.33A5.5 5.5 0 0 1 17.2 7.9A4.25 4.25 0 0 1 16.5 17.3Z" ' + S + ' stroke-width="1.7"/>' +
      '<line x1="12" y1="15" x2="12" y2="9" ' + S + ' stroke-width="1.7"/><polyline points="9.3,11.3 12,8.6 14.7,11.3" ' + S + ' stroke-width="1.7"/>',
    welcome: '<path d="M7.5 13V8.2M10.4 12V5M13.4 12V4.6M16.3 13V6.2" ' + S + ' stroke-width="2"/>' +
      '<path d="M7.5 13.5C7.5 12.6 6.5 11.8 5.6 12.4C4.8 13 5 14 5.6 14.9L8.3 19C9.2 20.3 10.4 21 12 21H13.4C15.7 21 17.5 19.3 17.8 17L18.2 13.6C18.3 12.6 17.5 12 16.6 12.4" ' + S + ' stroke-width="1.8"/>'
  };
  var GENERIC_ICON = '<circle cx="12" cy="12" r="7" ' + S + ' stroke-width="1.7"/><circle cx="12" cy="12" r="2.4" fill="currentColor"/>';

  // One module: its button as the panel shows it, the (at most) three points to say about it —
  // the essentials it brings, or what its new version brings — and, when Getting started is
  // installing it, where its download is.
  function moduleHTML(s) {
    var m = (s && s.module) || {};
    var st = state.modules.state;
    var line = m.news ? ''
      : !m.installing ? 'Installed'
      : st === 'done' ? 'Installed · starts the next time you open QuPath'
      : st === 'failed' ? 'Could not be downloaded — qTrace will offer it again'
      : 'Downloading…';
    var points = (m.features || []).slice(0, 3).map(function (f) {
      return '<li><span class="tick">✓</span><span>' + esc(f) + '</span></li>';
    }).join('');
    return '<div class="module-card"><div class="module-button"><svg viewBox="0 0 24 24" aria-hidden="true">' +
      (MODULE_ICONS[m.icon] || GENERIC_ICON) + '</svg><span>' + esc(m.label || m.name || s.title) + '</span></div>' +
      (points ? '<ul class="module-points">' + points + '</ul>' : '') +
      '<p class="module-line">' + (m.version ? '<span class="module-version">v' + esc(m.version) + '</span>' : '') + esc(line) + '</p></div>';
  }

  function reportHTML() {
    return '<div class="report"><div class="report-head"><span>Bug or Feature Request</span><span class="pill">Bug</span></div>' +
      '<div class="field">Replay stops on the second image</div>' +
      '<div class="field tall">Steps to reproduce, what you expected, what happened…</div>' +
      '<div class="report-foot"><span>Reported by <strong>' + esc((content && content.who) || 'you') + '</strong> · certified</span>' +
      '<span class="send">Send</span></div></div>';
  }

  function doneHTML() {
    return '<div class="done"><div class="big-check">✓</div>' +
      (content && content.who ? '<p>Certified for <strong>' + esc(content.who) + '</strong></p>' : '') + '</div>';
  }

  var VISUALS = { network: networkHTML, entry: entryHTML, device: deviceHTML, install: installHTML, module: moduleHTML, report: reportHTML, done: doneHTML };

  function visualHTML(s) {
    if (s.image) return '<img src="' + esc(s.image) + '" alt="' + esc(s.title) + '">';
    return VISUALS[s.visual] ? VISUALS[s.visual](s) : '';
  }

  function actionsHTML(s) {
    return (s.actions || []).map(function (a) {
      return '<button type="button" class="btn' + (a.primary ? ' primary' : '') + '" data-act="' + esc(a.action) +
        '" data-url="' + esc(a.url || '') + '">' + esc(a.label) + (a.action === 'open-url' ? ' ↗' : '') + '</button>';
    }).join('');
  }

  // ── Rendering ─────────────────────────────────────────────────────────────
  function build() {
    var stage = $('stage');
    Array.prototype.forEach.call(stage.querySelectorAll('.slide'), function (n) { n.remove(); });
    content.slides.forEach(function (s, i) {
      var el = document.createElement('section');
      el.className = 'slide';
      el.dataset.i = i;
      el.dataset.id = s.id;
      el.innerHTML = '<div class="visual">' + visualHTML(s) + '</div><div class="copy">' +
        (s.eyebrow ? '<div class="eyebrow">' + esc(s.eyebrow) + '</div>' : '') +
        '<h2>' + esc(s.title) + '</h2><p>' + esc(s.text) + '</p>' +
        '<div class="actions">' + actionsHTML(s) + '</div></div>';
      stage.insertBefore(el, $('toast'));
    });
    $('track').innerHTML = visible().map(function (i, k) {
      var s = content.slides[i];
      return '<button type="button" data-go="' + i + '" aria-label="Step ' + (k + 1) + ': ' + esc(s.title) + '"><i></i><span>' +
        esc(s.eyebrow || s.title) + '</span></button>';
    }).join('');
    document.title = content.title || 'qTrace';
    $('org').hidden = !content.org;
    $('org').textContent = content.org ? content.org.name : '';
    $('who').innerHTML = content.who ? 'Certified for <strong>' + esc(content.who) + '</strong>' : 'qTrace Core · no certificate yet';
    seen = {};
    go(0);
  }

  function refreshVisual(kind) {
    Array.prototype.forEach.call(document.querySelectorAll('.slide'), function (el) {
      var s = content.slides[+el.dataset.i];
      if (s.visual === kind) el.querySelector('.visual').innerHTML = visualHTML(s);
    });
  }

  function visible() {
    var out = [];
    content.slides.forEach(function (s, i) { if (!s.hidden) out.push(i); });
    return out;
  }
  function step(dir) {
    var v = visible(), k = v.indexOf(cur);
    if (k === -1) return;
    var n = v[k + dir];
    if (n != null) go(n);
  }

  function go(i) {
    if (!content) return;
    cur = Math.max(0, Math.min(content.slides.length - 1, i));
    var v = visible(), k = v.indexOf(cur);
    document.querySelector('.player').classList.toggle('final', k === -1);
    seen[cur] = true;
    Array.prototype.forEach.call(document.querySelectorAll('.slide'), function (n) {
      n.classList.toggle('on', +n.dataset.i === cur);
    });
    Array.prototype.forEach.call(document.querySelectorAll('#track button'), function (b) {
      var i = +b.dataset.go;
      b.classList.toggle('cur', i === cur);
      b.classList.toggle('seen', !!seen[i] && i !== cur);
    });
    $('count').textContent = k === -1 ? '' : (k + 1) + ' / ' + v.length;
    remember(cur);
    $('prev').disabled = k <= 0;
    $('next').disabled = k === -1 || k === v.length - 1;
    var s = content.slides[cur];
    if (s.visual === 'network' && !networkAsked && !Object.keys(state.network).length) { networkAsked = true; run('network-check'); }
  }

  // ── Events from QuPath ────────────────────────────────────────────────────
  function emit(event, data) {
    data = typeof data === 'string' ? JSON.parse(data) : (data || {});
    if (event === 'network') { state.network[data.name] = !!data.ok; refreshVisual('network'); }
    if (event === 'device') { state.device = data; refreshVisual('device'); }
    if (event === 'install') { state.install = data; refreshVisual('install'); }
    if (event === 'modules') { state.modules = data; refreshVisual('module'); }
  }

  function gotoId(id) {
    if (!content) return;
    for (var i = 0; i < content.slides.length; i++) if (content.slides[i].id === id) { go(i); return; }
  }

  // ── Browser preview: pretend to be QuPath ─────────────────────────────────
  function simulate(action) {
    if (action === 'network-check') {
      setTimeout(function () { emit('network', { name: 'qtrace.ca', ok: true }); }, 900);
      setTimeout(function () { emit('network', { name: 'github.com', ok: true }); }, 1500);
    }
    if (action === 'invite') action = 'sign-in';
    if (action === 'continue') gotoId('ready');
    if (action === 'sign-in') {
      gotoId('code');
      emit('device', { state: 'starting' });
      setTimeout(function () { emit('device', { state: 'waiting', code: 'EDW9-QJGL' }); }, 800);
      setTimeout(function () { emit('device', { state: 'approved', code: 'EDW9-QJGL' }); }, 4000);
      setTimeout(function () { gotoId('install'); emit('install', { state: 'running' }); }, 5000);
      setTimeout(function () { emit('install', { state: 'done' }); }, 7000);
    }
  }

  // ── Wiring ────────────────────────────────────────────────────────────────
  var stage = $('stage');
  stage.addEventListener('click', function (e) {
    var b = e.target.closest('[data-act]');
    if (!b) return;
    if (b.dataset.act === 'goto-account') { gotoId('account'); return; }
    run(b.dataset.act, b.dataset.url || '');
  });
  stage.addEventListener('submit', function (e) {
    var f = e.target.closest('[data-form=invite]');
    if (!f) return;
    e.preventDefault();
    var raw = f.invite.value.toUpperCase().replace(/[^A-Z0-9]/g, '');
    var hint = f.querySelector('#invite-hint');
    if (raw.length !== 12) {
      hint.textContent = 'An invitation code has 12 characters, like ABCD-EFGH-JKLM.';
      hint.classList.add('err');
      return;
    }
    f.invite.value = raw.match(/.{4}/g).join('-');
    run('invite', f.invite.value);
  });
  $('track').addEventListener('click', function (e) {
    var b = e.target.closest('[data-go]');
    if (b) go(+b.dataset.go);
  });
  $('prev').onclick = function () { step(-1); };
  $('next').onclick = function () { step(1); };
  // Skip leaves the tour. With a closing 'ready' slide (the trunk), that is the same exit as
  // "Not now": the host marks the tour done and shows it. Otherwise, the last slide of the track.
  $('skip').onclick = function () {
    if (content.slides.some(function (s) { return s.id === 'ready'; })) { run('continue'); return; }
    var v = visible(); go(v[v.length - 1]);
  };
  $('skip').onkeydown = function (e) { if (e.key === 'Enter') $('skip').click(); };
  document.addEventListener('keydown', function (e) {
    if (e.target.closest && e.target.closest('input, textarea')) return;
    if (e.key === 'Enter' && e.target.dataset && e.target.dataset.act) { e.target.click(); return; }
    if (e.key === 'ArrowRight') $('next').click();
    if (e.key === 'ArrowLeft') $('prev').click();
  });

  // ── The state sent by QuPath in the URL fragment ────────────────────────────
  function readHash() {
    var h = location.hash.slice(1);
    if (!h) return null;
    try {
      var b64 = h.replace(/-/g, '+').replace(/_/g, '/');
      while (b64.length % 4) b64 += '=';
      var bin = atob(b64), bytes = new Uint8Array(bin.length);
      for (var i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
      return JSON.parse(new TextDecoder().decode(bytes));
    } catch (e) {
      return null;
    }
  }

  var lastContent = null, lastGoto = 0;
  function remember(i) { try { sessionStorage.setItem('qtrace.slide', String(i)); } catch (e) { /* no storage */ } }
  function recall() { try { return +(sessionStorage.getItem('qtrace.slide') || 0); } catch (e) { return 0; } }

  function applyHash() {
    var m = readHash();
    if (!m || !m.content) return;
    if (m.host) window.__qtraceHost = true;
    var st = m.state || {};
    state.network = st.network || {};
    state.device = st.device || { state: 'idle' };
    state.install = st.install || { state: 'idle' };
    state.modules = st.modules || { state: 'idle' };
    var cs = JSON.stringify(m.content);
    if (cs !== lastContent) {
      lastContent = cs;
      content = m.content;
      var keep = recall(); // same page reloaded instead of a fragment change: keep the slide
      build();
      if (keep) go(keep);
    } else {
      ['network', 'device', 'install', 'module'].forEach(refreshVisual);
    }
    if (m.goto && m.goto.seq > lastGoto) { lastGoto = m.goto.seq; gotoId(m.goto.id); }
  }
  window.addEventListener('hashchange', applyHash);

  window.qtracePlayer = {
    load: function (json) {
      content = typeof json === 'string' ? JSON.parse(json) : json;
      build();
    },
    emit: emit,
    goto: gotoId
  };

  // In QuPath: the fragment carries everything. In a plain browser: play the embedded trunk.
  applyHash();
  setTimeout(function () {
    if (content || window.__qtraceHost) return;
    fetch('trunk.json').then(function (r) { return r.json(); }).then(window.qtracePlayer.load)
      .catch(function () { /* served without trunk.json: nothing to preview */ });
  }, 600);
})();
