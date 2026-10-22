# Contributing to PaperFlow

## Interface translations

See [the translation guide](docs/TRANSLATING.md) for the resource files, language registration, local checks and PR workflow. Translations do not require changes to Java or OCR/translation models.

## Code contributions

Every PR runs the full Android pipeline. See [CI checks and merge protection](docs/CI.md)
for the test matrix, local commands and the required `PR gate` repository rule.

Keep interface text in `app/src/main/res/values/strings.xml`. Use `UiText.get(R.string...)` or `UiText.format(R.string..., args)` where code has no localized Activity context. Use numbered placeholders for dynamic text; do not concatenate English fragments around user data.

Keep tool/mode/intent IDs stable. Translate their captions at display boundaries with `UiText.label`, never the IDs used in routing or saved data. Never pass filenames, recognized text or other user content through that label lookup. Use tags or item IDs for navigation, rather than matching translated captions.

Run the translation validator and its regression tests when changing UI resources. See [build instructions](docs/BUILD.md) for the Android toolchain requirements.
