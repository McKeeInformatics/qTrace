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
 * Slide: { id, eyebrow?, title, text, image?, visual?, hidden?, module?, account?, actions: [{ label, action, url?, primary? }] }
 *        account: { title?, text? } — what the slide says instead once QuPath is signed in to a basic
 *        account (device state 'account': no certificate; the text gets "Signed in as <name>." in front)
 *        module: { name, label?, version, icon, features: [..3], installing?, news? } — the module a
 *        'module' visual presents: its panel button and three points (news: what a version brings)
 * A hidden slide is not in the track: it is only reached by goto (e.g. the trunk's 'ready' after
 * Continue) and is shown as a final screen, without the bottom bar.
 * Drawn visuals: hello, capture, panel, network, entry, device, install, module, report, done.
 * tour: { image, width, height, bar, cx, buttons: [{ key, y, title, text }] } — what the 'panel' visual plays;
 *        each button is sent to QuPath as 'tour-highlight' (its key) while its bubble shows. Opened without QuPath (plain browser),
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
    'close': 'QuPath closes this window: you can start working',
    'tour-highlight': 'QuPath outlines this tool in the panel'
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
    } else if (action !== 'tour-highlight') {
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

  // Sign in comes first: anyone with a qtrace.ca account can. The invitation code is the way
  // to an identity certificate, for those who received one.
  function entryHTML() {
    return '<div class="entry"><form class="door" data-form="invite">' +
      '<label for="invite" class="lbl">Invitation code</label>' +
      '<input id="invite" name="invite" class="codein" placeholder="XXXX-XXXX-XXXX" autocomplete="off" spellcheck="false" maxlength="14">' +
      '<button type="submit" class="btn">Continue with this code</button>' +
      '<span class="hint" id="invite-hint">In the email from the qTrace team: it brings your identity certificate.</span></form></div>';
  }

  // Signed in to a basic account (no certificate): the device state 'account' carries the name.
  function isAccount() { return state.device && state.device.state === 'account'; }
  function signedInLine() {
    var name = (state.device && state.device.name) || '';
    return 'Signed in' + (name ? ' as ' + name : '') + '.';
  }

  function deviceHTML() {
    var d = state.device;
    var line = {
      idle: 'Sign in, or enter your invitation code, to start.',
      starting: 'Contacting qtrace.ca…',
      waiting: 'Waiting for you on qtrace.ca…',
      approved: 'Approved: your certificate is in QuPath.',
      account: signedInLine() + ' Records you upload are not certified.',
      failed: d.message || 'Not connected.'
    }[d.state] || '';
    var cls = d.state === 'approved' || d.state === 'account' ? 'ok' : d.state === 'failed' ? 'ko' : 'wait';
    var retry = d.state === 'failed' ? '<button type="button" class="btn primary" data-act="goto-account">Try again</button>' : '';
    var reopen = d.state === 'waiting' ? '<button type="button" class="btn" data-act="open-url" data-url="' + esc(d.url || '') + '">Open the page again</button>' : '';
    return '<div class="device"><div class="code">' + esc(d.code || '····-····') + '</div>' +
      '<div class="flow"><b>QuPath</b><span>→</span><span>qtrace.ca</span><span>→</span><b>Authorize QuPath</b></div>' +
      '<div class="check ' + cls + '" style="width:100%;max-width:340px"><span class="dot">' +
      (d.state === 'approved' || d.state === 'account' ? '✓' : d.state === 'failed' ? '!' : '') + '</span><span>' + esc(line) + '</span></div>' +
      (retry || reopen ? '<div class="actions">' + retry + reopen + '</div>' : '') + '</div>';
  }

  function installHTML() {
    var i = state.install;
    var account = isAccount();
    var mods = i.modules && i.modules.length ? i.modules : null;
    // Each module of the installation, with how far its download is; before the list arrives, what is known.
    var rows = (mods || (account ? [{ name: 'The modules of your account' }]
                                  : [{ name: 'qTrace Compliance' }, { name: 'Your welcome' }])).map(function (m) {
      var ok = i.state === 'done' ? !m.failed : !!m.done;
      var ko = !!m.failed;
      var pct = ok ? 100 : Math.max(0, Math.min(100, m.pct || 0));
      var note = ko ? 'failed' : ok ? (m.version || '') : mods && m.pct ? pct + ' %' : (m.version || '');
      return '<div class="check ' + (ko ? 'ko' : ok ? 'ok' : 'wait') + '"><span class="dot">' + (ok ? '✓' : ko ? '!' : '') +
        '</span><span>' + esc(m.name) + '</span><span class="st">' + esc(note) + '</span>' +
        (mods ? '<span class="mbar"><i style="width:' + pct + '%"></i></span>' : '') + '</div>';
    }).join('');
    var total = mods ? Math.round(mods.reduce(function (s, m) { return s + (i.state === 'done' || m.done ? 100 : (m.pct || 0)); }, 0) / mods.length)
                     : (i.state === 'done' ? 100 : i.state === 'running' ? 55 : 0);
    // An update installs what the user accepted: no certificate nor account behind it.
    var first = content && content.kind === 'update' ? '' :
      '<div class="check ' + (i.state === 'idle' ? 'wait' : 'ok') + '"><span class="dot">' + (i.state === 'idle' ? '' : '✓') +
      '</span><span>' + (account ? esc(signedInLine()) : 'Certificate saved') + '</span></div>';
    return '<div class="device"><div class="checks' + (mods && mods.length > 3 ? ' cols' : '') + '">' + first + rows +
      '</div><div class="progress"><i style="width:' + total + '%"></i></div></div>';
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
    dashboard: '<rect x="2" y="3" width="16" height="14" rx="1.5" ' + S + ' stroke-width="1.5"/>' +
      '<path d="M5.2 6.4H9.6M5.2 9H8.4M5.2 11.6H9.1M5.6 13.6V15M8 12.4V15M10.4 13.1V15" ' + S + ' stroke-width="1.5"/>' +
      '<polyline points="12,10.6 13.8,8.9 15.3,10.1 17.3,7.9" ' + S + ' stroke-width="1.5"/><circle cx="17.2" cy="6.1" r="1.15" fill="currentColor"/>' +
      '<circle cx="16.5" cy="16.5" r="5" ' + S + ' stroke-width="1.4"/><circle cx="16.5" cy="16.5" r="2.7" ' + S + ' stroke-width="1.4"/>' +
      '<circle cx="16.5" cy="16.5" r="0.9" fill="currentColor"/>',
    upload: '<path d="M7 17.3A4 4 0 0 1 6.5 9.33A5.5 5.5 0 0 1 17.2 7.9A4.25 4.25 0 0 1 16.5 17.3Z" ' + S + ' stroke-width="1.7"/>' +
      '<line x1="12" y1="15" x2="12" y2="9" ' + S + ' stroke-width="1.7"/><polyline points="9.3,11.3 12,8.6 14.7,11.3" ' + S + ' stroke-width="1.7"/>',
    import: '<circle cx="12" cy="12" r="9.3" ' + S + ' stroke-width="1.7"/><line x1="12" y1="7.2" x2="12" y2="14.2" ' + S + ' stroke-width="1.7"/>' +
      '<polyline points="8.6,11.2 12,14.6 15.4,11.2" ' + S + ' stroke-width="1.7"/><line x1="8" y1="16.6" x2="16" y2="16.6" ' + S + ' stroke-width="1.7"/>',
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

  // The panel's own icon (QTraceMiniPanel.reportIcon): a speech bubble with an exclamation mark.
  var REPORT_ICON = '<svg viewBox="0 0 16 16" width="22" height="22" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linejoin="round" aria-hidden="true">' +
    '<path d="M3 2H13Q15 2 15 4V9Q15 11 13 11H7.5L4.5 14V11H3Q1 11 1 9V4Q1 2 3 2Z"/>' +
    '<path d="M8 4.3V7" stroke-width="1.4" stroke-linecap="round"/><circle cx="8" cy="9" r="0.8" fill="currentColor" stroke="none"/></svg>';

  function reportHTML() {
    return '<div class="report-wrap"><div class="report"><div class="report-head"><span>Bug or Feature Request</span><span class="pill">Bug</span></div>' +
      '<div class="field">Replay stops on the second image</div>' +
      '<div class="field tall">Steps to reproduce, what you expected, what happened…</div>' +
      '<div class="report-foot"><span>Reported by <strong>' + esc((content && (content.who || content.account)) || 'you') + '</strong>' +
        (content && content.who ? ' · certified' : content && content.account ? ' · not certified' : '') + '</span>' +
      '<span class="send">Send</span></div></div>' +
      // The panel's button, here to click: the same message box, always one click away.
      '<div class="report-hint"><span class="report-open" data-act="issue-report" role="button" tabindex="0" title="Report a bug or a feature request">' +
      REPORT_ICON + '</span><span>This button, in the panel, opens it any time.</span></div></div>';
  }

  function doneHTML() {
    return '<div class="done"><div class="big-check">✓</div>' +
      (content && content.who ? '<p>Certified for <strong>' + esc(content.who) + '</strong></p>' : '') + '</div>';
  }

  // ── The tour's drawn visuals: hello, capture (Core's flow), panel (the mini-panel) ───────────
  function helloHTML() {
    return '<div class="hello"><div class="hello-mark">q<b>Trace</b></div><ul class="hello-points">' +
      '<li><span class="tick">●</span><span>Records each step you take in QuPath</span></li>' +
      '<li><span class="tick">●</span><span>Replays it on new images</span></li>' +
      '<li><span class="tick">●</span><span>Signs it with your identity</span></li></ul></div>';
  }

  var CS = 'fill="none" stroke="currentColor" stroke-width="1.4" stroke-linejoin="round" stroke-linecap="round"';
  // What flows into a record, in the order QuPath produces it (the last one is a person's, not a tool's).
  var CAPTURE_ROWS = [
    { k: 'annotations', t: 'Annotation', s: 'ROIs · author tracked', i: '<path d="M5 16v3.5h3.5L19 9l-4-4z"/><path d="M15 5l2.5-2.5a1.6 1.6 0 012.3 0 1.6 1.6 0 010 2.3L17.5 7"/>' },
    { k: 'cells', t: 'Cell detection', s: 'StarDist · parameters', i: '<circle cx="12" cy="12" r="10" stroke-dasharray="3 2"/><circle cx="8" cy="9" r="2.5"/><circle cx="15" cy="8" r="2"/><circle cx="10" cy="15" r="3"/><circle cx="17" cy="15" r="2"/>' },
    { k: 'transform', t: 'Image alignment', s: 'affine · transform matrix', i: '<circle cx="12" cy="12" r="10"/><path d="M2 12h20"/><path d="M5 12.5l4-2h6l4 2"/><path d="M12 2v4M12 18v4"/>' },
    { k: 'classifier', t: 'Pixel classifier', s: 'SHA-256 · git hash · fidelity', i: '<rect x="2" y="2" width="9" height="9" rx="1.5"/><rect x="13" y="2" width="9" height="9" rx="1.5"/><rect x="2" y="13" width="9" height="9" rx="1.5"/><rect x="13" y="13" width="9" height="9" rx="1.5"/>' },
    { k: 'cell_types', t: 'Cell typing', s: 'applied_at + by', i: '<path d="M3 7h11l6 5-6 5H3z"/><circle cx="7.5" cy="12" r="1.8" fill="currentColor"/>' },
    { k: 'validation', t: 'Validation stamp', s: 'validator · scope · confidence', human: true, i: '<circle cx="12" cy="12" r="10"/><path d="M7 12l3 3 7-6"/>' }
  ];
  var CAPTURE_LINES = [['steps_captured', '"steps_captured":'], ['annotations', '"annotations":'], ['cells', '"cells":'],
    ['classifier', '"classifier":'], ['sha256', '"sha256":'], ['validation', '"validation":']];

  function captureHTML() {
    var rows = CAPTURE_ROWS.map(function (r) {
      return '<div class="cap-row' + (r.human ? ' human' : '') + '" data-k="' + r.k + '"><svg viewBox="0 0 24 24" ' + CS + '>' + r.i + '</svg>' +
        '<div><b>' + r.t + (r.human ? '<span class="who">HUMAN</span>' : '') + '</b><small>' + r.s + '</small></div>' +
        '<span class="cap-rec"><i></i>REC</span></div>';
    }).join('');
    var lines = CAPTURE_LINES.map(function (l) {
      return '<div class="cap-ln" data-l="' + l[0] + '"><span class="k">' + l[1] + '</span><span class="v"></span></div>';
    }).join('');
    return '<div class="cap"><div class="cap-top"><span>QuPath workflow · glioma TMA</span><span class="cap-live">Live capture</span></div>' +
      '<div class="cap-body"><svg class="cap-links" aria-hidden="true"></svg><div class="cap-rows">' + rows + '</div><div class="cap-gap"></div>' +
      '<div class="cap-right"><div class="cap-node"><span class="lg">qTrace</span><p>listens to QuPath, writes the record</p></div>' +
      '<div class="cap-down">↓ 1 record per image</div>' +
      '<div class="cap-pass"><div class="fn">TMA_Core_07.qtrace</div>' + lines + '</div></div></div>' +
      '<a class="mp-again" role="button" tabindex="0" data-act="tour-again" title="Play again">↻</a></div>';
  }

  function panelHTML(s) {
    var t = s.tour || {};
    return '<div class="mp"><div class="mp-cam" style="width:' + t.width + 'px;height:' + t.height + 'px">' +
      '<img src="' + esc(t.image) + '" alt="QuPath with the qTrace mini-panel" width="' + t.width + '" height="' + t.height + '"></div>' +
      '<div class="mp-top"><div class="mp-ring"></div><div class="mp-bubble"><b></b><span></span></div></div>' +
      '<a class="mp-again" role="button" tabindex="0" data-act="tour-again" title="Play again">↻</a></div>';
  }

  // ── Animations, started when their slide shows and stopped when it leaves ──────────────────
  var timers = [], runId = 0;
  var REDUCED = window.matchMedia && matchMedia('(prefers-reduced-motion: reduce)').matches;
  function later(fn, ms) { timers.push(setTimeout(fn, ms)); }
  function stopVisual() {
    runId++;
    timers.forEach(clearTimeout);
    timers = [];
  }
  function startVisual(i) {
    var el = document.querySelector('.slide[data-i="' + i + '"]');
    var s = content.slides[i];
    if (!el || !s) return;
    var id = ++runId;
    // The slide is only laid out once it is displayed: measure on the next frame.
    later(function () {
      if (id !== runId) return;
      if (s.visual === 'capture') playCapture(el, id);
      if (s.visual === 'panel') playPanel(el, s, id);
    }, 80);
  }

  var SVGNS = 'http://www.w3.org/2000/svg';
  function playCapture(el, id) {
    var root = el.querySelector('.cap');
    if (!root) return;
    var body = root.querySelector('.cap-body'), svg = root.querySelector('.cap-links'),
        rows = [].slice.call(root.querySelectorAll('.cap-row')), node = root.querySelector('.cap-node'),
        live = root.querySelector('.cap-live'), paths = [];
    function line(k) { return root.querySelector('.cap-ln[data-l="' + k + '"]'); }
    function set(k, v, cls) {
      var l = line(k);
      l.querySelector('.v').textContent = v;
      l.className = 'cap-ln' + (cls ? ' ' + cls : '');
      void l.offsetWidth;
      l.classList.add('flash');
      later(function () { l.classList.remove('flash'); }, 90);
    }
    function hex() { var h = '0123456789abcdef', o = ''; for (var n = 0; n < 6; n++) o += h[Math.floor(Math.random() * 16)]; return o; }
    function geom() {
      svg.innerHTML = '';
      paths = [];
      var b = body.getBoundingClientRect(), n = node.getBoundingClientRect(), hx = n.left - b.left, hy = n.top - b.top + n.height / 2;
      rows.forEach(function (r) {
        var rr = r.getBoundingClientRect(), x = rr.right - b.left, y = rr.top - b.top + rr.height / 2, mx = (x + hx) / 2;
        var p = document.createElementNS(SVGNS, 'path');
        p.setAttribute('d', 'M' + x + ',' + y + ' C' + mx + ',' + y + ' ' + mx + ',' + hy + ' ' + hx + ',' + hy);
        svg.appendChild(p);
        paths.push(p);
        var o = document.createElementNS(SVGNS, 'circle');
        o.setAttribute('cx', x); o.setAttribute('cy', y); o.setAttribute('r', 3); o.setAttribute('class', 'start');
        svg.appendChild(o);
      });
      var hub = document.createElementNS(SVGNS, 'circle');
      hub.setAttribute('class', 'hub'); hub.setAttribute('cx', hx); hub.setAttribute('cy', hy); hub.setAttribute('r', 4.5);
      svg.appendChild(hub);
    }
    var st, i;
    function reset() {
      st = { steps: 22, ann: 41 };
      i = -1;
      root.classList.remove('over');
      rows.forEach(function (r) { r.classList.remove('on', 'done'); });
      live.textContent = 'Live capture';
      live.classList.remove('stamped');
      set('steps_captured', st.steps + ',');
      set('annotations', st.ann + ',');
      set('cells', 'null,', 'pend');
      set('classifier', 'null,', 'pend');
      set('sha256', '"' + hex() + '…' + hex() + '",');
      set('validation', 'pending', 'pend');
    }
    function apply(k) {
      node.classList.add('hit');
      later(function () { node.classList.remove('hit'); }, 450);
      st.steps += k === 'annotations' ? 3 : 1;
      set('steps_captured', st.steps + ',');
      set('sha256', '"' + hex() + '…' + hex() + '",');
      if (k === 'annotations') { st.ann += 6; set('annotations', st.ann + ','); }
      if (k === 'cells') set('cells', '18204,');
      if (k === 'classifier') set('classifier', '"tumor_stroma v3 · HIGH",');
      if (k === 'validation') {
        set('validation', '"A. Moreau · HIGH"', 'ok');
        live.textContent = 'Stamped · anchored';
        live.classList.add('stamped');
      }
    }
    function travel(p, done) {
      var L = p.getTotalLength(), dot = document.createElementNS(SVGNS, 'circle');
      dot.setAttribute('class', 'pk'); dot.setAttribute('r', 4.5);
      svg.appendChild(dot);
      p.classList.add('on');
      var t0 = performance.now(), D = 700;
      (function f(t) {
        if (id !== runId) return;
        var k = Math.min(1, (t - t0) / D), e = k < .5 ? 2 * k * k : 1 - Math.pow(-2 * k + 2, 2) / 2, pt = p.getPointAtLength(e * L);
        dot.setAttribute('cx', pt.x); dot.setAttribute('cy', pt.y);
        if (k < 1) requestAnimationFrame(f); else { dot.remove(); p.classList.remove('on'); done(); }
      })(t0);
    }
    function next() {
      if (id !== runId) return;
      i++;
      if (i >= rows.length) { root.classList.add('over'); return; } // played once: ↻ plays it again
      if (i > 0) { rows[i - 1].classList.remove('on'); rows[i - 1].classList.add('done'); }
      var r = rows[i];
      r.classList.add('on');
      travel(paths[i], function () {
        apply(r.dataset.k);
        later(function () { if (i === rows.length - 1) { r.classList.remove('on'); r.classList.add('done'); } next(); }, 600);
      });
    }
    geom();
    reset();
    if (REDUCED) {
      root.classList.add('over');
      rows.forEach(function (r) { r.classList.add('done'); });
      ['annotations', 'cells', 'classifier', 'validation'].forEach(apply);
      return;
    }
    later(next, 500);
  }

  // The mini-panel: QuPath whole, then a 5 s zoom onto the bar, the bar slides to the left and
  // each of its buttons gets, in turn, a bubble on its right — and, in the real panel, the green
  // square (tour-highlight). The zoom is geometric (the same ratio every moment: that is what the
  // eye reads as a steady zoom) and keeps the bar in view the whole way.
  var ZOOM_MS = 5000, SLIDE_MS = 900, BUBBLE_MS = 1200;
  function playPanel(el, s, id) {
    var t = s.tour, root = el.querySelector('.mp');
    if (!root || !t) return;
    var cam = root.querySelector('.mp-cam'), top = root.querySelector('.mp-top'),
        ring = root.querySelector('.mp-ring'), bubble = root.querySelector('.mp-bubble');
    var cw = root.clientWidth, ch = root.clientHeight, bar = t.bar;
    function pose(k, tx, ty) { return 'translate(' + tx + 'px,' + ty + 'px) scale(' + k + ')'; }
    function clip(on) {
      var m = 3, v = on
        ? 'inset(' + (bar.y - m) + 'px ' + (t.width - bar.x - bar.w - m) + 'px ' + (t.height - bar.y - bar.h - m) + 'px ' + (bar.x - m) + 'px round 14px)'
        : 'inset(0px 0px 0px 0px round 0px)';
      cam.style.webkitClipPath = v;
      cam.style.clipPath = v;
    }
    var k0 = Math.min(cw / t.width, ch / t.height), k1 = ch * 0.94 / bar.h;
    var cy = bar.y + bar.h / 2;
    var start = pose(k0, (cw - t.width * k0) / 2, (ch - t.height * k0) / 2);
    var left = cw * 0.17 - t.cx * k1;
    var slid = pose(k1, left, ch / 2 - cy * k1);
    top.className = 'mp-top';
    cam.style.transition = 'none';
    cam.style.transformOrigin = '0 0';
    cam.style.transform = start;
    clip(false);
    ring.style.opacity = 0;
    bubble.style.opacity = 0;
    Array.prototype.forEach.call(top.querySelectorAll('.mp-bubble.kept'), function (n) { n.remove(); });
    highlight('');
    void cam.offsetWidth;

    function show(n) {
      var b = t.buttons[n];
      if (!b) { ring.style.opacity = 0; highlight(''); return; }
      var y = ch / 2 + (b.y - cy) * k1, x = left + t.cx * k1;
      ring.style.left = (x - 16) + 'px';
      ring.style.top = (y - 16) + 'px';
      ring.style.opacity = 1;
      // One bubble per button, and each stays: the whole picture is there when the tour ends.
      Array.prototype.forEach.call(top.querySelectorAll('.mp-bubble.kept'), function (n) { n.classList.remove('cur'); });
      var nb = bubble.cloneNode(true);
      nb.classList.add('kept', 'cur');
      nb.querySelector('b').textContent = b.title;
      nb.querySelector('span').textContent = b.text;
      nb.style.left = (x + 30) + 'px';
      nb.style.opacity = 0;
      top.appendChild(nb);
      nb.style.top = (y - nb.offsetHeight / 2) + 'px';
      void nb.offsetWidth;
      nb.style.opacity = '';
      highlight(b.key);
      // The last button: its green square goes out after a second, the bubble stays.
      if (n + 1 === t.buttons.length) later(function () { ring.style.opacity = 0; highlight(''); }, 1000);
    }
    function sequence(n) {
      if (id !== runId) return;
      show(n);
      // The last bubble stays lit: the explanation is still there when the tour ends.
      if (n + 1 < t.buttons.length) later(function () { sequence(n + 1); }, BUBBLE_MS);
    }
    if (REDUCED) {
      cam.style.transform = slid;
      clip(true);
      sequence(0);
      return;
    }
    // Where the bar's centre is on screen at the start, and where it ends (the middle).
    var sx0 = parseFloat(start.match(/translate\(([-\d.]+)px/)[1]) + t.cx * k0,
        sy0 = (ch - t.height * k0) / 2 + cy * k0;
    var sx1 = cw / 2, sy1 = ch / 2;
    var t0 = performance.now();
    (function frame(now) {
      if (id !== runId) return;
      var u = Math.min(1, (now - t0) / ZOOM_MS);
      var e = u < .5 ? 4 * u * u * u : 1 - Math.pow(-2 * u + 2, 3) / 2; // ease in-out
      var k = k0 * Math.pow(k1 / k0, e);
      var x = sx0 + (sx1 - sx0) * e, y = sy0 + (sy1 - sy0) * e;
      cam.style.transform = pose(k, x - t.cx * k, y - cy * k);
      if (u < 1) requestAnimationFrame(frame);
    })(t0);
    later(function () {
      cam.style.transition = 'transform ' + SLIDE_MS + 'ms ease-in-out, clip-path ' + SLIDE_MS + 'ms ease-in-out, -webkit-clip-path ' + SLIDE_MS + 'ms ease-in-out';
      cam.style.transform = slid;
      clip(true);
    }, ZOOM_MS + 200);
    later(function () { sequence(0); }, ZOOM_MS + 200 + SLIDE_MS + 200);
  }

  // The green square in the real panel: which tool is being talked about ('' = none).
  var lastHighlight = '';
  function highlight(key) {
    if (key === lastHighlight) return;
    lastHighlight = key;
    if (window.__qtraceHost) run('tour-highlight', key);
  }

  var VISUALS = { hello: helloHTML, capture: captureHTML, panel: panelHTML, network: networkHTML, entry: entryHTML, device: deviceHTML, install: installHTML, module: moduleHTML, report: reportHTML, done: doneHTML };

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
    var ed = document.querySelector('.edition');
    if (ed) ed.textContent = ' Core' + (content.coreVersion ? ' v' + content.coreVersion : '');
    Array.prototype.forEach.call(stage.querySelectorAll('.slide'), function (n) { n.remove(); });
    content.slides.forEach(function (s, i) {
      var el = document.createElement('section');
      el.className = 'slide';
      el.dataset.i = i;
      el.dataset.id = s.id;
      el.dataset.visual = s.visual || '';
      el.innerHTML = '<div class="visual">' + visualHTML(s) + '</div><div class="copy">' +
        (s.eyebrow ? '<div class="eyebrow">' + esc(s.eyebrow) + '</div>' : '') +
        '<h2>' + esc(s.title) + '</h2><p>' + esc(s.text) + '</p>' +
        '<div class="actions">' + actionsHTML(s) + '</div></div>';
      stage.insertBefore(el, $('toast'));
    });
    // A metro line: the start, a station per slide, the arrival; the names are in the titles. The slides of
    // the sign-in journey (slide.branch) leave the line after the station they follow, on a violet branch.
    var L = layout(), onLine = L.onLine, onBranch = L.onBranch;
    var slots = Math.max(1, onLine.length - 1);   // the gaps of the main line
    function station(i, n, cls) {
      var s = content.slides[i], name = esc(s.eyebrow || s.title);
      return '<button type="button" ' + (cls ? 'class="bs" data-q="' + n + '"' : 'data-k="' + n + '" data-go="' + i + '"') +
        ' title="' + name + '" aria-label="' + name + '"><i></i></button>';
    }
    var html = '<div class="line">' +
      onLine.map(function (i, k) { return (k ? '<b class="rail" data-k="' + k + '"></b>' : '') + station(i, k); }).join('') + '</div>';
    if (onBranch.length && L.attach > -1) {
      var b = onBranch.length;
      html += '<div class="branch" style="left: calc((100% - 9px) * ' + (L.attach / slots) + ' + 4.5px); width: calc((100% - 9px) / ' + slots + ')">' +
        '<svg class="elbow" width="16" height="20" viewBox="0 0 16 20"><path d="M0 0 C0 12 4 20 16 20"/></svg>' +
        onBranch.map(function (i, q) {
          return '<b class="brail" data-q="' + q + '" style="left:' + (q ? (q / b * 100) + '%' : '16px') +
            '; width: calc(' + (100 / b) + '% - ' + (q ? 0 : 16) + 'px)"></b>' +
            '<span class="bstop" style="left:' + ((q + 1) / b * 100) + '%">' + station(i, q, true) +
              (q === b - 1 ? '<span class="bnote">Modules deployed</span>' : '') + '</span>';
        }).join('') + '</div>';
    }
    $('track').classList.toggle('has-branch', !!onBranch.length && L.attach > -1);
    document.querySelector('.player').classList.toggle('single', visible().length < 2);
    $('track').innerHTML = html;
    refreshInstallCopy();
    document.title = content.title || 'qTrace';
    $('org').hidden = !content.org;
    $('org').textContent = content.org ? content.org.name : '';
    refreshWho();
    seen = {};
    go(0);
  }

  // The header line: the certified identity, the basic account QuPath is signed in to, or neither.
  // The gold shield beside a certified name, as in the panel: this identity is verified.
  var SHIELD = '<svg class="shield" viewBox="0 0 24 24" width="15" height="15" aria-hidden="true">' +
    '<path d="M12 2 4 5v6c0 5 3.4 9.4 8 11 4.6-1.6 8-6 8-11V5l-8-3z" fill="#f2c14e"/>' +
    '<path d="m8.5 12 2.5 2.5 4.5-5" fill="none" stroke="#0d1b2a" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>';

  function refreshWho() {
    var name = isAccount() && state.device.name;
    var cert = state.device && state.device.state === 'approved' && state.device.name ? state.device : null;
    $('who').innerHTML = content.who ? SHIELD + 'Certified for <strong>' + esc(content.who) + '</strong>'
      : cert ? SHIELD + 'Certified for <strong>' + esc(cert.name) + '</strong>' + (cert.institution ? ' · ' + esc(cert.institution) : '')
      : content.account ? 'Signed in as <strong>' + esc(content.account) + '</strong> · not certified'
      : isAccount() ? 'Signed in' + (name ? ' as <strong>' + esc(name) + '</strong>' : '') + ' · not certified'
      : 'qTrace Core · no certificate yet';
  }

  // A slide's own words once signed in to a basic account (slide.account), the usual ones otherwise.
  function refreshCopy() {
    refreshWho();
    Array.prototype.forEach.call(document.querySelectorAll('.slide'), function (el) {
      var s = content.slides[+el.dataset.i];
      if (!s.account) return;
      var acc = isAccount();
      var title = (acc && s.account.title) || s.title;
      var text = (acc && s.account.text) ? signedInLine() + ' ' + s.account.text : s.text;
      el.querySelector('h2').textContent = title;
      el.querySelector('p').textContent = text;
    });
    // In QuPath every event arrives here (through the URL fragment): the install slide follows its state.
    refreshInstallCopy();
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
  // The main line, and the branch (the slides marked `branch`, which leave it after the station they follow).
  function layout() {
    var vis = visible();
    var onLine = vis.filter(function (i) { return !content.slides[i].branch; });
    var onBranch = vis.filter(function (i) { return content.slides[i].branch; });
    var attach = onBranch.length ? onLine.filter(function (i) { return i < onBranch[0]; }).length - 1 : -1;
    return { onLine: onLine, onBranch: onBranch, attach: attach };
  }
  // Reaching a slide that announces it (slide.enter) tells QuPath first: Getting started is done there.
  function reach(i) {
    var s = content.slides[i];
    if (s.enter) { run(s.enter); return; }
    go(i);
  }
  function step(dir) {
    var L = layout();
    if (content.slides[cur].branch) {            // on the branch: back along it, forward only by signing in
      if (dir > 0) return;
      var q = L.onBranch.indexOf(cur);
      go(q > 0 ? L.onBranch[q - 1] : L.onLine[L.attach]);
      return;
    }
    var k = L.onLine.indexOf(cur);
    if (k === -1) return;
    var n = L.onLine[k + dir];
    if (n != null) reach(n);
  }

  function go(i) {
    if (!content) return;
    cur = Math.max(0, Math.min(content.slides.length - 1, i));
    var v = visible(), k = v.indexOf(cur);
    document.querySelector('.player').classList.toggle('final', k === -1);
    // Hello page: the header says "qTrace Core" beside the logo and nothing on the right.
    document.querySelector('.player').classList.toggle('hello', content.slides[cur].id === 'what');
    seen[cur] = true;
    stopVisual();
    // A tool's own welcome: its button is outlined in the panel (the others stay as they are).
    var tool = /^tool-([a-z0-9]+)(-\d+)?$/.exec(content.slides[cur].id || '');
    // The feedback slide shows the panel's Bug or Feature Request button.
    var id = content.slides[cur].id || '';
    // The closing slide outlines nothing, but the gold squares stay away until the window closes
    // (the welcomes are marked read then): 'only:-' matches no button.
    var closing = id === 'done' || /-done$/.test(id);
    highlight(id === 'core-report' ? 'only:issue' : tool ? 'only:' + tool[1] : closing ? 'only:-' : '');
    Array.prototype.forEach.call(document.querySelectorAll('.slide'), function (n) {
      n.classList.toggle('on', +n.dataset.i === cur);
    });
    // Stations before this one are passed, the rails up to it are lit; past the last slide, the whole line is.
    var L = layout();
    var onBr = !!content.slides[cur].branch;
    var curK = onBr ? L.attach : (L.onLine.indexOf(cur) === -1 ? L.onLine.length : L.onLine.indexOf(cur));
    var curQ = onBr ? L.onBranch.indexOf(cur) : -1;
    Array.prototype.forEach.call(document.querySelectorAll('#track button[data-k]'), function (b) {
      var n = +b.dataset.k;
      b.classList.toggle('cur', !onBr && n === curK);
      b.classList.toggle('past', onBr ? n <= curK : n < curK);
    });
    Array.prototype.forEach.call(document.querySelectorAll('#track .rail'), function (r) {
      r.classList.toggle('lit', +r.dataset.k <= curK);
    });
    Array.prototype.forEach.call(document.querySelectorAll('#track button[data-q]'), function (b) {
      var q = +b.dataset.q;
      b.classList.toggle('cur', q === curQ);
      b.classList.toggle('past', q < curQ);
    });
    Array.prototype.forEach.call(document.querySelectorAll('#track .brail'), function (r) {
      r.classList.toggle('lit', +r.dataset.q <= curQ);
    });
    Array.prototype.forEach.call(document.querySelectorAll('#track .elbow'), function (r) {
      r.classList.toggle('lit', curQ >= 0);
    });
    // The count follows the main line; on the sign-in branch it stays where the branch leaves it.
    var at = onBr ? L.attach : L.onLine.indexOf(cur);
    $('count').textContent = at === -1 ? '' : (at + 1) + ' / ' + L.onLine.length;
    remember(cur);
    // Previous / Next under the text: none where there is nowhere to go.
    Array.prototype.forEach.call(document.querySelectorAll('.pager [data-nav]'), function (b) {
      var dir = +b.dataset.nav;
      var kk = L.onLine.indexOf(cur);            // -1 on the branch
      var none = dir < 0 ? (kk === 0) : (kk === -1 || kk === L.onLine.length - 1);
      b.style.visibility = none ? 'hidden' : 'visible';
    });
    placePager();
    var s = content.slides[cur];
    startVisual(cur);
    if (s.visual === 'network' && !networkAsked && !Object.keys(state.network).length) { networkAsked = true; run('network-check'); }
  }

  // ── Events from QuPath ────────────────────────────────────────────────────
  function emit(event, data) {
    data = typeof data === 'string' ? JSON.parse(data) : (data || {});
    if (event === 'network') { state.network[data.name] = !!data.ok; refreshVisual('network'); }
    if (event === 'device') { state.device = data; refreshVisual('device'); refreshCopy(); refreshVisual('install'); }
    if (event === 'install') { state.install = data; refreshVisual('install'); refreshInstallCopy(); }
    if (event === 'modules') { state.modules = data; refreshVisual('module'); }
  }

  // The install slide says how it went once every module is there: all delivered, or some not.
  function refreshInstallCopy() {
    var el = document.querySelector('.slide[data-id="install"]');
    if (!el || !content) return;
    var s = content.slides.filter(function (x) { return x.id === 'install'; })[0];
    var h = el.querySelector('h2'), p = el.querySelector('p'), i = state.install;
    var failed = (i.modules || []).some(function (m) { return m.failed; });
    // The restart is offered once every module has arrived; the line says so at its arrival.
    Array.prototype.forEach.call(el.querySelectorAll('[data-act="quit"]'), function (b) { b.disabled = i.state !== 'done'; });
    $('track').classList.toggle('deployed', i.state === 'done' && !failed);
    // A content may say it its own way (an update: s.done, s.partial).
    var said = i.state === 'done' && s ? (failed ? s.partial : s.done) : null;
    if (said) {
      h.textContent = said.title;
      p.textContent = said.text;
    } else if (i.state === 'done' && !failed) {
      h.textContent = 'Modules Download completed';
      p.textContent = 'Every module you are subscribed to has been delivered. They start the next time you open QuPath.';
    } else if (i.state === 'done') {
      h.textContent = 'Some modules did not arrive';
      p.textContent = 'The ones marked ! could not be downloaded. The others have been delivered and start the next time you open QuPath.';
    } else if (s && !s.account) {
      h.textContent = s.title;
      p.textContent = s.text;
    }
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
      setTimeout(function () { emit('device', { state: 'approved', code: 'EDW9-QJGL', name: 'Dr. Jane Demo', institution: 'Demo Hospital' }); }, 4000);
      setTimeout(function () { gotoId('install'); emit('install', { state: 'running' }); }, 5000);
      // Nine modules, downloaded one after the other.
      var sim = ['Compliance 1.2.6', 'Cohort 0.1.1', 'Dashboard 0.1.0', 'Import 0.1.0', 'Library 0.1.2', 'Security 0.1.0', 'Training 0.1.1', 'Upload 0.2.0', 'Workfloweditor 0.1.2'].map(function (s) { var a = s.split(' '); return { name: a[0], version: a[1], pct: 0 }; });
      setTimeout(function () { emit('install', { state: 'running', modules: JSON.parse(JSON.stringify(sim)) }); }, 5400);
      var t = 5800;
      sim.forEach(function (m) {
        for (var p = 10; p <= 100; p += 10) (function (pp) {
          setTimeout(function () {
            m.pct = pp; m.done = pp === 100;
            emit('install', { state: 'running', modules: JSON.parse(JSON.stringify(sim)) });
          }, t);
          t += 120;
        })(p);
      });
      setTimeout(function () { emit('install', { state: 'done', modules: JSON.parse(JSON.stringify(sim)) }); }, t + 300);
    }
  }

  // ── Wiring ────────────────────────────────────────────────────────────────
  var stage = $('stage');
  stage.addEventListener('click', function (e) {
    var b = e.target.closest('[data-act]');
    if (!b) return;
    if (b.dataset.act === 'goto-account') { gotoId('account'); return; }
    if (b.dataset.act === 'tour-again') { go(cur); return; }
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
    if (b) reach(+b.dataset.go);
  });
  $('pager').addEventListener('click', function (e) {
    var b = e.target.closest && e.target.closest('[data-nav]');
    if (b) step(+b.dataset.nav);
  });
  // Previous / Next stay at the bottom right edge, just above the bar, whatever the slide says.
  function placePager() { $('pager').style.bottom = (document.querySelector('.bar').offsetHeight + 14) + 'px'; }
  window.addEventListener('resize', placePager);
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
    if (e.key === 'ArrowRight') step(1);
    if (e.key === 'ArrowLeft') step(-1);
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
    refreshCopy();
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
