plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Phase 6 experiment (docs/06-fusion-mode-feasibility.md): a separate app, never shipped with vynq.
android {
    namespace = "app.vynq.fusion"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.vynq.fusion"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-experiment"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    testImplementation(libs.junit)
}
