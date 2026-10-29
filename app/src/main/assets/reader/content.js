(function () {
  'use strict';
  if (window.__hripsReader) return;
  window.__hripsReader = true;
  if (window !== window.top) return;

  var APP = 'hripsReader';
  var HOST_ID = '__hrips-reader';
  var MAX_NODES = 20000;     // защита от гигантских страниц при сборке читалки
  var MAX_DEPTH = 120;
  var MIN_TEXT = 200;        // короче этого статьёй не считаем

  var RU = /^ru\b/i.test(navigator.language || '');
  var T = RU ? {
    close: 'Закрыть режим чтения', view: 'Вид', smaller: 'Меньше', larger: 'Больше', serif: 'С засечками',
    sans: 'Без засечек', auto: 'Как в системе', light: 'Светлая', sepia: 'Сепия', dark: 'Тёмная',
    min: ' мин', original: 'Открыть оригинал'
  } : {
    close: 'Close reader view', view: 'Appearance', smaller: 'Smaller', larger: 'Larger', serif: 'Serif',
    sans: 'Sans-serif', auto: 'System', light: 'Light', sepia: 'Sepia', dark: 'Dark',
    min: ' min', original: 'Open original'
  };

  var prefs = { size: 18, font: 'serif', theme: 'auto' };
  var view = null;          // { host, root, scroller, ... } пока читалка открыта

  // ---------- связь с приложением ----------
  // Только sendNativeMessage (как у остальных встроенных расширений). Команды от приложения приходят ответом на
  // «длинный запрос» poll: приложение держит ответ, пока ему нечего сказать, и отдаёт его, когда нужна команда.

  var attempts = 0;
  var polling = false;

  // Приложение может подключить расширение к вкладке позже, чем страница загрузилась, поэтому не сдаёмся:
  // пауза растёт до 5 секунд и остаётся такой (раньше после десяти неудач связь пропадала до перезагрузки страницы).
  function retryLater(fn) {
    attempts++;
    setTimeout(fn, Math.min(5000, 500 * attempts));
  }

  function send(msg, done) {
    try {
      browser.runtime.sendNativeMessage(APP, msg).then(
        function (r) { attempts = 0; if (done) done(true, r); },
        function () { if (done) done(false); }
      );
    } catch (e) { if (done) done(false); }
  }

  // Журнал: строка уходит в приложение (AppLog, «Диагностика») и в консоль. Адреса и текст статей не передаём.
  var logged = 0;
  function log(m) {
    try { console.log('[hripsReader] ' + m); } catch (e) {}
    if (logged++ >= 40) return;   // потолок на страницу: журнал не должен нагружать сайт с ошибкой в цикле
    send({ t: 'log', m: String(m).slice(0, 300) });
  }

  // Связь страницы с приложением: «hello» при запуске (в журнале видно, что расширение внедрилось и канал работает).
  function hello() {
    send({ t: 'hello' }, function (ok) { if (!ok) retryLater(hello); });
  }

  function report() {
    send({ t: 'state', active: !!view }, function (ok) { if (!ok) retryLater(report); });
  }

  function poll() {
    if (polling) return;
    polling = true;
    send({ t: 'poll' }, function (ok, r) {
      polling = false;
      if (!ok) {
        // Приложение ещё не подключило делегат к этой вкладке (или связь оборвалась): пробуем снова.
        if (attempts < 3) { try { console.log('[hripsReader] poll не прошёл, попытка ' + (attempts + 1)); } catch (e) {} }
        retryLater(poll);
        return;
      }
      if (r && r.cmd) onCommand(r);
      setTimeout(poll, r && r.cmd ? 0 : 200);
    });
  }

  function onCommand(msg) {
    var cmd = msg && msg.cmd;
    log('команда «' + cmd + '», читалка ' + (view ? 'открыта' : 'закрыта'));
    if (cmd === 'toggle') { if (view) close(); else open(); }
    else if (cmd === 'open') { if (!view) open(); }
    else if (cmd === 'close') { if (view) close(); }
    else if (cmd === 'ping') { report(); }
  }

  // ---------- настройки вида ----------

  function loadPrefs(done0) {
    var finished = false;
    var done = function () { if (finished) return; finished = true; done0(); };
    setTimeout(done, 1500);   // хранилище не ответило: открываем с настройками по умолчанию
    try {
      browser.storage.local.get('prefs').then(function (r) {
        var p = r && r.prefs;
        if (p) {
          if (typeof p.size === 'number' && p.size >= 14 && p.size <= 30) prefs.size = p.size;
          if (p.font === 'serif' || p.font === 'sans') prefs.font = p.font;
          if (/^(auto|light|sepia|dark)$/.test(p.theme)) prefs.theme = p.theme;
        }
        done();
      }, done);
    } catch (e) { done(); }
  }

  function savePrefs() {
    try { browser.storage.local.set({ prefs: prefs }); } catch (e) {}
  }

  // ---------- сборка статьи ----------

  var TAGS = {
    P: 1, H1: 1, H2: 1, H3: 1, H4: 1, H5: 1, H6: 1, A: 1, IMG: 1, FIGURE: 1, FIGCAPTION: 1, UL: 1, OL: 1, LI: 1,
    BLOCKQUOTE: 1, PRE: 1, CODE: 1, EM: 1, STRONG: 1, B: 1, I: 1, U: 1, S: 1, SUB: 1, SUP: 1, BR: 1, HR: 1,
    TABLE: 1, THEAD: 1, TBODY: 1, TFOOT: 1, TR: 1, TD: 1, TH: 1, CAPTION: 1, DIV: 1, SPAN: 1, SECTION: 1,
    ARTICLE: 1, MARK: 1, SMALL: 1, ABBR: 1, CITE: 1, Q: 1, TIME: 1, DL: 1, DT: 1, DD: 1, DETAILS: 1, SUMMARY: 1,
    PICTURE: 1, KBD: 1, SAMP: 1, VAR: 1, DEL: 1, INS: 1, ADDRESS: 1, MAIN: 1, HEADER: 1, FOOTER: 1, ASIDE: 1
  };
  // Эти элементы выбрасываем целиком вместе с содержимым.
  var DROP = {
    SCRIPT: 1, STYLE: 1, NOSCRIPT: 1, IFRAME: 1, FRAME: 1, OBJECT: 1, EMBED: 1, FORM: 1, INPUT: 1, BUTTON: 1,
    SELECT: 1, TEXTAREA: 1, LINK: 1, META: 1, BASE: 1, TEMPLATE: 1, SVG: 1, MATH: 1, CANVAS: 1, AUDIO: 1,
    VIDEO: 1, DIALOG: 1, NAV: 1, HEAD: 1, TITLE: 1
  };

  function safeUrl(u, forImg) {
    if (!u) return null;
    u = String(u).trim();
    if (/^https?:\/\//i.test(u)) return u;
    if (forImg && /^data:image\/(png|jpe?g|gif|webp|avif|bmp);/i.test(u)) return u;
    if (!forImg && /^(mailto|tel):/i.test(u)) return u;
    if (!forImg && u.charAt(0) === '#') return u;
    return null;
  }

  function firstSrcset(v) {
    if (!v) return null;
    var first = String(v).split(',')[0].trim().split(/\s+/)[0];
    return safeUrl(first, true);
  }

  function copyAttr(from, to, name) {
    var v = from.getAttribute(name);
    if (v != null && v.length < 2000) to.setAttribute(name, v);
  }

  // Строим новое дерево по белому списку: ничего из страницы (атрибуты, обработчики, стили) в читалку не попадает.
  function build(src, out, ctx, depth) {
    for (var n = src.firstChild; n; n = n.nextSibling) {
      if (ctx.count++ > MAX_NODES) return;
      if (n.nodeType === 3) {
        out.appendChild(document.createTextNode(n.nodeValue));
        continue;
      }
      if (n.nodeType !== 1) continue;
      var tag = n.nodeName.toUpperCase();
      if (DROP[tag]) continue;
      if (depth > MAX_DEPTH) { out.appendChild(document.createTextNode(n.textContent || '')); continue; }
      if (tag === 'PICTURE') { build(n, out, ctx, depth + 1); continue; }
      if (tag === 'SOURCE') continue;
      if (tag === 'IMG') {
        var s = safeUrl(n.getAttribute('src'), true) || firstSrcset(n.getAttribute('srcset')) || firstSrcset(n.getAttribute('data-srcset'));
        if (!s) {
          var p = n.parentNode;
          if (p && p.nodeName.toUpperCase() === 'PICTURE') {
            var srcEl = p.querySelector('source[srcset]');
            if (srcEl) s = firstSrcset(srcEl.getAttribute('srcset'));
          }
        }
        if (!s) continue;
        var img = document.createElement('img');
        img.setAttribute('src', s);
        img.setAttribute('alt', n.getAttribute('alt') || '');
        img.setAttribute('loading', 'lazy');
        img.setAttribute('decoding', 'async');
        out.appendChild(img);
        continue;
      }
      if (!TAGS[tag]) { build(n, out, ctx, depth + 1); continue; }   // неизвестный тег: оставляем только содержимое
      var el = document.createElement(tag.toLowerCase());
      if (tag === 'A') {
        var h = safeUrl(n.getAttribute('href'), false);
        if (h) {
          el.setAttribute('href', h);
          if (h.charAt(0) !== '#') el.setAttribute('rel', 'noopener noreferrer');
        }
      } else if (tag === 'TD' || tag === 'TH') {
        copyAttr(n, el, 'colspan'); copyAttr(n, el, 'rowspan');
      } else if (tag === 'OL') {
        copyAttr(n, el, 'start');
      } else if (tag === 'TIME') {
        copyAttr(n, el, 'datetime');
      }
      var id = n.getAttribute('id');
      if (id && id.length < 200) el.setAttribute('id', id);
      var lang = n.getAttribute('lang');
      if (lang && lang.length < 20) el.setAttribute('lang', lang);
      var dir = n.getAttribute('dir');
      if (dir === 'rtl' || dir === 'ltr') el.setAttribute('dir', dir);
      build(n, el, ctx, depth + 1);
      out.appendChild(el);
    }
  }

  function readingTime(text) {
    var len = text.length;
    var words = text.split(/\s+/).filter(Boolean).length;
    var mins = words > len / 20 ? words / 200 : len / 500;   // мало пробелов: китайский, японский
    return Math.max(1, Math.round(mins));
  }

  var parseWhy = '';

  function parse() {
    var clone = document.cloneNode(true);
    var art = new Readability(clone, {
      charThreshold: MIN_TEXT,
      keepClasses: false,
      serializer: function (el) { return el; }
    }).parse();
    if (!art || !art.content || (art.length || 0) < MIN_TEXT) {
      parseWhy = !art ? 'Readability не нашёл статью' : 'текста слишком мало: ' + (art.length || 0) + ' < ' + MIN_TEXT;
      return null;
    }
    return art;
  }

  // ---------- оформление ----------

  var CSS = [
    ':host{all:initial}',
    '.root{position:absolute;inset:0;display:flex;flex-direction:column;background:var(--bg);color:var(--fg);',
    '  font-family:system-ui,-apple-system,"Roboto","Segoe UI",sans-serif;-webkit-text-size-adjust:100%;',
    '  --bg:#fff;--fg:#1d1d1f;--muted:#6b6b72;--link:#0b57d0;--line:#e2e2e7;--bar:#f5f5f8;--hi:#0b57d0;--hif:#fff}',
    '.root[data-theme=sepia]{--bg:#f4ecd8;--fg:#433422;--muted:#7a6a55;--link:#8a4b0f;--line:#dccfae;--bar:#ece2c9;--hi:#8a4b0f}',
    '.root[data-theme=dark]{--bg:#16161a;--fg:#e4e4e7;--muted:#9a9aa3;--link:#8ab4f8;--line:#2c2c33;--bar:#1f1f25;--hi:#8ab4f8;--hif:#101014}',
    '@media (prefers-color-scheme:dark){.root[data-theme=auto]{--bg:#16161a;--fg:#e4e4e7;--muted:#9a9aa3;--link:#8ab4f8;--line:#2c2c33;--bar:#1f1f25;--hi:#8ab4f8;--hif:#101014}}',
    '.bar{flex:none;font-size:calc(16px*var(--k));display:flex;align-items:center;gap:.5em;padding:.5em .75em;background:var(--bar);border-bottom:1px solid var(--line)}',
    '.bar .sp{flex:1}',
    '.btn{font:inherit;color:var(--fg);background:transparent;border:0;border-radius:.75em;min-width:2.75em;height:2.75em;padding:0 .75em;display:inline-flex;align-items:center;justify-content:center;cursor:pointer}',
    '.btn:active,.btn[aria-pressed=true]{background:var(--line)}',
    '.panel{flex:none;font-size:calc(16px*var(--k));display:none;flex-wrap:wrap;gap:.75em 1.25em;align-items:center;padding:.75em 1em;background:var(--bar);border-bottom:1px solid var(--line)}',
    '.panel.on{display:flex}',
    '.grp{display:flex;gap:.25em;align-items:center}',
    '.chip{font:inherit;font-size:.9em;color:var(--fg);background:transparent;border:1px solid var(--line);border-radius:2em;height:2.5em;padding:0 1em;cursor:pointer}',
    '.chip[aria-pressed=true]{background:var(--hi);color:var(--hif);border-color:var(--hi)}',
    '.scroll{flex:1;overflow-y:auto;overflow-x:hidden;overscroll-behavior:contain;-webkit-overflow-scrolling:touch}',
    '.art{box-sizing:border-box;margin:0 auto;max-width:36em;padding:1.5em 1.25em 4em;font-size:calc(var(--fs)*var(--k));line-height:1.65;overflow-wrap:break-word;hyphens:auto}',
    '.art[data-font=serif]{font-family:"Noto Serif",Georgia,"Times New Roman",serif}',
    '.art[data-font=sans]{font-family:system-ui,-apple-system,"Roboto","Segoe UI",sans-serif}',
    '.site{font-size:.75em;color:var(--muted);text-transform:uppercase;letter-spacing:.06em;margin:0 0 .5em}',
    '.art h1.t{font-size:1.6em;line-height:1.2;margin:0 0 .4em;font-weight:700}',
    '.meta{font-size:.8em;color:var(--muted);margin:0 0 1.5em}',
    '.body h1,.body h2,.body h3,.body h4{line-height:1.25;margin:1.4em 0 .5em}',
    '.body h1{font-size:1.4em}.body h2{font-size:1.25em}.body h3{font-size:1.1em}.body h4,.body h5,.body h6{font-size:1em}',
    '.body p,.body ul,.body ol,.body dl,.body figure,.body pre,.body table,.body blockquote{margin:0 0 1em}',
    '.body ul,.body ol{padding-inline-start:1.4em}',
    '.body a{color:var(--link);text-decoration:underline;text-underline-offset:.15em}',
    '.body img{display:block;max-width:100%;height:auto;margin:1em auto;border-radius:.4em}',
    '.body figcaption{font-size:.8em;color:var(--muted);text-align:center}',
    '.body blockquote{border-inline-start:.2em solid var(--line);padding-inline-start:1em;color:var(--muted);margin-inline:0}',
    '.body pre{overflow-x:auto;padding:.75em;background:var(--bar);border-radius:.5em;font-size:.85em;line-height:1.45}',
    '.body code,.body kbd,.body samp{font-family:ui-monospace,Menlo,Consolas,monospace;font-size:.88em}',
    '.body :not(pre)>code{background:var(--bar);padding:.1em .3em;border-radius:.25em}',
    '.body table{display:block;overflow-x:auto;border-collapse:collapse;max-width:100%;font-size:.9em}',
    '.body td,.body th{border:1px solid var(--line);padding:.35em .6em;vertical-align:top}',
    '.body hr{border:0;border-top:1px solid var(--line);margin:1.5em 0}',
    '.foot{margin-top:2.5em;text-align:center}'
  ].join('\n');

  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = text;
    return e;
  }

  function button(cls, label, text, onClick) {
    var b = el('button', cls, text);
    b.setAttribute('type', 'button');
    b.setAttribute('aria-label', label);
    b.addEventListener('click', onClick);
    return b;
  }

  function scaleK() {
    var iw = window.innerWidth || 0, sw = window.screen && window.screen.width || iw;
    var k = sw > 0 ? iw / sw : 1;
    return Math.min(4, Math.max(1, k || 1));   // на широких (десктопных) макетах страница уменьшена, читалку увеличиваем обратно
  }

  function applyPrefs() {
    if (!view) return;
    view.root.setAttribute('data-theme', prefs.theme);
    view.art.setAttribute('data-font', prefs.font);
    view.host.style.setProperty('--fs', prefs.size + 'px');
    view.sync();
  }

  function applyCss(shadow, root) {
    var st = document.createElement('style');
    st.textContent = CSS;
    shadow.appendChild(st);
    // Страница могла запретить встроенные стили своей политикой CSP: тогда пробуем через CSSOM.
    var ok = false;
    try { ok = getComputedStyle(root).position === 'absolute'; } catch (e) {}
    if (ok) return;
    try {
      var sheet = new CSSStyleSheet();
      sheet.replaceSync(CSS);
      shadow.adoptedStyleSheets = [sheet];
    } catch (e) {}
  }

  // ---------- открыть и закрыть ----------

  // Readability.js подключён манифестом вместе с этим скриптом. Раньше его по первой просьбе подгружал фоновый
  // скрипт (tabs.executeScript по sender.tab), но в GeckoView эта цепочка не срабатывала, и читалка отвечала «не удалось».
  function ensureLib(done) {
    done(typeof Readability === 'function');
  }

  var opening = false;

  function open() {
    if (view || opening || !document.body) {
      log('open пропущен: view=' + !!view + ', opening=' + opening + ', body=' + !!document.body);
      if (!view && !opening && !document.body) send({ t: 'fail', why: 'нет body' });
      return;
    }
    opening = true;
    ensureLib(function (ok) {
      opening = false;
      if (view) return;
      var art = null;
      var why = '';
      if (!ok) {
        why = 'Readability не загружен';
      } else {
        var t0 = Date.now();
        try { art = parse(); why = parseWhy; } catch (e) { art = null; why = 'ошибка разбора: ' + (e && e.message || e); }
        log('разбор за ' + (Date.now() - t0) + ' мс: ' + (art ? 'статья найдена, символов ' + art.length : why));
      }
      if (!art) { send({ t: 'fail', why: why }); return; }

      loadPrefs(function () {
        if (view) return;
        try { build_view(art); } catch (e) {
          var m = 'ошибка сборки читалки: ' + (e && e.message || e);
          log(m);
          teardown();
          send({ t: 'fail', why: m });
        }
      });
    });
  }

  function build_view(art) {
    var old = document.getElementById(HOST_ID);
    if (old) old.remove();

    var host = document.createElement('div');
    host.id = HOST_ID;
    host.style.cssText = 'all:initial;position:fixed;top:0;left:0;right:0;bottom:0;z-index:2147483647;display:block;';
    var shadow = host.attachShadow({ mode: 'closed' });
    var root = el('div', 'root');
    root.setAttribute('role', 'dialog');
    root.setAttribute('aria-modal', 'true');
    shadow.appendChild(root);

    // Верхняя панель.
    var bar = el('div', 'bar');
    var panel = el('div', 'panel');
    var closeBtn = button('btn', T.close, '✕', function () { close(); });
    var viewBtn = button('btn', T.view, 'Aa', function () {
      var on = !panel.classList.contains('on');
      panel.classList.toggle('on', on);
      viewBtn.setAttribute('aria-pressed', on ? 'true' : 'false');
    });
    viewBtn.setAttribute('aria-pressed', 'false');
    var sp = el('div', 'sp');
    bar.appendChild(closeBtn); bar.appendChild(sp); bar.appendChild(viewBtn);

    // Панель вида: размер, шрифт, тема.
    var sizeG = el('div', 'grp');
    sizeG.appendChild(button('chip', T.smaller, 'A−', function () { prefs.size = Math.max(14, prefs.size - 2); savePrefs(); applyPrefs(); }));
    sizeG.appendChild(button('chip', T.larger, 'A+', function () { prefs.size = Math.min(30, prefs.size + 2); savePrefs(); applyPrefs(); }));
    var fontG = el('div', 'grp');
    var fontBtns = {};
    [['serif', T.serif], ['sans', T.sans]].forEach(function (f) {
      var b = button('chip', f[1], f[1], function () { prefs.font = f[0]; savePrefs(); applyPrefs(); });
      fontBtns[f[0]] = b; fontG.appendChild(b);
    });
    var themeG = el('div', 'grp');
    var themeBtns = {};
    [['auto', T.auto], ['light', T.light], ['sepia', T.sepia], ['dark', T.dark]].forEach(function (th) {
      var b = button('chip', th[1], th[1], function () { prefs.theme = th[0]; savePrefs(); applyPrefs(); });
      themeBtns[th[0]] = b; themeG.appendChild(b);
    });
    panel.appendChild(sizeG); panel.appendChild(fontG); panel.appendChild(themeG);

    // Статья.
    var scroller = el('div', 'scroll');
    var artEl = el('article', 'art');
    var lang = art.lang || document.documentElement.lang;
    if (lang && lang.length < 20) artEl.setAttribute('lang', lang);
    if (art.dir === 'rtl' || art.dir === 'ltr') artEl.setAttribute('dir', art.dir);

    var site = art.siteName || location.hostname.replace(/^www\./, '');
    if (site) artEl.appendChild(el('p', 'site', site));
    artEl.appendChild(el('h1', 't', art.title || document.title || ''));
    var meta = [];
    if (art.byline) meta.push(String(art.byline).replace(/\s+/g, ' ').trim());
    if (art.publishedTime) {
      try {
        var d = new Date(art.publishedTime);
        if (!isNaN(d)) meta.push(new Intl.DateTimeFormat(lang || undefined, { dateStyle: 'long' }).format(d));
      } catch (e) {}
    }
    meta.push(readingTime(art.textContent || '') + T.min);
    artEl.appendChild(el('p', 'meta', meta.join(' · ')));

    var body = el('div', 'body');
    build(art.content, body, { count: 0 }, 0);
    artEl.appendChild(body);

    var foot = el('div', 'foot');
    foot.appendChild(button('chip', T.original, T.original, function () { close(); }));
    artEl.appendChild(foot);
    scroller.appendChild(artEl);

    // Ссылки на сноски внутри статьи не должны листать страницу под читалкой.
    body.addEventListener('click', function (ev) {
      var a = ev.target && ev.target.closest ? ev.target.closest('a[href^="#"]') : null;
      if (!a) return;
      ev.preventDefault();
      var id = decodeURIComponent((a.getAttribute('href') || '').slice(1));
      var t = null;
      try { t = body.querySelector('[id="' + id.replace(/["\\]/g, '') + '"]'); } catch (e) {}
      if (t && t.scrollIntoView) t.scrollIntoView({ block: 'start' });
    });

    root.appendChild(bar);
    root.appendChild(panel);
    root.appendChild(scroller);

    var onResize = function () { host.style.setProperty('--k', String(scaleK())); };
    window.addEventListener('resize', onResize);

    view = {
      host: host, root: root, art: artEl, scroller: scroller, onResize: onResize,
      sync: function () {
        for (var f in fontBtns) fontBtns[f].setAttribute('aria-pressed', String(f === prefs.font));
        for (var th in themeBtns) themeBtns[th].setAttribute('aria-pressed', String(th === prefs.theme));
      }
    };
    host.style.setProperty('--k', String(scaleK()));
    document.documentElement.appendChild(host);
    applyCss(shadow, root);
    applyPrefs();

    // Видео и музыка под читалкой не должны играть.
    try {
      var media = document.querySelectorAll('video,audio');
      for (var i = 0; i < media.length; i++) if (!media[i].paused) media[i].pause();
    } catch (e) {}

    try { closeBtn.focus({ preventScroll: true }); } catch (e) {}
    report();
  }

  function teardown() {
    if (!view) return;
    try { window.removeEventListener('resize', view.onResize); } catch (e) {}
    try { view.host.remove(); } catch (e) {}
    view = null;
  }

  function close() {
    teardown();
    report();
  }

  // ---------- запуск ----------

  // Страница ничего не проверяет и не следит за изменениями: статью ищем только по команде из меню.
  // Единственное, что работает всё время, это ожидание команды (один висящий запрос).
  log('скрипт запущен, Readability ' + (typeof Readability === 'function' ? 'есть' : 'НЕТ'));
  hello();
  poll();

  // Возврат на страницу из кэша «назад/вперёд»: состояние приложения могло сброситься.
  window.addEventListener('pageshow', function (ev) { if (ev.persisted) { report(); poll(); } });
})();
