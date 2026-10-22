# Local validation: appearance and universal APKs

Validated locally on Windows on 2026-10-21/22. Gradle uses JDK 25, as selected by the existing daemon JVM criteria; GitHub Actions now installs the same major version.

| Check | Result |
| --- | --- |
| Full Android suite, API 29 | 57 passed, none skipped |
| Full Android suite, API 37.0 | 57 passed, none skipped |
| Appearance at 320dp / font scale 1.3, API 29 | 3 passed |
| Appearance at 320dp / font scale 1.3, API 37.0 | 3 passed |
| JVM unit tests | 32 passed |
| Localization validator regression tests | 22 passed |
| CI tooling regression tests | 15 passed |
| Translation resource validation | 0 errors |
| Debug lint, app / scanengine / translation | 0 errors |
| GitHub workflow syntax (actionlint) | Passed |

Full-suite XML reports are saved locally under `artifacts/appearance/full-tests`; compact-screen reports are under `artifacts/appearance/compact-tests`. Full device validation includes the adaptive colors for the folder-lock shield and import/naming dialogs. The final Share/signature-handle foreground color adjustments and appearance-help wording were followed by builds, unit tests, lint and translation validation; the full device suite was not repeated for those cosmetic changes.

Debug and unsigned release APKs are produced for universal (`arm64-v8a`, `armeabi-v7a`, `x86_64`), ARMv8 and ARMv7. The local delivery script verifies each required native engine and ELF architecture, recording hashes in `artifacts/arm-builds/manifest.json`.

See [Appearance and adaptable layouts](APPEARANCE.md) for the implementation and CI coverage. Device checks used emulators; no physical ARM runtime test was performed. GitHub Actions configuration and the local ruleset template are prepared, but no remote repository or branch ruleset was activated in this workspace.
