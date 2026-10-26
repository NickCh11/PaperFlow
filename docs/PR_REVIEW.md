# Pull request review integrations

The existing **Android PR checks / PR gate** remains the build and correctness
gate. Greptile provides contextual AI review; SonarQube Cloud provides static
analysis and a new-code quality gate. Neither replaces device, native, leak,
camera, battery or performance testing.

## Greptile

The GitHub App has submitted three inline review comments on PR #1. Its earlier
comment reported exhausted review credits; account credits and automatic review
settings are managed in [Greptile](https://app.greptile.com/). A committed
workflow does not purchase credits or activate a subscription.

## SonarQube Cloud onboarding

Configured on 2026-10-24: private project `NickCh11_PaperFlow` in organization
`nickch11`, on the Free plan. Both Actions variables and the encrypted
`SONAR_TOKEN` secret are installed. Automatic analysis is off; Gradle CI is used.
At the owner's request, the Sonar main branch is `feature/offline-translator`;
GitHub's default branch remains `main`. Free PR analysis therefore applies to
PRs targeting `feature/offline-translator`. Change the Sonar main branch and the
workflow's push filter together when development moves back to `main`.

The initial baseline was scanned from target commit
`1c9eeaf265481018c7795169bf0d13dc5d1a0adb`, using a temporary copy of the scanner
build configuration without changing its application sources. The latest verified
Cloud analysis of production revision `95d43588ad8cfa3a02aa847e29cbf1f0dace94f4`
reported zero open new issues and A ratings for security, reliability and
maintainability. Its new-code coverage was 71.4%, so the quality gate still failed
the unchanged 80% coverage requirement. Later tests must be remeasured rather
than inferred from their passing result. No findings have been dismissed or
quality-gate thresholds weakened to make the check green.

The workflow also generates the Android Lint reports expected by the scanner,
uses the release version from `version.properties`, and ignores generated
`.sonar` / `.scannerwork` files.

PaperFlow became a **public GitHub repository** on 2026-10-25 after reviewing the
complete Git history for credentials. Standard Ubuntu Actions runners are now
eligible for GitHub's public-repository compute allowance. The existing Sonar
project remains private; changing GitHub visibility does not change its plan.
The documented Cloud Free
plan supports private projects up to **50,000 analyzed LOC across the organization**
and PR analysis targeting the project's main branch. The separate OSS plan is for
public projects. Plan eligibility must be confirmed
in the actual Sonar account. Excluded vendor files/tests/comments do not count
toward Sonar's analyzed production LOC; a local source line count is only an estimate.

1. Sign in to [SonarQube Cloud](https://sonarcloud.io/) with GitHub. Create or bind
   the organization for `NickCh11`, choosing the **Free** plan for a private project.
   If the UI requests payment, stop and review eligibility; do not start a paid plan.
2. Install/configure SonarQube Cloud's GitHub App for **only PaperFlow**, then import
   `NickCh11/PaperFlow` as a **private** project. Set `feature/offline-translator`
   as its Sonar main branch for the current development workflow.
3. Choose **CI-based analysis / GitHub Actions**, and disable automatic analysis
   for this project if enabled. Android Java/Kotlin analysis uses compiled outputs
   via the pinned SonarScanner for Gradle plugin, not the generic scan action.
4. Copy the exact **organization key** and **project key** from Sonar. Do not guess
   them from the repository name. In GitHub **Settings → Secrets and variables →
   Actions → Variables**, add:

   | Variable | Value |
   | --- | --- |
   | `SONAR_ORGANIZATION` | The actual Sonar organization key |
   | `SONAR_PROJECT_KEY` | The actual Sonar project key |

5. Generate an analysis token with the minimum supported analysis permissions
   and an expiration date in Sonar. Store it directly in GitHub **Actions secrets**
   as `SONAR_TOKEN`. Never paste it into source, a PR, chat, or a workflow log.
6. Establish a baseline from the actual `feature/offline-translator` source
   before the first PR analysis. After this setup PR is merged into that branch,
   its pushes refresh the baseline automatically. PRs targeting other branches require a plan that
   supports those targets. The workflow does not buy a plan or disguise a PR
   as a main-branch analysis.
7. Verify **SonarQube analysis** runs, the Cloud dashboard has all three JVM modules,
   and a Sonar quality-gate result appears on the PR. The workflow waits for the
   gate, so a failed gate fails the analysis job.
8. Only after successful end-to-end verification, consider requiring the analysis
   check in branch protection. Do not require the configuration check as a quality
   gate. Fork/dependabot secret availability must be considered before making a
   conditionally skipped check mandatory.

Android PR checks calls the reusable Sonar workflow for **every PR**, after the
fast correctness checks pass. Scanner inputs come from that run's already compiled
classes, JVM tests and Lint reports. The scanner does not repeat compilation or
JVM tests. An explicit full run adds device coverage from the same run; it never
imports another revision's data. Fast runs report JVM-only coverage honestly and
still enforce the unchanged Sonar quality gate, which may fail until full coverage
is available. Pushes to the selected Sonar main branch refresh its baseline.
For trusted PRs it reports a failing **setup required** configuration check when
keys/token are missing; the analysis job cannot start until onboarding is done.
The target branch must also be supported by the Cloud subscription. Triggering
a workflow is not evidence of successful analysis or a passing quality gate.
Fork PR code never receives the analysis token; the ordinary secret-free Android
PR checks still apply. There is no `pull_request_target` execution of PR code.

Scope: Java/Kotlin and Android sources selected by Gradle across `app`, `scanengine`
and `translation`. Vendored `third-party`, generated build directories and binary
assets are excluded. C/C++ analysis is disabled until a compilation-database/capture
pipeline is added; this setup makes no native-analysis claim. JaCoCo 0.8.15 merges real JVM execution data and instrumented device execution
data into one XML report per module. Enable collection with `-PpaperflowCoverage=true`
and generate reports with `coverageReport`. Sonar imports these XML files.
Generated classes and native code are outside this JVM coverage measurement;
production Java/Kotlin sources are not excluded to improve the percentage.
The Sonar way quality gate, including 80% coverage on new code, is unchanged.

## Controlling Actions consumption

Draft PR updates run Python/JVM tests, Android Lint and Sonar without the native,
release and emulator matrix. Opening a ready PR or marking a draft **Ready for
review** starts full validation automatically. Every subsequent push or reopen
of a ready PR also runs the full matrix. Converting it back to a draft cancels
the superseded run and returns to fast checks; a draft cannot be merged.

The `full-validation` label remains an optional request for a full run, including
on drafts. An old attached label does not force future draft pushes to be full;
remove/reapply it for another manual request. Unrelated labels do not cancel
validation. Manual dispatch discovers the open PR so Sonar uses its actual PR
context, and always runs full validation for a ready PR even if fast mode was
selected. Fast manual mode remains available for drafts and branches without a
PR. Only a full run emits the required `PR gate` check; fast runs emit
`Draft / fast checks`, so a green draft result cannot satisfy the merge gate
after the PR becomes ready. Maintainer approval and required checks still
control merging.

Main/master pushes and merge-queue runs require the full matrix. Releases verify
successful emulator, native, APK and Sonar jobs on the **exact main commit**; a
green fast check cannot authorize publishing. If full validation is absent, the
release stops immediately instead of waiting on a paid runner for 45 minutes.

Native binary caches require an exact key covering native source, fixtures,
Cargo manifests/lockfile, build script and toolchain workflow. Native tests still
execute during full validation even when their binaries are reused. Independent
native push triggers and the duplicate translation workflow were removed. Weekly
model link checks remain; full model downloads are manual. Reports/native
artifacts expire after three days; release APK inputs remain available for seven.
The account's existing Actions $0 hard budget was verified, not duplicated.

LLVM can emit a source branch once per generic Rust instantiation. Native
coverage unions actual hits for identical source ranges and expansion metadata,
without counting those instantiations as extra source branches. Conflicting
expansion mappings, duplicate source-file records, owned profile mismatches and
unexplained LLVM diagnostics still fail the export. This is verified against
the real archived font-provider exports as well as regression fixtures.

## Whole-project coverage target

The requested target is **100% of executable production lines and branches across
the entire project**, including owned Rust code. A passing new-code Sonar gate
does not establish that target. No production Java/Kotlin files are excluded to
raise coverage.

Each Sonar CI run publishes `build/coverage/whole-project.md` and `.json`, with
separate results for `app`, `scanengine`, `translation`, and native Rust. The job
summary explicitly reports `TARGET NOT MET` until all measurements meet 100%.
Missing native measurement is reported as unmeasured, never as zero or 100%.
This progress report is advisory; the existing required Sonar quality gate
remains enforced separately. Measurement errors fail the report step.

To check the target locally after generating current-revision JaCoCo reports:

```sh
python3 tools/ci/check_coverage.py --target 100 \
  --json build/coverage/whole-project.json \
  --markdown build/coverage/whole-project.md
```

The checker exits 0 only when the target is met, 1 for incomplete coverage, and
2 for invalid measurements. `--native-lcov path/to/native.lcov` imports separate
native execution records. A host-only Rust run cannot replace measurements for
Android-only JNI files; every owned native source needs line data and the native
report needs branch instrumentation. CI builds an Android x86_64 Rust test
executable from the same locked dependencies and runs the font-catalog and packed
frame-result tests on both emulators before the Java instrumentation suite. An
empty, incomplete or ignored native suite cannot pass. Cargo's successful
`build-finished` record is required because the pinned `cargo-ndk-test` wrapper
does not propagate the compiler's exit code; `cargo ndk` itself consumes the JSON
artifact records, so it cannot be used as their stdout source.

Full CI additionally builds a separate x86_64 library/test executable with the
pinned coverage-only nightly in `tools/ci/native_coverage_toolchain.txt`. API 29
uses this library for the instrumentation suite and flushes real LLVM profiles
through a JNI hook that exists only with the `ci-coverage` feature. API 37 and all
release APK builds retain the normal stable-Rust runtime. Native branch profiles
from Rust unit tests and the real app/JNI calls are merged separately for their
matching executable/library with that toolchain's LLVM tools. The exporter then
unions hits by source line and branch coordinates, rather than object-specific
branch IDs. It audits every zero-hash unused definition from locked third-party
crates and rejects any owned-function mismatch, nonzero hash or unexplained
diagnostic. The exporter checks object hashes, revision, source-file scope and
branch data before the whole-project report imports `build/coverage/native.lcov`.
Rust test functions and the CI-only flush hook are outside production coverage;
no production Rust functions are excluded. Coverage objects expire after one day
and are rebuilt only when their exact cache key changes.

A successful build or a standalone profiler smoke test does not establish native
production coverage. Only profiles from the actual current-run app and unit tests
can produce a measured result. The 100% target remains unmet until every module's
line and branch counters reach it, including the owned native sources.

Instrumentation keeps the tested app installed until LLVM profiles have been
copied out of its private data directory. AndroidX Test/Espresso uses stable
versions that avoid the removed `InputManager.getInstance` API on Android 17.
Full analysis imports successful current-run device artifacts even if another
Android API's job fails; PR acceptance still requires both emulator jobs. Missing
full-run coverage fails measurement instead of silently becoming JVM-only data.

Regression coverage includes real loopback HTTPS downloads (plain/gzip, retries,
size checks, cancellation and cleanup), PDF viewer lifecycle and bitmap ownership,
native JNI validation, library folder/trash operations and searchable OCR PDFs.
OCR PDF text uses character IDs mapped to complete Unicode scalars, so supplementary
characters survive save/reload; mapping batches and later pages retain earlier
text. Invalid nonfinite crop coordinates are rejected before OpenCV processing.
Still-image OCR retains the upstream 1500²-pixel detector budget and the original
RGB image for recognition, mapping detected boxes back to its coordinates. A
960²-pixel trial completed the 2 GiB suite without process death, but degraded
Korean/Uyghur language recognition; the all-language image test rejected it.
Before the native session opens, the global Rayon pool is limited to one worker
on low-RAM devices or devices with at most 3 GiB RAM, and two on larger devices.
The upstream ink engine retains a model session per global worker, so this bounds
those copies independently of its separate recognition pool.
On constrained devices, script changes and automatic OCR release idle model
caches before the next native inference. Explicit-language PDF pages reuse their
warm recognizer. Native read locks protect in-flight calls, and retained live
camera owners defer release. Rebuilding an idle session retains the current
catalog and install root; ABI 4 prevents older artifacts from skipping this API.
The API 29 emulator uses 2 GiB RAM to exercise this constrained-device path; API
37 retains 4 GiB to cover the larger-device policy in the same full-validation run.
White balance borrows its document mask even when no pixels are selected. Releasing
that mask early caused automatic color detection to fail in OpenCV; regression
tests cover empty/partial masks, missing color channels, capture fallback geometry
and the public Kotlin defaults for crop and bitonal export.
Automatic color decisions compute the four edge lengths without allocating edge
objects. Local regression profiles cover all executable lines and branches in
`ColorDetection.kt`; this does not establish whole-project coverage.
Gallery-copy and automatic-cleanup settings keep their choice lists visible next
to their explanations. Combining `AlertDialog.setMessage` with a choice list hid
the choices. Device tests reproduce that failure and verify Apply, Cancel, saved
checkbox state, settings recreation and denied storage permission handling.
Independent OCR passes choose an unused PDF font resource name, reusing it only
for the same writer's mapping. Repeated OCR no longer replaces an earlier
ToUnicode map, including after save/reload or when pages share resources. Two
regressions reproduce the previous collision and verify all text survives.
The scan engine's local JVM/device measurements now cover 100% of executable
lines and 93.14% of branches. Tests exercise the real JPEG dimension rejection,
invalid inpainting-mask sizes and numerically unresolvable contour intersections.
Grayscale processing validates empty/unsupported inputs before native allocation
and accumulates its single-channel Retinex result without an extra image copy.
Tool-grid regressions also verify outgoing destinations, capture modes, operation
names and supported import MIME types without launching external pickers.
Photo-translation UI regressions exercise language search/selection, cancellation,
clipboard contents, original/translated preview restoration, new-photo pickers,
editable-text cancellation and the offline explanation. Language controls now
update their accessibility descriptions alongside their visible values; a
regression reproduced a Greek target being announced as English before the fix.
Signing page choices and their explanatory text remain visible together. Three
UI regressions reproduced the hidden page list before replacing the dialog's
message with a separate help view. Tests cover selected/all-page choices, cancel,
page navigation and shared bitmap ownership, including a 640px display at 130%
font size. Real touch strokes also verify empty-drawing rejection, undo/clear,
selected ink, transparent trimmed PNGs and reusable signatures across recreation.
The PR workflow grants pull-request read access only to its planning job, which
needs it to resolve manually dispatched validation to the existing PR. Other
jobs retain only repository read access; the Sonar security gate stays enforced.
Sonar and the PR gate still run after validation failures, but stop when a
workflow is cancelled. Using `!cancelled()` avoids an obsolete Sonar analysis
holding the workflow's concurrency slot after newer code has superseded it.
Physical-size reconstruction checks the angle using a normalized plane normal,
rejecting almost edge-on views while preserving the projection used for normal
millimeter measurements. Rectangle fitting accumulates rotated bounds in one
pass without allocating a point/list per angle. JVM tests cover both parallel
orientations, inconsistent vanishing points, overflow and partially supplied
clipping bounds; `Perspective.kt` and `MinAreaRect.kt` have full line/branch coverage.
Native font tests read the bundled v6 catalog instead of a stale language list
and exercise the production family-selection algorithm. Georgian text selects
the installed Georgian font before the Latin fallback; Marathi, Uyghur and Urdu
language aliases agree with their catalog scripts. Android-only tests inspect
actual system font files for every bundled language and style. Parser, missing
file, style and fallback-order tests cover failure handling separately.
Native coverage manifests and the x86 test-artifact cache include the bundled
catalog hash, so catalog changes cannot silently reuse old font-test binaries.
The real language-download UI test chooses the smallest fresh core pack,
reports the language/size/latest status on timeout, and cancels and joins an
active download before cleanup. Cleanup errors are attached to the original
failure instead of replacing it, and delete confirmation must be visible.
Single-file translation exports honor cancellation before opening a selected
document. Once writing starts, they finish the current document because a SAF
provider may overwrite an existing file and cannot atomically roll it back.
Device regressions reproduced both a queued cancelled save replacing existing
bytes and an in-flight cancellation leaving only 131,072 of 1,048,595 bytes.
Four permanent tests verify queued cancellation preserves the destination,
in-flight cancellation finishes it, successful saves copy every byte, and
missing exports report errors. A debug-only pipe-backed document provider
pauses a real write after the first bytes to make the timing deterministic.
That debug-only provider and its nested control objects are excluded from the
production JaCoCo class set; no source under `src/main` is excluded for this.
Four photo-session regressions exercise large-photo downsampling followed by
real offline OCR and rendering, corrupt inputs, cancellation while queued, and
language changes with a stale preview. Failed/cancelled input retains the
previous visible image; a successful replacement recycles its old bitmaps.

PDF-to-Word OCR uses `LocalOcr`, which delegates to the same `OfflineRuntime`
PaddleOCR/MNN engine and downloaded language packs as the embedded offline
translator. The Google Cloud OCR transport, API-key UI and credential handlers
have been removed. Word conversion never uploads pages to an OCR service.
Model downloads remain explicit in OCR Languages; installed recognition works
without network access. Native PDF text and local editable/appearance exports
remain available. Cloud file export is a separate, user-requested file operation.

References: [plans and limits](https://docs.sonarsource.com/sonarqube-cloud/administering-sonarcloud/managing-subscription/subscription-plans),
[Gradle scanner](https://docs.sonarsource.com/sonarqube-cloud/analyzing-source-code/scanners/sonarscanner-for-gradle).
