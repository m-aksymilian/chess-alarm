plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Release signing comes from environment variables (set by the GitHub Actions workflow from repo secrets).
// Locally, without them, release builds fall back to the debug key so `assembleRelease` still works.
val keystorePath: String? = System.getenv("KEYSTORE_PATH")

android {
    namespace = "app.chessalarm"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "app.chessalarm"
        minSdk = 26
        targetSdk = 37
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"
        // Optional: bake a default Spotify client ID into the build (can also be entered in Settings).
        buildConfigField("String", "SPOTIFY_CLIENT_ID", "\"${System.getenv("SPOTIFY_CLIENT_ID") ?: ""}\"")
        manifestPlaceholders["redirectSchemeName"] = "chessalarm"
        manifestPlaceholders["redirectHostName"] = "spotify-callback"
        manifestPlaceholders["redirectPathPattern"] = ".*"
    }

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
            signingConfig = if (keystorePath != null) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // Screenshot previews only run on request: ./gradlew testDebugUnitTest -Pscreenshots
            it.systemProperty("screenshots", if (project.hasProperty("screenshots")) "true" else "false")
            it.systemProperty("robolectric.graphicsMode", "NATIVE")
            it.systemProperty("roborazzi.test.record", if (project.hasProperty("screenshots")) "true" else "false")
        }
    }
}

dependencies {
    implementation(files("libs/spotify-app-remote-release-0.8.0.aar"))
    implementation("com.spotify.android:auth:5.0.0")
    implementation("com.google.code.gson:gson:2.15.0") // required by Spotify App Remote

    implementation(platform("androidx.compose:compose-bom:2026.11.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.1")
    implementation("androidx.glance:glance-appwidget:1.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.18.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.79.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.79.0")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
