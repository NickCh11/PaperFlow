# Android API 28 font-catalog fixture

`fonts_api28.xml` is the unmodified font configuration from AOSP tag
`android-9.0.0_r1` (Android API 28):

https://android.googlesource.com/platform/frameworks/base/+/android-9.0.0_r1/data/fonts/fonts.xml

SHA-256: `f6daca06c169fcf1615bc5496117fcb2a7dbe73b1da45e5551f20bc3d40b7814`.

The existing tests in `android_font_provider.rs` include this file at compile
time to validate language tags, fallback families, weights and TTC indices.
It is test data, not a bundled application font or a runtime dependency.
