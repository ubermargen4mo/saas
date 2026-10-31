plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val geckoVersion = "157.0.20260924084938"

android {
    namespace = "com.hrips.browser"
    compileSdk { version = release(37) { minorApiLevel = 1 } }

    defaultConfig {
        applicationId = "com.hrips.browser"
        minSdk = 26
        targetSdk = 36
        versionCode = (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0) + 100
        versionName = "0.14.1"
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
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("com.google.zxing:core:3.5.3")
    val cameraxVersion = "1.5.1"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")

    testImplementation("junit:junit:4.13.2")
}
