# Build PaperFlow

Use JDK 25, Android SDK Platform 37.0, Build Tools 36.0.0, NDK 27.2.12479018 and CMake 3.22.1.
The SDK Manager package is `platforms;android-37.0`; `platforms;android-37` does not exist.
Build the offline language runtime on Linux first, using Rust 1.88.0,
cargo-ndk 4.1.2, Ninja, CMake and NDK **28.0.13004108**:

```sh
bash tools/ci/build_language_runtime.sh arm64-v8a
bash tools/ci/build_language_runtime.sh armeabi-v7a
bash tools/ci/build_language_runtime.sh x86_64
```

Alternatively download the three `language-runtime-*` artifacts from the
**Offline language runtime** workflow and merge each artifact's contents into
`translation/` (retaining `src/main/jniLibs/ABI/`). On Windows use these artifacts
or build under Linux/WSL. Generated native binaries are excluded from Git.

Set `sdk.dir` in your own `local.properties`, then run:

```sh
./gradlew :app:assembleDebug
```

On Windows use `.\gradlew.bat`. APKs appear in `app/build/outputs/apk/`.
The default APK includes ARMv8, ARMv7 and x86_64; select a smaller build with
`-PpaperflowAbi=arm64-v8a` or `-PpaperflowAbi=armeabi-v7a`.
Release builds use `:app:assembleRelease` and produce unsigned APKs locally.

## GitHub Releases

Only **publishing a stable GitHub Release** starts production APK builds.
Tag pushes, PRs, merges and manual Android CI runs do not build release APKs.
PR CI keeps compilation/R8, manifest/native checks, tests, Lint and Sonar;
its temporary debug/test APKs are installed only on test emulators.

### Publish the first release

1. Merge the workflow changes into `main` through the normal reviewed PR.
2. Wait for complete **Android PR checks** on the exact `main` commit to pass,
   including both emulator APIs, native jobs, compilation/R8 and Sonar. A green
   draft/fast run or a PR merge-ref run does not authorize a release.
3. Choose a new version, initially **v1.2.2** or higher. The checked-in baseline
   is 1.2.1; the release must exceed it and every other published version.
   Thereafter the tag is the release version source: no version-file bump is
   needed for each release. Android versionCode retains the existing formula
   `major*1000000 + minor*1000 + patch` (minor/patch <=999, Android upper bound).
4. Create the tag at the verified commit, for example
   `git tag v1.2.2 <VERIFIED_MAIN_COMMIT_SHA>` and `git push origin v1.2.2`.
   Pushing this tag does not build an APK.
5. GitHub **Releases > Draft a new release**: choose that existing tag, write
   the release notes, leave **pre-release** unchecked and click **Publish release**.
6. Follow **Actions > Publish Android release**. It builds and signs ARM64,
   ARMv7 and universal APKs from that exact source, then attaches all three,
   `SHA256SUMS.txt` and `release-manifest.json` to the existing Release.

Keep the same application ID (`com.example.myapplication`) and existing signing
identity. Existing repository secrets, whose names were verified during setup:

| Secret | Purpose |
| --- | --- |
| PAPERFLOW_KEYSTORE_BASE64 | Existing production keystore, base64 encoded |
| PAPERFLOW_STORE_PASSWORD | Keystore password |
| PAPERFLOW_KEY_ALIAS | Existing signing entry alias |
| PAPERFLOW_KEY_PASSWORD | Signing entry password |

No new production key is generated. Secret presence does not prove the values
are correct: the signing step verifies the actual APK signatures and requires
one certificate across all three files. Keep offline backups; APKs previously
signed with another certificate cannot be updated in place. No published GitHub
release exists yet; distribution outside GitHub and its certificate/versionCode
could not be independently established. Preserve these secrets and confirm any
previously distributed production APK before distributing updates.

The release's tag supplies Gradle `-PpaperflowReleaseVersion`; the ordinary
`version.properties` value remains the development/Sonar baseline. Local release
builds can use the same override. In PowerShell quote the entire argument, e.g.
`'-PpaperflowReleaseVersion=1.2.2'`.

### Failure handling and permissions

Only `release: published` is enabled; no release manual dispatch or tag-push
publisher remains. Full CI must already exist for the exact main SHA, but APKs
are rebuilt during the release: publishing no longer depends on seven-day CI
APK artifacts. Native caches are reused; missing ones are rebuilt from pinned
sources without rebuilding coverage/test-only artifacts in release mode.

Build/signing jobs have read-only repository access. Only the final upload job
has `contents: write`; signing credentials are scoped to the signing step and
temporary files, never caches or artifacts. R8 mappings are retained for 30 days.
The workflow never creates a release, edits notes, moves tags or replaces assets.

All APKs must pass native architecture, application ID/version, release manifest,
16KiB alignment and signature verification before any upload. Builds/signing
failures leave no APK assets. Complete matching uploads are a no-op on rerun;
partial/conflicting assets stop the run without overwriting anything. Inspect
such assets before retrying. On upload failure, only asset IDs acknowledged as
created by that attempt are cleaned up; an ambiguous network failure may require
maintainer inspection. GitHub uploads assets individually, so attachment is not
atomic; `release-manifest.json` is uploaded last as the completion marker.

If preflight fails because CI is not finished or credentials are missing,
complete that setup and use **Re-run all jobs** on the failed release run. Editing
release notes or pushing a tag does not trigger another build. Source fixes need
a new reviewed main commit and a newer release tag; never move an existing tag.
Publish one version at a time and wait for attachment to finish before the next.

Release immutability was disabled when inspected. Do not enable it with this
published-event design: GitHub forbids adding assets after an immutable release
is published. The workflow fails clearly instead of disabling that protection.
No branch protections, approval requirements or required checks are changed.

Native source revisions and the upstream language catalog are pinned. OCR and
translation share downloadable core packs; private archives are maintained separately.
See [CI](CI.md) for checks and [model distribution](MODEL_DISTRIBUTION.md) for download configuration.
