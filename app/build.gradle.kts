plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val geckoVersion = "159.0.20261102144711"

// Номер сборки GitHub Actions: 196 -> 1.1.96, 213 -> 1.2.13, 375 -> 1.3.75
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0

android {
    namespace = "com.hrips.browser"
    compileSdk { version = release(37) { minorApiLevel = 2 } }

    defaultConfig {
        applicationId = "com.hrips.browser"
        minSdk = 31
        targetSdk = 36
        versionCode = buildNumber + 100
        versionName = "1.${buildNumber / 100}.${buildNumber % 100}"
        ndk { abiFilters.add("arm64-v8a") }
        buildConfigField("String", "UPDATE_REPO", "\"${System.getenv("GITHUB_REPOSITORY") ?: ""}\"")
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
    testOptions {
        unitTests.isIncludeAndroidResources = true
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
    implementation("org.mozilla.geckoview:geckoview:$geckoVersion")

    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3:1.5.0-alpha29")
    implementation("androidx.compose.material3:material3-window-size-class:1.4.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.browser:browser:1.8.0")
    implementation("com.google.zxing:core:3.5.4")
    // Веб-push без Google: дистрибьютор UnifiedPush (ntfy, Sunup, NextPush), расшифровка RFC 8291 внутри библиотеки.
    implementation("org.unifiedpush.android:connector:3.3.5")
    // Встроенный дистрибьютор FCM: push через сервисы Google Play без Firebase-проекта и без сторонних приложений.
    implementation("org.unifiedpush.android:embedded-fcm-distributor:3.1.0")
    val cameraxVersion = "1.5.1"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")

    testImplementation("junit:junit:4.13.2")

    // Скриншоты интерфейса на JVM (Robolectric + Roborazzi), без устройства: ./gradlew testDebugUnitTest -Pscreenshots
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.78.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.78.0")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("io.mockk:mockk:1.14.12")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

tasks.withType<Test>().configureEach {
    val screenshots = project.hasProperty("screenshots")
    if (!screenshots) exclude("**/screens/**")
    systemProperty("roborazzi.test.record", "true")
    systemProperty("roborazzi.output.dir", rootProject.layout.projectDirectory.dir("screenshots").asFile.absolutePath)
    systemProperty("robolectric.pixelCopyRenderMode", "hardware")
    maxHeapSize = (project.findProperty("testXmx") as String?) ?: "1536m"
    jvmArgs(
        "-XX:+UseSerialGC",
        "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
        "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
        "--add-opens=java.base/java.io=ALL-UNNAMED",
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
    )
}
