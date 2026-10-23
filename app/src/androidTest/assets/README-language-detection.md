# Language detection fixtures

`language-detection-samples.json` must contain exactly the language codes in
`translation/src/main/assets/index_v6.json`. The GitHub CI tooling test rejects
missing and obsolete fixtures. Adding a downloadable language requires its own
meaningful text and expected detection code. An empty expectation cannot be used
to skip a new language.

Samples come from the pinned offline-translator catalog (the same samples in
translator-rs `data/sample_texts.json`); the Afrikaans sample was authored for
this test. Existing third-party notices cover the upstream sources.

Device tests check each sample as text and as a rendered image through automatic
OCR, followed by the photo tool's language detector. All OCR script fixtures are
seeded by `tools/ci/prepare_models.py --all-ocr --install`, with their installed
bytes verified before the tests run.

Two explicit detector limitations are tested, not silently skipped:

- Bosnian and Croatian: CLD2 does not distinguish Bosnian. Photo translation must
  request manual source selection instead of confidently mislabelling it.
- Norwegian and Norwegian Bokmal: both catalog entries use the same sample.
  Either `nb` or `no` identifies that writing system; both downloads remain usable.

Reference: https://github.com/CLD2Owners/cld2/blob/master/public/compact_lang_det.h
