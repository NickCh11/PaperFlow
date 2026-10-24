#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")/../.." && pwd)
binary="$root/build/native-tests/x86_64/paperflow-runtime-tests"
runtime="$root/translation/src/main/jniLibs/x86_64/libc++_shared.so"
test -s "$binary"
test -s "$runtime"
remote=/data/local/tmp/paperflow-native-tests
mkdir -p "$root/build/native-test-results"
adb shell mkdir -p "$remote"
adb push "$binary" "$remote/paperflow-runtime-tests"
adb push "$runtime" "$remote/libc++_shared.so"
adb shell chmod 755 "$remote/paperflow-runtime-tests"
adb shell "LD_LIBRARY_PATH=$remote $remote/paperflow-runtime-tests --test-threads=1" | tee "$root/build/native-test-results/rust-tests.txt"
# A successful shell or an empty/ignored Rust suite is not a test pass.
grep -Eq 'test result: ok\. [1-9][0-9]* passed; 0 failed; 0 ignored;' "$root/build/native-test-results/rust-tests.txt"
