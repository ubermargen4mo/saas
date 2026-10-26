(function () {
  'use strict';
  if (window.__hripsThemeColor) return;
  window.__hripsThemeColor = true;

  var APP = 'hripsThemeColor';
  var MAX_SENDS = 60;     // потолок на страницу: сайт с постоянной анимацией фона не должен грузить телефон
  var sent = 0;
  var last = null;
  var timer = null;

  // Любая запись цвета CSS (имя, hsl, hex, rgb) приводим к rgb через canvas: страницу не трогаем.
  // Раньше для этого в html добавлялся временный элемент, и наш же MutationObserver на каждый такой элемент
  // запускал новую проверку: цикл без конца.
  var ctx = null;
  function parse(value) {
    if (!value) return null;
    try {
      var m = value.match(/^\s*rgba?\(([^)]+)\)\s*$/i);
      var p;
      if (m) {
        p = m[1].split(/[ ,/]+/).filter(Boolean).map(parseFloat);
      } else {
        if (!ctx) ctx = document.createElement('canvas').getContext('2d');
        ctx.fillStyle = '#000001';
        ctx.fillStyle = String(value).trim();
        var c = ctx.fillStyle;                    // '#rrggbb' или 'rgba(r, g, b, a)'
        if (c === '#000001' && !/^#?000001$/i.test(String(value).trim())) return null;   // запись не распознана
        if (c.charAt(0) === '#') {
          p = [parseInt(c.slice(1, 3), 16), parseInt(c.slice(3, 5), 16), parseInt(c.slice(5, 7), 16), 1];
        } else {
          var q = c.match(/rgba?\(([^)]+)\)/);
          if (!q) return null;
          p = q[1].split(/[ ,/]+/).filter(Boolean).map(parseFloat);
        }
      }
      var a = p.length > 3 ? p[3] : 1;
      if (!(a >= 0.5)) return null;               // прозрачный: цвета нет
      return [Math.round(p[0]), Math.round(p[1]), Math.round(p[2])];
    } catch (e) { return null; }
  }

  function hex(rgb) {
    return '#' + rgb.map(function (v) { return ('0' + Math.max(0, Math.min(255, v)).toString(16)).slice(-2); }).join('');
  }

  // meta theme-color: тег с подходящим media важнее тега без media.
  function fromMeta() {
    var metas;
    try { metas = document.querySelectorAll('meta[name="theme-color" i]'); } catch (e) { return null; }
    var plain = null;
    for (var i = 0; i < metas.length; i++) {
      var media = metas[i].getAttribute('media');
      var content = metas[i].getAttribute('content');
      if (!content) continue;
      if (!media) { if (!plain) plain = content; continue; }
      try { if (window.matchMedia(media).matches) { var c = parse(content); if (c) return c; } } catch (e) {}
    }
    return plain ? parse(plain) : null;
  }

  // Нет тега: берём фон страницы (body, потом html).
  function fromBackground() {
    var els = [document.body, document.documentElement];
    for (var i = 0; i < els.length; i++) {
      if (!els[i]) continue;
      try {
        var c = parse(getComputedStyle(els[i]).backgroundColor);
        if (c) return c;
      } catch (e) {}
    }
    return null;
  }

  function send() {
    timer = null;
    if (sent >= MAX_SENDS) return;
    var c = fromMeta() || fromBackground();
    var value = c ? hex(c) : '';
    if (value === last) return;
    last = value;
    sent++;
    try { browser.runtime.sendNativeMessage(APP, { url: location.href, color: value }); } catch (e) {}
  }

  function schedule(ms) {
    if (timer) clearTimeout(timer);
    timer = setTimeout(send, ms);
  }

  // Страница прогружается: смотрим на нескольких этапах, затем следим за изменениями.
  schedule(60);
  document.addEventListener('DOMContentLoaded', function () { schedule(30); });
  window.addEventListener('load', function () { schedule(30); setTimeout(send, 1500); });

  // Смена светлой/тёмной темы (например, на YouTube) и правка meta или классов на html/body.
  try {
    window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', function () { schedule(50); });
  } catch (e) {}
  try {
    new MutationObserver(function () { schedule(150); }).observe(document.documentElement, {
      childList: true, subtree: false, attributes: true, attributeFilter: ['class', 'style', 'data-theme', 'theme'],
    });
  } catch (e) {}
  var watchHead = function () {
    if (!document.head) return false;
    try {
      new MutationObserver(function () { schedule(100); }).observe(document.head, {
        childList: true, subtree: true, attributes: true, attributeFilter: ['content', 'media', 'name'],
      });
    } catch (e) {}
    return true;
  };
  if (!watchHead()) document.addEventListener('DOMContentLoaded', watchHead);
  document.addEventListener('DOMContentLoaded', function () {
    try {
      new MutationObserver(function () { schedule(200); }).observe(document.body, { attributes: true, attributeFilter: ['class', 'style'] });
    } catch (e) {}
  });
})();
