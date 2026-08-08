#!/bin/sh
set -eu

NATIVE_DIR=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
TEST_DIR=$(mktemp -d)
trap 'rm -rf "$TEST_DIR"' EXIT INT TERM
CC=${CC:-cc}
CFLAGS=${CFLAGS:-}
LDFLAGS=${LDFLAGS:-}

"$CC" -std=c11 -O1 -fPIC -pthread -Wall -Wextra -Werror $CFLAGS -shared \
    "$NATIVE_DIR/tests/mock_core.c" $LDFLAGS -o "$TEST_DIR/mock_core.so"
cp "$TEST_DIR/mock_core.so" "$TEST_DIR/liblucent_core_puae.so"
"$CC" -std=c11 -O1 -fPIC -pthread -Wall -Wextra -Werror $CFLAGS -shared \
    "$NATIVE_DIR/tests/hw_mock_core.c" $LDFLAGS -o "$TEST_DIR/hw_gles_core.so"
"$CC" -std=c11 -O1 -fPIC -pthread -Wall -Wextra -Werror $CFLAGS \
    -DLUCENT_MOCK_HW_VULKAN=1 -shared \
    "$NATIVE_DIR/tests/hw_mock_core.c" $LDFLAGS -o "$TEST_DIR/hw_vulkan_core.so"
"$CC" -std=c11 -O1 -fPIC -pthread -Wall -Wextra -Werror $CFLAGS \
    -DLUCENT_MOCK_ANDROID_GLES=1 -shared \
    "$NATIVE_DIR/tests/hw_mock_core.c" $LDFLAGS \
    -o "$TEST_DIR/android_gles_core.so"
"$CC" -std=c11 -O1 -fPIC -pthread -Wall -Wextra -Werror $CFLAGS \
    -DLUCENT_MOCK_MUPEN_GLES=1 -shared \
    "$NATIVE_DIR/tests/hw_mock_core.c" $LDFLAGS \
    -o "$TEST_DIR/mupen_gles_core.so"
"$CC" -std=c11 -O1 -pthread -Wall -Wextra -Werror $CFLAGS \
    "$NATIVE_DIR/lucent_libretro_host.c" "$NATIVE_DIR/tests/host_test.c" \
    -ldl $LDFLAGS -o "$TEST_DIR/host_test"
"$CC" -std=c11 -O1 -pthread -Wall -Wextra -Werror $CFLAGS \
    "$NATIVE_DIR/lucent_libretro_host.c" "$NATIVE_DIR/tests/hw_host_test.c" \
    -ldl $LDFLAGS -o "$TEST_DIR/hw_host_test"
"$CC" -std=c11 -O1 -pthread -Wall -Wextra -Werror $CFLAGS -D__ANDROID__ \
    -I"$NATIVE_DIR/tests/fake-android" \
    "$NATIVE_DIR/lucent_libretro_host.c" \
    "$NATIVE_DIR/lucent_android_gles_backend.c" \
    "$NATIVE_DIR/tests/fake_android_egl.c" \
    "$NATIVE_DIR/tests/android_gles_backend_test.c" \
    -ldl $LDFLAGS -o "$TEST_DIR/android_gles_backend_test"
# Phase 3 native-adapter host: the mock adapter is built as a .so the host
# dlopen's, plus an ABI-mismatched sibling for the fail-closed load gate.
"$CC" -std=c11 -O1 -fPIC -pthread -Wall -Wextra -Werror $CFLAGS -shared \
    "$NATIVE_DIR/tests/mock_native_adapter.c" $LDFLAGS \
    -o "$TEST_DIR/mock_native_adapter.so"
"$CC" -std=c11 -O1 -fPIC -pthread -Wall -Wextra -Werror $CFLAGS \
    -DMOCK_ABI_MISMATCH=1 -shared \
    "$NATIVE_DIR/tests/mock_native_adapter.c" $LDFLAGS \
    -o "$TEST_DIR/mock_native_adapter_mismatch.so"
"$CC" -std=c11 -O1 -pthread -Wall -Wextra -Werror $CFLAGS \
    "$NATIVE_DIR/lucent_native_adapter_host.c" \
    "$NATIVE_DIR/tests/native_adapter_host_test.c" \
    -ldl $LDFLAGS -o "$TEST_DIR/native_adapter_host_test"
mkdir -p "$TEST_DIR/system" "$TEST_DIR/save"
printf '\007' > "$TEST_DIR/game.mock"
"$TEST_DIR/host_test" "$TEST_DIR/mock_core.so" "$TEST_DIR" \
    "$TEST_DIR/system" "$TEST_DIR/save" "$TEST_DIR/game.mock"
"$TEST_DIR/host_test" "$TEST_DIR/liblucent_core_puae.so" "$TEST_DIR" \
    "$TEST_DIR/system" "$TEST_DIR/save" "$TEST_DIR/game.mock"
"$TEST_DIR/hw_host_test" "$TEST_DIR/hw_gles_core.so" \
    "$TEST_DIR/hw_vulkan_core.so" "$TEST_DIR" "$TEST_DIR/system" \
    "$TEST_DIR/save" "$TEST_DIR/game.mock"
"$TEST_DIR/android_gles_backend_test" "$TEST_DIR/android_gles_core.so" \
    "$TEST_DIR/mupen_gles_core.so" "$TEST_DIR" "$TEST_DIR/system" \
    "$TEST_DIR/save" "$TEST_DIR/game.mock"
"$TEST_DIR/native_adapter_host_test" "$TEST_DIR/mock_native_adapter.so" \
    "$TEST_DIR/mock_native_adapter_mismatch.so" "$TEST_DIR" \
    "$TEST_DIR/save" "$TEST_DIR/game.mock"

# The adapter loader must reject an otherwise-valid adapter outside its trusted
# root, exactly as the libretro core loader does below.
mkdir -p "$TEST_DIR/adapter-not-trusted"
if "$TEST_DIR/native_adapter_host_test" "$TEST_DIR/mock_native_adapter.so" \
        "$TEST_DIR/mock_native_adapter_mismatch.so" \
        "$TEST_DIR/adapter-not-trusted" "$TEST_DIR/save" \
        "$TEST_DIR/game.mock" >/dev/null 2>&1; then
    printf 'trusted native-adapter path rejection test failed\n' >&2
    exit 1
fi
printf 'trusted native-adapter path rejection passed\n'

# The loader must reject an otherwise-valid core outside its trusted root.
mkdir -p "$TEST_DIR/not-trusted"
if "$TEST_DIR/host_test" "$TEST_DIR/mock_core.so" "$TEST_DIR/not-trusted" \
        "$TEST_DIR/system" "$TEST_DIR/save" "$TEST_DIR/game.mock" >/dev/null 2>&1; then
    printf 'trusted path rejection test failed\n' >&2
    exit 1
fi
printf 'trusted core path rejection passed\n'

# Run the complete software and hardware mock suites again under memory and
# undefined-behavior instrumentation. Phase 2 negotiation is callback-heavy;
# the sanitizer pass catches lifetime and ABI mistakes that ordinary assertions
# cannot observe.
SANITIZER_FLAGS="-fsanitize=address,undefined -fno-omit-frame-pointer"
SANITIZED="$TEST_DIR/sanitized"
mkdir -p "$SANITIZED/system" "$SANITIZED/save"
printf '\007' > "$SANITIZED/game.mock"
"$CC" -std=c11 -O1 -g -fPIC -pthread -Wall -Wextra -Werror $CFLAGS \
    $SANITIZER_FLAGS -shared "$NATIVE_DIR/tests/mock_core.c" $LDFLAGS \
    -o "$SANITIZED/mock_core.so"
cp "$SANITIZED/mock_core.so" "$SANITIZED/liblucent_core_puae.so"
"$CC" -std=c11 -O1 -g -fPIC -pthread -Wall -Wextra -Werror $CFLAGS \
    $SANITIZER_FLAGS -shared "$NATIVE_DIR/tests/hw_mock_core.c" $LDFLAGS \
    -o "$SANITIZED/hw_gles_core.so"
"$CC" -std=c11 -O1 -g -fPIC -pthread -Wall -Wextra -Werror $CFLAGS \
    $SANITIZER_FLAGS -DLUCENT_MOCK_HW_VULKAN=1 -shared \
    "$NATIVE_DIR/tests/hw_mock_core.c" $LDFLAGS \
    -o "$SANITIZED/hw_vulkan_core.so"
"$CC" -std=c11 -O1 -g -fPIC -pthread -Wall -Wextra -Werror $CFLAGS \
    $SANITIZER_FLAGS -DLUCENT_MOCK_ANDROID_GLES=1 -shared \
    "$NATIVE_DIR/tests/hw_mock_core.c" $LDFLAGS \
    -o "$SANITIZED/android_gles_core.so"
"$CC" -std=c11 -O1 -g -fPIC -pthread -Wall -Wextra -Werror $CFLAGS \
    $SANITIZER_FLAGS -DLUCENT_MOCK_MUPEN_GLES=1 -shared \
    "$NATIVE_DIR/tests/hw_mock_core.c" $LDFLAGS \
    -o "$SANITIZED/mupen_gles_core.so"
"$CC" -std=c11 -O1 -g -pthread -Wall -Wextra -Werror $CFLAGS \
    $SANITIZER_FLAGS "$NATIVE_DIR/lucent_libretro_host.c" \
    "$NATIVE_DIR/tests/host_test.c" -ldl $LDFLAGS \
    -o "$SANITIZED/host_test"
"$CC" -std=c11 -O1 -g -pthread -Wall -Wextra -Werror $CFLAGS \
    $SANITIZER_FLAGS "$NATIVE_DIR/lucent_libretro_host.c" \
    "$NATIVE_DIR/tests/hw_host_test.c" -ldl $LDFLAGS \
    -o "$SANITIZED/hw_host_test"
"$CC" -std=c11 -O1 -g -pthread -Wall -Wextra -Werror $CFLAGS \
    $SANITIZER_FLAGS -D__ANDROID__ -I"$NATIVE_DIR/tests/fake-android" \
    "$NATIVE_DIR/lucent_libretro_host.c" \
    "$NATIVE_DIR/lucent_android_gles_backend.c" \
    "$NATIVE_DIR/tests/fake_android_egl.c" \
    "$NATIVE_DIR/tests/android_gles_backend_test.c" \
    -ldl $LDFLAGS -o "$SANITIZED/android_gles_backend_test"
"$CC" -std=c11 -O1 -g -fPIC -pthread -Wall -Wextra -Werror $CFLAGS \
    $SANITIZER_FLAGS -shared "$NATIVE_DIR/tests/mock_native_adapter.c" \
    $LDFLAGS -o "$SANITIZED/mock_native_adapter.so"
"$CC" -std=c11 -O1 -g -fPIC -pthread -Wall -Wextra -Werror $CFLAGS \
    $SANITIZER_FLAGS -DMOCK_ABI_MISMATCH=1 -shared \
    "$NATIVE_DIR/tests/mock_native_adapter.c" $LDFLAGS \
    -o "$SANITIZED/mock_native_adapter_mismatch.so"
"$CC" -std=c11 -O1 -g -pthread -Wall -Wextra -Werror $CFLAGS \
    $SANITIZER_FLAGS "$NATIVE_DIR/lucent_native_adapter_host.c" \
    "$NATIVE_DIR/tests/native_adapter_host_test.c" -ldl $LDFLAGS \
    -o "$SANITIZED/native_adapter_host_test"
# Apple's host ASan runtime does not support LeakSanitizer; address and
# undefined-behavior instrumentation still run with fail-fast semantics.
ASAN_OPTIONS=detect_leaks=0:halt_on_error=1 UBSAN_OPTIONS=halt_on_error=1 \
    "$SANITIZED/host_test" "$SANITIZED/mock_core.so" "$SANITIZED" \
    "$SANITIZED/system" "$SANITIZED/save" "$SANITIZED/game.mock"
ASAN_OPTIONS=detect_leaks=0:halt_on_error=1 UBSAN_OPTIONS=halt_on_error=1 \
    "$SANITIZED/host_test" "$SANITIZED/liblucent_core_puae.so" "$SANITIZED" \
    "$SANITIZED/system" "$SANITIZED/save" "$SANITIZED/game.mock"
ASAN_OPTIONS=detect_leaks=0:halt_on_error=1 UBSAN_OPTIONS=halt_on_error=1 \
    "$SANITIZED/hw_host_test" "$SANITIZED/hw_gles_core.so" \
    "$SANITIZED/hw_vulkan_core.so" "$SANITIZED" "$SANITIZED/system" \
    "$SANITIZED/save" "$SANITIZED/game.mock"
ASAN_OPTIONS=detect_leaks=0:halt_on_error=1 UBSAN_OPTIONS=halt_on_error=1 \
    "$SANITIZED/android_gles_backend_test" \
    "$SANITIZED/android_gles_core.so" "$SANITIZED/mupen_gles_core.so" \
    "$SANITIZED" "$SANITIZED/system" "$SANITIZED/save" \
    "$SANITIZED/game.mock"
ASAN_OPTIONS=detect_leaks=0:halt_on_error=1 UBSAN_OPTIONS=halt_on_error=1 \
    "$SANITIZED/native_adapter_host_test" \
    "$SANITIZED/mock_native_adapter.so" \
    "$SANITIZED/mock_native_adapter_mismatch.so" "$SANITIZED" \
    "$SANITIZED/save" "$SANITIZED/game.mock"
printf 'address/undefined sanitizer suites passed\n'
