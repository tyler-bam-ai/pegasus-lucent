#!/bin/sh
set -eu

PROJECT_DIR=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
SDK_DIR=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/Users/tyleryoung/Code/cemu/Cemu-0.5/android-sdk}}
NDK_VERSION=${NDK_VERSION:-27.0.12077973}
NDK_DIR=${ANDROID_NDK_ROOT:-$SDK_DIR/ndk/$NDK_VERSION}
API=${LUCENT_NATIVE_API:-23}
ABI=${LUCENT_NATIVE_ABI:-arm64-v8a}
BUILD_DIR="$PROJECT_DIR/build/native/$ABI"

case "$ABI" in
    arm64-v8a) TARGET=aarch64-linux-android ;;
    x86_64) TARGET=x86_64-linux-android ;;
    *) printf 'Unsupported Lucent native ABI: %s\n' "$ABI" >&2; exit 1 ;;
esac

case "$(uname -s)" in
    Darwin) HOST=darwin-x86_64 ;;
    Linux) HOST=linux-x86_64 ;;
    *) printf 'Unsupported native build host\n' >&2; exit 1 ;;
esac

TOOLCHAIN="$NDK_DIR/toolchains/llvm/prebuilt/$HOST"
CC="$TOOLCHAIN/bin/$TARGET$API-clang"
VULKAN_API=${LUCENT_VULKAN_API:-24}
VULKAN_CC="$TOOLCHAIN/bin/$TARGET$VULKAN_API-clang"
PAGE_SIZE_LDFLAGS="-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384"
if [ ! -x "$CC" ]; then
    # Modern NDKs on Apple Silicon still commonly name the prebuilt darwin-x86_64.
    printf 'Android NDK compiler not found: %s\n' "$CC" >&2
    exit 1
fi

mkdir -p "$BUILD_DIR"
"$CC" -std=c11 -O2 -fPIC -fvisibility=hidden -Wall -Wextra -Werror \
    -I"$PROJECT_DIR/native/include" \
    -shared "$PROJECT_DIR/native/lucent_libretro_host.c" \
    "$PROJECT_DIR/native/lucent_android_gles_backend.c" \
    "$PROJECT_DIR/native/lucent_libretro_jni.c" \
    -Wl,--no-undefined -Wl,-z,relro,-z,now $PAGE_SIZE_LDFLAGS \
    -ldl -landroid -llog -lEGL -lGLESv3 \
    -o "$BUILD_DIR/liblucent_libretro_host.so"
"$VULKAN_CC" -std=c11 -O2 -fPIC -fvisibility=hidden -Wall -Wextra -Werror \
    -I"$PROJECT_DIR/native/include" \
    -shared "$PROJECT_DIR/native/lucent_libretro_host.c" \
    "$PROJECT_DIR/native/lucent_android_vulkan_backend.c" \
    "$PROJECT_DIR/native/lucent_libretro_vulkan_jni.c" \
    -Wl,--no-undefined -Wl,-z,relro,-z,now $PAGE_SIZE_LDFLAGS \
    -ldl -landroid -llog -lvulkan \
    -o "$BUILD_DIR/liblucent_vulkan_host.so"
printf '%s\n' "$BUILD_DIR/liblucent_libretro_host.so"
printf '%s\n' "$BUILD_DIR/liblucent_vulkan_host.so"
