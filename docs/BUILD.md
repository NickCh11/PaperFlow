# Build PaperFlow

Use JDK 25, Android SDK 37, Build Tools 36.0.0, NDK 27.2.12479018 and CMake 3.22.1.
Set `sdk.dir` in your own `local.properties`, then run:

```sh
./gradlew :app:assembleDebug
```

On Windows use `.\gradlew.bat`. APKs appear in `app/build/outputs/apk/`.
The default APK includes ARMv8, ARMv7 and x86_64; select a smaller build with
`-PpaperflowAbi=arm64-v8a` or `-PpaperflowAbi=armeabi-v7a`.
Release builds use `:app:assembleRelease` and require your own signing configuration.

Native sources and runtime scan models are included. OCR and translation packs
are downloaded on demand; their private archive is maintained separately.
See [CI](CI.md) for checks and [model distribution](MODEL_DISTRIBUTION.md) for download configuration.
