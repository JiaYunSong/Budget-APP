#!/usr/bin/env bash
set -euo pipefail
REPO_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
: "${DEPOSIT_KEYSTORE:?Set DEPOSIT_KEYSTORE to your private release keystore}"
: "${DEPOSIT_STORE_PASSWORD:?Set DEPOSIT_STORE_PASSWORD securely}"
: "${DEPOSIT_KEY_PASSWORD:?Set DEPOSIT_KEY_PASSWORD securely}"
cd "$REPO_ROOT"
./gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleRelease --no-daemon
mkdir -p releases
cp app/build/outputs/apk/release/app-release.apk releases/存期.apk
"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --verbose releases/存期.apk
sha256sum releases/存期.apk > releases/SHA256SUMS
