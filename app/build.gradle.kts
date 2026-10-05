plugins {
    id("com.android.application")
    // Плагина kotlin-android здесь нет специально: в AGP 9 Kotlin встроен
    id("org.jetbrains.kotlin.plugin.compose")
}

// Версию движка меняем здесь (список: maven.mozilla.org -> maven2/org/mozilla/geckoview/geckoview).
// Берём стабильные версии вида 157.0.<build id>, без -beta и -nightly.
val geckoVersion = "157.0.20260924084938"

android {
    namespace = "com.hrips.browser"
    // GeckoView 157 в метаданных AAR требует compileSdk не ниже 37.1
    compileSdk { version = release(37) { minorApiLevel = 1 } }

    defaultConfig {
        applicationId = "com.hrips.browser"
        // GeckoView и Firefox требуют Android 8.0+, Android 12 (API 31) полностью в диапазоне
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
        // Только 64-бит ARM (все современные телефоны и планшеты). Для 32-бит добавь "armeabi-v7a"
        ndk { abiFilters.add("arm64-v8a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }

    // По умолчанию AGP выкидывает из assets каталоги вида "_*", а расширению нужен _locales.
    // Шаблон по умолчанию заменяем своим, без пункта "<dir>_*".
    androidResources {
        ignoreAssetsPatterns.addAll(
            listOf("!.svn", "!.git", "!.ds_store", "!*.scc", ".*", "!CVS", "!thumbs.db", "!picasa.ini", "!*~")
        )
    }

    // Сжимает нативные библиотеки внутри APK (иначе APK ~ равен размеру после установки)
    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    implementation("org.mozilla.geckoview:geckoview:$geckoVersion")

    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    // Expressive-компоненты живут только в линейке 1.5.0 (alpha), в стабильной 1.4.0 их нет
    implementation("androidx.compose.material3:material3:1.5.0-alpha29")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.17.0")
}
