# ---- sherpa-onnx (com.k2fsa.sherpa.onnx) ----
# The AAR is consumed as a local file dependency. R8 cannot see the Kotlin
# reflection-free JNI entry points, so keep the whole package.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclassmembers class com.k2fsa.sherpa.onnx.** { *; }
-dontwarn com.k2fsa.sherpa.onnx.**

# Native method holders
-keepclasseswithmembernames class * {
    native <methods>;
}

# Kotlin metadata / coroutines
-dontwarn kotlinx.coroutines.**
-dontwarn org.jetbrains.annotations.**

# Room generated code
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-dontwarn androidx.room.paging.**
