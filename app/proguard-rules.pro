# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.

# Keep Room entities
-keep class com.localyze.domain.models.** { *; }

# Keep serialized classes
-keep class com.localyze.domain.models.ToolCall { *; }
-keep class com.localyze.domain.models.ToolResult { *; }

# Keep Hilt generated classes
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }

# TensorFlow Lite
-keep class org.tensorflow.lite.** { *; }

# LiteRT-LM (native JNI classes and model loading)
-keep class com.google.ai.edge.litertlm.** { *; }
-keep class com.google.ai.edge.litertlm.** { *; }

# Kotlin Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
    *** EMPTY;
}

-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class com.localyze.**$$serializer { *; }
-keepclassmembers class com.localyze.** {
    *** Companion;
}

-keepclasseswithmembers class com.localyze.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# SQLCipher (net.zetetic:sqlcipher-android 4.6+)
-keep class net.zetetic.database.** { *; }
-keep class net.zetetic.database.sqlcipher.** { *; }

# Firebase Crashlytics
-keep class com.google.firebase.crashlytics.** { *; }
-keepattributes SourceFile,LineNumberTable
-keep public class * extends java.lang.Exception

# Play Integrity API
-keep class com.google.android.play.core.integrity.** { *; }

# Paging3
-keep class androidx.paging.** { *; }
-keep class androidx.paging.compose.** { *; }

# ── OkHttp ──────────────────────────────────────────────────────
# OkHttp uses reflection in its platform / TLS layer.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
-keepclassmembers class okhttp3.internal.publicsuffix.PublicSuffixDatabase { *; }

# ── JSoup ───────────────────────────────────────────────────────
# JSoup parses HTML reflectively and runs on the JVM stdlib.
-keep class org.jsoup.** { *; }
-keepnames class org.jsoup.nodes.**
-dontwarn org.jsoup.**

# ── WorkManager ─────────────────────────────────────────────────
# Worker subclasses are instantiated via reflection by WorkManager.
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.CoroutineWorker
-keep class * extends androidx.work.ListenableWorker
-keepclassmembers class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# ── Hilt / Dagger ───────────────────────────────────────────────
# Generated Hilt entry points + module classes are reflectively wired.
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }
-keep @dagger.hilt.android.HiltAndroidApp class *
-keep @dagger.hilt.android.AndroidEntryPoint class *
-keep @dagger.Module class *
-keep @dagger.hilt.InstallIn class *
-keepclassmembers class * {
    @javax.inject.Inject <init>(...);
}

# ── Room ────────────────────────────────────────────────────────
# Room generates impl classes ending in _Impl that are looked up by name.
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao class *
-keepclassmembers class ** {
    @androidx.room.* <methods>;
}
-dontwarn androidx.room.paging.**

# ── DataStore ───────────────────────────────────────────────────
-keep class androidx.datastore.preferences.** { *; }

# ── Billing ─────────────────────────────────────────────────────
-keep class com.android.billingclient.api.** { *; }

# ── kotlinx.coroutines (rarely needed but cheap) ────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.android.AndroidExceptionPreHandler {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# ── App models / DTOs (broad — cheap to keep, expensive to lose) ─
# All domain models go through serialization / Room / reflection in tests.
-keep class com.localyze.domain.** { *; }
-keep class com.localyze.data.** { *; }
-keep class com.localyze.ai.** { *; }
-keep class com.localyze.tools.** { *; }

# Keep enum values (ToolDispatcher, CapabilityMode, ConversationFilter, etc.)
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}