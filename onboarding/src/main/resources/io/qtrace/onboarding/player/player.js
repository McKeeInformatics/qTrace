/*
 * qTrace onboarding player (docs/architecture/loader.md § 17).
 *
 *   Java → JS  the URL fragment: #<base64url JSON> = { host, content, state, goto }
 *              content: { title, who?, org?, slides: [...] }
 *              state:   { network: {name: ok}, device: {...}, install: {...}, invite: {state} }
 *              goto:    { id, seq } — shown once per seq
 *              (read on load and on every hashchange: WebEngine.executeScript crashes QuPath 0.7)
 *   JS → Java  alert('qtrace:' + {action, arg})  caught by PlayerWindow, whitelisted in PlayerBridge
 *   Preview    qtracePlayer.load / emit / goto   used when opened in a plain browser
 *
 * Slide: { id, eyebrow?, title, text, image?, visual?, actions: [{ label, action, url?, primary? }] }
 * Drawn visuals: network, entry, device, install, report. Opened without QuPath (plain browser),
 * the player plays trunk.json and shows what QuPath would do.
 */
(function () {
  'use strict';

  var DWELL = 7000;
  var BROWSER_PREVIEW = {
    'sign-in': 'QuPath opens qtrace.ca to sign in, then waits for your certificate',
    'invite': 'QuPath opens qtrace.ca with your invitation code filled in',
    'continue': 'QuPath closes this window: qTrace already records your work',
    'quit': 'QuPath quits; reopen it to finish',
    'open-url': 'QuPath opens this page in your browser',
    'open-player': 'QuPath opens the Player',
    'issue-report': 'QuPath opens Bug or Feature Request, which creates an issue for the team',
    'unlock-key': 'QuPath opens the passphrase dialog',
    'network-check': 'QuPath checks qtrace.ca and github.com'
  };

  var $ = function (id) { return document.getElementById(id); };
  var content = null, cur = 0, playing = false, started = 0, raf = 0, seen = {};
  var state = { network: {}, device: { state: 'idle' }, install: { state: 'idle' }, invite: null };
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
    var hint = state.invite === 'opened'
      ? 'Finish creating your account in your browser, then click Sign in below.'
      : 'The code the qTrace team sent you.';
    return '<div class="entry">' +
      '<form class="door" data-form="invite">' +
      '<label for="invite" class="lbl">Invitation code</label>' +
      '<input id="invite" name="invite" class="codein" placeholder="XXXX-XXXX-XXXX" autocomplete="off" spellcheck="false" maxlength="14">' +
      '<button type="submit" class="btn primary">Use this code</button>' +
      '<span class="hint" id="invite-hint">' + esc(hint) + '</span></form>' +
      '<div class="door"><span class="lbl">Already have an account?</span>' +
      '<button type="button" class="btn" data-act="sign-in">Sign in to qtrace.ca</button></div></div>';
  }

  function deviceHTML() {
    var d = state.device;
    var line = {
      idle: 'Click Sign in to get your code.',
      starting: 'Contacting qtrace.ca…',
      waiting: 'Waiting for your approval in the browser…',
      approved: 'Approved: your certificate is in QuPath.',
      failed: d.message || 'Not connected.'
    }[d.state] || '';
    var cls = d.state === 'approved' ? 'ok' : d.state === 'failed' ? 'ko' : 'wait';
    var retry = d.state === 'failed' ? '<button type="button" class="btn primary" data-act="sign-in">Try again</button>' : '';
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

  function reportHTML() {
    return '<div class="report"><div class="report-head"><span>Bug or Feature Request</span><span class="pill">Bug</span></div>' +
      '<div class="field">Replay stops on the second image</div>' +
      '<div class="field tall">Steps to reproduce, what you expected, what happened…</div>' +
      '<div class="report-foot"><span>Reported by <strong>' + esc((content && content.who) || 'you') + '</strong> · certified</span>' +
      '<span class="send">Send</span></div></div>';
  }

  var VISUALS = { network: networkHTML, entry: entryHTML, device: deviceHTML, install: installHTML, report: reportHTML };

  function visualHTML(s) {
    if (s.image) return '<img src="' + esc(s.image) + '" alt="' + esc(s.title) + '">';
    return VISUALS[s.visual] ? VISUALS[s.visual]() : '';
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
    $('track').innerHTML = content.slides.map(function (s, i) {
      return '<button type="button" data-go="' + i + '" aria-label="Step ' + (i + 1) + ': ' + esc(s.title) + '"><i></i><span>' +
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

  function go(i) {
    if (!content) return;
    cur = Math.max(0, Math.min(content.slides.length - 1, i));
    seen[cur] = true;
    Array.prototype.forEach.call(document.querySelectorAll('.slide'), function (n) {
      n.classList.toggle('on', +n.dataset.i === cur);
    });
    Array.prototype.forEach.call(document.querySelectorAll('#track button'), function (b, k) {
      b.classList.toggle('cur', k === cur);
      b.classList.toggle('seen', !!seen[k] && k !== cur);
      b.querySelector('i').style.setProperty('--fill', k === cur ? '0%' : '');
    });
    $('count').textContent = (cur + 1) + ' / ' + content.slides.length;
    remember(cur);
    $('prev').disabled = cur === 0;
    $('next').disabled = cur === content.slides.length - 1;
    var s = content.slides[cur];
    if (s.visual === 'network' && !networkAsked && !Object.keys(state.network).length) { networkAsked = true; run('network-check'); }
    started = performance.now();
  }

  function tick(now) {
    if (!playing) return;
    var p = Math.min(1, (now - started) / DWELL);
    var b = document.querySelector('#track button.cur i');
    if (b) b.style.setProperty('--fill', (p * 100).toFixed(1) + '%');
    if (p >= 1) {
      if (cur < content.slides.length - 1) go(cur + 1); else setPlaying(false);
    }
    raf = requestAnimationFrame(tick);
  }

  function setPlaying(on) {
    playing = on;
    $('play').textContent = on ? '❚❚' : '▶';
    $('play').setAttribute('aria-label', on ? 'Pause' : 'Play');
    cancelAnimationFrame(raf);
    if (on) { started = performance.now(); raf = requestAnimationFrame(tick); }
    else { var b = document.querySelector('#track button.cur i'); if (b) b.style.setProperty('--fill', '0%'); }
  }

  // ── Events from QuPath ────────────────────────────────────────────────────
  function emit(event, data) {
    data = typeof data === 'string' ? JSON.parse(data) : (data || {});
    if (event === 'network') { state.network[data.name] = !!data.ok; refreshVisual('network'); }
    if (event === 'device') { state.device = data; refreshVisual('device'); }
    if (event === 'install') { state.install = data; refreshVisual('install'); }
    if (event === 'invite') { state.invite = data.state; refreshVisual('entry'); }
  }

  function gotoId(id) {
    if (!content) return;
    for (var i = 0; i < content.slides.length; i++) if (content.slides[i].id === id) { setPlaying(false); go(i); return; }
  }

  // ── Browser preview: pretend to be QuPath ─────────────────────────────────
  function simulate(action) {
    if (action === 'network-check') {
      setTimeout(function () { emit('network', { name: 'qtrace.ca', ok: true }); }, 900);
      setTimeout(function () { emit('network', { name: 'github.com', ok: true }); }, 1500);
    }
    if (action === 'invite') emit('invite', { state: 'opened' });
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
    if (b) run(b.dataset.act, b.dataset.url || '');
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
    if (b) { go(+b.dataset.go); if (playing) setPlaying(true); }
  });
  $('prev').onclick = function () { go(cur - 1); if (playing) setPlaying(true); };
  $('next').onclick = function () { go(cur + 1); if (playing) setPlaying(true); };
  $('play').onclick = function () { setPlaying(!playing); };
  $('skip').onclick = function () { setPlaying(false); go(content.slides.length - 1); };
  $('skip').onkeydown = function (e) { if (e.key === 'Enter') $('skip').click(); };
  document.addEventListener('keydown', function (e) {
    if (e.target.closest && e.target.closest('input, textarea')) return;
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
    state.invite = st.invite ? st.invite.state : null;
    var cs = JSON.stringify(m.content);
    if (cs !== lastContent) {
      lastContent = cs;
      content = m.content;
      var keep = recall(); // same page reloaded instead of a fragment change: keep the slide
      build();
      if (keep) go(keep);
    } else {
      ['network', 'entry', 'device', 'install'].forEach(refreshVisual);
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
