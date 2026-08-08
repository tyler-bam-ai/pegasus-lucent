#!/bin/sh
set -eu

PROJECT_DIR=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
SDK_DIR=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/Users/tyleryoung/Code/cemu/Cemu-0.5/android-sdk}}
NDK_VERSION=${NDK_VERSION:-27.0.12077973}
NDK_DIR=${ANDROID_NDK_ROOT:-$SDK_DIR/ndk/$NDK_VERSION}
JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}
ANDROID_JAR=${LUCENT_ANDROID_JAR:-$SDK_DIR/platforms/android-35/android.jar}
ABI=${LUCENT_NATIVE_ABI:-arm64-v8a}
CHECK_DIR=$(mktemp -d)
trap 'rm -rf "$CHECK_DIR"' EXIT INT TERM

case "$(uname -s)" in
    Darwin) HOST=darwin-x86_64 ;;
    Linux) HOST=linux-x86_64 ;;
    *) printf 'Unsupported JNI verification host\n' >&2; exit 1 ;;
esac

"$PROJECT_DIR/native/build.sh" >/dev/null
LIBRARY="$PROJECT_DIR/build/native/$ABI/liblucent_libretro_host.so"
READELF="$NDK_DIR/toolchains/llvm/prebuilt/$HOST/bin/llvm-readelf"
if [ ! -f "$ANDROID_JAR" ] || [ ! -x "$JAVA_HOME/bin/javac" ] ||
        [ ! -x "$READELF" ] || [ ! -f "$LIBRARY" ]; then
    printf 'Android SDK, NDK, Java, and built ARM library are required\n' >&2
    exit 1
fi

"$JAVA_HOME/bin/javac" -source 8 -target 8 -encoding UTF-8 \
    -classpath "$ANDROID_JAR" -h "$CHECK_DIR" -d "$CHECK_DIR" \
    "$PROJECT_DIR/src/com/thorium/preview/LibretroHost.java" \
    "$PROJECT_DIR/src/com/thorium/preview/ExperimentalGlesLibretroHost.java" \
    "$PROJECT_DIR/src/com/thorium/preview/ExperimentalVulkanLibretroHost.java" \
    "$PROJECT_DIR/src/com/thorium/preview/ExperimentalGlesRenderLoop.java" \
    >/dev/null 2>&1

SYMBOLS=$($READELF -Ws "$LIBRARY")
for method in CreateGles LoadGameGles AttachSurfaceGles RecreateSurfaceGles \
        RunAndPresentGles DetachSurfaceGles SetPausedGles HardwareInfoGles \
        SetJoypadButtonGles SetAnalogAxisGles DrainAudioGles AvInfoGles \
        SerializeGles UnserializeGles ReadSaveRamGles WriteSaveRamGles \
        DestroyGles; do
    symbol="Java_com_thorium_preview_ExperimentalGlesLibretroHost_native$method"
    if ! printf '%s\n' "$SYMBOLS" | grep -q " $symbol$"; then
        printf 'Missing experimental GLES JNI export: %s\n' "$symbol" >&2
        exit 1
    fi
done

HEADER="$CHECK_DIR/com_thorium_preview_ExperimentalGlesLibretroHost.h"
for signature in 'nativeAttachSurfaceGles' 'Landroid/view/Surface;' \
        'nativeRunAndPresentGles' 'nativeRecreateSurfaceGles' \
        'nativeDrainAudioGles' 'nativeAvInfoGles' 'nativeStateReadyGles' 'nativeSerializeGles' \
        'nativeUnserializeGles' 'nativeReadSaveRamGles' \
        'nativeWriteSaveRamGles'; do
    if ! grep -q "$signature" "$HEADER"; then
        printf 'Generated Java/JNI contract is missing: %s\n' "$signature" >&2
        exit 1
    fi
done
"$PROJECT_DIR/native/run_experimental_gles_java_test.sh"
printf 'experimental Android Surface/GLES JNI compile contract passed\n'
