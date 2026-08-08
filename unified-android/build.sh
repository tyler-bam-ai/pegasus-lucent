#!/bin/sh
set -eu

PROJECT_DIR=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
ROOT_DIR=$(CDPATH= cd -- "$PROJECT_DIR/.." && pwd)
SDK_DIR=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/Users/tyleryoung/Code/cemu/Cemu-0.5/android-sdk}}
BUILD_TOOLS_VERSION=${BUILD_TOOLS_VERSION:-36.0.0}
ANDROID_PLATFORM=${ANDROID_PLATFORM:-android-36}
BUILD_TOOLS="$SDK_DIR/build-tools/$BUILD_TOOLS_VERSION"
ANDROID_JAR="$SDK_DIR/platforms/$ANDROID_PLATFORM/android.jar"
JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}
APKTOOL=${APKTOOL:-/opt/homebrew/bin/apktool}
BUILD_DIR="$PROJECT_DIR/build"
BUILD_LOCK="$BUILD_DIR/.lucent-build-lock"
VERSION_NAME=3.2.15
VERSION_CODE=89
INCLUDE_EXPERIMENTAL_CORES=${LUCENT_INCLUDE_EXPERIMENTAL_CORES:-0}
AUTOSELECT_EXPERIMENTAL_CORES=${LUCENT_AUTOSELECT_EXPERIMENTAL_CORES:-0}
REUSE_QUALIFICATION_CORES=${LUCENT_REUSE_QUALIFICATION_CORES:-0}
INCLUDE_PHASE2_PPSSPP=${LUCENT_INCLUDE_PHASE2_PPSSPP:-0}
REUSE_PHASE2_PPSSPP=${LUCENT_REUSE_PHASE2_PPSSPP:-0}
DEPS_DIR="$BUILD_DIR/deps"
COMMONS_COMPRESS_JAR="$DEPS_DIR/commons-compress-1.21.jar"
XZ_JAR="$DEPS_DIR/xz-1.9.jar"
BASE_NAME=pegasus-fe_alpha16-105-g6b322063_android64.apk
BASE_URL="https://raw.githubusercontent.com/mmatyas/pegasus-deploy-staging/continuous-android64/$BASE_NAME"
BASE_SHA256=e595be198bfd21c1855eaf563d5af0deae9c9601e6efb195ed299f2065287c67
BASE_APK=${PEGASUS_BASE_APK:-$BUILD_DIR/$BASE_NAME}

export JAVA_HOME
PATH="$JAVA_HOME/bin:$PATH"
export PATH

# The APK assembly directory is intentionally shared so large verified inputs
# stay cached. Refuse concurrent writers instead of allowing two builds to
# delete or replace each other's decoded resources and dex output.
mkdir -p "$BUILD_DIR"
if ! mkdir "$BUILD_LOCK" 2>/dev/null; then
    printf 'Another Lucent Android build is using %s\n' "$BUILD_DIR" >&2
    exit 1
fi
printf '%s\n' "$$" > "$BUILD_LOCK/pid"
cleanup_build_lock() {
    rm -f "$BUILD_LOCK/pid"
    rmdir "$BUILD_LOCK" 2>/dev/null || true
}
trap cleanup_build_lock 0 1 2 3 15

if [ "$AUTOSELECT_EXPERIMENTAL_CORES" = 1 ] &&
        [ "$INCLUDE_EXPERIMENTAL_CORES" != 1 ]; then
    printf 'Experimental auto-selection requires LUCENT_INCLUDE_EXPERIMENTAL_CORES=1\n' >&2
    exit 1
fi

case "$INCLUDE_PHASE2_PPSSPP:$REUSE_PHASE2_PPSSPP" in
    0:0|0:1|1:0|1:1) ;;
    *) printf 'Phase 2 PPSSPP flags must be 0 or 1\n' >&2; exit 1 ;;
esac
if [ "$REUSE_PHASE2_PPSSPP" = 1 ] && [ "$INCLUDE_PHASE2_PPSSPP" != 1 ]; then
    printf 'Reusing Phase 2 PPSSPP requires LUCENT_INCLUDE_PHASE2_PPSSPP=1\n' >&2
    exit 1
fi

fetch_dependency() {
    dependency_url=$1
    dependency_path=$2
    expected_sha=$3
    mkdir -p "$(dirname "$dependency_path")"
    if [ ! -f "$dependency_path" ] ||
            [ "$(shasum -a 256 "$dependency_path" | awk '{print $1}')" != "$expected_sha" ]; then
        rm -f "$dependency_path.partial"
        curl -fL "$dependency_url" -o "$dependency_path.partial"
        actual_sha=$(shasum -a 256 "$dependency_path.partial" | awk '{print $1}')
        if [ "$actual_sha" != "$expected_sha" ]; then
            rm -f "$dependency_path.partial"
            printf 'Unexpected dependency checksum: %s\n' "$actual_sha" >&2
            exit 1
        fi
        mv "$dependency_path.partial" "$dependency_path"
    fi
}

fetch_dependency \
    "https://repo1.maven.org/maven2/org/apache/commons/commons-compress/1.21/commons-compress-1.21.jar" \
    "$COMMONS_COMPRESS_JAR" \
    "6aecfd5459728a595601cfa07258d131972ffc39b492eb48bdd596577a2f244a"
fetch_dependency \
    "https://repo1.maven.org/maven2/org/tukaani/xz/1.9/xz-1.9.jar" \
    "$XZ_JAR" \
    "211b306cfc44f8f96df3a0a3ddaf75ba8c5289eed77d60d72f889bb855f535e5"

rm -rf "$BUILD_DIR/work" "$BUILD_DIR/classes" "$BUILD_DIR/stub-classes" \
    "$BUILD_DIR/dex"
mkdir -p "$BUILD_DIR/work" "$BUILD_DIR/classes" "$BUILD_DIR/stub-classes" \
    "$BUILD_DIR/dex"

# Fail closed before any core or APK work starts. Runtime checks are a second
# boundary, not a substitute for validating the release registry.
python3 "$ROOT_DIR/tools/validate_engine_registry.py" \
    "$ROOT_DIR/engines/registry.json" >/dev/null
python3 "$ROOT_DIR/tools/validate_phase3_registry.py" \
    "$ROOT_DIR/engines/phase3-registry.json" >/dev/null
if [ "$INCLUDE_PHASE2_PPSSPP" = 1 ]; then
    # Phase 2 remains a qualification-only path. The policy validator insists
    # every release/device/renderer gate is still closed, while the optional
    # artifact pass proves the exact pinned compiler output is what we package.
    python3 "$ROOT_DIR/tools/validate_phase2_registry.py" \
        "$ROOT_DIR/engines/phase2-registry.json" --verify-artifacts >/dev/null
fi

# Build Lucent's independent libretro API host. This packages only the host;
# engine binaries remain excluded until their registry entry is approved.
"$PROJECT_DIR/native/build.sh" >/dev/null
if [ "$INCLUDE_EXPERIMENTAL_CORES" = 1 ]; then
    # Qualification-only APKs may opt into the pinned Phase 1A source builds. They
    # remain disabled in the default/release path and are not auto-selected.
    for qualification_core in mesen mesen-s sameboy mgba gearsystem swanstation melonds-ds fuse mame dosbox-pure prosystem beetle-pce-fast beetle-neopop beetle-cygne blastem mupen64plus-next; do
        if [ "$REUSE_QUALIFICATION_CORES" = 1 ] &&
                [ -f "$ROOT_DIR/engines/build/arm64-v8a/${qualification_core}_libretro.so" ]; then
            continue
        fi
        "$ROOT_DIR/engines/build_core.sh" "$qualification_core"
    done
    python3 "$ROOT_DIR/tools/verify_phase1_reproducibility.py" \
        --artifact-dir "$ROOT_DIR/engines/build/arm64-v8a" >/dev/null
fi
if [ "$INCLUDE_PHASE2_PPSSPP" = 1 ]; then
    for phase2_core in applewin puae beetle-saturn dolphin ppsspp play armsx2 flycast azahar virtualjaguar; do
        if [ "$REUSE_PHASE2_PPSSPP" != 1 ] ||
                [ ! -f "$ROOT_DIR/engines/build/arm64-v8a/${phase2_core}_libretro.so" ]; then
            "$ROOT_DIR/engines/build_core.sh" "$phase2_core"
        fi
    done
    if [ "$REUSE_PHASE2_PPSSPP" != 1 ] ||
            [ ! -f "$ROOT_DIR/engines/build/arm64-v8a/scummvm_libretro.so" ]; then
        "$ROOT_DIR/engines/build_scummvm_core.sh"
    else
        # Reuse still executes the exact binary/object/license compliance gate.
        python3 "$ROOT_DIR/tools/generate_scummvm_compliance_bundle.py" \
            --source "$ROOT_DIR/engines/build/scummvm-arm64-work/source" \
            --objects "$ROOT_DIR/engines/build/scummvm-arm64-work/obj/local/arm64-v8a/objs/retro" \
            --artifact "$ROOT_DIR/engines/build/arm64-v8a/scummvm_libretro.so" \
            --audit "$ROOT_DIR/engines/scummvm-dependency-audit.json" \
            --repository "$ROOT_DIR" \
            --output "$ROOT_DIR/engines/build/arm64-v8a/scummvm-compliance"
    fi
fi

if [ ! -f "$BASE_APK" ]; then
    mkdir -p "$(dirname "$BASE_APK")"
    curl -fL "$BASE_URL" -o "$BASE_APK.partial"
    mv "$BASE_APK.partial" "$BASE_APK"
fi
ACTUAL_BASE_SHA=$(shasum -a 256 "$BASE_APK" | awk '{print $1}')
if [ "$ACTUAL_BASE_SHA" != "$BASE_SHA256" ]; then
    printf 'Unexpected Pegasus base checksum: %s\n' "$ACTUAL_BASE_SHA" >&2
    exit 1
fi

# Build the exact theme delivered by the unified package.
THEME_ARCHIVE="$ROOT_DIR/android-companion/assets/pegasus-lucent-theme.zip"
rm -f "$THEME_ARCHIVE.partial.zip"
(cd "$ROOT_DIR/theme" && /usr/bin/zip -q -r "$THEME_ARCHIVE.partial.zip" .)
mv "$THEME_ARCHIVE.partial.zip" "$THEME_ARCHIVE"

DECODED="$BUILD_DIR/work/apk"
"$APKTOOL" d -f "$BASE_APK" -o "$DECODED" >/dev/null

# Rebrand the compiled Qt startup wordmark without shifting ELF resource
# offsets. Attribution remains visible in the splash and legal/About pages.
python3 "$PROJECT_DIR/tools/patch_pegasus_splash.py" \
    "$DECODED/lib/arm64-v8a/libpegasus-fe_arm64-v8a.so"
python3 "$PROJECT_DIR/tools/patch_lucent_branding.py" \
    "$DECODED/lib/arm64-v8a/libpegasus-fe_arm64-v8a.so"
# Lucent's internal engines live in the inherited MainActivity. Preserve the
# already-rendered QML scene and its exact navigation selection instead of
# running Pegasus's external-process teardown/rebuild lifecycle.
python3 "$PROJECT_DIR/tools/patch_pegasus_in_process_launch.py" \
    "$DECODED/lib/arm64-v8a/libpegasus-fe_arm64-v8a.so"

# Keep Pegasus's JNI class names, but make Lucent the Android package and the
# only launcher. The package intentionally matches the existing companion so
# this unified build installs in place without deleting its settings.
perl -0pi -e 's/package="org\.pegasus_frontend\.android"/package="com.thorium.preview"/g;
    s/android:name="org\.qtproject\.qt5\.android\.bindings\.QtApplication"/android:name="com.thorium.preview.LucentApplication"/g;
    s/android:label="Pegasus"/android:label="Lucent"/g;
    s/org\.pegasus_frontend\.android\.files/com.thorium.preview.files/g' \
    "$DECODED/AndroidManifest.xml"
perl -0pi -e 's#android:icon="[^"]+"#android:icon="\@drawable/lucent_icon"#' \
    "$DECODED/AndroidManifest.xml"
# Lucent never enumerates or terminates standalone emulator applications.  The
# pinned frontend requested broad package visibility and the old companion
# requested process-kill authority; neither belongs in the one-app product.
perl -0pi -e 's#<uses-permission android:name="android\.permission\.QUERY_ALL_PACKAGES"/>##g;
    s#<uses-permission android:name="android\.permission\.KILL_BACKGROUND_PROCESSES"/>##g' \
    "$DECODED/AndroidManifest.xml"
perl -0pi -e 's/android:launchMode="singleTop" android:name="org\.pegasus_frontend\.android\.MainActivity"/android:launchMode="singleTask" android:name="org.pegasus_frontend.android.MainActivity"/' \
    "$DECODED/AndroidManifest.xml"
perl -0pi -e "s/versionCode: .*/versionCode: $VERSION_CODE/; s/versionName: .*/versionName: $VERSION_NAME/" \
    "$DECODED/apktool.yml"
perl -0pi -e 's/org\.pegasus_frontend\.android\.files/com.thorium.preview.files/g;
    s/"org\.pegasus_frontend\.android"/"com.thorium.preview"/g' \
    "$DECODED/smali/org/pegasus_frontend/android/MainActivity.smali" \
    "$DECODED/smali/org/pegasus_frontend/android/BuildConfig.smali"
python3 "$PROJECT_DIR/tools/patch_main_activity_right_stick.py" \
    "$DECODED/smali/org/pegasus_frontend/android/MainActivity.smali"

MANIFEST_COMPONENTS="$BUILD_DIR/work/manifest-components.xml"
printf '%s\n' \
'        <activity android:name="com.thorium.preview.PreviewActivity" android:configChanges="keyboard|keyboardHidden|orientation|screenLayout|screenSize|smallestScreenSize|uiMode" android:excludeFromRecents="true" android:launchMode="singleTop" android:resizeableActivity="true" android:screenOrientation="landscape" android:taskAffinity="com.thorium.preview.preview" android:exported="false"/>' \
'        <activity android:name="com.thorium.preview.BrowserActivity" android:configChanges="keyboard|keyboardHidden|orientation|screenLayout|screenSize|smallestScreenSize|uiMode" android:exported="false"/>' \
'        <service android:name="com.thorium.preview.PreviewService" android:exported="false"/>' \
'        <provider android:name="com.thorium.preview.UpdateFileProvider" android:authorities="com.thorium.preview.updates" android:exported="false" android:grantUriPermissions="true"/>' \
'        <receiver android:name="com.thorium.preview.BootReceiver" android:enabled="true" android:exported="true"><intent-filter><action android:name="android.intent.action.BOOT_COMPLETED"/><action android:name="android.intent.action.MY_PACKAGE_REPLACED"/></intent-filter></receiver>' \
    > "$MANIFEST_COMPONENTS"
# Qualification launches target this same singleTask MainActivity explicitly;
# no test-only Activity trampoline is packaged.
COMPONENTS=$(sed 's/[&/]/\\&/g' "$MANIFEST_COMPONENTS" | tr '\n' ' ')
perl -0pi -e "s#</application>#$COMPONENTS</application>#" "$DECODED/AndroidManifest.xml"
perl -0pi -e 's#<application#<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"/><uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"/><uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/><uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED"/><uses-permission android:name="android.permission.FOREGROUND_SERVICE"/><uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES"/><uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW"/><application#' \
    "$DECODED/AndroidManifest.xml"
perl -0pi -e 's#<application#<application android:largeHeap="true"#' \
    "$DECODED/AndroidManifest.xml"

mkdir -p "$DECODED/res/raw" "$DECODED/res/drawable" "$DECODED/assets"
cp "$ROOT_DIR/android-companion/res/raw/"* "$DECODED/res/raw/"
cp "$PROJECT_DIR/res/drawable/lucent_icon.png" "$DECODED/res/drawable/"
cp "$THEME_ARCHIVE" "$ROOT_DIR/android-companion/assets/pegasus-lucent-version.txt" \
    "$DECODED/assets/"
cp "$ROOT_DIR/LICENSE" "$DECODED/assets/LICENSE"
cp "$ROOT_DIR/LICENSING.md" "$DECODED/assets/LICENSING.md"
cp "$ROOT_DIR/SOURCE_OFFER.md" "$DECODED/assets/SOURCE_OFFER.md"
cp "$ROOT_DIR/THIRD_PARTY_NOTICES.md" "$DECODED/assets/THIRD_PARTY_NOTICES.md"
cp "$ROOT_DIR/theme/LICENSE" "$DECODED/assets/THEME_LICENSE"
cp "$ROOT_DIR/engines/registry.json" "$DECODED/assets/engine-registry.json"
cp "$ROOT_DIR/engines/registry.schema.json" "$DECODED/assets/engine-registry.schema.json"
cp "$ROOT_DIR/engines/phase3-registry.json" "$DECODED/assets/phase3-engine-registry.json"
cp "$ROOT_DIR/engines/phase3-registry.schema.json" \
    "$DECODED/assets/phase3-engine-registry.schema.json"
mkdir -p "$DECODED/lib/arm64-v8a"
cp "$BUILD_DIR/native/arm64-v8a/liblucent_libretro_host.so" \
    "$DECODED/lib/arm64-v8a/liblucent_libretro_host.so"
cp "$BUILD_DIR/native/arm64-v8a/liblucent_vulkan_host.so" \
    "$DECODED/lib/arm64-v8a/liblucent_vulkan_host.so"
if [ "$INCLUDE_EXPERIMENTAL_CORES" = 1 ]; then
    for qualification_core in mesen mesen-s sameboy mgba gearsystem swanstation melonds-ds fuse mame dosbox-pure prosystem beetle-pce-fast beetle-neopop beetle-cygne blastem mupen64plus-next; do
        normalized_core=$(printf '%s' "$qualification_core" | tr '-' '_')
        cp "$ROOT_DIR/engines/build/arm64-v8a/${qualification_core}_libretro.so" \
            "$DECODED/lib/arm64-v8a/liblucent_core_${normalized_core}.so"
    done
    cp "$ROOT_DIR/engines/qualification-opt-in.json" \
        "$DECODED/assets/engine-qualification-opt-in.json"
    if [ "$AUTOSELECT_EXPERIMENTAL_CORES" = 1 ]; then
        perl -0pi -e 's/"autoSelect": false/"autoSelect": true/' \
            "$DECODED/assets/engine-qualification-opt-in.json"
    fi
    # Generate compliance material from the exact staged binaries. Hand-edited
    # notice lists are intentionally not trusted for qualification packages.
    python3 "$PROJECT_DIR/tools/generate_phase1_compliance_bundle.py" \
        --registry "$ROOT_DIR/engines/registry.json" \
        --opt-in "$ROOT_DIR/engines/qualification-opt-in.json" \
        --library-dir "$DECODED/lib/arm64-v8a" \
        --manifest "$DECODED/assets/phase1-engine-artifacts.json" \
        --sbom "$DECODED/assets/phase1-sbom.spdx.json" \
        --notice "$DECODED/assets/PHASE1-CORE-NOTICES.txt"
    mkdir -p "$DECODED/assets/core-licenses"
    for license_file in "$ROOT_DIR/engines/build/arm64-v8a/"*-LICENSE.txt; do
        [ -f "$license_file" ] || continue
        cp "$license_file" "$DECODED/assets/core-licenses/"
    done
fi
if [ "$INCLUDE_PHASE2_PPSSPP" = 1 ]; then
    # Keep Phase 2 artifacts visibly separate from Phase 1. No production
    # auto-selection consumes them; the qualification catalog requires the
    # explicit package flag plus all signed asset and artifact identities.
    for phase2_core in applewin puae beetle-saturn dolphin ppsspp play armsx2 flycast azahar virtualjaguar scummvm; do
        normalized_core=$(printf '%s' "$phase2_core" | tr '-' '_')
        cp "$ROOT_DIR/engines/build/arm64-v8a/${phase2_core}_libretro.so" \
            "$DECODED/lib/arm64-v8a/liblucent_core_${normalized_core}.so"
    done
    cp "$ROOT_DIR/engines/phase2-registry.json" \
        "$DECODED/assets/phase2-engine-registry.json"
    cp "$ROOT_DIR/engines/phase2-registry.schema.json" \
        "$DECODED/assets/phase2-engine-registry.schema.json"
    cp "$ROOT_DIR/engines/phase2-qualification-opt-in.json" \
        "$DECODED/assets/phase2-qualification-opt-in.json"
    cp "$ROOT_DIR/engines/applewin-source-lock.json" \
        "$DECODED/assets/phase2-applewin-source-lock.json"
    cp "$ROOT_DIR/engines/applewin-firmware-policy.json" \
        "$DECODED/assets/phase2-applewin-firmware-policy.json"
    cp "$ROOT_DIR/engines/play-source-lock.json" \
        "$DECODED/assets/phase2-play-source-lock.json"
    cp "$ROOT_DIR/engines/armsx2-source-lock.json" \
        "$DECODED/assets/phase2-armsx2-source-lock.json"
    cp "$ROOT_DIR/engines/flycast-source-lock.json" \
        "$DECODED/assets/phase2-flycast-source-lock.json"
    cp "$ROOT_DIR/engines/azahar-source-lock.json" \
        "$DECODED/assets/phase2-azahar-source-lock.json"
    cp "$ROOT_DIR/engines/dolphin-source-lock.json" \
        "$DECODED/assets/phase2-dolphin-source-lock.json"
    cp "$ROOT_DIR/engines/puae-source-lock.json" \
        "$DECODED/assets/phase2-puae-source-lock.json"
    cp "$ROOT_DIR/engines/puae-firmware-policy.json" \
        "$DECODED/assets/phase2-puae-firmware-policy.json"
    cp "$ROOT_DIR/engines/puae-dependency-audit.json" \
        "$DECODED/assets/phase2-puae-dependency-audit.json"
    cp "$ROOT_DIR/engines/puae-test-content-lock.json" \
        "$DECODED/assets/phase2-puae-test-content-lock.json"
    cp "$ROOT_DIR/engines/PUAE-CORE-NOTICES.txt" \
        "$DECODED/assets/PUAE-CORE-NOTICES.txt"
    cp "$ROOT_DIR/engines/scummvm-source-lock.json" \
        "$DECODED/assets/phase2-scummvm-source-lock.json"
    cp "$ROOT_DIR/engines/scummvm-dependency-audit.json" \
        "$DECODED/assets/phase2-scummvm-dependency-audit.json"
    cp "$ROOT_DIR/engines/scummvm-test-content-lock.json" \
        "$DECODED/assets/phase2-scummvm-test-content-lock.json"
    cp "$ROOT_DIR/engines/build/arm64-v8a/ppsspp-LICENSE.txt" \
        "$DECODED/assets/PPSSPP-LICENSE.txt"
    cp "$ROOT_DIR/engines/build/arm64-v8a/play-LICENSE.txt" \
        "$DECODED/assets/PLAY-LICENSE.txt"
    cp "$ROOT_DIR/engines/build/arm64-v8a/armsx2-LICENSE.txt" \
        "$DECODED/assets/ARMSX2-LICENSE.txt"
    cp "$ROOT_DIR/engines/build/arm64-v8a/flycast-LICENSE.txt" \
        "$DECODED/assets/FLYCAST-LICENSE.txt"
    cp "$ROOT_DIR/engines/build/arm64-v8a/azahar-LICENSE.txt" \
        "$DECODED/assets/AZAHAR-LICENSE.txt"
    cp "$ROOT_DIR/engines/build/arm64-v8a/virtualjaguar-LICENSE.txt" \
        "$DECODED/assets/VIRTUALJAGUAR-LICENSE.txt"
    cp "$ROOT_DIR/engines/build/arm64-v8a/puae-LICENSE.txt" \
        "$DECODED/assets/PUAE-LICENSE.txt"
    cp "$ROOT_DIR/engines/build/arm64-v8a/beetle-saturn-LICENSE.txt" \
        "$DECODED/assets/BEETLE-SATURN-LICENSE.txt"
    cp "$ROOT_DIR/engines/build/arm64-v8a/dolphin-LICENSE.txt" \
        "$DECODED/assets/DOLPHIN-LICENSE.txt"
    cp "$ROOT_DIR/engines/build/arm64-v8a/applewin-LICENSE.txt" \
        "$DECODED/assets/APPLEWIN-LICENSE.txt"
    cp -R "$ROOT_DIR/engines/build/arm64-v8a/scummvm-compliance" \
        "$DECODED/assets/scummvm-compliance"
    mkdir -p "$DECODED/assets/phase2-system/ppsspp"
    cp -R "$ROOT_DIR/engines/build/arm64-v8a/ppsspp-system/PPSSPP" \
        "$DECODED/assets/phase2-system/ppsspp/"
    mkdir -p "$DECODED/assets/phase2-system/armsx2"
    cp -R "$ROOT_DIR/engines/build/arm64-v8a/armsx2-system/pcsx2" \
        "$DECODED/assets/phase2-system/armsx2/"
    mkdir -p "$DECODED/assets/phase2-system/dolphin"
    cp -R "$ROOT_DIR/engines/build/arm64-v8a/dolphin-system/dolphin-emu" \
        "$DECODED/assets/phase2-system/dolphin/"
    mkdir -p "$DECODED/assets/phase2-system/scummvm"
    cp -R "$ROOT_DIR/engines/build/arm64-v8a/scummvm-system/scummvm" \
        "$DECODED/assets/phase2-system/scummvm/"
    python3 "$PROJECT_DIR/tools/generate_engine_artifact_manifest.py" \
        --registry "$ROOT_DIR/engines/phase2-registry.json" \
        --library-dir "$DECODED/lib/arm64-v8a" \
        --output "$DECODED/assets/phase2-engine-artifacts.json"
    python3 "$PROJECT_DIR/tools/generate_phase2_sbom.py" \
        --registry "$ROOT_DIR/engines/phase2-registry.json" \
        --dependency-lock "applewin=$ROOT_DIR/engines/applewin-source-lock.json" \
        --dependency-lock "puae=$ROOT_DIR/engines/puae-source-lock.json" \
        --dependency-lock "ppsspp=$ROOT_DIR/engines/ppsspp-source-lock.json" \
        --dependency-lock "play=$ROOT_DIR/engines/play-source-lock.json" \
        --dependency-lock "armsx2=$ROOT_DIR/engines/armsx2-source-lock.json" \
        --dependency-lock "flycast=$ROOT_DIR/engines/flycast-source-lock.json" \
        --dependency-lock "azahar=$ROOT_DIR/engines/azahar-source-lock.json" \
        --dependency-lock "dolphin=$ROOT_DIR/engines/dolphin-source-lock.json" \
        --dependency-lock "scummvm=$ROOT_DIR/engines/scummvm-source-lock.json" \
        --artifacts "$DECODED/assets/phase2-engine-artifacts.json" \
        --output "$DECODED/assets/phase2-sbom.spdx.json"
fi
python3 "$PROJECT_DIR/tools/generate_engine_artifact_manifest.py" \
    --registry "$ROOT_DIR/engines/registry.json" \
    --library-dir "$DECODED/lib/arm64-v8a" \
    --output "$DECODED/assets/engine-artifacts.json"

# Compile all Java services together. The QtApplication stub is compile-only;
# the real superclass remains in Pegasus's primary classes.dex.
# The old standalone-emulator bridges and superseded game Activities remain in
# source history for migration/reference, but they are deliberately absent
# from Lucent's dex. Every emulator session is hosted by MainActivity through
# InWindowGameHost.
SOURCES=$(find "$ROOT_DIR/android-companion/src" "$ROOT_DIR/android-launch-bridge/src" \
    "$PROJECT_DIR/src" "$PROJECT_DIR/stubs" -name '*.java' \
    ! -path '*/com/thorium/preview/RomLaunchActivity.java' \
    ! -path '*/com/thorium/preview/RomFileProvider.java' \
    ! -path '*/com/thorium/preview/MainActivity.java' \
    ! -path '*/com/thorium/preview/EmulatorCatalog.java' \
    ! -path '*/com/thorium/launchbridge/LaunchActivity.java' \
    ! -path '*/com/thorium/launchbridge/RomFileProvider.java' \
    ! -path '*/com/thorium/launchbridge/StopButtonService.java' \
    ! -path '*/com/thorium/preview/game/InternalGameLaunchActivity.java' \
    ! -path '*/com/thorium/preview/game/LucentGameActivity.java' \
    ! -path '*/com/thorium/preview/game/QualificationLaunchActivity.java' \
    ! -path '*/com/thorium/preview/game/SessionReturnRouter.java' -print)
"$JAVA_HOME/bin/javac" -source 8 -target 8 -encoding UTF-8 \
    -classpath "$ANDROID_JAR:$COMMONS_COMPRESS_JAR:$XZ_JAR" \
    -d "$BUILD_DIR/classes" $SOURCES
mkdir -p "$BUILD_DIR/stub-classes/org/qtproject/qt5/android/bindings"
mv "$BUILD_DIR/classes/org/qtproject/qt5/android/bindings/QtApplication.class" \
    "$BUILD_DIR/stub-classes/org/qtproject/qt5/android/bindings/"

CLASS_INPUTS=$(find "$BUILD_DIR/classes" -name '*.class' -print)
"$BUILD_TOOLS/d8" --lib "$ANDROID_JAR" --classpath "$BUILD_DIR/stub-classes" \
    --output "$BUILD_DIR/dex" $CLASS_INPUTS "$COMMONS_COMPRESS_JAR" "$XZ_JAR"

UNSIGNED="$BUILD_DIR/lucent-unified-unsigned.apk"
"$APKTOOL" b "$DECODED" -o "$UNSIGNED" >/dev/null
cp "$BUILD_DIR/dex/classes.dex" "$BUILD_DIR/work/classes2.dex"
(cd "$BUILD_DIR/work" && "$BUILD_TOOLS/aapt" add "$UNSIGNED" classes2.dex >/dev/null)

ALIGNED="$BUILD_DIR/lucent-unified-aligned.apk"
if [ "$INCLUDE_PHASE2_PPSSPP" = 1 ]; then
    OUTPUT="$BUILD_DIR/lucent-$VERSION_NAME-phase2-qualification.apk"
else
    OUTPUT="$BUILD_DIR/lucent-$VERSION_NAME.apk"
fi
"$BUILD_TOOLS/zipalign" -f 4 "$UNSIGNED" "$ALIGNED"
KEYSTORE=${LUCENT_KEYSTORE:-$ROOT_DIR/android-companion/debug.keystore}
STORE_PASS=${LUCENT_STORE_PASS:-android}
KEY_PASS=${LUCENT_KEY_PASS:-$STORE_PASS}
KEY_ALIAS=${LUCENT_KEY_ALIAS:-androiddebugkey}
"$BUILD_TOOLS/apksigner" sign --ks "$KEYSTORE" --ks-pass "pass:$STORE_PASS" \
    --key-pass "pass:$KEY_PASS" --ks-key-alias "$KEY_ALIAS" \
    --out "$OUTPUT" "$ALIGNED"
"$BUILD_TOOLS/apksigner" verify --verbose "$OUTPUT" >/dev/null
python3 "$PROJECT_DIR/tools/verify_one_app_apk.py" \
    --aapt "$BUILD_TOOLS/aapt" "$OUTPUT" >/dev/null
if [ "$INCLUDE_EXPERIMENTAL_CORES" = 1 ]; then
    python3 "$PROJECT_DIR/tools/verify_phase1_apk.py" "$OUTPUT" >/dev/null
fi
if [ "$INCLUDE_PHASE2_PPSSPP" = 1 ]; then
    python3 "$PROJECT_DIR/tools/verify_phase2_apk.py" "$OUTPUT" >/dev/null
fi
# A mutable convenience filename is useful for local installs, but QA evidence
# must never point at a path that a later build can overwrite.  Emit a
# content-addressed copy as the authoritative artifact.
OUTPUT_SHA=$(shasum -a 256 "$OUTPUT" | awk '{print $1}')
case "$OUTPUT" in
    *-phase2-qualification.apk)
        IMMUTABLE_OUTPUT="$BUILD_DIR/lucent-$VERSION_NAME-phase2-qualification-$OUTPUT_SHA.apk" ;;
    *)  IMMUTABLE_OUTPUT="$BUILD_DIR/lucent-$VERSION_NAME-$OUTPUT_SHA.apk" ;;
esac
if [ -f "$IMMUTABLE_OUTPUT" ]; then
    IMMUTABLE_SHA=$(shasum -a 256 "$IMMUTABLE_OUTPUT" | awk '{print $1}')
    if [ "$IMMUTABLE_SHA" != "$OUTPUT_SHA" ]; then
        printf 'Immutable Lucent artifact collision: %s\n' "$IMMUTABLE_OUTPUT" >&2
        exit 1
    fi
else
    cp "$OUTPUT" "$IMMUTABLE_OUTPUT"
fi
printf '%s\n' "$IMMUTABLE_OUTPUT"
