// Команды приложения приходят кликом по действию расширения (browserAction): приложение зовёт Action.click(),
// а мы пересылаем команду в страницу активной вкладки. Ответ на sendNativeMessage до страницы в GeckoView
// не доходит, поэтому этим каналом команды не передаём.
function send(tabId) {
  browser.tabs.sendMessage(tabId, { cmd: 'toggle' }).catch(function (e) {
    console.log('[hripsReader] страница не ответила: ' + (e && e.message || e));
  });
}

browser.browserAction.onClicked.addListener(function (tab) {
  if (tab && tab.id != null) { send(tab.id); return; }
  browser.tabs.query({ active: true, currentWindow: true }).then(function (tabs) {
    if (tabs && tabs[0] && tabs[0].id != null) send(tabs[0].id);
  }, function () {});
});

// Readability.js (~90 КБ) не внедряется в каждую страницу: content.js просит подгрузить его только при открытии читалки.
// Скрипт из tabs.executeScript попадает в ту же песочницу, что и content.js, и видит функцию Readability.
browser.runtime.onMessage.addListener(function (msg, sender) {
  if (!msg || msg.cmd !== 'loadReadability' || !sender || !sender.tab || sender.tab.id == null) return undefined;
  return browser.tabs.executeScript(sender.tab.id, { file: '/Readability.js', frameId: sender.frameId || 0 })
    .then(function () { return { ok: true }; });
});
