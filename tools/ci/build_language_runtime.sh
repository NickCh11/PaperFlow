#!/usr/bin/env bash
set -euo pipefail
abi="${1:-arm64-v8a}"
case "$abi" in
  arm64-v8a) target=aarch64-linux-android ;;
  armeabi-v7a) target=armv7-linux-androideabi ;;
  x86_64) target=x86_64-linux-android ;;
  *) echo "Unsupported ABI: $abi" >&2; exit 2 ;;
esac
root=$(cd "$(dirname "$0")/../.." && pwd)
export ANDROID_NDK_HOME="${ANDROID_SDK_ROOT:-${ANDROID_HOME:?Android SDK is required}}/ndk/28.0.13004108"
export ANDROID_NDK_ROOT="$ANDROID_NDK_HOME"
export CMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake"
export ANDROID_ABI="$abi" ANDROID_PLATFORM=android-26 CMAKE_GENERATOR=Ninja
case "$abi" in
  arm64-v8a) runtime_arch=aarch64-android ;;
  armeabi-v7a) runtime_arch=arm-android ;;
  x86_64) runtime_arch=x86_64-android ;;
esac
runtime_lib="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/lib/clang/19/lib/linux/libclang_rt.builtins-$runtime_arch.a"
export RUSTFLAGS="${RUSTFLAGS:-} -C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=$runtime_lib"
cd "$root/translation/native"
cargo ndk --target "$target" --platform 26 --link-libcxx-shared --output-dir ../src/main/jniLibs build --release --lib
