(function () {
  'use strict';
  if (window.__hripsSiteColor) return;
  window.__hripsSiteColor = true;

  var APP = 'hripsSiteColor';
  var last = null;
  var timer = null;

  function parse(str) {
    if (!str) return null;
    var m = /rgba?\(\s*(\d+)[,\s]+(\d+)[,\s]+(\d+)(?:[,\s/]+([\d.]+%?))?\s*\)/.exec(str);
    if (!m) return null;
    var a = 1;
    if (m[4] !== undefined) a = m[4].indexOf('%') > 0 ? parseFloat(m[4]) / 100 : parseFloat(m[4]);
    if (!(a >= 0.5)) return null;
    return [+m[1], +m[2], +m[3]];
  }

  function normalize(value) {
    if (!value) return null;
    var el = document.createElement('span');
    el.style.color = value;
    if (!el.style.color) return null;
    (document.body || document.documentElement).appendChild(el);
    var c = getComputedStyle(el).color;
    el.remove();
    return parse(c);
  }

  function themeMeta() {
    var list = document.querySelectorAll('meta[name="theme-color"]');
    for (var i = 0; i < list.length; i++) {
      var media = list[i].getAttribute('media');
      var ok = true;
      if (media) {
        try { ok = matchMedia(media).matches; } catch (e) { ok = false; }
      }
      if (!ok) continue;
      var c = normalize(list[i].getAttribute('content'));
      if (c) return c;
    }
    return null;
  }

  function backdrop() {
    var x = Math.max(1, Math.floor(innerWidth / 2));
    var y = Math.max(1, innerHeight - 2);
    var el = document.elementFromPoint(x, y);
    for (var n = el; n && n.nodeType === 1; n = n.parentElement) {
      var c = parse(getComputedStyle(n).backgroundColor);
      if (c) return c;
    }
    return parse(getComputedStyle(document.documentElement).backgroundColor);
  }

  function hex(c) {
    return '#' + c.map(function (v) { return ('0' + v.toString(16)).slice(-2); }).join('');
  }

  function send() {
    timer = null;
    var c = null;
    try { c = themeMeta() || backdrop(); } catch (e) {}
    var value = c ? hex(c) : '';
    if (value === last) return;
    last = value;
    try {
      var p = browser.runtime.sendNativeMessage(APP, { color: value });
      if (p && p.catch) p.catch(function () {});
    } catch (e) {}
  }

  function soon(ms) {
    if (timer) clearTimeout(timer);
    timer = setTimeout(send, ms);
  }

  soon(0);
  setTimeout(function () { soon(0); }, 900);
  window.addEventListener('load', function () { soon(250); });
  window.addEventListener('scroll', function () { soon(250); }, { passive: true });
  window.addEventListener('resize', function () { soon(250); });
  document.addEventListener('visibilitychange', function () { if (!document.hidden) soon(100); });
  try {
    matchMedia('(prefers-color-scheme: dark)').addEventListener('change', function () { soon(150); });
  } catch (e) {}
  try {
    var mo = new MutationObserver(function () { soon(200); });
    mo.observe(document.documentElement, {
      attributes: true,
      attributeFilter: ['class', 'style', 'data-theme', 'data-bs-theme', 'data-color-mode']
    });
    if (document.head) mo.observe(document.head, { childList: true, subtree: true, attributes: true, attributeFilter: ['content', 'media', 'name'] });
  } catch (e) {}
})();
