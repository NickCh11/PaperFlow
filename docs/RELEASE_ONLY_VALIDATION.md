# Release-only APK delivery: implementation and validation

## Workflow inventory

| Workflow | Result |
| --- | --- |
| android-ci.yml | Existing events and quality gates retained; three debug/release packaging jobs replaced by one compilation/R8, merged-manifest and selected-native-library check. No downloadable APK artifacts. |
| android-release.yml | Only release: published. Validate the published stable release, exact-main full CI and version; build/sign three APKs; attach verified assets to that existing release. |
| language-runtime.yml | Reused by CI and releases; default full validation unchanged. Release callers omit validation-only tests/coverage artifacts and cannot populate the full-validation binary cache with an incomplete payload. |
| sonarqube-cloud.yml | Unchanged. Same checks, secrets policy and quality gate. |
| model-health.yml | Unchanged scheduled/manual availability checks. |

The existing PR gate and Greptile Review contexts, maintainer review, stale-review
dismissal and branch protections were not changed. CI still covers PRs to main
and the existing feature branch, main/master pushes and merge queue entries.
Emulator tests necessarily package temporary debug/test APKs, which are not
published or uploaded as downloadable builds. R8/manifest compilation does not
package a release APK. Actual release packaging/ZIP checks run at release time.

## Changed files

* `.github/workflows/android-ci.yml`
* `.github/workflows/android-release.yml`
* `.github/workflows/language-runtime.yml`
* `app/build.gradle.kts`
* `gradle/verification-metadata.xml`
* `tools/ci/check_apk.py`
* `tools/ci/release_android.py`
* `tools/ci/test_release_validation.py`
* `tools/ci/test_release_automation.py`
* `tools/ci/test_dependency_verification.py`
* `CONTRIBUTING.md`
* `docs/BUILD.md`
* `docs/CI.md`
* `docs/PR_REVIEW.md`
* `docs/ACTIONS_COSTS.md`
* `docs/APPEARANCE.md`
* `docs/BASIC_VALIDATION.md`
* `docs/RELEASE_ONLY_VALIDATION.md`

## Local verification

* **116 CI-tooling tests** pass, including negative release/event/version/source,
  full-CI, signature/certificate, artifact integrity, duplicate upload and
  acknowledged-upload rollback cases. External writes in these tests are mocked.
* **111 JVM tests** pass (68 app, 25 scanengine, 18 translation), Android Lint,
  **22 localization tests**, resource validation and all-workflow actionlint pass.
* The real x86_64 compilation/R8/manifest task set passes after moving prior APK
  outputs aside: it does **not create app/build/outputs/apk**. Real merged native
  library and release manifest policies pass. AGP keeps unselected AAR ABIs in
  merge output; the directory check validates configured target ABIs, while the
  final APK check retains strict architecture-set enforcement.
* A local optimized unsigned APK using the release version override reports
  `com.example.myapplication`, versionName **1.2.2**, versionCode **1002002**;
  native ELF checks and 16KiB alignment pass. A copy signed with the pre-existing
  local **debug** key passes apksigner verification for minimum SDK 26, with v2
  and v3 signatures. This tests the SDK path without using production secrets.
* Static workflow boundary tests and a repository workflow/script search find
  production APK build/sign/upload invocations only in the published-release
  path. No release tag-push or manual publisher remains.

The first hosted run exposed four missing dependency-metadata checksums inherited
from the prior bootstrap: the Guava 33.4.0-jre parent POM and JUnit BOM module
metadata for 5.10.2, 5.11.0-M2 and 6.0.3. The downloaded CI verification report
confirms missing entries, not checksum mismatches. All four files were fetched
from Maven Central HTTPS and their component identities checked. JUnit's published
SHA-256 values match; Guava's POM has no published SHA-256/SHA-512, so its published
SHA-1 was cross-checked and its bytes pinned with a locally calculated SHA-256.
This is an integrity baseline, not an independent publisher-signature claim.
Strict verification and metadata verification remain enabled; no wildcard trust
or ignored artifacts were added. A regression checks the uncached Linux metadata
and all three host aapt2 pins.

A full dependency refresh additionally identified seven parent/BOM POMs: Guava
33.3.1-android, Groovy 4.0.29, Kotlin 1.8.0, coroutines 1.6.4/1.8.0/1.8.1 and
JUnit 5.13.3. All **11 added metadata files** match Maven Central's downloaded
bytes and declared component identities. Eight match published SHA-256 values;
the two Guava parents and Kotlin 1.8.0 have published SHA-1 cross-checks with
locally calculated SHA-256 pins. Every previously recorded digest is unchanged.
A subsequent `--refresh-dependencies --dependency-verification=strict` run of
JVM tests, Lint, R8 and debug instrumentation-APK assembly passes (45 seconds).

ARM release builds and live GitHub upload/signing with the production secrets
were not executed locally. A new real release was deliberately not created.
Fresh hosted CI must pass; the first authorized published release exercises the
production credentials and upload API. The previous 331/331-per-API device run
belongs to commit 80ea00d; this workflow change does not claim a new local device
run or new whole-project coverage percentage.

## Required configuration and first release

All four existing PAPERFLOW signing secret names were verified present, without
reading their values or replacing the key. GITHUB_TOKEN supplies the upload
permission; no new PAT is required. Repository release immutability was inspected
as disabled. If enabled later, publishing fails instead of weakening protection.

See [BUILD.md](BUILD.md#github-releases) for the four secret names and the exact
first-release instructions: merge, wait for full exact-main CI, create v1.2.2 or
higher at that commit, then publish a stable GitHub Release. Tag push alone and
merge to main do not produce a release APK. Future tags must increase Android's
existing versionCode mapping and retain the existing signing identity.

All three APKs are built and verified before attachment begins. GitHub asset
uploads are individual API operations: attachment cannot be atomic after the
Release is already public. The completion manifest goes last, failed uploads
clean up acknowledged IDs from that attempt, and ambiguous partial uploads block
retry until inspected. Existing assets, notes, tags and releases are never
silently replaced or created by the workflow.
