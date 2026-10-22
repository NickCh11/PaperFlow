# Reliable language-model distribution

## Runtime downloads

The app tries the official pinned URL first. Transient network/server failures receive one retry; missing or corrupt assets move straight to the next source. When a public GitHub model repository is configured, the fallback is its versioned Release. Every source must provide the same pinned bytes, size and digest. Mirror downloads are raw original model bytes, including when the official source is gzip-compressed.

Cancellation stops retries/fallback. A failed download removes its `.part` file and preserves an existing target. Only a fully verified file is moved into place. HTTPS redirects may not downgrade to HTTP. OCR uses its existing Git blob SHA-1 pin; translation uses SHA-256. Release assets additionally have SHA-256 checksums for every file.

Downloaded working packs remain local and usable offline. No document or recognized text is sent to download hosts.

## Private archive

`PaperFlow-models` is prepared as a separate private repository. It contains the
OCR/translation catalogs, model licenses, matching release tag and distribution
scripts. Its manually triggered workflow requires a private repository and uploads
the 305 verified files as release assets, rather than adding 1.73 GB to Git history.

Private releases require authenticated GitHub access. They preserve a maintainer
copy but are **not an anonymous fallback endpoint for the Android app**. No personal
GitHub token is embedded in the application. Official downloads and retries remain
enabled; `MODEL_MIRROR_REPOSITORY` stays unset until a public endpoint is available.
See [GitHub's release asset authentication documentation](https://docs.github.com/en/rest/releases/assets).

The private publisher uses `--private-archive`. It verifies that the destination is
private and validates asset sizes and SHA-256 values through the authenticated API.

## Optional public mirror setup

No model repository is assumed for local builds. Until one is configured, official downloads and retries work; a GitHub fallback URL is not invented.

1. Use a separately approved public model repository containing the catalogs, licenses, distribution tag resource and release scripts. Do not change a private repository's visibility as part of routine publication.
2. Run `prepare_model_release.py` and then `publish_model_release.py` without `--private-archive`. It downloads and verifies all 305 unique files (about 1.73 GB), includes licenses, original manifests, attribution and SHA256SUMS, and creates a draft Release for the catalog's deterministic tag.
3. The publisher compares the uploaded assets' server-reported SHA-256 digests and sizes before publishing. A partial draft can be resumed. Existing differing bytes are never overwritten; unexpected assets cause a failure. An already published identical complete release is accepted without modification.
4. Set the app repository variable `MODEL_MIRROR_REPOSITORY` to the public `owner/models-repository`. There is no automatic default to the private app repository.
5. For local builds set `-PpaperflowModelRepository=owner/models-repository` or environment variable `PAPERFLOW_MODEL_REPOSITORY`. APKs must be rebuilt with that setting after the Release is available. Do not put private access tokens in the app.

Local staging and optional manual publication:

```text
python tools/ci/prepare_model_release.py
python tools/ci/model_distribution.py
python tools/ci/publish_model_release.py --repository OWNER/REPO --folder build/model-releases/TAG --commit TARGET_COMMIT
```

The final command publishes a public mirror using an authenticated `gh` CLI and the named repository's valid target commit. To publish an authenticated private archive instead, add `--private-archive`. Local staging performs no upload. GitHub permissions are read-only for checks and `contents: write` only for the manually triggered publication job.

Retain old model Releases: existing APKs reference their exact catalog tag, never `latest`. When either catalog changes, run `python tools/ci/model_distribution.py --write-tag`, publish the new Release, then build the app. The required PR checks reject a stale tag.

## Availability checks

`Language model availability` runs every Monday. It checks every official and mirror URL with a one-byte GET request, retrying temporary failures. It fails on any unavailable source, including a missing/unpublished mirror; it never silently treats a missing mirror as healthy. The monthly run on day 1 downloads and verifies all bytes from both sources. A manual run can also enable full verification.

Reports are retained as Actions artifacts. Failures appear as failed GitHub workflow runs; notification delivery follows repository/user GitHub notification settings. No messaging or issue creation is performed by the workflow.

Local checks:

```text
python tools/ci/check_model_links.py --report build/model-health.json
python tools/ci/check_model_links.py --repository OWNER/REPO --verify
```

Without `--repository`, the command checks official sources only. Scheduled Actions add mirror checks only when `MODEL_MIRROR_REPOSITORY` is explicitly configured for a public endpoint. They do not send anonymous requests to the private archive. Live network health checks run separately from PR checks, so an upstream outage does not block unrelated PRs; inventory/tag validation and deterministic regression tests are required by PR gate.

## Redistribution records

Model bytes are unchanged. The staged Release includes the Tesseract tessdata_fast Apache-2.0 license, Mozilla models MPL-2.0 license, original source URLs, versions/hashes and attribution. Original model files are independently downloadable in the Release, and the original manifests map the content-addressed asset names to their upstream names. Source licenses: https://github.com/tesseract-ocr/tessdata_fast/blob/87416418657359cb625c412a48b6e1d6d41c29bd/LICENSE and https://github.com/mozilla/firefox-translations-models/blob/main/LICENSE.

## Validation for this change

- 40 JVM tests passed, including eight real local-HTTP download scenarios.
- 21 CI tooling tests passed, including inventory/tag checks, health-probe retries and remote-release asset validation.
- All 305 unique upstream model files were downloaded and validated while staging the local Release directory.
- All 305 official endpoints passed the separate link health check.
- Three isolated Android OCR/download integration tests passed on API 29, including real language downloads and searchable PDF output.
- Translation validation and debug lint/build passed.
- The private archive source and release files are prepared locally. GitHub upload still requires an authenticated repository creation/push connection. No configured public mirror is claimed to be active in local APKs.
