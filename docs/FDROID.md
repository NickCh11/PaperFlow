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
| FairScan segmentation | GPL-3.0 model repository, public train.py and linked dataset | Linked training dataset has a noncommercial license; exact model rights and F-Droid treatment need review |
| UVDoc | MIT upstream repository includes model/best_model.pkl and training code; conversion and hashes in app/model-tools/uvdoc | Model and training-data terms separately from code |
| Selfie segmentation | Model source/card and SHA-256 in THIRD_PARTY_NOTICES.md | Confirm license applies to the exact bundled model bytes |
| translator-rs / bindings | Pinned MIT package declarations; JNI integration built from source | Licenses of recursively included C/C++ submodules and Cargo dependencies |
| slimt | Pinned upstream COPYRIGHT explicitly grants GPL-2.0-or-later and identifies Apache-2.0 and MPL-2.0 derived files; the original notice is bundled in assets | Review nested dependencies and ensure their notices accompany distribution |
| Firefox translation models | Mozilla repository contains MPL-2.0 license | Map exact mirrored catalog artifacts to upstream model/license records |
| PaddleOCR / DocAligner | Apache-2.0 project attribution | Exact converted/quantized MNN files, support/ink models and conversion provenance |

Upstream evidence:

- https://github.com/pynicolas/fairscan-segmentation-model
- https://github.com/pynicolas/fairscan-dataset/tree/v2.1
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

The bundled `scanengine/src/main/assets/scanner/page-segmentation.tflite` has
SHA-256 `96e14d7e610dd0c27b768b228fbc553b4ec119ebe68f3a3594029a25400691d2`,
matching the digest published for the TFLite asset in the
[FairScan v1.2.0 release](https://github.com/pynicolas/fairscan-segmentation-model/releases/tag/v1.2.0).
That release's
[training script](https://github.com/pynicolas/fairscan-segmentation-model/blob/78cde680c36137922218260ffdd74dcde4ee1c8b/train.py)
selects dataset v2.1, whose
[README](https://github.com/pynicolas/fairscan-dataset/blob/b46fd1ba3cbd1b1427ebe56541ef0311dfa6f35a/README.md)
states CC BY-NC-SA 4.0. The model repository states GPLv3. This identifies a
question about the training data, not proof that the model weights have a
noncommercial license or that they are incompatible with F-Droid. Confirm the
exact model's provenance and applicable rights with its author and the F-Droid
reviewers before claiming that all model licensing has been resolved.

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
This limitation belongs to the old Ubuntu package used locally: public F-Droid
build logs show the production Gradle helper has already built other apps with
Gradle 9.6.0, for example:
https://monitor.f-droid.org/builds/log/org.arkikeskus.launcher/60
Ubuntu's newer CMake also rejected sentencepiece; the recipe now selects the
project's Android SDK CMake 3.22.1 explicitly. The build used the local WSL
environment, not `--server`; it does not establish F-Droid build-server support
or bit-for-bit reproducibility. Do not enable automatic updates or submit the
candidate as an independently verified F-Droid build yet.

A second WSL build set the single local `ndk` field to 27.2.12479018, while
retaining NDK 28 for the Rust runtime script. It passed the source scanner and
built successfully; the CXX1104 version mismatch and unstripped-library warnings
disappeared. Newer fdroidserver versions support an NDK list, and its first
entry controls `ndk.dir`; the candidate therefore lists 27 before 28. Linux
build-server support for the list must still be verified.

## Publishing and signatures

The release certificate SHA-256 is
`30ebbadef47f0e4336f8c3b47bd0cdfa0bf73e1f907e804d430791cecec02caf`.
No private signing material belongs in F-Droid metadata. Upstream-signature
distribution requires a successful reproducibility comparison; normal F-Droid
signing would require reinstalling when switching from the GitHub build.
Cross-machine reproducibility of a complete APK has not yet been established.

An exploratory comparison of the GitHub v1.2.4 ARM64 APK (download SHA-256
`14ca683a16dd295338e08b2e94ffdb18572ebc2bef24f9ce0c941e2f7b90891e`)
with the unsigned WSL build using Ubuntu's apksigcopier 1.1.1 ended with
`APK Signing Block offset < central directory offset`. This is an inconclusive
tool error, not proof that the binaries match or differ. Investigate with an
F-Droid-supported verifier/toolchain before claiming reproducible builds.

ZIP entry comparisons confirmed actual differences in five native libraries
in the first WSL build, where Gradle used the wrong NDK and skipped stripping.
After selecting NDK 27 for Gradle, only `libpaperflow_runtime.so` differs in
size/content; all other common ZIP entries have identical CRC and size. The
signed APK also contains three JAR signature entries absent from the unsigned
build. This is not an APK signature-verification test: the native runtime still
differs and its source of nondeterminism needs investigation. In particular,
the local build uses CMake 3.22.1 whereas GitHub's Ubuntu runner supplies its
own CMake; equivalence has not been established.

Inspection of strings embedded in the two runtime libraries confirms a
concrete reproducibility obstacle: Cargo paths include `/home/runner/.cargo/`
in GitHub's library and `/root/.cargo/` in the WSL library. Both report the
same Rust 1.88.0 and Android NDK compiler versions in their ELF comments.
The native build script now remaps both Rust and C/C++ source and Cargo paths.
Three ARM64 source builds using different source directories and two different
Cargo homes on the same Ubuntu/WSL host produced identical unstripped JNI
libraries (SHA-256 `7e4a7a12e3843380242aa410403b21a466d714f923e2255852bf5c20d3b007ad`).
The updated build script was rerun in two of those locations and `cmp` passed.
The updated script also compiled both the x86_64 JNI library and native test
binary on WSL; this checked compilation, not execution of Android tests.
These initial native comparisons were not comparisons of complete APKs or
independent build machines; subsequent comparisons are documented below.
Toolchain differences, build-server behavior and paths containing
whitespace (for which remapping is skipped) remain to be checked. The already
published v1.2.4 binary cannot be made reproducible by changing its source now.

Mozilla explicitly states that its translation model files use MPL 2.0:
https://github.com/mozilla/translations . Exact mirrored catalog artifacts,
other OCR/support models, training data and transitive licenses remain to audit.
On 2026-11-01, 365 of the catalog's 410 translation file paths were present in
the then-current Mozilla registry; the remaining 45 need separate provenance
review. A matching path alone does not establish byte identity. One sampled
Greek-to-English model downloaded from the mirror matched the registry's
uncompressed SHA-256 `0a43c3716156061494d995d0c88113e789f8d919e0c88a15bc1ad360e0482a8c`.
This result does not verify the other model, shortlist or vocabulary files.

Subsequent native builds from the same source revision on GitHub Actions and
Ubuntu/WSL matched byte-for-byte before APK packaging for arm64-v8a (SHA-256
`7e4a7a12e3843380242aa410403b21a466d714f923e2255852bf5c20d3b007ad`) and
armeabi-v7a (`faf20fc5d70dddb7fbacc7d6164b789b39e4886a12e5c94c83afc3163dc4e661`).
The x86_64 native JNI outputs also matched (SHA-256
`982b36b9e44a34969f24b151aa7287300ed1df465e7cbfdf7ee23726f7073abc`).
Locally, CMake 3.22.1 and 3.28.3 yielded identical ARM64 native outputs.
Two complete unsigned ARM64 APKs built from different source directories on
the same WSL host were also byte-identical (SHA-256
`0dd41186a8c61fe960dbbdcdedf55653982e6c8e4b47a74443c815ac78def60f`).
Those test APKs used the v1.2.4 source plus the remapping script; they do not
establish full APK reproducibility across machines or include later PR assets.

The repository began in October 2026. Any exception to F-Droid's young-project
policy must be decided by its maintainers; do not claim that it has been granted.
