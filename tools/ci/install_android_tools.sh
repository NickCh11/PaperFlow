#!/usr/bin/env bash
set -euo pipefail

packages=("$@")
if [ "${#packages[@]}" -eq 0 ]; then
  packages=('platforms;android-37.0' 'build-tools;36.0.0' 'ndk;27.2.12479018' 'cmake;3.22.1')
fi

# Google SDK downloads occasionally end with Premature EOF on hosted runners.
for attempt in 1 2 3; do
  if sdkmanager "${packages[@]}"; then
    exit 0
  fi
  sleep "$((attempt * 5))"
done
exit 1
