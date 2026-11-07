-optimizationpasses 5
-allowaccessmodification

# Keep JNI methods in NativeEngine
-keepclasseswithmembernames class com.vibe.tv.core.NativeEngine {
    native <methods>;
}

# Keep Media3 / ExoPlayer constructors
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**
-dontwarn okhttp3.**
-dontwarn okio.**
