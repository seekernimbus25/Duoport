plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CI sets GITHUB_RUN_NUMBER, so every published APK has a higher versionCode
// and installs over the previous one.
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.dualframe.camera"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.dualframe.camera"
        minSdk = 29
        targetSdk = 36
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
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
        }
        debug {
            signingConfig = signingConfigs.getByName("sideload")
        }
        // The Play Store build: same as release, signed with the private upload key.
        // `./gradlew bundlePlay` makes the .aab to upload.
        create("play") {
            initWith(getByName("release"))
            matchingFallbacks += "release"
            signingConfig = signingConfigs.findByName("upload")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // Release builds run lint; don't let a style warning block the APK.
        abortOnError = false
        checkReleaseBuilds = false
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    val camerax = "1.5.0"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-video:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    val media3 = "1.5.1"
    implementation("androidx.media3:media3-transformer:$media3")
    implementation("androidx.media3:media3-effect:$media3")
    implementation("androidx.media3:media3-common:$media3")
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-container:$media3")

    // Cinematic mode: on-device person segmentation (bundled model, no network).
    implementation("com.google.mlkit:segmentation-selfie:16.0.0-beta6")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
