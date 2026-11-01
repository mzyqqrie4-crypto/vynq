# vynq release rules. Compose, AndroidX and DataStore ship their own consumer rules.
# Strip verbose/debug logging in release builds.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
