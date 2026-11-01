// AGP 8.13 bundles an R8 that can't read Kotlin 2.4 metadata. Pin a newer R8
// until the move to AGP 9 (which needs the built-in Kotlin migration).
buildscript {
    repositories { google() }
    dependencies { classpath(libs.r8) }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.roborazzi) apply false
}
