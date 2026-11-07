-optimizationpasses 5
-allowaccessmodification

-keepclasseswithmembernames class com.vibe.tv.core.NativeEngine {
    native <methods>;
}

-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**
-dontwarn okhttp3.**
-dontwarn okio.**
