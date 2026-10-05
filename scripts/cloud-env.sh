#!/usr/bin/env bash
# Source this in each shell. Each cloud task already has an isolated checkout.
export CUNQI_TOOLCHAINS="${CUNQI_TOOLCHAINS:-/workspace/toolchains}"
export JAVA_HOME="$CUNQI_TOOLCHAINS/jdk-17.0.16+8"
export ANDROID_HOME="$CUNQI_TOOLCHAINS/android-sdk"
export ANDROID_USER_HOME="${CUNQI_ANDROID_USER_HOME:-/workspace/.android}"
export ANDROID_EMULATOR_HOME="$ANDROID_USER_HOME"
export ANDROID_AVD_HOME="$ANDROID_USER_HOME/avd"
export GRADLE_USER_HOME="${CUNQI_GRADLE_USER_HOME:-/workspace/.gradle}"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/23.0/bin:$ANDROID_HOME/platform-tools:$PATH"
export JAVA_TOOL_OPTIONS="-Duser.home=$(dirname "$ANDROID_USER_HOME") -Djavax.net.ssl.trustStore=$CUNQI_TOOLCHAINS/cacerts"
