# F-Droid packaging status

Checked against release v1.2.4, commit
`b3bf5b4b16b2611d785e787799cbd7aeb087111b`.
Request: https://gitlab.com/fdroid/rfp/-/work_items/4721

## Completed

- English and Greek Fastlane descriptions and release notes, icon and four screenshots.
- Gradle 9.6.0 wrapper JAR checked against its official SHA-256. Distribution
  checksum remains enabled. Foojay resolver and daemon download URLs removed.
- Full main CI and signed release build passed, including both emulator APIs.
- Public native source revisions are pinned in `translation/native/Cargo.lock`.
  `mnn-sys` compiles its MNN submodule with CMake; `slimt-sys` compiles its slimt
  submodule. The build script does not download a ready-made JNI runtime.

## Existing F-Droid precedent

F-Droid already has a recipe for the upstream Translator app, including version
0.8.6 at `8a6707756528b878f6b5cf6e5ffc9b5ed71f315e`, the same Android integration
revision identified in PaperFlow's notices. It uses Rust 1.88.0, cargo-ndk 4.1.2
and NDK r28. This is useful precedent, not approval of PaperFlow or every model.

https://gitlab.com/fdroid/fdroiddata/-/blob/master/metadata/dev.davidv.translator.yml

## License evidence and open scope

| Component | Evidence | Remaining review |
| --- | --- | --- |
| PaperFlow | Root GPL-3.0-or-later license and notices | Full transitive dependency license inventory |
| FairScan segmentation | GPL-3.0 model repository, public train.py and linked dataset | Exact model release/data terms in packaging review |
| UVDoc | MIT upstream repository includes model/best_model.pkl and training code; conversion and hashes in app/model-tools/uvdoc | Model and training-data terms separately from code |
| Selfie segmentation | Model source/card and SHA-256 in THIRD_PARTY_NOTICES.md | Confirm license applies to the exact bundled model bytes |
| translator-rs / bindings | Pinned MIT package declarations; JNI integration built from source | Licenses of recursively included C/C++ submodules and Cargo dependencies |
| slimt | Pinned source includes GPL v2 license text | Confirm effective grant/version compatibility, including Marian-derived files; generic license appendix alone is not proof of an “or later” grant |
| Firefox translation models | Mozilla repository contains MPL-2.0 license | Map exact mirrored catalog artifacts to upstream model/license records |
| PaddleOCR / DocAligner | Apache-2.0 project attribution | Exact converted/quantized MNN files, support/ink models and conversion provenance |

Upstream evidence:

- https://github.com/pynicolas/fairscan-segmentation-model
- https://github.com/tanguymagne/UVDoc
- https://github.com/mozilla/firefox-translations-models/blob/main/LICENSE
- https://github.com/PaddlePaddle/PaddleOCR
- https://github.com/DocsaidLab/DocAligner
- https://github.com/DavidVentura/mnn-sys/tree/9c0d4527d5755de37affc5dd1517cf10073844ad
- https://github.com/DavidVentura/slimt-sys/tree/1cef6a655b713aef25695eba8451d10ac1c4f21a
- https://github.com/DavidVentura/slimt/tree/2beb403abfff5b2f4c165a6d51362c973d037e97

The v6 catalog contains translation, OCR, support, dictionary and TTS descriptors.
The catalog's presence does not mean all these features ship in PaperFlow.
Do not attribute all files to one license based only on the app or engine license.
Runtime model entries supply URLs and sizes, but not per-file license evidence
or cryptographic digests. See MODEL_DISTRIBUTION.md for integrity limitations.

Regenerate the inventory without downloading any model:

```sh
python3 tools/ci/fdroid_inventory.py > build/fdroid-inventory.json
```

The checked catalog has 121 translation, 15 OCR, 49 support, 56 dictionary and
127 TTS descriptors. Its 818 file entries lack explicit per-entry license and
SHA-256 fields. Entries can share files; this is not 818 unique models and does
not mean they all ship or are used. The inventory verifies the three bundled
model hashes against the checked source lock/conversion record; matching a hash
establishes identity, not licensing rights.

## Candidate build recipe

`fdroid/com.example.myapplication.yml` is an ARM64-only starting recipe,
adapted from the official upstream recipe and PaperFlow's successful source CI.
It preserves the application ID and versionCode and builds JNI during the build
phase, after source scanning. It contains no blanket scanignore/scandelete and
does not substitute GitHub native artifacts or production signing credentials.
The build remains disabled pending an unmodified F-Droid build-server run and
the remaining model and dependency license review.

Local validation used fdroidserver 2.4.5 from PyPI: its metadata parser accepted
the recipe. The selected `check_builds`, `check_license_tag`,
`check_app_field_types`, `check_current_version_code` and
`check_certificate_pinned_binaries` checks returned no warnings (the candidate
disable flag was cleared in memory for validation). This was not a full
`fdroid lint`, source scanner, dependency license audit or APK build.

A separate fdroidserver 2.4.5 `scan_source` run on a clean git archive of
`f1600de` with refreshed SUSS signatures returned **0 fatal findings** on
2026-11-01. It warned about seven binary assets: three Office test fixtures,
UVDoc, the searchable-PDF font and the two TFLite models. This Windows scan
does not scan resolved Maven/Cargo dependencies or prove model licensing.
Executable-bit/type handling and all warnings need Linux confirmation.

On a Linux F-Droid build environment with FLOSS JDK 25 and Android SDK 37.0,
copy the candidate into an fdroiddata checkout's metadata directory, then run:

```sh
fdroid lint com.example.myapplication
fdroid readmeta
# Remove the candidate's disable field only in the local test checkout.
fdroid build --verbose com.example.myapplication:1002004
```

Record fdroidserver and build-image revisions, full log, APK application ID,
version, native architecture and source scanner result. Check the build image's
Gradle 9.6.0/JDK 25 support, both NDK paths and Rust/CMake submodule dependencies.
Use fdroidserver's Gradle provisioning rather than downloading a replacement
prebuilt JNI library. Investigate scanner findings individually.

On 2026-11-01, Ubuntu 26.04.1 on WSL 2 with fdroidserver 2.4.3 passed
`fdroid lint` after changing the candidate category from Office to OCR. An
exploratory `fdroid build --test --stop --no-tarball` then passed the Linux
source scanner and built an unsigned ARM64 APK from the pinned v1.2.4 commit.
`aapt dump badging` confirmed application ID `com.example.myapplication`,
versionName 1.2.4, versionCode 1002004, minSdk 26 and targetSdk 37; APK native
libraries are arm64-v8a. Native OCR/translation was compiled from source.

Two local adaptations were required: Ubuntu's fdroidserver 2.4.3 does not
accept an NDK version list, so the temporary test recipe specified NDK 28 only
while both NDKs were preinstalled; and its Gradle helper lacks Gradle 9.6.0,
so the official distribution SHA-256 was temporarily added to that helper.
The download passed checksum verification and the helper was restored afterward.
Ubuntu's newer CMake also rejected sentencepiece; the recipe now selects the
project's Android SDK CMake 3.22.1 explicitly. The build used the local WSL
environment, not `--server`; it does not establish F-Droid build-server support
or bit-for-bit reproducibility. Do not enable automatic updates or submit the
candidate as an independently verified F-Droid build yet.

## Publishing and signatures

The release certificate SHA-256 is
`30ebbadef47f0e4336f8c3b47bd0cdfa0bf73e1f907e804d430791cecec02caf`.
No private signing material belongs in F-Droid metadata. Upstream-signature
distribution requires a successful reproducibility comparison; normal F-Droid
signing would require reinstalling when switching from the GitHub build.
No byte-for-byte reproducibility result is available yet.

The repository began in October 2026. Any exception to F-Droid's young-project
policy must be decided by its maintainers; do not claim that it has been granted.
