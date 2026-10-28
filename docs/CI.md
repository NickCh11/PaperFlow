# Pull request checks

`.github/workflows/android-ci.yml` runs on every pull request, pushes to
`main`/`master`, merge queue groups and manual dispatch. There are no path filters.
Fork PRs need no repository secrets. Actions use immutable commit pins and read-only
repository permissions. CI does not package release APKs, publish or sign releases.

## What runs

| Job | Coverage |
| --- | --- |
| Unit tests, lint and translations | JVM tests, Android Lint in all modules, localization validator and its regression tests, CI tooling tests |
| Offline language runtime | Build the pinned Rust/slimt/MNN/CLD2 runtime for ARM64, ARMv7 and x86_64, with cached native dependencies |
| Android compilation and native checks | One compilation/R8 check, merged release manifest policy and merged native ELF/engine verification for ARM64, ARMv7 and x86_64; no release APK |
| Device tests API 29 and 37.0 | Complete app instrumentation suite on fresh x86_64 emulators |
| PR gate | Succeeds only when all preceding jobs succeed; failure, cancellation or a skipped job blocks this check |

ARMv8 maps to `arm64-v8a` and ARMv7 to `armeabi-v7a`. Native engine libraries
and ELF headers are verified directly in AGP's merged library tree. The common
compilation job also checks AGP's merged release manifest with
`tools/ci/check_android_manifest.py`, including dependency declarations. Unsafe
debug/test-only/cleartext flags, additional permissions, unexpected exported
components, weakened file-sharing configuration and the debug document provider
fail the existing build/PR gate. See [BASIC_VALIDATION.md](BASIC_VALIDATION.md)
for the core scenario map and real-device acceptance limits.
The device test matrix runs on
x86_64; ARM builds and packaging checks do not replace tests on real ARM devices.

Device tests cover capture modes and scan filters, repeated Book captures and
review, crop and preview navigation, page rotation, eraser gestures, searchable
PDF OCR and selected pages, language downloads and integrity, native offline
translation, snapshot restoration, PDF permissions, folder PIN access and expiry,
Office conversion, exports, collage, app language and system bars.

CI creates synthetic page images instead of depending on private device photos.
Language fixtures use the upstream v6 core packs and PaddleOCR script models.
The fixture installer never seeds the retired Tesseract language directory.
This suite checks correctness, not production multithreaded performance.
Office fixtures are in `app/src/androidTest/assets/office-fixtures`. English and
Greek core packs are fetched from the upstream
catalog. Transport sizes and gzip integrity are checked; device fixture copies
are compared against the local files with SHA-256. The catalog does not publish
upstream content digests. Download tests also
exercise the production network downloader; an unavailable upstream can fail the
job. Downloaded packs, private files and credentials are not committed.

The suite validates the app's integration with pinned upstream models, rather
than benchmarking their language accuracy. It retains real offline recognition,
automatic source/error propagation, mixed-script searchable PDF text, unchanged
PDF appearance, Word output, translation sessions, download integrity and
cancellation. Exhaustive language/image corpora and OCR word-error-rate scoring
were removed at the owner's request. App-owned script-routing boundaries remain
covered by fast deterministic JVM tests. Both Android APIs run the same complete
remaining suite; there is no hidden skip list or reduced Sonar threshold.

Reports are retained as workflow artifacts for three days. No downloadable APK
artifacts are produced by PR/push CI. Temporary debug/test APKs are necessary
for emulator execution. JVM and device report
validation rejects zero tests, failures, errors, skipped tests and missing source tests.

These checks do not establish physical camera accuracy, translation quality for
every language, or behavior on every Android device. API 26 is the app minimum;
the current complete device matrix starts at API 29 because some test inspection
APIs require it. Lint still checks production API compatibility against API 26.
Real biometric hardware and sign-in to third-party document providers still need
device/provider acceptance checks. Snapshot tests reconstruct a fresh session
from disk; they do not kill and relaunch the Android process.

## Require checks before merging

Two active rulesets protect `main` and `feature/offline-translator`:

- Mandatory PR checks and branch safety requires **PR gate**,
  an up-to-date branch, resolved threads and a PR; deletion/force-push are blocked.
  It has no bypass actors, including administrators.
  **Greptile Review is optional**: the app remains installed, but exhausted review
  credits do not block merging. The PR gate still requires full CI and Sonar.
- PR approval with maintainer exception requires one approving review, dismisses
  stale approvals and requires latest-push approval. Maintainer/Admin roles can
  bypass this review rule only through a PR. The exception depends on who merges,
  not who authored the PR. It does not bypass the mandatory CI ruleset.

Push validation runs on main/master; working branches use PR validation. Sonar's
primary branch is main for PR analysis after the migration.

`PR gate` remains the stable required check and depends on compilation/native
checks, both emulator APIs, native builds, static checks and Sonar. Ready PRs run
full checks automatically; drafts use `Draft / fast checks` and cannot satisfy
the merge gate. No `full-validation` label is necessary for a ready PR.

The checked-in ruleset JSON is an older minimal template, not a snapshot of the
live policy. Do not import it over the existing maintainer-review ruleset.

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
python3 tools/ci/prepare_emulator.py
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

CI checks the three configured native architectures without packaging distributable APKs. Only the published-release workflow builds ARM64, ARMv7 and universal APKs; its native APK validator checks each included ABI separately. See [Appearance and adaptable layouts](APPEARANCE.md) for the additional night-mode and compact-screen regression checks.

The workflow installs JDK 25, matching the repository's existing `gradle/gradle-daemon-jvm.properties` criteria and the daemon used for local validation.

SDK installation uses Google's exact package ID `platforms;android-37.0` in every
Android job. The Gradle API level remains 37; the SDK package path includes `.0`.

Device jobs free unused preinstalled .NET/Haskell toolchains and Android images/NDKs
on disposable GitHub-hosted Ubuntu runners before installing the pinned Android tools.
This leaves space for the API 37.0 system image and AVD data; it does not affect local builds.

The emulator action uses Command-line Tools 22.0 through its `latest` path. Older
runner tools can silently create an invalid `android-0` AVD target for API 37.0;
CI checks the generated target before booting. API 29 has 2 GB RAM and API 37 has
4 GB RAM; both have an 8 GB
data partition. The language fixture installer waits for the package manager and
collects emulator logcat on installation failures.

Before any tests or fixture installation, API 29 preparation sets ART's growth
limit to 256 MiB and maximum heap to 512 MiB. The emulator action's `heap-size`
alone leaves a 16 MiB growth limit on some images, which can crash even
`system_server` during background system updates. The helper temporarily roots
only an API 29 emulator, restarts the Android framework, verifies a new system
process and both properties, then restores unprivileged adb and waits for a
stable package manager. Other APIs are unchanged. The CI test runner also checks
the actual Java heap before starting the suite, so a bad configuration fails
clearly rather than interrupting an unrelated test. Pass `--serial` and `--adb`
to the helper when selecting a dedicated local emulator or SDK installation.
