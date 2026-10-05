plugins {
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
    // GeckoView 157 тянет kotlin-stdlib 2.4.20, а встроенный в AGP 9 Kotlin по умолчанию 2.2.x
    // и не умеет читать метаданные 2.4. Объявление плагина (он нигде не применяется) выбирает 2.4.20.
    id("org.jetbrains.kotlin.jvm") version "2.4.20" apply false
}

buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin") {
            version { strictly("2.4.20") }
        }
    }
}
