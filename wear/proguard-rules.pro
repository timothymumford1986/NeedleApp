# Needler Wear OS companion release keep rules.
#
# Must exist because needler.android.application names it on the release build
# type and AGP 9 fails the build on a missing keep file.
#
# The watch app has no serialization plugin and no DTOs of its own — it talks to
# the phone over the Wearable data layer — so it needs none of the
# kotlinx.serialization rules that app/proguard-rules.pro carries. Wear Compose
# and play-services-wearable both ship consumer rules.

# No verbose/debug logging in release builds.
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static boolean isLoggable(...);
}

# Readable stack traces from user bug reports, with names still obfuscated.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
