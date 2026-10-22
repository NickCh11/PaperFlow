# Build PaperFlow

Use JDK 25, Android SDK Platform 37.0, Build Tools 36.0.0, NDK 27.2.12479018 and CMake 3.22.1.
The SDK Manager package is `platforms;android-37.0`; `platforms;android-37` does not exist.
Set `sdk.dir` in your own `local.properties`, then run:

```sh
./gradlew :app:assembleDebug
```

On Windows use `.\gradlew.bat`. APKs appear in `app/build/outputs/apk/`.
The default APK includes ARMv8, ARMv7 and x86_64; select a smaller build with
`-PpaperflowAbi=arm64-v8a` or `-PpaperflowAbi=armeabi-v7a`.
Release builds use `:app:assembleRelease` and produce unsigned APKs locally.

## GitHub Releases

The app version lives in [`version.properties`](../version.properties), for example
`versionName=1.0.0`. Android's versionCode increases automatically with that version.

After changing the version and merging into `main`, wait for Android PR checks to
pass, then run **Actions > Publish Android release > Run workflow** on `main`.
Alternatively push a matching tag, such as `v1.0.0`, for that commit.

The workflow reuses the exact commit's tested release APKs, signs them, verifies
their version and certificate, and publishes ARMv8, ARMv7 and universal APKs plus
`SHA256SUMS.txt`. Releases require a newer version and never move an existing tag.
CI artifacts expire after seven days; rerun Android PR checks for older commits.

Maintainers configure these Actions Secrets once: `PAPERFLOW_KEYSTORE_BASE64`,
`PAPERFLOW_STORE_PASSWORD`, `PAPERFLOW_KEY_ALIAS`, `PAPERFLOW_KEY_PASSWORD`.
Keep an offline backup of the signing keystore and passwords: future updates must
use the same key. Signing material belongs outside Git. Release signing uses only
temporary files, with no Gradle signing configuration or signing-key cache.
Debug APKs use a different certificate and cannot be updated in place with release APKs.

Native sources and runtime scan models are included. OCR and translation packs
are downloaded on demand; their private archive is maintained separately.
See [CI](CI.md) for checks and [model distribution](MODEL_DISTRIBUTION.md) for download configuration.
