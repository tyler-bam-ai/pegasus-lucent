#!/bin/sh
set -eu

PROJECT_DIR=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
SDK_DIR=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/Users/tyleryoung/Code/cemu/Cemu-0.5/android-sdk}}
LINT=${LINT:-/Users/tyleryoung/Library/Android/sdk/cmdline-tools/latest/bin/lint}
JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}
export JAVA_HOME

"$LINT" --check NewApi --disable LintError --exitcode --compile-sdk-version 36 \
    --sdk-home "$SDK_DIR" --sources "$PROJECT_DIR/src" \
    "$PROJECT_DIR/api21-lint"
