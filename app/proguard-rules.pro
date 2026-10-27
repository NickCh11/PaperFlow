# Retain diagnostic tracing entry points for instrumentation against optimized preview APKs.
-keep class androidx.tracing.Trace { *; }
-keep class kotlin.** { *; }
-keep class androidx.exifinterface.media.ExifInterface { *; }

# Insets diagnostics exercise these public AndroidX APIs directly.
-keep class androidx.core.view.ViewCompat { *; }
-keep class androidx.core.view.WindowInsetsCompat** { *; }
-keep class androidx.core.graphics.Insets { *; }

# PDFBox deliberately supports an absent optional JPEG2000 codec and handles
# Class.forName lookup failure. JP2Android is absent from both build variants.
# https://github.com/TomRoush/PdfBox-Android#reading-jpx-images
-dontwarn com.gemalto.jp2.JP2Decoder
-dontwarn com.gemalto.jp2.JP2Encoder
