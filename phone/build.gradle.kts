import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Set by the GitHub Actions workflow. Phone and watch apps MUST be signed with the same key,
// otherwise the Wearable Data Layer will not connect them.
val keystorePath: String? = System.getenv("SIGNING_KEYSTORE_FILE")

// The workflow passes the GitHub Actions run number, so every build has a higher version.
val buildNumber: Int = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1

android {
    namespace = "io.github.salmansoltaniyan.lessonsubtitles.phone"
    compileSdk = 35

    defaultConfig {
        // Must be identical to the wear module's applicationId.
        applicationId = "io.github.salmansoltaniyan.lessonsubtitles"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystorePath != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true // BuildConfig.VERSION_CODE is used by the update check
    }

    lint {
        // The app is side-loaded, not published on Google Play.
        disable += "ExpiredTargetSdkVersion"
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.media3:media3-exoplayer:1.5.0")
    implementation("androidx.media3:media3-session:1.5.0")
    implementation("com.google.guava:guava:33.3.1-android")
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.9.0")
    implementation("androidx.wear:wear-remote-interactions:1.0.0")
}
