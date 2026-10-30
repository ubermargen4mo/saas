// Команды от приложения приходят кликом по действию расширения (browserAction): приложение зовёт Action.click(),
// а мы пересылаем команду в страницу активной вкладки. Обычный канал «приложение -> страница» через ответ на
// sendNativeMessage в GeckoView до страницы не доходит (ответ приложения терялся), поэтому им не пользуемся.
function send(tabId) {
  browser.tabs.sendMessage(tabId, { cmd: 'toggle' }).catch(function (e) {
    try { console.log('[hripsReader] страница не ответила: ' + (e && e.message || e)); } catch (x) {}
  });
}

browser.browserAction.onClicked.addListener(function (tab) {
  if (tab && tab.id != null) { send(tab.id); return; }
  browser.tabs.query({ active: true, currentWindow: true }).then(function (tabs) {
    if (tabs && tabs[0] && tabs[0].id != null) send(tabs[0].id);
  }, function () {});
});
