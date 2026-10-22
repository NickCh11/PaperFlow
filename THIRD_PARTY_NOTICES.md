# PaperFlow third-party notices

PaperFlow as a combined application is provided under GPL-3.0-or-later. Original third-party copyright notices and license terms remain applicable to their components. This application is independent of FairScan, unmark, PlayTranslate, Mozilla and CamScanner.

## FairScan

Copyright 2025–2026 The FairScan authors. GPL-3.0-or-later.
https://github.com/pynicolas/FairScan/tree/c1517c52bf10eeeea8aef6710c7fad94d4edcc67

Image processing sources, OCR language naming, word/line extraction and searchable PDF text layer were adapted. Integration changes include Java entry points, bitmap ownership, shared serialized inference, OpenCV resource cleanup, a unique OCR font resource name and support for nonzero PDF crop origins. Upstream mask thresholding, preprocessing, geometry, color selection and enhancement are retained. CameraX live display and existing Book crease handling belong to PaperFlow's integration.

Segmentation model v1.2.0, GPL-3.0:
https://github.com/pynicolas/fairscan-segmentation-model
SHA-256: 96e14d7e610dd0c27b768b228fbc553b4ec119ebe68f3a3594029a25400691d2
Model bytes are unchanged. PaperScan Pro is a PaperFlow UI label for FairScan's automatic color/grayscale enhancement.

## unmark

EmreO33 and contributors. GPL-3.0.
https://github.com/EmreO33/unmark/tree/b757a9b4273d9078749f57f430b09bab8df89047

Inpainter.kt retains the nearest-neighbor multi-source fill, six smoothing passes, 1024-pixel processing limit and full-resolution masked blend. Adaptations: synchronous worker entry point, Java access, temporary bitmap recycling and rejection of an entirely selected image. Pinch zoom and drawing controls are PaperFlow integration code.

## Local translation

PlayTranslate, GPL-3.0:
https://github.com/dominostars/playtranslate/tree/c797cafbe3347a1c6bce7ac57978d973b9211b20
The Bergamot JNI bridge and Android CMake integration were adapted. Native services are serialized; the C++ runtime is linked statically inside the JNI library. ARM NEON / x86 SSE2 selection, API 26 aligned allocation, a vendored PCRE2 source path and 16 KB library alignment are integration changes.

slimt fork and contributors, GNU GPL version 2 as distributed; embedded Marian-derived files retain their MPL-2.0 notices:
https://github.com/dominostars/slimt/tree/a0a486cf573c862c2f74eb32f068fec561ba0a08
Complete pinned sources and dependency licenses are retained in translation/third-party. Exact source archives and hashes are recorded in translation/upstream-lock.json. Dependencies include SentencePiece (Apache-2.0), ruy (Apache-2.0), cpuinfo (BSD), gemmology (MPL-2.0), xsimd (BSD), intgemm (MIT), GoogleTest (BSD), and PCRE2 10.43 (BSD). Some vendored dependencies are not selected in the Android build.

PaperFlow's ARMv7 integration selects the existing ruy int8 backend because the
vendored gemmology ARM kernels require ARM64. The model reader validates 64-bit
on-disk sizes before converting them to the target's `size_t`, rejecting values
and array sizes that exceed the address space.

Mozilla Firefox Translations tiny models, MPL-2.0:
https://github.com/mozilla/firefox-translations-models
https://storage.googleapis.com/moz-fx-translations-data--303e-prod-translations-data/db/models.json
The catalog includes 62 stable tiny-model directions connecting 32 languages through English. Pinned model versions are recorded per entry. Weights, vocabulary and shortlist are downloaded unchanged from Mozilla's official attachments CDN. Exact URLs, sizes and SHA-256 hashes are in assets/languages/translation.json. Attribution and model license apply to model files; model output is not relicensed as a model adaptation.

## OCR and other runtimes

UVDoc, MIT, Nicolas Markovitz and contributors:
https://github.com/tanguymagne/UVDoc/tree/4c9b82b537057aff2526e6dd118a847cdd072e82
The checkpoint is converted to ONNX with only its 2D backward-grid output.
Architecture, conversion script, hashes and adaptation details are in app/model-tools/uvdoc.
The MIT license is included as assets/licenses/UVDoc-LICENSE.

ONNX Runtime Android 1.23.2, Microsoft and contributors, MIT:
https://github.com/microsoft/onnxruntime/tree/v1.23.2
Used to execute the UVDoc grid locally. Its upstream license is included as
assets/licenses/ONNX-RUNTIME-LICENSE.

Tesseract and tessdata_fast, Apache-2.0:
https://github.com/tesseract-ocr/tesseract
https://github.com/tesseract-ocr/tessdata_fast/tree/87416418657359cb625c412a48b6e1d6d41c29bd
126 downloadable traineddata entries are pinned to Git blob SHA-1 and byte size. TesseractGlyphLessFont.ttf is included for invisible searchable text.

Tesseract4Android 4.9.0 (Apache-2.0), Leptonica (BSD), libpng (libpng license), libjpeg-turbo (IJG/BSD/zlib): https://github.com/adaptech-cz/Tesseract4Android

LiteRT 1.4.1 and support API 1.4.1 (Apache-2.0): https://github.com/google-ai-edge/LiteRT
OpenCV 4.12.0 (Apache-2.0 and bundled third-party notices): https://github.com/opencv/opencv
PDFBox-Android 2.0.27.0 / Apache PDFBox (Apache-2.0): https://github.com/TomRoush/PdfBox-Android
ZXing 3.5.3 (Apache-2.0): https://github.com/zxing/zxing
AndroidX, CameraX and Material Components (Apache-2.0): https://source.android.com/ and https://github.com/material-components/material-components-android
Kotlin standard library (Apache-2.0): https://github.com/JetBrains/kotlin

MediaPipe Selfie Segmenter float16 v1 model, Apache-2.0:
https://storage.googleapis.com/mediapipe-assets/Model%20Card%20MediaPipe%20Selfie%20Segmentation.pdf
SHA-256: 191ac9529ae506ee0beefa6b2c945a172dab9d07d1e802a290a4e4038226658b
Model bytes are unchanged; it runs through LiteRT for ID Photo Maker. No ML Kit SDK is packaged.

Full license texts accompanying included sources and standard GPL, Apache, MPL and BSD licenses are available under assets/licenses and in the corresponding vendored source directories. The APK's Settings → Open source licenses screen displays this attribution document.


Language-detection profiles, Apache-2.0:
https://github.com/optimaize/language-detector/tree/1a322c462f977b29eca8d3142b816b7111d3fa19
Original profile data by Nakatani Shuyo and contributors, distributed by Optimaize. The app includes derived lowercase NFKC rank profiles (top 700 n-grams for each of 32 languages) rather than the upstream Java library or complete profiles. A custom local rank-distance classifier consumes them. The upstream license text is included as assets/licenses/LANGUAGE-PROFILES-APACHE-2.0.txt.

## Language model distribution mirrors

Unmodified pinned Tesseract tessdata_fast files (Apache-2.0) and Mozilla Firefox Translations model/vocabulary/shortlist files (MPL-2.0) may additionally be downloaded from the project's configured public GitHub model Release. Each mirror uses the same pinned model bytes and verification as its official source. The model Release includes the upstream manifests, full model licenses, attribution, original source URLs and SHA-256 checksums. No model license is replaced by the application license. See docs/MODEL_DISTRIBUTION.md.
