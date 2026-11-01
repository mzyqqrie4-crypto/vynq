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

android {
    namespace = "app.vynq"
    compileSdk = 36
    ndkVersion = "29.0.14206865" // only used to strip debug symbols from native libraries

    defaultConfig {
        applicationId = "app.vynq"
        minSdk = 28
        targetSdk = 36
        versionCode = 3
        versionName = "0.3.0-phase3"
        // libsignal ships large native code per ABI; every phone we target is arm64.
        // (Add "x86_64" here temporarily to run on an emulator.)
        ndk { abiFilters += listOf("arm64-v8a") }
        buildConfigField("String", "SUPABASE_URL", "\"${backendSetting("vynq.supabaseUrl", "VYNQ_SUPABASE_URL")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${backendSetting("vynq.supabaseAnonKey", "VYNQ_SUPABASE_ANON_KEY")}\"")
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Release signing is injected by CI from encrypted secrets (Phase 8). No keystore in git.
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
