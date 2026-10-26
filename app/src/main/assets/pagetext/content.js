(function () {
  'use strict';
  if (window.__hripsPageText) return;
  window.__hripsPageText = true;

  var APP = 'hripsPageText';
  var MAX_CHARS = 100000;      // больше в приложении всё равно не хранится
  var FIRST_MS = 1200;         // после загрузки даём странице дорисоваться
  var QUIET_MS = 2500;         // после последнего изменения страницы ждём тишины
  var MIN_GAP_MS = 5000;       // не чаще одного отправления в 5 секунд
  var MAX_SENDS = 25;          // потолок на одну страницу: бесконечные ленты не должны грузить телефон

  var sent = 0;
  var lastSig = '';
  var lastAt = 0;
  var timer = null;

  // Страницы с полями пароля не индексируем: вход, банки, смена пароля.
  function sensitive() {
    try { return !!document.querySelector('input[type="password"]'); } catch (e) { return false; }
  }

  function extract() {
    timer = null;
    if (sent >= MAX_SENDS || !document.body || sensitive()) return;
    var now = Date.now();
    if (now - lastAt < MIN_GAP_MS) { schedule(MIN_GAP_MS - (now - lastAt)); return; }
    var text = '';
    try { text = (document.body.innerText || '').replace(/\s+/g, ' ').trim(); } catch (e) { return; }
    if (text.length > MAX_CHARS) text = text.slice(0, MAX_CHARS);
    var sig = text.length + ':' + text.slice(0, 80) + text.slice(-80);
    if (sig === lastSig) return;
    lastSig = sig;
    lastAt = now;
    sent++;
    try {
      browser.runtime.sendNativeMessage(APP, { url: location.href, title: document.title || '', text: text });
    } catch (e) {}
  }

  function schedule(ms) {
    if (timer) clearTimeout(timer);
    timer = setTimeout(extract, ms);
  }

  schedule(FIRST_MS);

  try {
    new MutationObserver(function () { schedule(QUIET_MS); })
      .observe(document.body || document.documentElement, { childList: true, subtree: true, characterData: true });
  } catch (e) {}

  // Уходим со страницы или сворачиваем приложение: отдаём последнее состояние.
  document.addEventListener('visibilitychange', function () {
    if (document.visibilityState === 'hidden') extract();
  });
})();
