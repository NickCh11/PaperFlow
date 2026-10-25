#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")/../.." && pwd)
export RUSTUP_TOOLCHAIN
RUSTUP_TOOLCHAIN=$(cat "$root/tools/ci/native_coverage_toolchain.txt")
export ANDROID_NDK_HOME="${ANDROID_SDK_ROOT:-${ANDROID_HOME:?Android SDK is required}}/ndk/28.0.13004108"
export ANDROID_NDK_ROOT="$ANDROID_NDK_HOME"
export CMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake"
export ANDROID_ABI=x86_64 ANDROID_PLATFORM=android-26 CMAKE_GENERATOR=Ninja
# Separate objects keep coverage flags/toolchains out of release builds.
export CARGO_TARGET_DIR="$root/translation/native/target/ci-coverage"
runtime_lib="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/lib/clang/19/lib/linux/libclang_rt.builtins-x86_64-android.a"
export RUSTFLAGS="-C instrument-coverage -Z coverage-options=branch -C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=$runtime_lib"
destination="$root/build/native-coverage/x86_64"
mkdir -p "$destination"
cd "$root/translation/native"
cargo ndk --target x86_64-linux-android --platform 26 --link-libcxx-shared --output-dir "$(dirname "$destination")" build --locked --release --lib --features ci-coverage
cargo ndk-test --target x86_64-linux-android --platform 26 --link-cxx-shared --locked --release --lib --features ci-coverage --no-run --message-format=json > "$destination/cargo-artifacts.json"
python3 "$root/tools/ci/collect_native_tests.py" "$destination/cargo-artifacts.json" "$destination/paperflow-runtime-tests"
python3 "$root/tools/ci/native_coverage.py" manifest --artifacts "$destination"
