# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keep,includedescriptorclasses class com.litemusic.**$$serializer { *; }
-keepclassmembers class com.litemusic.** { *** Companion; }
-keepclasseswithmembers class com.litemusic.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Media3
-keep class androidx.media3.** { *; }

# Koin
-dontwarn org.koin.**

# 混淆 5 轮优化标志（R8 全量）
-optimizationpasses 5
