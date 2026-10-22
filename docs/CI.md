# Pull request checks

`.github/workflows/android-ci.yml` runs on every pull request, pushes to
`main`/`master`, merge queue groups and manual dispatch. There are no path filters.
Fork PRs need no repository secrets. Actions use immutable commit pins and read-only
repository permissions. Builds do not publish or sign releases.

## What runs

| Job | Coverage |
| --- | --- |
| Unit tests, lint and translations | JVM tests, Android Lint in all modules, localization validator and its regression tests, CI tooling tests |
| Build arm64-v8a / armeabi-v7a debug and release | Separate ARMv8 and ARMv7 debug/unsigned release APKs, including native translation code |
| Device tests API 29 and 37.0 | Complete app instrumentation suite on fresh x86_64 emulators |
| PR gate | Succeeds only when all preceding jobs succeed; failure, cancellation or a skipped job blocks this check |

ARMv8 maps to `arm64-v8a` and ARMv7 to `armeabi-v7a`. Each build job verifies
the APK's native engine libraries and ELF headers for its selected ABI.
ARMv7 uses the translation engine's ruy backend. The device test matrix runs on
x86_64; ARM builds and packaging checks do not replace tests on real ARM devices.

Device tests cover capture modes and scan filters, repeated Book captures and
review, crop and preview navigation, page rotation, eraser gestures, searchable
PDF OCR and selected pages, language downloads and integrity, native offline
translation, snapshot restoration, PDF permissions, folder PIN access and expiry,
Office conversion, exports, collage, app language and system bars.

CI creates synthetic page images instead of depending on private device photos.
The test runner limits OCR to one OpenMP thread on CI's small emulator CPUs;
this suite checks correctness, not production multithreaded performance.
Office fixtures are in `app/src/androidTest/assets/office-fixtures`. English and
Greek OCR/translation packs are fetched from the URLs in the app manifests and
verified against their pinned digests before installation. Download tests also
exercise the production network downloader; an unavailable upstream can fail the
job. Downloaded packs, private files and credentials are not committed.

Reports and APKs are retained as workflow artifacts for seven days. JVM and device report
validation rejects zero tests, failures, errors, skipped tests and missing source tests.

These checks do not establish physical camera accuracy, translation quality for
every language, or behavior on every Android device. API 26 is the app minimum;
the current complete device matrix starts at API 29 because some test inspection
APIs require it. Lint still checks production API compatibility against API 26.
Real biometric hardware and sign-in to third-party document providers still need
device/provider acceptance checks. Snapshot tests reconstruct a fresh session
from disk; they do not kill and relaunch the Android process.

## Require checks before merging

Adding a workflow alone does **not** block merging. After pushing the files and
running the workflow once, a repository administrator must enable a branch ruleset:

An importable template is provided in `.github/rulesets/pr-gate.json`. It targets
the default branch, requires PRs and the GitHub Actions `PR gate`, with no review
count requirement and no bypass actors. It is a template, not an applied rule.
Alternatively configure the same rule manually:

1. Repository **Settings > Rules > Rulesets > New branch ruleset**.
2. Target the default branch and set enforcement to **Active**.
3. Enable **Require a pull request before merging**.
4. Enable **Require status checks to pass**, add **PR gate** from GitHub Actions,
   and enable **Require branches to be up to date before merging**.
5. Leave the bypass list empty if the rule must apply to administrators too.

Use the stable `PR gate` check as required; its dependencies include both build
architectures and both device jobs. Do not require only one emulator job or the old path-filtered translation
workflow. If using a merge queue, the `merge_group` trigger provides the same gate.
Availability of branch rules depends on the repository plan and visibility.

## Run locally

Use the SDK/JDK/NDK/CMake versions documented in [BUILD.md](BUILD.md). From the project
root (Bash):

```bash
python3 -m unittest discover -s tools/localization -p 'test_*.py' -v
python3 tools/localization/check_translations.py
python3 -m unittest discover -s tools/ci -p 'test_*.py' -v
bash gradlew testDebugUnitTest lintDebug -PpaperflowAbi=x86_64
python3 tools/ci/check_test_results.py app/build/test-results/testDebugUnitTest app/src/test/java
bash gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PpaperflowAbi=x86_64
# Start a fresh API 29+ emulator, then:
python3 tools/ci/prepare_models.py --install
bash gradlew :app:connectedDebugAndroidTest -PpaperflowAbi=x86_64 -Pandroid.testInstrumentationRunnerArguments.ci=true
python3 tools/ci/check_test_results.py app/build/outputs/androidTest-results/connected app/src/androidTest/java
bash gradlew :app:assembleDebug :app:assembleRelease -PpaperflowAbi=arm64-v8a
bash gradlew :app:assembleDebug :app:assembleRelease -PpaperflowAbi=armeabi-v7a
```

On PowerShell use `./gradlew.bat` and quote the whole
`'-Pandroid.testInstrumentationRunnerArguments.ci=true'` argument. Set
`ANDROID_SERIAL` (and pass `--serial` to the model helper if needed) when more than
one device is connected. Use a dedicated emulator: tests create and remove
documents, change preferences temporarily and exercise downloads and permissions.

## Universal builds and appearance checks

The build matrix now also includes `all`: a universal APK with ARMv8, ARMv7 and x86_64. All three build jobs are required by PR gate. The native APK validator checks each included ABI separately. See [Appearance and adaptable layouts](APPEARANCE.md) for the additional night-mode and compact-screen regression checks.

The workflow installs JDK 25, matching the repository's existing `gradle/gradle-daemon-jvm.properties` criteria and the daemon used for local validation.

SDK installation uses Google's exact package ID `platforms;android-37.0` in every
Android job. The Gradle API level remains 37; the SDK package path includes `.0`.

Device jobs free unused preinstalled .NET/Haskell toolchains and Android images/NDKs
on disposable GitHub-hosted Ubuntu runners before installing the pinned Android tools.
This leaves space for the API 37.0 system image and AVD data; it does not affect local builds.
