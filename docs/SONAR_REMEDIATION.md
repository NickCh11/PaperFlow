# Sonar remediation, 26 October 2026

The project overview currently describes the last analysis of
`feature/offline-translator`. PR #1 analyzes the proposed changes separately.
Unmerged fixes cannot change that branch's historical ratings. A passing PR gate
does not establish whole-project 100% coverage or absence of defects.

## Changes submitted for analysis

| Finding | Remediation |
| --- | --- |
| Page mask indexing | Validate dimensions and source length before resampling; use long scaling arithmetic. JVM cases cover valid scaling, truncated inputs and overflow. |
| Release obfuscation | Enable full optimized R8 and resource shrinking. Preserve the mapping for each ABI as a CI artifact. |
| Unverified dependencies | Commit SHA-256 verification metadata for artifacts and metadata, including Linux/macOS/Windows aapt2; require strict verification. |
| Backup / cleartext | Disable debug backup and explicitly disable cleartext in both manifests. Release already disables backup. |
| Cross-thread generation counters | Replace camera live-analysis, PDF-layout and scan-editor counters with AtomicInteger. UI-only counters remain plain integers. |
| Regex runtime | Use linear scanning for Excel format literals, literal decimal-format matching and literal naming-placeholder validation. |
| Native detector lifetime | Replace per-thread native detectors with one lazily created detector; serialize only native detection. |
| Floating-point increment | Use an integer pixel sample count. |
| Misleading save/SAVE names | Rename the long-image state method to saveState, including its regression caller. |
| Constant callback results | Reject unsupported PDF actions and touches after close/destroy or without an image. |
| Five PR Android Lint issues | Use START gravity, implement accessibility clicks, reuse drawing rectangles/cache page labels, remove one unused resource. |
| Duplicated code | Share editor view construction, document-directory creation and two-finger panning across the affected screens. |

The interruption warning in ModelDownload and the older PdfViewer volatile
counter warnings refer to code already changed before this batch. Download
interruption restores the interrupt and exits through cancellation. The current
viewer version counter is UI-thread confined.

The shared pan helper also resets on a multi-pointer CANCEL. New unit cases
exercise relative translation, consuming the remaining finger and cancellation.
The device regression checks that panning discards a provisional pen mark and
that the next real stroke succeeds, without depending on a removed private flag.

## Security findings requiring a design review

These findings remain open for review; this change does not change their Sonar
status, suppress their rules or claim that they are fixed by unrelated edits.

* **Debug DocumentsProvider permission:** Android requires both read and write
  permissions to be MANAGE_DOCUMENTS, as well as exported=true and URI grants.
  Splitting the permission breaks DocumentsProvider.attachInfo. This provider
  exists only in debug; the merged-release-manifest guard rejects it in release.
  See [AOSP DocumentsProvider](https://android.googlesource.com/platform/frameworks/base/+/HEAD/core/java/android/provider/DocumentsProvider.java).
* **Gallery export:** Gallery copies are an explicit optional user feature.
  Android 29+ uses MediaStore pending rows with commit/rollback; Android 26–28
  requires WRITE_EXTERNAL_STORAGE and writes to Pictures/PaperFlow. Names use
  fixed labels, generated UUIDs and platform MIME extensions. These copies are
  intentionally shared gallery media and do not inherit folder-lock privacy.
* **Biometrics without CryptoObject:** FolderLock is an app-level access gate,
  explicitly documented as distinct from an encrypted vault. Files use Android
  private storage. Strong biometrics or the folder PIN opens a five-minute
  in-process session. CryptoObject alone would not encrypt existing documents.
  A cryptographically encrypted vault requires a separate design, key recovery,
  migration and device acceptance work; it is not claimed here.
  [Android's biometric guide](https://developer.android.com/identity/sign-in/biometric-auth)
  distinguishes a basic authentication prompt from authentication-bound
  cryptographic operations. This distinction is not evidence of an encrypted vault.

## Build integrity and release evidence

Verification metadata is a bootstrap baseline from the configured Google/Maven
repositories, not proof that every dependency is trustworthy. Published SHA-256
digests were separately compared for AGP, the three aapt2 host jars and PdfBox.
The remaining entries use trust on first use. Signatures are not verified.

When intentionally updating a dependency, regenerate metadata for the affected
build/test configurations with `--write-verification-metadata sha256`, inspect
the exact version/digest diff and check publisher digests or signatures where
available. Do not disable verification or add wildcard trusted artifacts to make
a failing build pass.

A separate offline verification probe resolves JUnit with the committed
metadata. Replacing just its expected JAR digest with zeroes fails with an
incorrect-checksum report; restoring the digest passes. Production metadata is
not changed by the negative probe.

The optimized x86_64 release APK builds and its mapping contains renamed app
classes. A locally debug-signed copy cold-launches on API 29 and completes real
image import, embedded offline OCR and English-to-Greek translation. This copy
is not a published or production-signed release. Physical ARM devices and other
release features still need acceptance testing.

R8 suppresses only the two absent optional PdfBox JPEG2000 codec class warnings.
PdfBox handles their absence through Class.forName; no JP2 codec was previously
included in either variant. See [upstream JPX support](https://github.com/TomRoush/PdfBox-Android#reading-jpx-images).
No broad missing-class or Sonar suppression was added.

## Validation status

Current local evidence includes 111 passing JVM tests, JVM/Lint/release builds,
91 CI-tooling tests, 22 localization tests, translation validation and workflow
lint. A fresh full run passes all 331 source cases independently on API 29 and
API 37, with no failures, errors or skips, at 640x1280/density 320/130% font scaling
and disabled animations. The next cloud analysis must be checked before claiming
updated ratings or duplication measurements. Historical coverage is not reused
as a current measurement after production classes change.

The preceding uploaded revision passed Sonar (83.5% new-code coverage, 0% new-code
duplication), API 29 and all ABI builds, but failed API 37. Its report includes
System UI not responding, loss of window focus and clipboard-denied failures.
CI now bounds the software-rendered display to 640x1280 at density 320 before
the full run, matching the locally exercised setup. All test cases and the strict
merge gate remain required; a fresh hosted run is necessary to verify this change.
