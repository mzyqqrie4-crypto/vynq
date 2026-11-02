# Not used while release shrinking is off (see buildTypes in build.gradle.kts). Kept so the
# libsignal lesson isn't lost if shrinking is ever turned back on.
# vynq release rules. Compose, AndroidX and DataStore ship their own consumer rules.
# Strip verbose/debug logging in release builds.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}

# libsignal: its native (Rust) code calls Java bridge methods that no Java code calls,
# e.g. org.signal.libsignal.protocol.state.internal.SessionStore.loadSession(long).
# libsignal's own rules only keep those interfaces' names, so R8 removed the methods and
# every send failed in 0.6.0 ("JNI error Method not found: loadSession"). Keep the
# library whole, and every member of classes that implement its interfaces (our stores).
-keep class org.signal.libsignal.** { *; }
-keep interface org.signal.libsignal.** { *; }
-keepclassmembers class * implements org.signal.libsignal.** { *; }
