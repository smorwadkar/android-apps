# --- CloudShelf ProGuard / R8 rules ---

# Compose / Kotlin metadata is kept by AGP defaults; this file holds the extras
# we need because of the SDKs we pull in.

# --- AWS SDK for Kotlin ---
# Smithy-generated service clients use reflection-driven serializers.
-keep class aws.sdk.kotlin.** { *; }
-keep class aws.smithy.kotlin.** { *; }
-keep class software.amazon.awssdk.** { *; }
-keepclassmembers class aws.sdk.kotlin.** { *; }
-keepclassmembers class aws.smithy.kotlin.** { *; }

# --- Amplify (Cognito Auth) ---
-keep class com.amplifyframework.** { *; }
-keep class com.amazonaws.** { *; }
-dontwarn com.amazonaws.**

# --- Coroutines internal classes referenced via reflection in stack traces ---
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# --- Media3 ExoPlayer ---
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# --- Room generated DAOs ---
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }

# --- Hilt ---
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper

# --- Timber ---
-keep class timber.log.** { *; }

# Strip log/debug from release
-assumenosideeffects class timber.log.Timber$Tree {
    public void v(...);
    public void d(...);
}

# Don't strip line numbers — we want readable stack traces from Crashlytics.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
