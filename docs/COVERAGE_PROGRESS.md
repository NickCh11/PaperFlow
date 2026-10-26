# Whole-project coverage progress

The target is **100% of executable production lines and branches**, including
all Java/Kotlin modules and the owned Rust runtime. The target is not reached.
Tests, generated code and dependencies are outside the production report;
production files have not been excluded to raise the percentage.

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

- Full local API 29 / 2 GiB production run: **280/280 tests pass**, no failures or skips, 12m 4s. Source-to-JUnit completeness passes.
- After the final test synchronization changes, **43/43 follow-up tests pass** with animations enabled, 640px width and 130% font scaling (30.475s). No assertions were removed.
- **103 JVM tests** pass (61 app, 25 scanengine, 17 translation); Android Lint passes.
- The preceding GitHub run at `1b61305` failed on six premature dialog lookups across API 29/37. Its native coverage export then correctly failed because a failed device suite had not collected application profiles. The test helpers now wait for focused, attached dialogs, visible list rows and completed drawing-screen orientation changes. Final API 37 verification remains with the next CI run.

| Scope | Executable lines | Branches |
| --- | ---: | ---: |
| app | 5000/5816 (85.97%) | 5791/10972 (52.78%) |
| scanengine | 1412/1412 (100.00%) | 396/423 (93.62%) |
| translation | 530/638 (83.07%) | 353/496 (71.17%) |
| JVM/Android total | 6942/7866 (88.25%) | 6540/11891 (55.00%) |
| Owned Rust | 686/837 (81.96%) | 171/256 (66.80%) |

Compared with the previous local measurement, 59 more executable lines and 98 more
branch sides are covered. The PDF viewer now reaches **200/200 executable lines**
(100%) and 246/343 branch sides (71.72%); it does not have 100% branch coverage.
Rust sources did not change in this batch, so its real native measurement remains
valid and unchanged. Overall **100% is not reached**. Failed or incomplete runs
are not imported.

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
