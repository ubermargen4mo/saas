plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val mozilla = "157.0.1"
fun moz(name: String) = "org.mozilla.components:$name:$mozilla"

android {
    namespace = "com.hrips.browser"
    compileSdk { version = release(37) { minorApiLevel = 1 } }

    defaultConfig {
        applicationId = "com.hrips.browser"
        minSdk = 26
        targetSdk = 36
        versionCode = (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0) + 100
        versionName = "1.0.0-moz1"
        ndk { abiFilters.add("arm64-v8a") }
    }

    val keystorePath: String? = System.getenv("KEYSTORE_FILE")
    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (keystorePath != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        localeFilters += listOf("en", "ru")
        ignoreAssetsPatterns.addAll(
            listOf("!.svn", "!.git", "!.ds_store", "!*.scc", ".*", "!CVS", "!thumbs.db", "!picasa.ini", "!*~")
        )
    }

    packaging {
        jniLibs { useLegacyPackaging = true }
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/DebugProbesKt.bin",
            "/kotlin-tooling-metadata.json",
        )
    }
}

dependencies {
    // ── Ядро: готовые модули Mozilla Android Components (тот же движок и логика, что в Firefox для Android) ──
    implementation(moz("browser-engine-gecko"))
    implementation(moz("browser-state"))
    implementation(moz("browser-session-storage"))
    implementation(moz("browser-errorpages"))
    implementation(moz("concept-engine"))
    implementation(moz("concept-fetch"))
    implementation(moz("concept-storage"))
    implementation(moz("lib-state"))
    implementation(moz("support-base"))
    implementation(moz("support-ktx"))
    implementation(moz("support-utils"))
    implementation(moz("service-digitalassetlinks"))
    implementation(moz("feature-session"))
    implementation(moz("feature-tabs"))
    implementation(moz("feature-app-links"))
    implementation(moz("feature-customtabs"))
    implementation(moz("feature-prompts"))
    implementation(moz("feature-webauthn"))

    // Glean у Mozilla подключён как compileOnly: без него модули падают при старте.
    implementation("org.mozilla.telemetry:glean:68.0.1")

    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.fragment:fragment-ktx:1.9.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-process:2.11.0")
    implementation("androidx.browser:browser:1.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3:1.5.0-alpha29")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")

    testImplementation("junit:junit:4.13.2")
}

// GeckoView уже содержит нативную часть Glean. Автономный glean-native конфликтует с ней по capability,
// поэтому, как и в самой Mozilla, выбираем вариант из GeckoView.
configurations.configureEach {
    resolutionStrategy.capabilitiesResolution.withCapability("org.mozilla.telemetry:glean-native") {
        val gecko = candidates.firstOrNull {
            (it.id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier)?.module?.contains("geckoview") == true
        }
        if (gecko != null) {
            select(gecko)
            because("use GeckoView Glean instead of standalone Glean")
        }
    }
}
