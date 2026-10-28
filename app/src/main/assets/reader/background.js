// Подгружает Readability.js в страницу только по просьбе читалки (кнопка в меню): без этого библиотека
// (около 90 КБ) разбиралась бы на каждой загруженной странице.
browser.runtime.onMessage.addListener(function (msg, sender) {
  if (!msg || msg.t !== 'load' || !sender || !sender.tab) return undefined;
  return browser.tabs.executeScript(sender.tab.id, {
    file: 'Readability.js',
    frameId: sender.frameId || 0,
    runAt: 'document_idle'
  }).then(function () { return { ok: true }; });
});
