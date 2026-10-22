# Repository contents

| In GitHub | Purpose |
|---|---|
| `app/`, `scanengine/`, `translation/` | Application, tests, bundled runtime models and licensed native sources |
| `gradle/`, Gradle wrapper and root build files | Reproducible Android build configuration |
| `.github/`, `tools/` | PR checks, model checks, localization and artwork tooling |
| `docs/`, `branding/`, README and contribution guide | Project documentation and original artwork |
| License files and upstream locks | Redistribution notices and dependency provenance |

## Kept locally

`.local-work/` contains historical research, comparison screenshots, reports and
machine-specific audit scripts. Nothing was deleted; those files are ignored by Git.

Build output, APKs, downloaded language packs, emulator files, caches, IDE state,
`local.properties`, credentials and signing keys are also ignored.
Required runtime models under `src/` remain in the application repository.

## Separate model repository

`PaperFlow-models` contains OCR/translation catalogs, licenses and release tooling.
The model binaries belong in versioned release assets, not Git history.
Its private releases are an authenticated archive and cannot serve anonymous APK downloads.
