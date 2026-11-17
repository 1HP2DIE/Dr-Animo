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

# --- The stream resolver is instantiated reflectively from ServiceLocator ---
-keep class com.animo.app.player.MegaplayStreamResolver { <init>(...); }

# --- Keep line numbers for readable crash reports, hide the original file names ---
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
