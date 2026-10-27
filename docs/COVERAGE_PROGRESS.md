# Whole-project coverage progress

The target is **100% of executable production lines and branches**, including
all Java/Kotlin modules and the owned Rust runtime. The target is not reached.
Tests, generated code and dependencies are outside the production report;
production files have not been excluded to raise the percentage.

## Sonar remediation and release hardening, 26 October 2026

[SONAR_REMEDIATION.md](SONAR_REMEDIATION.md) records the fixes for mask indexing,
cross-thread counters, regex runtime, native detector lifetime, callback handling,
release obfuscation, dependency integrity and duplicated editor/gesture code.
It also records three security findings that require a documented design review;
their Sonar status has not been changed to hide them.

The five PR Lint findings are absent from the generated local Lint report. Two
new signing device cases verify accessibility clicks/cancel behavior and stable
rendering of rotated shared marks across repeated frames. Seven new JVM cases
exercise mask resampling, malformed Excel formats and shared pan cancellation.
The complete **111 JVM tests** (68 app, 25 scanengine, 18 translation), **91 CI-tooling
tests**, **22 localization tests**, translation validation and workflow lint pass.
Strict dependency verification rejects a deliberately incorrect JUnit checksum
in an isolated probe. Actual debug/release x86_64 APK ELF checks and the merged
release manifest policy pass.

The fully optimized release builds and a locally debug-signed copy cold-launches,
imports an image, runs embedded offline OCR and translates to Greek on API 29.
This is local release smoke evidence, not physical-device or production-signing
acceptance. The preceding uploaded revision passed Sonar but failed API 37, so
fresh hosted validation is required before merge.

The fresh full local run passes **331/331 cases on API 29 and 331/331 on API 37**,
with no failures, errors or skipped cases; each report exactly matches the source
case IDs. Both use 640x1280, density 320, 130% font scaling and disabled animations.
The previous run exposed one test reading a removed private gesture flag; its
behavioral stroke assertions are retained and pass in the fresh full run.
Earlier coverage percentages below remain historical until replaced with fresh
matching profiles. No model accuracy corpus, coverage exclusion or lower gate threshold
was introduced.

Fresh coverage from only these successful current device profiles and current
JVM execution data is **7167/7942 Java/Kotlin executable lines (90.24%)** and
**7021/11947 branch sides (58.77%)**. App: 5214/5892 lines and 6267/11028 branches;
scanengine: 1412/1412 lines and 400/423 branches; translation: 541/638 lines and
354/496 branches. No class-mismatch warnings were reported. Native coverage was
not recollected locally in this batch; this is not a new whole-project/native
100% measurement.

## Basic security and workflow contracts, 26 October 2026

Reviewing the essential scenarios found a concrete sanitizer bypass: Android
user-qualified private authorities (`0@<app>.files`) were accepted as external
imports. A failing device test reproduced VIEW being forwarded instead of MAIN.
Provider comparison now strips the user prefix and rejects missing authorities;
user-qualified external imports remain supported. This establishes a validation
bypass, not a demonstrated external file-exfiltration exploit.

Four permanent device regressions cover that boundary, legitimate shared output
bytes, rejection of private state/sibling folders and encoded/plain traversal.
All **9 import, file-sharing and exported-entry lifecycle tests pass on each API
29 and 37**. **104 JVM tests and Lint pass**. The source now contains 329 device
cases; the entire 329-case suite has not been rerun locally after this change.

Each ABI build now enforces a merged-release-manifest policy after assembly.
It checks the actual merged dependency declarations, permissions, explicit
exports, signature-protected profile receiver, private FileProvider and absence
of debug/test-only/cleartext release flags or the debug document provider.
The actual local x86_64 debug/release APKs pass ELF checks, and the built release
manifest passes the new policy. Eight regression tests simulate unsafe manifests;
the full **90-test CI-tooling suite and workflow lint pass**.

[BASIC_VALIDATION.md](BASIC_VALIDATION.md) maps the existing core regressions and
records physical-device acceptance gaps explicitly. No model accuracy benchmark,
coverage exclusion, reduced Sonar threshold or weaker merge rule was introduced.
The coverage percentages below describe their recorded earlier revisions; fresh
whole-project coverage and the actual Sonar result belong to the next CI run.

## Focus on app integration rather than upstream model benchmarks, 26 October 2026

At the owner's request, remove the exhaustive upstream language-detection corpus
(catalogue/image/text accuracy checks) and the OCR word-error-rate benchmark.
The photo auto-detection integration now uses three representative app-boundary
cases instead of ten. The PDF test compares recognized text with exported text
and verifies unchanged page rendering and blank-page handling; it no longer
scores the model against a linguistic ground-truth corpus or repeats raw inference
only for diagnostic JSON.

The remaining complete suite has **325 device tests**, required on **both** APIs;
no profile-specific skips were introduced. Real OCR/translation sessions, original
image coordinates, local Word/PDF output, downloads, integrity, cancellation and
permissions remain covered. App-owned script recovery uses deterministic JVM
boundary assertions. Production code, Sonar scope and quality-gate thresholds
are unchanged.

CI now seeds only the English/Greek integration packs: **15 files / 53,036,549
bytes**, versus the previous all-script **39 files / 87,399,959 bytes**. The
optional `--all-ocr` installer remains available for manual diagnostics. The
327-case measurements below describe the preceding suite.

Validation of the reduced suite:

- Fresh complete **324/324 runs on both API 29 and API 37** before adding the
  small flat-image contract. Summed test durations: **248.676s / 351.856s**;
  the combined Gradle invocation completed in 6m 9s. Both use the minimal packs.
- The final native-adapter contract class then passed **7/7 on both APIs**,
  including the new flat-image case, in **0.096s / 0.317s**. Combined reports
  account for **325 distinct source cases per API** (662 executions across both
  devices, including 12 repeated contract cases); strict completeness passes.
  This is not a fresh single 325-case run.
- **104 JVM tests**, Android Lint, **82 CI-tooling tests** and workflow lint pass.
- Fresh Java/Kotlin profiles from only the reduced suite and JVM tests cover
  **7,095/7,866 lines (90.20%)** and **6,967/11,891 branch sides (58.59%)**.
  Compared with the preceding accumulated 327-case report, that is **+23 covered
  lines and +17 branch sides**, with no previously covered source line lost.
  App: 5,142/5,816 lines; scanengine: 1,412/1,412; translation: 541/638.
- Script-routing and flat-image rules are tested with Unicode characters and
  synthetic pixels, without inference. Rust sources are unchanged; fresh native
  coverage and the actual Sonar gate must be verified by the new CI run. No
  historical native profile is mixed into this local Java/Kotlin measurement.

## Main-screen Word security and settings, 26 October 2026

Twenty-two permanent tests were added: thirteen MainActivity Word workflows,
eight settings/tool-search workflows and one backend permission regression that
checks every Word route. The UI tests inspect real DOCX XML, page images, history
provenance, provider bytes and outgoing grants; they also exercise password retry,
queued cancellation, running/completed recreation and settings persistence.

A failing device test reproduced a production permission bypass before the fix:
a PDF opened with a restricted user password still produced a Word document even
though `canExtractContent()` was false. Native text, automatic and local OCR routes
now call the same `PdfPermissions.extract()` guard used by the other PDF tools,
before extracting text, rendering OCR input or writing an output. All five Word
routes reject the restricted user password without leaving output files; an
owner password still permits legitimate conversion. The original PDF is unchanged.

The previous `3093aa4` CI run passed API 29, all native/ABI builds and Sonar. Its
only API 37 failure was an existing language test requesting explicit recreation
while LocaleManager was already destroying the old Activity. Locale tests now
wait for a resumed screen in the requested language, and for a new instance when
the locale changes. Explicit recreation and all registered-locale/formatting
assertions are retained. A bounded retry handles only the framework's specific
stale `DESTROYED` stage race; other exceptions still fail the test.

Current focused validation:

- **33/33 on API 29 and 33/33 on API 37**, including all Word routes, offline OCR,
  permission denial/owner access, new Main UI and settings tests. Both use 640px
  width, 130% font scaling and animations (30.590s / 72.637s).
- Final language tests: **4/4 on each API**, with animations, including every
  registered language and formatted-resource validation (4.038s / 7.690s).
- **103 JVM tests and Android Lint pass**. Rust sources are unchanged.

## PDF tool exports and remaining API 37 dialog race, 26 October 2026

Twenty-five new permanent device tests inspect actual PDF, ZIP and image output:
selected-page extraction, page order, password validation and removal, encrypted
merge, restrictive source permissions, failed/cancelled imports, lossless and JPEG
archives, compression fallback, watermark editing/style/removal, recreation,
result navigation, byte-exact device save, sharing, registration provenance and
back/cancellation handling. Fixture files, preferences and worker threads are
cleaned up after each test. No production source was changed in this batch.

The OCR PDF tests execute the embedded PaddleOCR/MNN runtime. They verify a
selected-page export that preserves existing text, and queued cancellation that
leaves no partial PDF. The selected-page OCR test also passes on API 37 with both
Wi-Fi and mobile data disabled before instrumentation (4.717s).

The prior CI revision `969f0c3` passed API 29, all ABI/debug/release builds and
Sonar. API 37 reported one premature reader-dialog lookup: the test observed a
button before the dialog had window focus. Reader waits now require the expected
button in the focused dialog before accessing its text. Assertions are retained.

The 25 new tests and 12 reader tests pass together on API 29 and API 37 at 640px
width, density 320, 130% font scale and with window animations enabled: **37/37**
on each API (33.967s and 78.305s). The focused reader suite also passes **12/12**
on the default API 37 layout. Full matrix verification of this update belongs to
the next CI run; the previous failed API 37 run is not described as passing.

## Signing and reader regressions, 26 October 2026

Two signing bugs were reproduced with failing device tests before being fixed:

- Copying one mark from a document with 999 marks could not reach exactly 1,000:
  the guard counted the already-existing source-page mark as another copy.
  The limit now counts only the copies that will actually be added. A separate
  overflow test verifies that an excessive request changes no existing marks.
- On a narrow mark, the edit and resize hit areas overlapped the delete area.
  Tapping the edit handle deleted the mark. The closest handle now receives the
  touch. Tests cover editing, resizing and deleting shared bitmap references.

Twelve additional signing tests cover these boundaries, rotation, size limits,
preservation of placement when copying, replacement, dragging and drawing limits.
Twelve new reader tests use visible controls to cover page selection, bitmap
ownership in zoom, real two-finger zoom and panning, text copy/translation, scanned
page OCR routing, cancelled file creation, explicit cloud-file export, signing,
editing a separate image copy and Add/Edit/To Word routing. Cloud-file export is
separate from OCR: scanned text continues to use the embedded offline OCR runtime.

The 33 signing/reader tests pass at 640px width with 130% font scaling. The reader
test monitor is removed before scenario teardown, so it cannot intercept the
Android test framework's own cleanup activity. An older tool-routing monitor now
matches only application components: the same eight routing assertions take
0.644s locally, down from 45.443s without dropping any checks.

Three further JVM geometry tests reject undersampled/collapsed contours, verify
that an intentionally strict minimum side length returns no partial crop corners,
and retain four outer page sides when a shallow notch interrupts one edge. The
last case allows a documented 5% page-height tolerance for fitted corners.

## Measurement

- **327 distinct passing device tests** are accounted for by compatible baseline reports and the current focused runs. The combined reports contain 334 executions because seven existing backend cases were rerun; their 327 unique case IDs exactly match the current test sources. This is not a fresh full 327-test local run.
- Every Word conversion test affected by the production permission guard was rerun, including all four offline-recognition tests. Coverage uses only matching production class IDs and passing profiles from both APIs.
- Current focused results: **33/33 per API plus 4/4 language tests per API**. Fresh full CI validation of this update is still required.
- The unchanged **103 JVM tests** pass (61 app, 25 scanengine, 17 translation); current Android Lint passes.

| Scope | Executable lines | Branches |
| --- | ---: | ---: |
| app | 5121/5816 (88.05%) | 6191/10972 (56.43%) |
| scanengine | 1412/1412 (100.00%) | 396/423 (93.62%) |
| translation | 539/638 (84.48%) | 363/496 (73.19%) |
| JVM/Android total | 7072/7866 (89.91%) | 6950/11891 (58.45%) |
| Owned Rust | 686/837 (81.96%) | 171/256 (66.80%) |

Compared with `3093aa4`, **34 more executable lines and 123 more branch sides**
are covered. MainActivity now covers 457/516 executable lines (88.57%), with 59
remaining, down from 89. The PDF viewer and scan engine retain 100% executable-line
coverage, but not 100% branch coverage. Owned Rust sources did not change; its real
measurement is unchanged. Overall **100% is not reached**. Failed or incomplete
execution data is not imported.

The repository's whole-project checker reports line and branch counters for each
module, checks the owned native sources separately, and returns a failing result
while the 100% target is unmet. It does not substitute a passing Sonar quality gate
for whole-project 100% coverage.

For an x86_64 test device, collect reports only after the complete test run passes:

```powershell
.\gradlew.bat testDebugUnitTest :app:connectedDebugAndroidTest `
  -PpaperflowAbi=x86_64 -PpaperflowCoverage=true --no-configuration-cache `
  -Pandroid.testInstrumentationRunnerArguments.ci=true
if ($LASTEXITCODE -ne 0) { throw "Tests failed; do not import this coverage" }
python tools/ci/check_test_results.py app/build/outputs/androidTest-results/connected app/src/androidTest/java
if ($LASTEXITCODE -ne 0) { throw "Incomplete device results" }
New-Item -ItemType Directory -Force build/device-coverage/local | Out-Null
Copy-Item app/build/outputs/code_coverage build/device-coverage/local -Recurse -Force
.\gradlew.bat coverageReport -PpaperflowAbi=x86_64 -PpaperflowCoverage=true --no-configuration-cache
python tools/ci/check_coverage.py --target 100 --native-lcov native.lcov `
  --json coverage-progress.json --markdown coverage-progress.md
```

Use the native LCOV artifact for the same native sources, produced by CI's
instrumented Rust tests and Android JNI execution. Missing native measurement
cannot produce a whole-project pass. Device test XML is also checked against the
instrumentation test sources to reject missing or skipped tests.
