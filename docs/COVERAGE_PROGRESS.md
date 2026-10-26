# Whole-project coverage progress

The target is **100% of executable production lines and branches**, including
all Java/Kotlin modules and the owned Rust runtime. The target is not reached.
Tests, generated code and dependencies are outside the production report;
production files have not been excluded to raise the percentage.

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

- **305 distinct passing device tests** are accounted for by the previous complete 280-test API 29 run plus the 25-test export batch. Source-to-JUnit completeness passes over these separate reports. This is not a fresh full 305-test local run.
- Current focused checks: **37/37 on API 29 and 37/37 on API 37**, both with animations and the compact/large-font layout; **12/12 reader tests** also pass on the default API 37 layout.
- The unchanged **103 JVM tests** remain passing (61 app, 25 scanengine, 17 translation); current Android Lint passes.
- Earlier full local baseline: **280/280**, none failed/skipped, 12m 4s, followed by the previous 43-test synchronization check. The latest coverage union includes only passing profiles whose production class IDs match the current code.

| Scope | Executable lines | Branches |
| --- | ---: | ---: |
| app | 5087/5816 (87.47%) | 6068/10972 (55.30%) |
| scanengine | 1412/1412 (100.00%) | 396/423 (93.62%) |
| translation | 539/638 (84.48%) | 363/496 (73.19%) |
| JVM/Android total | 7038/7866 (89.47%) | 6827/11891 (57.41%) |
| Owned Rust | 686/837 (81.96%) | 171/256 (66.80%) |

Compared with `969f0c3`, **96 more executable lines and 287 more branch sides**
are covered. ToolWorkflowActivity now covers 424/451 executable lines (94.01%),
with 27 remaining, down from 114. The PDF viewer retains
**200/200 executable lines** (100%) and 246/343 branch sides
(71.72%). Owned Rust sources did not change; its real measurement is unchanged.
Overall **100% is not reached**. Failed or incomplete execution data is not imported.

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
