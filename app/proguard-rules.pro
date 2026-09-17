# Needler release keep rules.
#
# REQUIREMENTS.md "Security" requires release builds to be obfuscated with
# minifyEnabled and to carry no debug logging. The convention plugin
# (needler.android.application) turns on minify and resource shrinking for the
# release build type and names this file, so this file must exist: AGP 9
# defaults android.proguard.failOnMissingFiles to true.
#
# Most dependencies ship their own consumer rules and need nothing here: Room,
# Hilt/Dagger, OkHttp, Coil, Media3, WorkManager and the Compose libraries all
# do. kotlinx.serialization does not, because it is a plain JVM artifact, so its
# rules are below.

# ---------------------------------------------------------------------------
# kotlinx.serialization — the canonical rule set from the library's own README.
# Needed for the /api/v1 and OpenSubsonic DTOs in :core:network and for the
# write-queue payloads in :core:data.
# ---------------------------------------------------------------------------

# Keep `Companion` object fields of serializable classes.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

# Keep `serializer()` on companion objects (default and named) of serializable classes.
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep `INSTANCE.serializer()` of serializable objects.
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# @Serializable and @Polymorphic are read at runtime for polymorphic serialization.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

# ---------------------------------------------------------------------------
# No debug logging in release builds.
#
# Verbose and debug calls are removed outright. Warn and error are kept: the
# user-viewable diagnostics log in REQUIREMENTS.md "Observability" has to keep
# working in a release build, and it is the only place request URLs (with
# secrets redacted), status codes and playback errors are recorded.
# ---------------------------------------------------------------------------
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static boolean isLoggable(...);
}

# ---------------------------------------------------------------------------
# Line numbers, so a stack trace a user pastes into a bug report is readable,
# while class and member names stay obfuscated.
# ---------------------------------------------------------------------------
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
