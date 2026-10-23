# Offline language runtime

PaperFlow integrates [offline-translator](https://github.com/DavidVentura/offline-translator)
at commit `8a6707756528b878f6b5cf6e5ffc9b5ed71f315e` (David V, GPL-3.0-or-later).
The JNI live pipeline, Android font provider, GL camera surface and catalog are
adapted from that source. Copyright notices remain in the source files.

Its [translator-rs](https://github.com/DavidVentura/translator-rs) core is pinned
to `a1e232488007cfe28b9198da1a3351cf32078ef9` (MIT); Cargo.lock pins transitive
dependencies, including slimt, MNN and CLD2. Model licenses are independent of
the application license and remain those declared by the upstream publishers.

Build each ABI using `bash tools/ci/build_language_runtime.sh ABI` on Linux with
Rust 1.88, cargo-ndk 4.1.2, Ninja, CMake and Android NDK 28.0.13004108.
The GitHub `Offline language runtime` workflow builds all three supported ABIs.
