# ============================================================================
# Animo — R8 / ProGuard rules
# ============================================================================

# --- kotlinx.serialization: generated serializers are looked up reflection-free, but the
# --- companion `serializer()` lookup uses the generated `$serializer` field by name.
-keepclassmembers class **$$serializer {
    *** descriptor;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisibleAnnotations
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class com.animo.app.data.remote.dto.**$$serializer { *; }
-keepclassmembers class com.animo.app.data.remote.dto.** {
    *** Companion;
}
-keepclasseswithmembers class com.animo.app.data.remote.dto.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- OkHttp / Okio ---
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --- Room: entities are reflected on at runtime by the generated implementation ---
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-dontwarn androidx.room.paging.**

# --- Media3: media session service is instantiated by the framework from the manifest ---
-keep class com.animo.app.player.PlaybackService { <init>(...); }
-keep class androidx.media3.session.** { *; }
-dontwarn androidx.media3.**

# --- WorkManager instantiates the worker by class name ---
-keep class com.animo.app.data.sync.SyncWorker { <init>(...); }

# ============================================================================
# Hardening
#
# A release build must not hand a reader a map of the app: no stable package or class names,
# no log echoes of request URLs, no source file names, no line numbers. Debug builds are
# untouched, so day-to-day stack traces stay useful.
# ============================================================================

# One renamed package for everything; access may be widened so the optimizer is free to inline.
-repackageclasses 'o'
-allowaccessmodification

# Skip the generic "com.animo.app.ui.player.Something" style names.
-obfuscationdictionary obfuscation-dictionary.txt
-classobfuscationdictionary obfuscation-dictionary.txt
-packageobfuscationdictionary obfuscation-dictionary.txt

# Rewrite class-name string constants that point at obfuscated classes.
-adaptclassstrings

# Log calls (and therefore the URLs they interpolate) are compiled out of release builds.
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
}

# Our logger wraps android.util.Log; stripping it too lets R8 delete the message strings.
-assumenosideeffects class com.animo.app.AppLog {
    public *** d(...);
    public *** i(...);
    public *** w(...);
    public *** e(...);
}

# No original file names or line numbers in a release stack trace.
-renamesourcefileattribute SourceFile
