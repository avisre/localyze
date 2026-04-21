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

# MediaPipe
-keep class com.google.mediapipe.** { *; }

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