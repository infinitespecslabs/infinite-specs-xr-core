/*
 * :app module build script — infinite-specs-xr-core
 *
 * Android XR host application module.
 * Targets the Jetpack XR SDK 1.0 (minSdk 33, compileSdk 37, targetSdk 36).
 */

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
    alias(libs.plugins.google.services)
}

android {
    namespace   = "com.infinitespecs.xr"
    compileSdk  = 37

    defaultConfig {
        applicationId = "com.infinitespecs.xr"
        minSdk        = 33
        targetSdk     = 36
        versionCode   = 1
        versionName   = "0.1.0-alpha"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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



    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            // AGP 9.5 registers Compose Preview tasks that refuse to be created
            // without this, which breaks `./gradlew tasks --all`.
            isIncludeAndroidResources = true
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

    // ── Jetpack XR SDK 1.0 (Compose for XR still beta) ─────────────────────────────────
    implementation(libs.androidx.xr.compose)
    implementation(libs.androidx.xr.scenecore)
    implementation(libs.androidx.xr.runtime)
    implementation(libs.androidx.xr.arcore)

    // ── Ktor SSE bridge ──────────────────────────────────────────────────────
    // The BOM also aligns the Ktor modules firebase-ai brings in (okhttp engine,
    // websockets, logging), which otherwise resolve to an older 3.x than ours.
    implementation(platform(libs.ktor.bom))
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.contentnegotiation)

    // ── Gemini via Firebase AI Logic ─────────────────────────────────────────
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.ai)
    debugImplementation(libs.firebase.appcheck.debug)
    constraints {
        // Play services (via Firebase) pulls in Fragment 1.1.0, which fails the
        // release lint check for registerForActivityResult. The app has no fragments.
        implementation(libs.androidx.fragment)
    }

    // ── Test ─────────────────────────────────────────────────────────────────
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
    androidTestImplementation(libs.androidx.junit.ext)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
