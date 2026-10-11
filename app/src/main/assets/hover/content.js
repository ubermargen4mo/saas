// Удержание пальцем (или пером) на миниатюре имитирует наведение мыши: сайт сам запускает свой предпросмотр.
// Работает только там, где предпросмотр по наведению есть у самого сайта.
(function () {
  'use strict';
  if (window.__hripsHover) return;
  window.__hripsHover = true;

  var HOLD_MS = 200;      // через сколько удержания «наводим»; системное меню срабатывает позже (~500 мс)
  var MOVE_TOL = 10;      // сдвиг пальца в px, после которого это уже прокрутка
  var SWALLOW_MS = 800;   // после подавленного меню съедаем «клик» при отпускании

  var hold = null;
  var swallowUntil = 0;

  function make(type, x, y, bubbles) {
    var init = {
      bubbles: bubbles, cancelable: true, composed: true,
      clientX: x, clientY: y, screenX: x, screenY: y, button: 0, buttons: 0,
      pointerId: 1, pointerType: 'mouse', isPrimary: true, width: 1, height: 1, pressure: 0
    };
    try {
      return type.indexOf('pointer') === 0 ? new PointerEvent(type, init) : new MouseEvent(type, init);
    } catch (e) {
      return null;
    }
  }
  function fire(el, type, x, y, bubbles) {
    var ev = make(type, x, y, bubbles);
    if (ev) { try { el.dispatchEvent(ev); } catch (e) {} }
  }

  function chainOf(el) {
    var list = [];
    for (var n = el; n && n.nodeType === 1; n = n.parentElement) list.push(n);
    return list; // от самого вложенного к внешнему
  }

  // Контейнер карточки: следим только за ним, чтобы шум со всей страницы не считался «реакцией»
  function cardOf(el, chain) {
    var c = el.closest('a, article, li, [role="listitem"]');
    if (c && c !== document.body && c !== document.documentElement) return c;
    return chain[Math.min(4, chain.length - 1)];
  }

  function begin(target, x, y) {
    var chain = chainOf(target);
    var h = { target: target, x: x, y: y, chain: chain, active: false, reacted: false, suppressed: false, timer: null, mo: null };
    h.card = cardOf(target, chain);
    h.timer = setTimeout(function () { activate(h); }, HOLD_MS);
    hold = h;
  }

  function activate(h) {
    if (hold !== h) return;
    h.active = true;
    try {
      h.mo = new MutationObserver(function () { h.reacted = true; });
      h.mo.observe(h.card, { childList: true, subtree: true, attributes: true, attributeFilter: ['src'] });
    } catch (e) {}
    var outerFirst = h.chain.slice().reverse();
    fire(h.target, 'pointerover', h.x, h.y, true);
    fire(h.target, 'mouseover', h.x, h.y, true);
    outerFirst.forEach(function (n) {
      fire(n, 'pointerenter', h.x, h.y, false);
      fire(n, 'mouseenter', h.x, h.y, false);
    });
    fire(h.target, 'pointermove', h.x, h.y, true);
    fire(h.target, 'mousemove', h.x, h.y, true);
  }

  function finish(wasCancel) {
    var h = hold;
    hold = null;
    if (!h) return;
    clearTimeout(h.timer);
    if (h.mo) h.mo.disconnect();
    if (!h.active) return;
    fire(h.target, 'pointerout', h.x, h.y, true);
    fire(h.target, 'mouseout', h.x, h.y, true);
    h.chain.forEach(function (n) {
      fire(n, 'pointerleave', h.x, h.y, false);
      fire(n, 'mouseleave', h.x, h.y, false);
    });
    if (h.suppressed && !wasCancel) swallowUntil = Date.now() + SWALLOW_MS;
  }

  window.addEventListener('pointerdown', function (e) {
    if (!e.isTrusted || e.pointerType === 'mouse' || !e.isPrimary) return; // мышь и так наводится сама
    finish(true);
    if (e.target && e.target.nodeType === 1) begin(e.target, e.clientX, e.clientY);
  }, true);

  window.addEventListener('pointermove', function (e) {
    if (!e.isTrusted || !hold || e.pointerType === 'mouse') return;
    var dx = e.clientX - hold.x, dy = e.clientY - hold.y;
    if (dx * dx + dy * dy > MOVE_TOL * MOVE_TOL && !hold.active) finish(true);
  }, true);

  window.addEventListener('pointerup', function (e) { if (e.isTrusted) finish(false); }, true);
  window.addEventListener('pointercancel', function (e) { if (e.isTrusted) finish(true); }, true);

  // Сайт отреагировал на «наведение» (у него пошло видео): системное меню поверх не нужно
  document.addEventListener('play', function (e) {
    if (hold && hold.active && e.target && hold.card.contains(e.target)) hold.reacted = true;
  }, true);
  document.addEventListener('playing', function (e) {
    if (hold && hold.active && e.target && hold.card.contains(e.target)) hold.reacted = true;
  }, true);

  window.addEventListener('contextmenu', function (e) {
    if (!hold || !hold.active || !hold.reacted) return;
    hold.suppressed = true;
    e.preventDefault();
    e.stopImmediatePropagation();
  }, true);

  // После подавленного меню движок при отпускании может выстрелить кликом: не даём открыть ссылку
  window.addEventListener('click', function (e) {
    if (Date.now() < swallowUntil) { e.preventDefault(); e.stopImmediatePropagation(); }
  }, true);
})();
