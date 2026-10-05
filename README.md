# Hrips
Браузер на GeckoView + Jetpack Compose (Material 3 Expressive).

Сборка: push в GitHub -> Actions -> Artifacts -> `hrips-apk`.
Локально: Android Studio (нужны SDK Platform 37.1, JDK 17, Gradle 9.6+).

## Версии
- Движок: `geckoVersion` в `app/build.gradle.kts` (стабильные 157.0.*; список на maven.mozilla.org).
- GeckoView 157 требует compileSdk 37.1 и Kotlin 2.4.x, поэтому AGP 9.4.1 + Gradle 9.6.0.
- Android 8.0+ (minSdk 26), проверяется на Android 12 и 16.

## Шрифт
`res/font/roboto_flex_*.ttf` - статические начертания Roboto Flex (латиница + кириллица),
получены из вариативного шрифта сайта (opsz 14, wght 400/500/600/700/800).
