plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

// CI sets GITHUB_RUN_NUMBER, so every published APK has a higher versionCode
// and installs over the previous one.
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
// Public client-side Sentry DSN; provided at build time by GitHub Actions secret.
// Never put tokens, media, scripts, user emails or model API keys here.
val studioSentryDsn = System.getenv("STUDIO_SENTRY_DSN").orEmpty().trim()
require(studioSentryDsn.isEmpty() ||
    (studioSentryDsn.startsWith("https://") && !studioSentryDsn.contains('"') &&
        !studioSentryDsn.contains('\\'))) { "STUDIO_SENTRY_DSN must be a valid HTTPS DSN" }

android {
    namespace = "com.dualframe.camera"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.dualframe.camera"
        minSdk = 29
        targetSdk = 36
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "SENTRY_DSN", "\"$studioSentryDsn\"")
    }

    signingConfigs {
        // Fixed sideload key committed to the repo so each new APK can be
        // installed over the old one. Replace with a private key (kept out of
        // git) before ever publishing to the Play Store.
        create("sideload") {
            storeFile = rootProject.file("keystore/sideload.jks")
            storePassword = "dualframe"
            keyAlias = "dualframe"
            keyPassword = "dualframe"
        }
        // Google Play upload key. Never committed: CI writes it from repository secrets
        // (see docs/PLAY_STORE.md). Google re-signs the app with its own key for users.
        val uploadStore = System.getenv("UPLOAD_KEYSTORE_FILE")
        if (!uploadStore.isNullOrEmpty() && file(uploadStore).exists()) {
            create("upload") {
                storeFile = file(uploadStore)
                storePassword = System.getenv("UPLOAD_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("UPLOAD_KEY_ALIAS")
                keyPassword = System.getenv("UPLOAD_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("sideload")
            // The sideloaded APK only carries the phone's 64-bit ARM libraries: every native
            // library (LiteRT, face detection) otherwise ships four times over, for
            // 32-bit ARM and two emulator CPUs too, ~100 MB of the APK. Play splits by CPU itself.
            ndk { abiFilters += "arm64-v8a" }
        }
        debug {
            signingConfig = signingConfigs.getByName("sideload")
        }
        // The Play Store build: same as release, signed with the private upload key.
        // `./gradlew bundlePlay` makes the .aab to upload.
        create("play") {
            initWith(getByName("release"))
            matchingFallbacks += "release"
            ndk { abiFilters.clear() }
            signingConfig = signingConfigs.findByName("upload")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // The person-cutout model is memory-mapped straight from the APK, so keep it uncompressed.
    androidResources {
        noCompress += "tflite"
    }

    lint {
        // Release builds run lint; don't let a style warning block the APK.
        abortOnError = false
        checkReleaseBuilds = false
    }

    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures { compose = true; buildConfig = true }
}

dependencies {
    implementation(project(":studio-contracts"))
    // Sentry is inert unless STUDIO_SENTRY_DSN and in-app consent are set.
    // The NDK component covers native camera / codec crashes, not only Kotlin exceptions.
    implementation("io.sentry:sentry-android:8.63.0")
    implementation("io.sentry:sentry-android-ndk:8.63.0")
    implementation(platform("androidx.compose:compose-bom:2025.06.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.room:room-testing:2.7.2")
    androidTestImplementation("junit:junit:4.13.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    val camerax = "1.5.0"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-video:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    // The phone maker's own photo processing (HDR, Night, Portrait) where it offers it to apps.
    implementation("androidx.camera:camera-extensions:$camerax")

    val media3 = "1.5.1"
    implementation("androidx.media3:media3-transformer:$media3")
    implementation("androidx.media3:media3-effect:$media3")
    implementation("androidx.media3:media3-common:$media3")
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-container:$media3")
    implementation("androidx.media3:media3-muxer:$media3")

    // Person segmentation for the design editor's cutout and Cinematic mode: Google's selfie
    // multiclass model (keeps hair and headwear), run on LiteRT (TensorFlow Lite's new name,
    // same API) with its GPU delegate. LiteRT's libraries are built for 16 KB memory pages,
    // which Play requires; TFLite 2.16's weren't.
    val litert = "1.4.2"
    implementation("com.google.ai.edge.litert:litert:$litert")
    implementation("com.google.ai.edge.litert:litert-gpu:$litert")
    implementation("com.google.ai.edge.litert:litert-gpu-api:$litert")
    // Follow me (auto-reframe): on-device face detection.
    implementation("com.google.mlkit:face-detection:16.1.7")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
