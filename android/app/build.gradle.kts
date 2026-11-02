import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi)
}

// Backend endpoint comes from git-ignored local.properties (or CI env vars).
// Missing values build an app that says honestly that accounts aren't connected.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}
fun backendSetting(key: String, env: String): String =
    (localProps.getProperty(key) ?: System.getenv(env) ?: "").trim()

// Release signing: from CI secrets (env) or a git-ignored keystore.properties next to
// local.properties. Without them the release APK is built unsigned (it won't install).
val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}
fun signingSetting(key: String, env: String): String? =
    (System.getenv(env) ?: keystoreProps.getProperty(key))?.trim()?.takeIf { it.isNotEmpty() }
val releaseKeystore = signingSetting("storeFile", "VYNQ_KEYSTORE_FILE")

android {
    namespace = "app.vynq"
    compileSdk = 36
    ndkVersion = "29.0.14206865" // only used to strip debug symbols from native libraries

    defaultConfig {
        applicationId = "app.vynq"
        minSdk = 28
        targetSdk = 36
        versionCode = 8
        versionName = "0.7.0"
        // libsignal ships large native code per ABI; every phone we target is arm64.
        // (Add "x86_64" here temporarily to run on an emulator.)
        ndk { abiFilters += listOf("arm64-v8a") }
        // Home-screen name; test builds say what they are so they can't be mistaken for the real app.
        manifestPlaceholders["appLabel"] = "vynq"
        buildConfigField("String", "SUPABASE_URL", "\"${backendSetting("vynq.supabaseUrl", "VYNQ_SUPABASE_URL")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${backendSetting("vynq.supabaseAnonKey", "VYNQ_SUPABASE_ANON_KEY")}\"")
    }

    signingConfigs {
        if (releaseKeystore != null) create("release") {
            storeFile = rootProject.file(releaseKeystore)
            storePassword = signingSetting("storePassword", "VYNQ_KEYSTORE_PASSWORD")
            keyAlias = signingSetting("keyAlias", "VYNQ_KEY_ALIAS")
            keyPassword = signingSetting("keyPassword", "VYNQ_KEY_PASSWORD")
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            manifestPlaceholders["appLabel"] = "vynq debug"
        }
        release {
            // No shrinking or renaming (owner decision after 0.6.0): native libraries
            // (libsignal, WebRTC, SQLCipher) call Java code by name, and R8 broke sending in
            // 0.6.0. Release runs the same code as the tested debug build; the APK is larger.
            isMinifyEnabled = false
            isShrinkResources = false
            // Signing key from CI secrets or keystore.properties; never in git.
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
        // Same code as release, installed beside it ("vynq staging") and signed with the
        // public debug key: for reproducing release-only bugs without the real signing key.
        create("staging") {
            initWith(getByName("release"))
            applicationIdSuffix = ".staging"
            versionNameSuffix = "-staging"
            manifestPlaceholders["appLabel"] = "vynq staging"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true // required by libsignal
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // libsignal-client carries desktop natives (Windows/Linux/macOS, ~600 MB) as
        // jar resources; Android uses the per-ABI libraries from libsignal-android.
        resources.excludes += listOf("/libsignal_jni*.so", "/libsignal_jni*.dylib", "/signal_jni*.dll")
        // Signal's test-only build of the native library.
        jniLibs.excludes += "**/libsignal_jni_testing.so"
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":core:designsystem"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.libsignal.android)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(libs.sqlcipher.android)
    implementation(libs.androidx.sqlite)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.work.runtime)
    implementation(libs.livekit.android)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    // Desktop build of libsignal (Windows/Linux/macOS natives) so JVM tests run the real protocol.
    testImplementation(libs.libsignal.client)
    // JVM screenshot tests (Robolectric native graphics + Roborazzi): `gradlew recordRoborazziDebug`
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}

// The desktop libsignal used by JVM unit tests is compiled for Java 21; the
// app itself still targets Java 17.
tasks.withType<Test>().configureEach {
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
}
