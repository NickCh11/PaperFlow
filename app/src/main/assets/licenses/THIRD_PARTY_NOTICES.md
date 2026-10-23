# PaperFlow third-party notices

PaperFlow as a combined application is provided under GPL-3.0-or-later. Original third-party copyright notices and license terms remain applicable to their components. This application is independent of FairScan, unmark, offline-translator, Mozilla and CamScanner.

## FairScan

Copyright 2025–2026 The FairScan authors. GPL-3.0-or-later.
https://github.com/pynicolas/FairScan/tree/c1517c52bf10eeeea8aef6710c7fad94d4edcc67

Image processing sources and the searchable PDF text layer were adapted. OCR inference now comes from offline-translator. Integration changes include Java entry points, bitmap ownership, shared serialized inference, OpenCV resource cleanup, a unique OCR font resource name and support for nonzero PDF crop origins. Upstream mask thresholding, preprocessing, geometry, color selection and enhancement are retained. CameraX live display and existing Book crease handling belong to PaperFlow's integration.

Segmentation model v1.2.0, GPL-3.0:
https://github.com/pynicolas/fairscan-segmentation-model
SHA-256: 96e14d7e610dd0c27b768b228fbc553b4ec119ebe68f3a3594029a25400691d2
Model bytes are unchanged. PaperScan Pro is a PaperFlow UI label for FairScan's automatic color/grayscale enhancement.

## unmark

EmreO33 and contributors. GPL-3.0.
https://github.com/EmreO33/unmark/tree/b757a9b4273d9078749f57f430b09bab8df89047

Inpainter.kt retains the nearest-neighbor multi-source fill, six smoothing passes, 1024-pixel processing limit and full-resolution masked blend. Adaptations: synchronous worker entry point, Java access, temporary bitmap recycling and rejection of an entirely selected image. Pinch zoom and drawing controls are PaperFlow integration code.

## Offline translation and OCR

offline-translator, Copyright (C) 2024 David V, GPL-3.0-or-later:
https://github.com/DavidVentura/offline-translator/tree/8a6707756528b878f6b5cf6e5ffc9b5ed71f315e
PaperFlow adapts its live GL camera surface, JNI tracker fast path, Android font
provider and v6 pack catalog. Live tracking, PaddleOCR/MNN recognition, CLD2
language detection, slimt translation and native image rendering use its pinned
translator-rs engine. The host UI retains PaperFlow capture modes and exports.
See translation/OFFLINE-TRANSLATOR-NOTICE.md and translation/native/Cargo.lock.

translator-rs, Copyright 2026 David, MIT:
https://github.com/DavidVentura/translator-rs/tree/a1e232488007cfe28b9198da1a3351cf32078ef9
The MIT license is included as assets/licenses/TRANSLATOR-RS-LICENSE.

PaddleOCR models and contributors, Apache-2.0:
https://github.com/PaddlePaddle/PaddleOCR
DocAligner lcnet050, DocsaidLab and contributors, Apache-2.0:
https://github.com/DocsaidLab/DocAligner
MNN runtime and contributors, Apache-2.0:
https://github.com/alibaba/MNN
CLD2 and contributors, Apache-2.0:
https://github.com/CLD2Owners/cld2
slimt and its dependencies retain their upstream license notices in the pinned
Git sources resolved by Cargo.lock. Those include Marian-derived translation
code and vocabulary/shortlist handling. Model licenses are independent.

Firefox Translations models, Mozilla and contributors, MPL-2.0:
https://github.com/mozilla/firefox-translations-models
The pinned upstream v6 catalog contains original model URLs, install paths and
decoded sizes. Downloads use offline-translator's model host. No document or
camera data is sent to that host.

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

TesseractGlyphLessFont.ttf, Apache-2.0: retained only as the invisible searchable
PDF font; the Tesseract OCR runtime and traineddata packs are no longer used.
https://github.com/tesseract-ocr/tesseract

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


## Language packs and archives

The app uses the offline-translator catalog and shared core packs directly.
Downloads are staged; complete archive markers are committed last. The upstream
catalog declares decoded byte sizes, not cryptographic model digests. Optional
private archives include separately calculated SHA-256 checksums for their
downloaded bytes. They are not anonymous app download endpoints.
See docs/MODEL_DISTRIBUTION.md.
