# Retain diagnostic tracing entry points for instrumentation against optimized preview APKs.
-keep class androidx.tracing.Trace { *; }
-keep class kotlin.** { *; }
-keep class androidx.exifinterface.media.ExifInterface { *; }

# Insets diagnostics exercise these public AndroidX APIs directly.
-keep class androidx.core.view.ViewCompat { *; }
-keep class androidx.core.view.WindowInsetsCompat** { *; }
-keep class androidx.core.graphics.Insets { *; }
