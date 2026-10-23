# Rechecked audit fixes — offline-translator

The eight findings from the architecture audit were rechecked against
`feature/offline-translator` at `1c9eeaf265481018c7795169bf0d13dc5d1a0adb` before
editing. This is a different snapshot from the original `main` audit
(`f072efff6f1252c47f552e3be5f791f998360126`). In particular, the feature branch
has SQLite schema 9 and a different offline language runtime and multi-page
translation flow. The original audit is retained separately; these changes do
not assert that the entire feature branch has been audited.

| Finding | Recheck on the feature branch | Correction |
| --- | --- | --- |
| F01: eager PDF thumbnails | Still rendered and retained every page before showing options; watermark held two sets. | Metadata is read first. The UI shows windows of 30 pages, renders only requested previews, and shares a 4 MiB byte-budgeted thumbnail cache. Selection and reorder state still cover all pages. |
| F02: main-thread trim lock | `onTrimMemory` still called synchronized native cleanup directly. | A coalescing background cleanup request waits for inference off the main thread; inference/close still use the same monitor. |
| F03: exported document commands | Exported MainActivity still dispatched `document_action` extras. | MainActivity is non-exported. A new public DocumentEntryActivity forwards only sanitized external import URIs/MIME/read grants or a launcher action. It drops internal commands and rejects forged URIs to the app's private FileProvider. |
| F04: unbounded library | Home/library still queried full rows and created every document card; indexing and thumbnails shared a queue. | Document queries and folder-visibility filtering run on a separate worker with explicit projection and 30-row windows. Thumbnails have a bounded queue/cache and generation checks; initial duplicate rendering is avoided. |
| F05: watermark bitmap race | Worker still appended to Activity-owned bitmap lists while onDestroy iterated them. | Preview results are worker-local until a main-thread handoff guarded by a thumbnail epoch. Stale results are released; workers no longer append to the Activity's owned bitmap lists. |
| F06: captured translation inputs | Imported images gained tracking, but camera captures still transferred ownership without final cleanup. | Explicitly track validated camera/import input ownership, including recreation. On session completion, delete only owned temporary inputs and retain all active Book-draft paths. |
| F07: default backups | Both backup XML files still contained defaults/comments. The actual model directory is now `offline-language-packs`. | Cloud/device-transfer rules exclude models, temporary captures/sessions and non-portable Keystore-encrypted cloud settings. Documents, retained originals, DB and folder-lock preferences remain together under normal Android backup eligibility. API 31+ cloud backup requires encryption capability. |
| F08: lifecycle disk IO | Annotation/text snapshots still wrote synchronously during onSaveInstanceState. | Stable per-session paths and a serial atomic snapshot writer move JSON serialization/file IO off-main. Annotation copies are immutable at handoff; restoration reads follow queued writes on a worker. Normal finish deletes the session snapshot. Text sharing also writes its export off-main. |

## Behavioral details

- Paging is explicit: Previous/Next document or page windows. It does not limit
  the PDF's page count, export selection, or reorder state to 30 pages.
- Thumbnail eviction drops the cache reference rather than recycling a bitmap
  that a currently attached Android View may still draw. Removed windows release
  their View references; active UI plus cache memory remains bounded.
- The existing `MainActivity` remains the internal routing target, so internal
  editing/signing/registration flows do not need to grant public command access.
- Backup remains enabled for durable user data. Models can be downloaded again;
  cloud credentials must be re-entered after transfer because their Android
  Keystore key is device-bound. Temporary Book drafts are local, not transferred.
- Snapshots recover the last completed atomic write after process interruption.
  The UI never waits for disk durability in a lifecycle callback. Snapshots are
  temporary cache data, capped at 32 MiB, and can be removed by Android. This does
  not promise recovery of a write killed before its first successful commit.
- Cleanup is deliberately conservative if Book-draft ownership cannot be read.
  No broad startup deletion of captures or drafts has been added.
- Existing small folder/navigation actions still use the current architecture;
  this is not a full conversion to asynchronous repositories or RecyclerView.

## Validation

Regression coverage added:

- JVM: coalescing/non-blocking cleanup, rejected submission recovery, pagination
  boundaries and reachability, owned-input cleanup, traversal/external-file and
  active-draft protection.
- Android: sanitized import boundary and exported component metadata; memory
  trim while the inference monitor is held; backup policy; atomic snapshot
  ordering/failure recovery; a 65-document paged library; a 61-page watermark
  options flow with selection retained after paging/recreation; translation
  cleanup with an active Book draft; annotation snapshot recreation.

Device checks use a new isolated API 29 emulator. The existing user emulator
and its app data are not modified. The local debug build reuses the offline
runtime libraries from the already-installed debug APK and checks the expected
runtime API symbol. These checks concern the changed UI/lifecycle/storage code;
they do not validate the native runtime's numerical behavior or provenance as a
fresh source build. Native source code is unchanged. CI should rebuild/use its
pinned runtime artifacts for release verification.

Validation passed: 50 JVM tests (none skipped), 9 new Android regression tests,
2 existing folder-protection tests and 5 existing PDF/translation tests on the
isolated emulator. Debug app/test APK compilation and localization validation
passed. Android Lint reports zero errors and 96 warnings across the modules
(86 app, 10 scanengine); this change does not resolve all lint warnings. An
external launch of the now-private MainActivity was rejected by Android.

Physical-camera, biometric hardware, ARM, production memory/energy
benchmarks and actual cloud-backup transport still require their normal device
acceptance checks.
