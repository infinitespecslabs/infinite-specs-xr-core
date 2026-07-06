/*
 * :app module build script — infinite-specs-xr-core
 *
 * Android XR host application module.
 * Targets Android XR Developer Preview 4 (minSdk 33, compileSdk 36).
 */

import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace   = "com.infinitespecs.xr"
    compileSdk  = 36

    val localProperties = Properties()
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localProperties.load(FileInputStream(localPropertiesFile))
    }

    defaultConfig {
        applicationId = "com.infinitespecs.xr"
        minSdk        = 33
        targetSdk     = 36
        versionCode   = 1
        versionName   = "0.1.0-alpha"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        val googleAiApiKey = localProperties.getProperty("GOOGLE_AI_API_KEY") ?: ""
        buildConfigField("String", "GOOGLE_AI_API_KEY", "\"$googleAiApiKey\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    // ── Kotlin ───────────────────────────────────────────────────────────────
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // ── AndroidX core ────────────────────────────────────────────────────────
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    // ── Jetpack Compose ──────────────────────────────────────────────────────
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // ── Jetpack XR SDK (Developer Preview 4) ─────────────────────────────────
    implementation(libs.androidx.xr.compose)
    implementation(libs.androidx.xr.scenecore)
    implementation(libs.androidx.xr.runtime)
    implementation(libs.androidx.xr.arcore)

    // ── Ktor SSE bridge ──────────────────────────────────────────────────────
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.contentnegotiation)
    implementation(libs.ktor.serialization.json)

    // ── Google AI (Gemini) ───────────────────────────────────────────────────
    implementation(libs.generativeai)

    // ── Test ─────────────────────────────────────────────────────────────────
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit.ext)
}
