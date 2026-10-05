#!/usr/bin/env bash
set -euo pipefail
REPO_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
python3 "$REPO_ROOT/scripts/cloud-setup.py"
source "$REPO_ROOT/scripts/cloud-env.sh"
android --no-metrics --sdk="$ANDROID_HOME" sdk install 'platforms;android-37.2' --no-downgrade
android --no-metrics --sdk="$ANDROID_HOME" sdk install 'build-tools;36.0.0' --no-downgrade
cd "$REPO_ROOT"
./gradlew :app:assembleDebug :app:testDebugUnitTest --no-daemon
