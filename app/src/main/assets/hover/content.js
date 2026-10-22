(function () {
  'use strict';
  if (window.__hripsHover) return;
  window.__hripsHover = true;
  // Внутри встроенного плеера делать нечего.
  if (window !== window.top && /\/embed\//.test(location.pathname)) return;

  // Предпросмотр по удержанию пальца на превью видео. Мышь не трогаем: у неё наведение работает само.
  // Системное меню долгого нажатия не отключаем: превью идёт рядом с ним и дожидается его закрытия.
  var HOLD_MS = 150;          // через сколько после касания «наводим» курсор
  var MOVE_TOL = 10;          // сдвиг пальца, после которого это уже прокрутка
  var BEAT_MS = 250;          // как часто «шевелим» курсор, пока палец держится
  var EMBED_DELAY_MS = 450;   // сколько ждём реакции сайта, прежде чем включить своё превью
  var LINGER_MS = 8000;       // сколько превью живёт после того, как открылось меню
  var MAX_HOLD_MS = 20000;

  var HOST = location.hostname;
  var IS_YT = /(^|\.)youtube\.com$/.test(HOST);
  var IS_YT_DESKTOP = /^(www\.)?youtube\.com$/.test(HOST);

  var VIDEO_HREF = /\/(watch|shorts|video|videos|clip|clips|reel|reels|embed|live|movie|film|tv|stream)(\/|\?|$)|[?&]v=/i;
  var DURATION = /(?:^|\s)(?:\d{1,2}:)?\d{1,2}:\d{2}(?:\s|$)/;
  var CARD_SEL = 'a, article, li, [role="listitem"], ytm-media-item, ytd-rich-item-renderer';

  var hold = null;
  var lingering = null;
  var lastType = '';

  // На десктопной версии YouTube превью включается только если сайт считает, что есть мышь.
  // Под тач-экраном он отвечает «нет», поэтому пальцем не работало, а мышью работало.
  function patchMatchMedia() {
    try {
      var pw = window.wrappedJSObject;
      var orig = pw.matchMedia;
      if (!orig || typeof exportFunction !== 'function') return;
      var wrapped = function (q) {
        var m = String(q)
          .replace(/\(\s*(?:any-)?(?:hover\s*:\s*hover|pointer\s*:\s*fine)\s*\)/gi, '(min-width:0px)')
          .replace(/\(\s*(?:any-)?(?:hover\s*:\s*none|pointer\s*:\s*coarse)\s*\)/gi, '(min-width:100000px)');
        return orig.call(pw, m);
      };
      pw.matchMedia = exportFunction(wrapped, pw);
    } catch (e) {}
  }
  if (IS_YT_DESKTOP) patchMatchMedia();

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
    return list;
  }

  function cardOf(el, chain) {
    var c = el.closest(CARD_SEL);
    if (c && c !== document.body && c !== document.documentElement) return c;
    return chain[Math.min(4, chain.length - 1)];
  }

  // Похоже ли это на превью видео: есть картинка/видео и признак ролика (ссылка, длительность, тег video).
  function looksLikeVideo(card, target) {
    try {
      if (card.querySelector('video')) return true;
      if (IS_YT && youtubeId(card, target)) return true;
      if (!card.querySelector('img, picture, canvas')) return false;
      var a = target.closest('a[href]') || card.querySelector('a[href]');
      if (a && VIDEO_HREF.test(a.getAttribute('href') || '')) return true;
      var t = card.textContent || '';
      return t.length < 800 && DURATION.test(t);
    } catch (e) {
      return false;
    }
  }

  function youtubeId(card, target) {
    try {
      var a = target.closest('a[href]') || card.querySelector('a[href*="/watch"], a[href*="/shorts/"]');
      var href = a ? (a.getAttribute('href') || '') : '';
      var m = /[?&]v=([\w-]{11})/.exec(href) || /\/(?:shorts|embed|live)\/([\w-]{11})/.exec(href);
      return m ? m[1] : null;
    } catch (e) {
      return null;
    }
  }

  function create(target, x, y) {
    var chain = chainOf(target);
    var card = cardOf(target, chain);
    var root = card.parentElement && card.parentElement !== document.body ? card.parentElement : card;
    return {
      target: target, x: x, y: y, chain: chain, card: card, root: root,
      video: looksLikeVideo(card, target),
      active: false, reacted: false, menu: false, frame: null,
      timer: null, beat: null, kill: null, embedTimer: null, linger: null, mo: null
    };
  }

  function begin(target, x, y) {
    var h = create(target, x, y);
    h.timer = setTimeout(function () { activate(h); }, HOLD_MS);
    h.kill = setTimeout(function () { if (hold === h) stop(h); }, MAX_HOLD_MS);
    hold = h;
    return h;
  }

  function activate(h) {
    if ((hold !== h && lingering !== h) || h.active) return;
    h.active = true;
    try {
      h.mo = new MutationObserver(function () { h.reacted = true; });
      h.mo.observe(h.root, {
        childList: true, subtree: true,
        attributes: true, attributeFilter: ['src', 'style', 'class', 'hidden', 'poster']
      });
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
    // Сайты запускают превью после паузы наведения: держим курсор «живым», пока жест не закончился.
    h.beat = setInterval(function () {
      if (hold !== h && lingering !== h) return;
      fire(h.target, 'pointermove', h.x, h.y, true);
      fire(h.target, 'mousemove', h.x, h.y, true);
    }, BEAT_MS);
    // Если сайт сам превью не запустил (мобильный YouTube его не умеет), показываем своё.
    if (IS_YT && h.video) h.embedTimer = setTimeout(function () { showEmbed(h); }, EMBED_DELAY_MS);
  }

  function showEmbed(h) {
    if ((hold !== h && lingering !== h) || h.reacted || h.frame) return;
    var id = youtubeId(h.card, h.target);
    if (!id) return;
    try {
      var scope = h.card.querySelector('img') ? h.card : (h.card.closest(
        'ytm-media-item, ytm-compact-video-renderer, ytm-video-with-context-renderer, ytd-rich-item-renderer, ' +
        'ytd-video-renderer, ytd-compact-video-renderer, ytd-grid-video-renderer, article, li') || h.card);
      var img = scope.querySelector('img');
      if (!img) return;
      var r = img.getBoundingClientRect();
      if (r.width < 40 || r.height < 30) return;
      var f = document.createElement('iframe');
      f.src = 'https://www.youtube.com/embed/' + id +
        '?autoplay=1&mute=1&controls=0&playsinline=1&rel=0&modestbranding=1&disablekb=1&iv_load_policy=3&loop=1&playlist=' + id;
      f.setAttribute('allow', 'autoplay; encrypted-media');
      f.setAttribute('tabindex', '-1');
      f.style.cssText = 'position:fixed;border:0;margin:0;padding:0;background:#000;pointer-events:none;' +
        'z-index:2147483647;left:' + r.left + 'px;top:' + r.top + 'px;width:' + r.width + 'px;height:' + r.height + 'px;';
      document.documentElement.appendChild(f);
      h.frame = f;
    } catch (e) {}
  }

  // Полностью заканчивает жест: убирает превью и «уводит» курсор.
  function stop(h) {
    if (!h) return;
    if (hold === h) hold = null;
    if (lingering === h) lingering = null;
    clearTimeout(h.timer);
    clearTimeout(h.kill);
    clearTimeout(h.embedTimer);
    clearTimeout(h.linger);
    clearInterval(h.beat);
    if (h.mo) h.mo.disconnect();
    if (h.frame) { try { h.frame.remove(); } catch (e) {} h.frame = null; }
    if (!h.active) return;
    fire(h.target, 'pointerout', h.x, h.y, true);
    fire(h.target, 'mouseout', h.x, h.y, true);
    h.chain.forEach(function (n) {
      fire(n, 'pointerleave', h.x, h.y, false);
      fire(n, 'mouseleave', h.x, h.y, false);
    });
  }

  function dropLinger() {
    if (lingering) stop(lingering);
  }

  // Палец поднят: превью заканчивается.
  function lift() {
    if (hold && hold.menu) interrupted(); else stop(hold);
  }

  // Касание прервано. Если при этом открылось меню, превью оставляем на время, пока пользователь выбирает.
  function interrupted() {
    var h = hold;
    if (!h) return;
    if (!h.active || !(h.video || h.reacted || h.frame)) { stop(h); return; }
    hold = null;
    clearTimeout(h.kill);
    lingering = h;
    h.linger = setTimeout(function () { stop(h); }, LINGER_MS);
  }

  function touchStart(target, x, y) {
    dropLinger();
    stop(hold);
    if (target && target.nodeType === 1) begin(target, x, y);
  }

  // --- Палец: указатель ---
  window.addEventListener('pointerdown', function (e) {
    if (!e.isTrusted) return;
    lastType = e.pointerType;
    if (e.pointerType === 'mouse' || !e.isPrimary) return;
    touchStart(e.target, e.clientX, e.clientY);
  }, true);

  window.addEventListener('pointermove', function (e) {
    if (!e.isTrusted || !hold || e.pointerType === 'mouse') return;
    var dx = e.clientX - hold.x, dy = e.clientY - hold.y;
    if (dx * dx + dy * dy > MOVE_TOL * MOVE_TOL && !hold.active) stop(hold);
  }, true);

  window.addEventListener('pointerup', function (e) {
    if (e.isTrusted && e.pointerType !== 'mouse') lift();
  }, true);
  window.addEventListener('pointercancel', function (e) {
    if (e.isTrusted && e.pointerType !== 'mouse') interrupted();
  }, true);

  // --- Палец: касания (запасной путь, если указатели не доставлены) ---
  window.addEventListener('touchstart', function (e) {
    if (!e.isTrusted) return;
    lastType = 'touch';
    if (e.touches.length !== 1 || hold) return;
    var t = e.touches[0];
    touchStart(e.target, t.clientX, t.clientY);
  }, true);
  window.addEventListener('touchend', function (e) { if (e.isTrusted) lift(); }, true);
  window.addEventListener('touchcancel', function (e) { if (e.isTrusted) interrupted(); }, true);

  document.addEventListener('play', function (e) {
    var h = hold || lingering;
    if (h && h.active && e.target && h.root.contains(e.target)) h.reacted = true;
  }, true);
  document.addEventListener('playing', function (e) {
    var h = hold || lingering;
    if (h && h.active && e.target && h.root.contains(e.target)) h.reacted = true;
  }, true);

  // Долгое нажатие приходит как contextmenu. Его НЕ отменяем: меню открывается как обычно.
  // Нужно только на случай, когда сами касания до скрипта не дошли: тогда запускаем превью от него.
  window.addEventListener('contextmenu', function (e) {
    if (!e.isTrusted || lastType === 'mouse') return;
    if (hold) { hold.menu = true; return; }
    if (lingering || !e.target || e.target.nodeType !== 1) return;
    var h = create(e.target, e.clientX, e.clientY);
    if (!h.video) return;
    hold = h;
    h.kill = setTimeout(function () { if (hold === h) stop(h); }, MAX_HOLD_MS);
    activate(h);
    interrupted();
  }, true);

  // Прокрутка или уход со страницы заканчивают превью, оставшееся после меню.
  window.addEventListener('scroll', dropLinger, true);
  window.addEventListener('pagehide', function () { dropLinger(); stop(hold); }, true);
  document.addEventListener('visibilitychange', function () { if (document.hidden) { dropLinger(); stop(hold); } });
})();
