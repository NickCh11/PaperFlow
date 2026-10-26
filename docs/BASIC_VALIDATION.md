# Basic validation and its limits

The priority is useful regression protection, rather than 100% coverage. A test
pass establishes its asserted scenarios, not the absence of all bugs. Upstream
model accuracy benchmarks are intentionally outside the automatic suite.

## Automated contracts

| Area | Existing protection and representative suites |
| --- | --- |
| Imports and private files | `ImportBoundaryTest`, `PublicEntryLifecycleTest`, `FileSharingBoundaryTest`: sanitized commands, content-only/read-only imports, import cap, user-qualified private authorities, allowed export folders and traversal rejection |
| PDF/Word output and permissions | `PdfToolExportFlowTest`, `WordConversionRoutesTest`, `MainWordWorkflowTest`: actual output bytes/content/pages, restrictive PDF access, passwords, owner access, sharing and cancellation |
| Save/cancel and concurrency | `TranslationSaveRegressionTest`, `PhotoSessionInputTest`, `PreviewConcurrencyTest`, `AuditRegressionTest`: queued cancellation, complete in-flight writes, latest request ownership, retained previews and responsive memory trim |
| Local OCR/translation integration | `OfflineLanguageIntegrationTest`, `OfflineWordRecognitionTest`, `TranslationFlowTest`, `NativeRuntimeContractTest`: usable offline sessions, output text/coordinates, missing models, download integrity and cancellation; no linguistic accuracy corpus |
| Storage and recovery | `LibraryManagementFlowTest`, `LibraryPagingRegressionTest`, `FolderProtectionFlowTest`, `SessionSnapshotFailureTest`, `BookCaptureSessionTest`: folder/PIN operations, paging, failed writes, last good snapshot and new sessions restoring disk data |
| Screens and lifecycle | `AppLanguageTest`, `AppearanceTest`, `PdfViewerLifecycleTest`, `PdfInputRecoveryTest`: registered locales, contrast/bounds, recreation, restored options/results and asynchronous UI readiness |
| Editing and capture processing | `SignaturePageChoicesTest`, `PdfEditorInteractionTest`, `ImageProcessingRegressionTest`, `MigrationIntegrationTest`: copy limits, handle selection, page edits, synthetic captures, filters, rotation and geometry |
| APK/native packaging | `check_apk.py`: required native engines, ABI sets and ELF architecture in debug and release artifacts; native test executable plus JNI contracts |
| Release manifest | `check_android_manifest.py`: AGP's merged release manifest, including library declarations; rejects debugging/test-only/cleartext settings, unexpected permissions/exports, unprotected profile receiver and debug document provider |
| Workflow decisions | `test_ci_plan.py`, `test_ci.py`, `test_release_validation.py`: ready PRs cannot select fast acceptance, incomplete/skipped test reports fail, release requires successful full jobs for the exact main revision |

The release-manifest policy runs in each of the ARM64, ARMv7 and universal build
jobs. Failure blocks their existing required PR gate. Source permissions/exports
can change deliberately, but need a reviewed policy update. No Sonar threshold,
coverage exclusion, required reviewer or release prerequisite is weakened.

## Concrete regression found during this check

The import sanitizer rejected `content://<app>.files/...`, but accepted Android's
user-qualified equivalent `content://0@<app>.files/...`. A device regression failed
before the fix because the sanitized action was VIEW instead of MAIN. Provider
comparison now removes the user prefix before enforcing the same private-file
boundary, and rejects missing authorities. Tests retain user-qualified external
imports and reject private variants in single and multiple import paths. This
reproduces an input-validation bypass; it does not claim demonstrated external
data exfiltration.

## Checks still requiring a real-device acceptance pass

These are explicit remaining checks, not automated passes:

- Physical camera focus/flash/orientation and camera permission denial/recovery.
- Biometric success, cancellation, lockout and device credential behavior.
- Real document-provider permission revocation and full-storage/write failures.
- OS process death in the background and reopening from Recents; new-session
  snapshot tests and Activity recreation are not a complete substitute.
- Low-memory pressure, repeated large scans, UI frame timing and battery use on
  representative ARM phones, plus Android 8/API 26 runtime acceptance. Emulators
  cover API 29/37 and Lint checks API compatibility against the app's minimum.

For each real-device run, record model/Android version, application revision,
scenario, expected/actual behavior and any crash/ANR. Do not mark this section
passed based only on source coverage or emulator results.
