# Contributing to PaperFlow

## Interface translations

See [the translation guide](docs/TRANSLATING.md) for the resource files, language registration, local checks and PR workflow. Translations do not require changes to Java or OCR/translation models.

## Code contributions

Ready PRs run the full Android pipeline automatically, including on subsequent
pushes. Draft PRs run fast checks; marking them Ready for review starts full
validation. See [CI checks and merge protection](docs/CI.md)
for the test matrix, local commands and the required `PR gate` repository rule.
Open contribution PRs against `main` unless a maintainer explicitly requests
another base. Developers do not need signing secrets or release tags. Maintainers
publish stable GitHub Releases after exact-main CI passes; see [release steps](docs/BUILD.md#github-releases).

Keep interface text in `app/src/main/res/values/strings.xml`. Use `UiText.get(R.string...)` or `UiText.format(R.string..., args)` where code has no localized Activity context. Use numbered placeholders for dynamic text; do not concatenate English fragments around user data.

Keep tool/mode/intent IDs stable. Translate their captions at display boundaries with `UiText.label`, never the IDs used in routing or saved data. Never pass filenames, recognized text or other user content through that label lookup. Use tags or item IDs for navigation, rather than matching translated captions.

Run the translation validator and its regression tests when changing UI resources. See [build instructions](docs/BUILD.md) for the Android toolchain requirements.

## Downloadable OCR and translation languages

These are separate from interface translations. The app uses pinned upstream
models; contributors do not need to add a language accuracy corpus or benchmark
OCR/translation quality for every catalog entry.

Tests cover our integration: download integrity and cancellation, catalog/script
routing, offline sessions and real PDF/Word output. CI seeds English/Greek packs
for representative checks on both Android APIs. Changes to app-owned routing,
storage or export behavior should retain or update the relevant regressions.
See [the test scope](docs/CI.md) and [basic validation and its limits](docs/BASIC_VALIDATION.md).
