/*
 * Root build script — infinite-specs-xr-core
 *
 * Applies the Android + Kotlin Gradle plugins to all sub-projects via the
 * version catalog defined in gradle/libs.versions.toml.
 *
 * Compatible with Android XR Developer Preview 4 build toolchain.
 */

// Top-level build file: configuration here applies to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application)  apply false
    alias(libs.plugins.android.library)      apply false
    alias(libs.plugins.kotlin.android)       apply false
    alias(libs.plugins.kotlin.compose)       apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
