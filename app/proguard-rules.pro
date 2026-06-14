# ProGuard rules — infinite-specs-xr-core
# Add project-specific ProGuard rules here.
# See: https://developer.android.com/studio/build/shrink-code

# Keep Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }

# Keep MCP envelope classes
-keep class com.infinitespecs.xr.bridge.McpEnvelope { *; }
-keep class com.infinitespecs.xr.bridge.McpParams    { *; }
