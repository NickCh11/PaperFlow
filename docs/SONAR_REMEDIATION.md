# Sonar remediation, 27 October 2026

The project overview currently describes the last analysis of
`feature/offline-translator`. PR #1 analyzes the proposed changes separately.
Unmerged fixes cannot change that branch's historical ratings. A passing PR gate
does not establish whole-project 100% coverage or absence of defects.

## Latest analyzed revision and tap-handling correction

Run `40542672471` analyzed `01c7ebc` after both complete API 29/37 device
jobs, all three native ABI builds, compilation, JVM/Lint and Greptile passed.
Sonar reports 81.75% new-code coverage and 0.0% new-code duplication. The
previous 32 findings are closed. One remaining medium reliability finding at
CameraCaptureActivity line 244 blocks the gate: the analyzer treats the
callback-mutated tapCompleted flag as always false after it is reset.

The correction removes that flag and uses GestureDetector.onTouchEvent's
handled result for ACTION_UP. The single-tap callback returns true only after
an eligible focus request; busy or unavailable cameras return false. Touch
clicks still preserve the tapped focus point; accessibility clicks focus the
center. Regression checks cover ordinary taps, accessibility clicks, swipe,
cancel, busy capture, and a real saved photo without detected paper bounds.
Quality thresholds, analysis scope and required checks remain unchanged.
All three camera cases pass independently on API 29 and API 37 (six executions,
zero failures/errors/skips); JVM tests, Lint and debug/test compilation pass.
The next hosted analysis must independently confirm closure of the finding.

## Cloud analysis blocked by a device-test failure

Run `40402857542` at `ebfb7f6` passed JVM tests, lint, Android compilation,
all three native ABI jobs and Greptile. Both API 29 and API 37 failed the same
PDF preview test: ten inward pinch gestures left the scale near 2.04 instead of
1. The smaller hosted display and Android's scaling touch slop make a fixed
gesture count unreliable. The test now permits a bounded number of real inward
pinches, requires each gesture to reduce the scale, and still asserts the exact
minimum scale and zero pan offsets. No production zoom behavior was changed.

Because the device suite failed, its successful-suite coverage was not uploaded.
Sonar then stopped at native coverage export before submitting a new analysis.
The five displayed PR Lint issues therefore still describe the older analyzed
revision; their code fixes are already in `80ea00d`. They must be confirmed closed
by a fresh successful analysis, not manually marked resolved. Test failures must
continue blocking the PR gate and must not be bypassed with old coverage.

The corrected reader suite passes all 12 tests on each of API 29 and API 37 at
640x1280/density 320 with disabled animations (24 executions, no failures or
skips). This focused verification is separate from the complete hosted suite;
its results do not establish a fresh whole-project coverage percentage.

## Findings from the refreshed PR analysis

Run `40523664601` at `86d8acd` passed all 331 source device cases on each API,
three additional appearance cases per API, JVM/Lint, the three native builds,
compilation/R8 and Greptile. Both current-run native profiles were collected and
Sonar submitted a fresh analysis. The original five PR Lint findings closed.
The new analysis exposed 32 other findings on changed files. Its gate failed
for security rating C and 73.8% new-code coverage; new-code duplication was 0.0%.

The follow-up addresses those findings as follows:

* Give the release workflow an empty default permission set and grant read
  permissions to its individual jobs; only asset publishing receives write access.
* Route camera, selection, crop and portrait clicks through performClick, and
  avoid emitting clicks for cancelled gestures. Preserve swipe and pinch behavior.
* Share a comparison control that holds the original during touch and restores
  the result on release/cancel; accessibility clicks toggle both previews.
* Reuse camera drawing paths/dash effects, mask tint, page bounds and portrait
  guide rectangles. Do not reuse rectangles returned to input callers.
* Use logical START/END gravity and formatted resources for translation/tonal labels.
* Keep the existing default simple Office line-breaking strategy without referring
  to an API-29-only constant. Android documents SIMPLE as the builder's default:
  [StaticLayout.Builder](https://developer.android.com/reference/android/text/StaticLayout.Builder#setBreakStrategy(int)).
* Format the page-number input with a locale and persist naming counters with
  SharedPreferences.apply; synchronized allocation still updates the in-memory
  counter before another caller can allocate a name.
* Exclude only binary WebP images and binary Office test fixtures from text
  analysis to remove UTF-8 warnings. Java/Kotlin/Rust coverage scope and the gate
  thresholds are unchanged.

New regression flows exercise saved page geometry/numbering, invalid page settings,
cancelled save pickers, markup undo/clear/cancel, drawing across recreation and its
actual encoded pixels, glyph selection/copy/translation, repeated overlay drawing,
bitmap release and accessibility comparison. Further cases exercise real emulated
camera focus and mode changes, crop/mask drag and cancel, tonal controls, stale
preview requests, read-only PDF permissions and recovery after an unreadable image.
The focused cases pass independently on API 29 and API 37. An earlier complete
local run passed 342 cases on API 37 before the final test extensions; the final
suite now contains 348 cases per API.

The corresponding local API 29 run used an incorrectly configured GLES2 emulator
and failed four native-renderer cases. That failed coverage is excluded. After
restarting with the CI's GLESDynamicVersion setting, all five native graphics
cases pass. Complete final device runs and the cloud gate remain the authority
for current merge readiness.

The changed-Java/Kotlin outcome estimate from successful current-application
profiles is 81.52% (575/598 executable lines and 973/1301 branch sides). This is
a local diff estimate, not a Sonar result or a whole-project measurement. No
failed device profile or mismatched production class is imported. The final
Sonar analysis must independently confirm its coverage and open-issue count.

## Camera outline and manual shutter follow-up

The manual capture path already accepts a frame without detected bounds. The
visible 64dp shutter, however, previously attached its click listener only to the
inner 54dp face, leaving the ring unresponsive. The whole visible control now
accepts taps. A real emulated CameraX capture test taps the outer ring with no
outline and verifies one readable saved JPEG, independently on API 29 and 37.

LivePageOutline confirms two nearby detections, smooths small corner changes,
and holds a missing outline for at most 350ms. Large page movement removes the
old outline and requires confirmation again. Its state belongs to one analyzer
binding; stale callbacks cannot update the guide during capture or after pause.
Only the original, current detection is passed to automatic capture, so the
brief visual hold cannot trigger a shot on a missing page. Manual capture does
not wait for that confirmation. Six JVM regressions cover jitter, dropout,
movement, stale state, invalid geometry and snapshot ownership; all pass. The
three camera instrumentation cases pass on both APIs without failures or skips.
Physical camera acceptance testing remains necessary for the perceived result.

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
