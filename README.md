# hrips (ядро на Mozilla Android Components)

Интерфейс — Jetpack Compose (тема, иконки, шрифты hrips). Всё, что связано с движком, — готовые модули
`org.mozilla.components:*:157.0.1` (см. `app/build.gradle.kts`).

Файлы ядра: `Components.kt` (сборка store/engine/useCases), `HripsApp.kt`, `MainActivity.kt` (PromptFeature,
AppLinksFeature, WebAuthnFeature, WindowFeature), `AppRequestInterceptor.kt`, `HripsCustomTabsService.kt`, `Intents.kt`.
Интерфейс: `BrowserScreen.kt`, `StartPage.kt`, `TabSwitcher.kt`, `Search.kt`.

Сборка: `gradle assembleRelease` (нужен доступ к https://maven.mozilla.org/maven2/).
