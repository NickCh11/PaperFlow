# Offline language packs

PaperFlow uses offline-translator's v6 catalog and its pinned `translator-rs`
planner. A core pack installs both translation directions through English,
the language's PaddleOCR recognizer and shared detector/support models.
Shared files are downloaded once. Files are staged before installation; archive
markers are written last. Cancelling a download discards its staging directory.

The catalog is bundled at `translation/src/main/assets/index_v6.json`.
**Settings → Offline languages (OCR and translation) → Check for language pack
updates** refreshes it from `https://offline-translator.davidv.dev/index_v6.json`
and upgrades installed packs. Model URLs come from that upstream catalog.
Camera frames, document images and text stay on the device.

The upstream catalog declares transport sizes rather than cryptographic model
digests. The installer checks HTTPS, downloaded bytes before gzip decoding and archive paths.
This is not a claim of cryptographic verification of upstream model bytes.

Old Tesseract and previous translation packs are not loaded by the new runtime.
Their app-private directories are reclaimed after a successful new pack install.
Previously selected OCR languages are migrated to the new catalog codes; missing
new packs must be downloaded before those languages can recognize documents.

## Private archives

`PaperFlow-models` remains a separate private archive. It is not an anonymous
download endpoint for Android apps and the new runtime does not embed GitHub
credentials. Existing archive releases remain available for older application
versions.

The staging and publication tools now read the v6 catalog:

```sh
python tools/ci/model_distribution.py
python tools/ci/prepare_model_release.py
python tools/ci/publish_model_release.py --repository OWNER/REPO --folder build/model-releases/TAG --commit TARGET_COMMIT --private-archive
```

Staging performs no upload. Publication requires an authenticated GitHub CLI.
Asset names identify catalog descriptors; `SHA256SUMS.txt` records the bytes
actually downloaded. Archives include the upstream catalog, original source
URLs, licenses and notices. Model terms remain independent of the app license.
Changing the bundled catalog requires regenerating its archive tag with
`python tools/ci/model_distribution.py --write-tag`.

## Availability checks

The separate scheduled model health workflow probes catalog URLs and retries
temporary failures. Its full verification option downloads models and checks
transport sizes and gzip integrity. Reports are saved as Actions artifacts. PR checks validate the
bundled catalog and archive tag without depending on a live health probe.

CI and local device fixtures use `tools/ci/prepare_models.py` to install the
English and Greek core files into `offline-language-packs/`.
