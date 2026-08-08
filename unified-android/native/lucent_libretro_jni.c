#include "include/lucent_libretro_host.h"
#include "include/lucent_android_gles_backend.h"

#include <jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define ERROR_SIZE 512

static void throw_state(JNIEnv *env, const char *message) {
    jclass type = (*env)->FindClass(env, "java/lang/IllegalStateException");
    if (type) (*env)->ThrowNew(env, type, message && *message ? message : "native core host failed");
}

static lucent_retro_host *from_handle(jlong handle) {
    return (lucent_retro_host *)(intptr_t)handle;
}

typedef struct lucent_gles_jni_session {
    lucent_android_gles_backend *backend;
    lucent_retro_host *host;
} lucent_gles_jni_session;

static lucent_gles_jni_session *from_gles_handle(jlong handle) {
    return (lucent_gles_jni_session *)(intptr_t)handle;
}

JNIEXPORT jlong JNICALL
Java_com_thorium_preview_LibretroHost_nativeCreate(
        JNIEnv *env, jclass type, jstring core_path, jstring trusted_root,
        jstring system_directory, jstring save_directory) {
    (void)type;
    char error[ERROR_SIZE] = {0};
    const char *core = (*env)->GetStringUTFChars(env, core_path, NULL);
    const char *root = (*env)->GetStringUTFChars(env, trusted_root, NULL);
    const char *system = (*env)->GetStringUTFChars(env, system_directory, NULL);
    const char *save = (*env)->GetStringUTFChars(env, save_directory, NULL);
    lucent_retro_host *host = NULL;
    if (core && root && system && save)
        host = lucent_retro_create(core, root, system, save, error, sizeof(error));
    if (host) {
        JavaVM *vm = NULL;
        if ((*env)->GetJavaVM(env, &vm) != JNI_OK || !vm ||
                !lucent_retro_supply_android_java_vm(host, vm, error, sizeof(error))) {
            lucent_retro_destroy(host);
            host = NULL;
        }
    }
    if (core) (*env)->ReleaseStringUTFChars(env, core_path, core);
    if (root) (*env)->ReleaseStringUTFChars(env, trusted_root, root);
    if (system) (*env)->ReleaseStringUTFChars(env, system_directory, system);
    if (save) (*env)->ReleaseStringUTFChars(env, save_directory, save);
    if (!host && !(*env)->ExceptionCheck(env)) throw_state(env, error);
    return (jlong)(intptr_t)host;
}

JNIEXPORT void JNICALL
Java_com_thorium_preview_LibretroHost_nativeLoadGame(
        JNIEnv *env, jclass type, jlong handle, jstring game_path) {
    (void)type;
    char error[ERROR_SIZE] = {0};
    const char *path = (*env)->GetStringUTFChars(env, game_path, NULL);
    bool success = path && lucent_retro_load_game(from_handle(handle), path, error, sizeof(error));
    if (path) (*env)->ReleaseStringUTFChars(env, game_path, path);
    if (!success && !(*env)->ExceptionCheck(env)) throw_state(env, error);
}

JNIEXPORT void JNICALL
Java_com_thorium_preview_LibretroHost_nativeSetControllerPortDevice(
        JNIEnv *env, jclass type, jlong handle, jint port, jint device) {
    (void)type;
    char error[ERROR_SIZE] = {0};
    if (!lucent_retro_set_controller_port_device(from_handle(handle),
            (unsigned)port, (unsigned)device, error, sizeof(error)) &&
            !(*env)->ExceptionCheck(env))
        throw_state(env, error);
}

JNIEXPORT void JNICALL
Java_com_thorium_preview_LibretroHost_nativeReset(
        JNIEnv *env, jclass type, jlong handle) {
    (void)type;
    char error[ERROR_SIZE] = {0};
    if (!lucent_retro_reset(from_handle(handle), error, sizeof(error)) &&
            !(*env)->ExceptionCheck(env))
        throw_state(env, error);
}

JNIEXPORT void JNICALL
Java_com_thorium_preview_LibretroHost_nativeUnloadGame(
        JNIEnv *env, jclass type, jlong handle) {
    (void)type;
    char error[ERROR_SIZE] = {0};
    if (!lucent_retro_unload_game(from_handle(handle), error, sizeof(error)))
        throw_state(env, error);
}

JNIEXPORT void JNICALL
Java_com_thorium_preview_LibretroHost_nativeRunFrame(
        JNIEnv *env, jclass type, jlong handle) {
    (void)type;
    char error[ERROR_SIZE] = {0};
    if (!lucent_retro_run_frame(from_handle(handle), error, sizeof(error)))
        throw_state(env, error);
}

JNIEXPORT void JNICALL
Java_com_thorium_preview_LibretroHost_nativeSetPaused(
        JNIEnv *env, jclass type, jlong handle, jboolean paused) {
    (void)env;
    (void)type;
    lucent_retro_set_paused(from_handle(handle), paused == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_thorium_preview_LibretroHost_nativeSetJoypadButton(
        JNIEnv *env, jclass type, jlong handle, jint port, jint button, jboolean pressed) {
    (void)type;
    if (port < 0 || button < 0 || !lucent_retro_set_joypad_button(
            from_handle(handle), (unsigned)port, (unsigned)button, pressed == JNI_TRUE))
        throw_state(env, "invalid joypad port or button");
}

JNIEXPORT void JNICALL
Java_com_thorium_preview_LibretroHost_nativeSetAnalogAxis(
        JNIEnv *env, jclass type, jlong handle, jint port, jint index, jint id,
        jint value) {
    (void)type;
    if (port < 0 || index < 0 || id < 0 || value < INT16_MIN || value > INT16_MAX ||
            !lucent_retro_set_analog_axis(from_handle(handle), (unsigned)port,
                                          (unsigned)index, (unsigned)id,
                                          (int16_t)value))
        throw_state(env, "invalid analog port, index, id, or value");
}

JNIEXPORT void JNICALL
Java_com_thorium_preview_LibretroHost_nativeSetPointer(
        JNIEnv *env, jclass type, jlong handle, jint port, jint x, jint y,
        jboolean pressed) {
    (void)type;
    if (port < 0 || x < INT16_MIN || x > INT16_MAX || y < INT16_MIN ||
            y > INT16_MAX || !lucent_retro_set_pointer(from_handle(handle),
                    (unsigned)port, (int16_t)x, (int16_t)y,
                    pressed == JNI_TRUE))
        throw_state(env, "invalid pointer port or coordinate");
}

JNIEXPORT jintArray JNICALL
Java_com_thorium_preview_LibretroHost_nativeVideoInfo(
        JNIEnv *env, jclass type, jlong handle) {
    (void)type;
    lucent_retro_video_info info;
    if (!lucent_retro_latest_video_info(from_handle(handle), &info)) return NULL;
    if (info.pitch > INT32_MAX || info.byte_size > INT32_MAX || info.sequence > INT32_MAX)
        return NULL;
    jint values[6] = {(jint)info.width, (jint)info.height, (jint)info.pitch,
                     (jint)info.pixel_format, (jint)info.byte_size, (jint)info.sequence};
    jintArray result = (*env)->NewIntArray(env, 6);
    if (result) (*env)->SetIntArrayRegion(env, result, 0, 6, values);
    return result;
}

JNIEXPORT jbyteArray JNICALL
Java_com_thorium_preview_LibretroHost_nativeCopyVideoFrame(
        JNIEnv *env, jclass type, jlong handle, jint size) {
    (void)type;
    if (size <= 0) return NULL;
    void *bytes = malloc((size_t)size);
    if (!bytes) {
        throw_state(env, "out of memory copying video frame");
        return NULL;
    }
    if (!lucent_retro_copy_video_frame(from_handle(handle), bytes, (size_t)size)) {
        free(bytes);
        return NULL;
    }
    jbyteArray result = (*env)->NewByteArray(env, size);
    if (result) (*env)->SetByteArrayRegion(env, result, 0, size, (const jbyte *)bytes);
    free(bytes);
    return result;
}

JNIEXPORT jshortArray JNICALL
Java_com_thorium_preview_LibretroHost_nativeDrainAudio(
        JNIEnv *env, jclass type, jlong handle, jint max_frames) {
    (void)type;
    if (max_frames <= 0 || max_frames > INT32_MAX / 2) return NULL;
    size_t max_samples = (size_t)max_frames * 2;
    int16_t *samples = (int16_t *)malloc(max_samples * sizeof(int16_t));
    if (!samples) {
        throw_state(env, "out of memory draining audio");
        return NULL;
    }
    size_t frames = lucent_retro_drain_audio(from_handle(handle), samples,
                                              (size_t)max_frames);
    jsize sample_count = (jsize)(frames * 2);
    jshortArray result = (*env)->NewShortArray(env, sample_count);
    if (result && sample_count)
        (*env)->SetShortArrayRegion(env, result, 0, sample_count, (const jshort *)samples);
    free(samples);
    return result;
}

JNIEXPORT jdoubleArray JNICALL
Java_com_thorium_preview_LibretroHost_nativeAvInfo(
        JNIEnv *env, jclass type, jlong handle) {
    (void)type;
    lucent_retro_av_info info;
    if (!lucent_retro_get_av_info(from_handle(handle), &info)) return NULL;
    jdouble values[5] = {info.frames_per_second, info.sample_rate,
                         (double)info.aspect_ratio, (double)info.base_width,
                         (double)info.base_height};
    jdoubleArray result = (*env)->NewDoubleArray(env, 5);
    if (result) (*env)->SetDoubleArrayRegion(env, result, 0, 5, values);
    return result;
}

JNIEXPORT jbyteArray JNICALL
Java_com_thorium_preview_LibretroHost_nativeSerialize(
        JNIEnv *env, jclass type, jlong handle) {
    (void)type;
    char error[ERROR_SIZE] = {0};
    lucent_retro_host *host = from_handle(handle);
    size_t size = 0;
    void *bytes = NULL;
    if (!lucent_retro_serialize_alloc(host, &bytes, &size,
                                      error, sizeof(error))) {
        throw_state(env, error);
        return NULL;
    }
    if (size > INT32_MAX) {
        free(bytes);
        throw_state(env, "serialized state exceeds the Java array limit");
        return NULL;
    }
    jbyteArray result = (*env)->NewByteArray(env, (jsize)size);
    if (result) (*env)->SetByteArrayRegion(env, result, 0, (jsize)size, (const jbyte *)bytes);
    free(bytes);
    return result;
}

JNIEXPORT void JNICALL
Java_com_thorium_preview_LibretroHost_nativeUnserialize(
        JNIEnv *env, jclass type, jlong handle, jbyteArray state) {
    (void)type;
    char error[ERROR_SIZE] = {0};
    if (!state) {
        throw_state(env, "serialized state is required");
        return;
    }
    jsize size = (*env)->GetArrayLength(env, state);
    jbyte *bytes = (*env)->GetByteArrayElements(env, state, NULL);
    bool success = bytes && lucent_retro_unserialize(
            from_handle(handle), bytes, (size_t)size, error, sizeof(error));
    if (bytes) (*env)->ReleaseByteArrayElements(env, state, bytes, JNI_ABORT);
    if (!success && !(*env)->ExceptionCheck(env)) throw_state(env, error);
}

JNIEXPORT jstring JNICALL
Java_com_thorium_preview_LibretroHost_nativeLibraryName(
        JNIEnv *env, jclass type, jlong handle) {
    (void)type;
    return (*env)->NewStringUTF(env, lucent_retro_library_name(from_handle(handle)));
}

JNIEXPORT jstring JNICALL
Java_com_thorium_preview_LibretroHost_nativeLibraryVersion(
        JNIEnv *env, jclass type, jlong handle) {
    (void)type;
    return (*env)->NewStringUTF(env, lucent_retro_library_version(from_handle(handle)));
}

JNIEXPORT jbyteArray JNICALL
Java_com_thorium_preview_LibretroHost_nativeReadSaveRam(
        JNIEnv *env, jclass type, jlong handle) {
    (void)type;
    lucent_retro_host *host = from_handle(handle);
    size_t size = lucent_retro_save_ram_size(host);
    if (!size || size > INT32_MAX) return NULL;
    void *bytes = malloc(size);
    if (!bytes) {
        throw_state(env, "out of memory copying save RAM");
        return NULL;
    }
    if (!lucent_retro_read_save_ram(host, bytes, size)) {
        free(bytes);
        return NULL;
    }
    jbyteArray result = (*env)->NewByteArray(env, (jsize)size);
    if (result) (*env)->SetByteArrayRegion(env, result, 0, (jsize)size, (const jbyte *)bytes);
    free(bytes);
    return result;
}

JNIEXPORT void JNICALL
Java_com_thorium_preview_LibretroHost_nativeWriteSaveRam(
        JNIEnv *env, jclass type, jlong handle, jbyteArray save_ram) {
    (void)type;
    if (!save_ram) {
        throw_state(env, "save RAM is required");
        return;
    }
    jsize size = (*env)->GetArrayLength(env, save_ram);
    jbyte *bytes = (*env)->GetByteArrayElements(env, save_ram, NULL);
    bool success = bytes && lucent_retro_write_save_ram(
            from_handle(handle), bytes, (size_t)size);
    if (bytes) (*env)->ReleaseByteArrayElements(env, save_ram, bytes, JNI_ABORT);
    if (!success && !(*env)->ExceptionCheck(env))
        throw_state(env, "save RAM size does not match the loaded core");
}

JNIEXPORT void JNICALL
Java_com_thorium_preview_LibretroHost_nativeDestroy(
        JNIEnv *env, jclass type, jlong handle) {
    (void)type;
    char error[ERROR_SIZE] = {0};
    lucent_retro_host *host = from_handle(handle);
    if (lucent_retro_game_loaded(host) &&
            !lucent_retro_unload_game(host, error, sizeof(error))) {
        throw_state(env, error);
        return;
    }
    lucent_retro_destroy(host);
}

/* Experimental Phase 2 bridge. It is intentionally isolated from
 * LibretroHost's software constructor and is not referenced by the production
 * engine catalog. Every surface/frame call must run on one render thread. */
JNIEXPORT jlong JNICALL
Java_com_thorium_preview_ExperimentalGlesLibretroHost_nativeCreateGles(
        JNIEnv *env, jclass type, jstring core_path, jstring trusted_root,
        jstring system_directory, jstring save_directory,
        jint presentation_policy) {
    (void)type;
    char error[ERROR_SIZE] = {0};
    const char *core = NULL;
    const char *root = NULL;
    const char *system = NULL;
    const char *save = NULL;
    lucent_retro_hw_options options;
    lucent_gles_jni_session *session = NULL;
    if (!core_path || !trusted_root || !system_directory || !save_directory) {
        throw_state(env, "core, trusted root, system, and save paths are required");
        return 0;
    }
    core = (*env)->GetStringUTFChars(env, core_path, NULL);
    root = (*env)->GetStringUTFChars(env, trusted_root, NULL);
    system = (*env)->GetStringUTFChars(env, system_directory, NULL);
    save = (*env)->GetStringUTFChars(env, save_directory, NULL);
    if (!core || !root || !system || !save) goto done;
    session = (lucent_gles_jni_session *)calloc(1, sizeof(*session));
    if (!session) {
        snprintf(error, sizeof(error), "out of memory creating GLES JNI session");
        goto done;
    }
    session->backend = lucent_android_gles_create(error, sizeof(error));
    if (!session->backend || !lucent_android_gles_set_presentation_policy(
            session->backend, (unsigned)presentation_policy,
            error, sizeof(error)) || !lucent_android_gles_get_host_options(
            session->backend, &options, error, sizeof(error))) goto done;
    /* PPSSPP's pinned Android libretro target treats OPENGLES3 as the
     * frontend preference but requests its compiled OPENGLES2 context during
     * SET_HW_RENDER. Advertising OPENGLES_VERSION here makes that core skip
     * hardware rendering entirely. Keep the backend's complete capability
     * set, while selecting the compatible preference only on this isolated
     * qualification bridge. */
    options.preferred_context =
            (options.context_capabilities & LUCENT_RETRO_HW_GLES3) ?
                    LUCENT_RETRO_HW_GLES3 : LUCENT_RETRO_HW_GLES2;
    session->host = lucent_retro_create_with_options(
            core, root, system, save, &options, error, sizeof(error));
    if (session->host) {
        JavaVM *vm = NULL;
        if ((*env)->GetJavaVM(env, &vm) != JNI_OK || !vm ||
                !lucent_retro_supply_android_java_vm(
                        session->host, vm, error, sizeof(error))) {
            lucent_retro_destroy(session->host);
            session->host = NULL;
        }
    }
done:
    if (core) (*env)->ReleaseStringUTFChars(env, core_path, core);
    if (root) (*env)->ReleaseStringUTFChars(env, trusted_root, root);
    if (system) (*env)->ReleaseStringUTFChars(env, system_directory, system);
    if (save) (*env)->ReleaseStringUTFChars(env, save_directory, save);
    if ((!session || !session->host) && !(*env)->ExceptionCheck(env)) {
        if (session) {
            lucent_android_gles_destroy(session->backend);
            free(session);
            session = NULL;
        }
        throw_state(env, error);
    }
    return (jlong)(intptr_t)session;
}

JNIEXPORT void JNICALL
Java_com_thorium_preview_ExperimentalGlesLibretroHost_nativeLoadGameGles(
        JNIEnv *env, jclass type, jlong handle, jstring game_path) {
    (void)type;
    char error[ERROR_SIZE] = {0};
    lucent_gles_jni_session *session = from_gles_handle(handle);
    const char *path = game_path ?
            (*env)->GetStringUTFChars(env, game_path, NULL) : NULL;
    bool success = session && session->host && path &&
            lucent_retro_load_game(session->host, path, error, sizeof(error));
    if (path) (*env)->ReleaseStringUTFChars(env, game_path, path);
    if (!success && !(*env)->ExceptionCheck(env)) throw_state(env, error);
}

JNIEXPORT void JNICALL
Java_com_thorium_preview_ExperimentalGlesLibretroHost_nativeSetControllerPortDeviceGles(
        JNIEnv *env, jclass type, jlong handle, jint port, jint device) {
    (void)type;
    char error[ERROR_SIZE] = {0};
    lucent_gles_jni_session *session = from_gles_handle(handle);
    bool success = session && session->host &&
            lucent_retro_set_controller_port_device(session->host,
                    (unsigned)port, (unsigned)device, error, sizeof(error));
    if (!success && !(*env)->ExceptionCheck(env)) throw_state(env, error);
}

JNIEXPORT void JNICALL
Java_com_thorium_preview_ExperimentalGlesLibretroHost_nativeResetGles(
        JNIEnv *env, jclass type, jlong handle) {
    (void)type;
    char error[ERROR_SIZE] = {0};
    lucent_gles_jni_session *session = from_gles_handle(handle);
    bool success = session && session->host &&
            lucent_retro_reset(session->host, error, sizeof(error));
    if (!success && !(*env)->ExceptionCheck(env)) throw_state(env, error);
}

static bool attach_java_surface(JNIEnv *env, lucent_gles_jni_session *session,
                                jobject surface, bool recreate,
                                char *error, size_t error_size) {
    ANativeWindow *window;
    bool success;
    if (!session || !session->backend || !session->host || !surface) {
        snprintf(error, error_size, "GLES session and Android Surface are required");
        return false;
    }
    if (recreate && !lucent_android_gles_detach(
            session->backend, error, error_size)) return false;
    window = ANativeWindow_fromSurface(env, surface);
    if (!window) {
        snprintf(error, error_size, "Android Surface has no valid ANativeWindow");
        return false;
    }
    /* fromSurface and the backend each own one reference during this call. */
    success = lucent_android_gles_attach(session->backend, session->host,
                                         window, error, error_size);
    ANativeWindow_release(window);
    return success;
}

JNIEXPORT void JNICALL
Java_com_thorium_preview_ExperimentalGlesLibretroHost_nativeAttachSurfaceGles(
        JNIEnv *env, jclass type, jlong handle, jobject surface) {
    (void)type;
    char error[ERROR_SIZE] = {0};
    if (!attach_java_surface(env, from_gles_handle(handle), surface, false,
                             error, sizeof(error)) &&
            !(*env)->ExceptionCheck(env)) throw_state(env, error);
}

JNIEXPORT void JNICALL
Java_com_thorium_preview_ExperimentalGlesLibretroHost_nativeRecreateSurfaceGles(
        JNIEnv *env, jclass type, jlong handle, jobject surface) {
    (void)type;
    char error[ERROR_SIZE] = {0};
    if (!attach_java_surface(env, from_gles_handle(handle), surface, true,
                             error, sizeof(error)) &&
            !(*env)->ExceptionCheck(env)) throw_state(env, error);
}

JNIEXPORT jboolean JNICALL
Java_com_thorium_preview_ExperimentalGlesLibretroHost_nativeRunAndPresentGles(
        JNIEnv *env, jclass type, jlong handle) {
    (void)type;
    char error[ERROR_SIZE] = {0};
    bool presented = false;
    lucent_gles_jni_session *session = from_gles_handle(handle);
    if (!session || !session->host || !session->backend ||
            !lucent_retro_run_frame(session->host, error, sizeof(error)) ||
            !lucent_android_gles_present_if_ready(
                    session->backend, &presented, error, sizeof(error))) {
        if (!(*env)->ExceptionCheck(env)) throw_state(env, error);
        return JNI_FALSE;
    }
    return presented ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_thorium_preview_ExperimentalGlesLibretroHost_nativeDetachSurfaceGles(
        JNIEnv *env, jclass type, jlong handle) {
    (void)type;
    char error[ERROR_SIZE] = {0};
    lucent_gles_jni_session *session = from_gles_handle(handle);
    if ((!session || !session->backend || !lucent_android_gles_detach(
            session->backend, error, sizeof(error))) &&
            !(*env)->ExceptionCheck(env)) throw_state(env, error);
}

JNIEXPORT void JNICALL
Java_com_thorium_preview_ExperimentalGlesLibretroHost_nativeSetPausedGles(
        JNIEnv *env, jclass type, jlong handle, jboolean paused) {
    (void)env;
    (void)type;
    lucent_gles_jni_session *session = from_gles_handle(handle);
    if (session) lucent_retro_set_paused(session->host, paused == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_thorium_preview_ExperimentalGlesLibretroHost_nativeSetJoypadButtonGles(
        JNIEnv *env, jclass type, jlong handle, jint port, jint button,
        jboolean pressed) {
    (void)type;
    lucent_gles_jni_session *session = from_gles_handle(handle);
    if (!session || port < 0 || button < 0 ||
            !lucent_retro_set_joypad_button(session->host, (unsigned)port,
                    (unsigned)button, pressed == JNI_TRUE))
        throw_state(env, "invalid experimental GLES joypad input");
}

JNIEXPORT void JNICALL
Java_com_thorium_preview_ExperimentalGlesLibretroHost_nativeSetAnalogAxisGles(
        JNIEnv *env, jclass type, jlong handle, jint port, jint index, jint id,
        jint value) {
    (void)type;
    lucent_gles_jni_session *session = from_gles_handle(handle);
    if (!session || port < 0 || index < 0 || id < 0 ||
            value < INT16_MIN || value > INT16_MAX ||
            !lucent_retro_set_analog_axis(session->host, (unsigned)port,
                    (unsigned)index, (unsigned)id, (int16_t)value))
        throw_state(env, "invalid experimental GLES analog input");
}

JNIEXPORT jintArray JNICALL
Java_com_thorium_preview_ExperimentalGlesLibretroHost_nativeHardwareInfoGles(
        JNIEnv *env, jclass type, jlong handle) {
    (void)type;
    lucent_gles_jni_session *session = from_gles_handle(handle);
    lucent_retro_hw_info info;
    jint values[7];
    jintArray result;
    if (!session || !lucent_retro_get_hw_info(session->host, &info)) return NULL;
    values[0] = info.negotiated ? 1 : 0;
    values[1] = info.context_ready ? 1 : 0;
    values[2] = (jint)info.context_type;
    values[3] = (jint)info.version_major;
    values[4] = (jint)info.version_minor;
    values[5] = info.bottom_left_origin ? 1 : 0;
    values[6] = info.frame_sequence > INT32_MAX ? INT32_MAX :
            (jint)info.frame_sequence;
    result = (*env)->NewIntArray(env, 7);
    if (result) (*env)->SetIntArrayRegion(env, result, 0, 7, values);
    return result;
}

JNIEXPORT jshortArray JNICALL
Java_com_thorium_preview_ExperimentalGlesLibretroHost_nativeDrainAudioGles(
        JNIEnv *env, jclass type, jlong handle, jint max_frames) {
    (void)type;
    lucent_gles_jni_session *session = from_gles_handle(handle);
    size_t max_samples;
    int16_t *samples;
    size_t frames;
    jsize sample_count;
    jshortArray result;
    if (!session || !session->host || max_frames <= 0 || max_frames > INT32_MAX / 2)
        return NULL;
    max_samples = (size_t)max_frames * 2;
    samples = (int16_t *)malloc(max_samples * sizeof(int16_t));
    if (!samples) {
        throw_state(env, "out of memory draining experimental GLES audio");
        return NULL;
    }
    frames = lucent_retro_drain_audio(session->host, samples, (size_t)max_frames);
    sample_count = (jsize)(frames * 2);
    result = (*env)->NewShortArray(env, sample_count);
    if (result && sample_count)
        (*env)->SetShortArrayRegion(env, result, 0, sample_count,
                                    (const jshort *)samples);
    free(samples);
    return result;
}

JNIEXPORT jdoubleArray JNICALL
Java_com_thorium_preview_ExperimentalGlesLibretroHost_nativeAvInfoGles(
        JNIEnv *env, jclass type, jlong handle) {
    (void)type;
    lucent_gles_jni_session *session = from_gles_handle(handle);
    lucent_retro_av_info info;
    jdouble values[5];
    jdoubleArray result;
    if (!session || !session->host ||
            !lucent_retro_get_av_info(session->host, &info)) return NULL;
    values[0] = info.frames_per_second;
    values[1] = info.sample_rate;
    values[2] = (double)info.aspect_ratio;
    values[3] = (double)info.base_width;
    values[4] = (double)info.base_height;
    result = (*env)->NewDoubleArray(env, 5);
    if (result) (*env)->SetDoubleArrayRegion(env, result, 0, 5, values);
    return result;
}

JNIEXPORT jbyteArray JNICALL
Java_com_thorium_preview_ExperimentalGlesLibretroHost_nativeSerializeGles(
        JNIEnv *env, jclass type, jlong handle) {
    (void)type;
    char error[ERROR_SIZE] = {0};
    lucent_gles_jni_session *session = from_gles_handle(handle);
    size_t size;
    void *bytes;
    jbyteArray result;
    if (!session || !session->host) {
        throw_state(env, "experimental GLES session is unavailable");
        return NULL;
    }
    size = 0;
    bytes = NULL;
    if (!lucent_retro_serialize_alloc(session->host, &bytes, &size,
                                      error, sizeof(error))) {
        throw_state(env, error);
        return NULL;
    }
    if (size > INT32_MAX) {
        free(bytes);
        throw_state(env, "serialized GLES state exceeds the Java array limit");
        return NULL;
    }
    result = (*env)->NewByteArray(env, (jsize)size);
    if (result)
        (*env)->SetByteArrayRegion(env, result, 0, (jsize)size,
                                   (const jbyte *)bytes);
    free(bytes);
    return result;
}

JNIEXPORT jboolean JNICALL
Java_com_thorium_preview_ExperimentalGlesLibretroHost_nativeStateReadyGles(
        JNIEnv *env, jclass type, jlong handle) {
    (void)env;
    (void)type;
    lucent_gles_jni_session *session = from_gles_handle(handle);
    return session && session->host && lucent_retro_serialize_size(session->host) > 0
            ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_thorium_preview_ExperimentalGlesLibretroHost_nativeUnserializeGles(
        JNIEnv *env, jclass type, jlong handle, jbyteArray state) {
    (void)type;
    char error[ERROR_SIZE] = {0};
    lucent_gles_jni_session *session = from_gles_handle(handle);
    jsize size;
    jbyte *bytes;
    bool success;
    if (!session || !session->host || !state) {
        throw_state(env, "experimental GLES serialized state is required");
        return;
    }
    size = (*env)->GetArrayLength(env, state);
    bytes = (*env)->GetByteArrayElements(env, state, NULL);
    success = bytes && lucent_retro_unserialize(session->host, bytes,
                                                (size_t)size, error, sizeof(error));
    if (bytes) (*env)->ReleaseByteArrayElements(env, state, bytes, JNI_ABORT);
    if (!success && !(*env)->ExceptionCheck(env)) throw_state(env, error);
}

JNIEXPORT jbyteArray JNICALL
Java_com_thorium_preview_ExperimentalGlesLibretroHost_nativeReadSaveRamGles(
        JNIEnv *env, jclass type, jlong handle) {
    (void)type;
    lucent_gles_jni_session *session = from_gles_handle(handle);
    size_t size;
    void *bytes;
    jbyteArray result;
    if (!session || !session->host) return NULL;
    size = lucent_retro_save_ram_size(session->host);
    if (!size || size > INT32_MAX) return NULL;
    bytes = malloc(size);
    if (!bytes) {
        throw_state(env, "out of memory copying experimental GLES save RAM");
        return NULL;
    }
    if (!lucent_retro_read_save_ram(session->host, bytes, size)) {
        free(bytes);
        return NULL;
    }
    result = (*env)->NewByteArray(env, (jsize)size);
    if (result)
        (*env)->SetByteArrayRegion(env, result, 0, (jsize)size,
                                   (const jbyte *)bytes);
    free(bytes);
    return result;
}

JNIEXPORT void JNICALL
Java_com_thorium_preview_ExperimentalGlesLibretroHost_nativeWriteSaveRamGles(
        JNIEnv *env, jclass type, jlong handle, jbyteArray save_ram) {
    (void)type;
    lucent_gles_jni_session *session = from_gles_handle(handle);
    jsize size;
    jbyte *bytes;
    bool success;
    if (!session || !session->host || !save_ram) {
        throw_state(env, "experimental GLES save RAM is required");
        return;
    }
    size = (*env)->GetArrayLength(env, save_ram);
    bytes = (*env)->GetByteArrayElements(env, save_ram, NULL);
    success = bytes && lucent_retro_write_save_ram(session->host, bytes,
                                                   (size_t)size);
    if (bytes) (*env)->ReleaseByteArrayElements(env, save_ram, bytes, JNI_ABORT);
    if (!success && !(*env)->ExceptionCheck(env))
        throw_state(env, "experimental GLES save RAM size mismatch");
}

JNIEXPORT void JNICALL
Java_com_thorium_preview_ExperimentalGlesLibretroHost_nativeDestroyGles(
        JNIEnv *env, jclass type, jlong handle) {
    (void)type;
    char error[ERROR_SIZE] = {0};
    lucent_android_gles_info info;
    lucent_gles_jni_session *session = from_gles_handle(handle);
    if (!session) return;
    if (lucent_android_gles_get_info(session->backend, &info) &&
            info.surface_attached && !lucent_android_gles_detach(
                    session->backend, error, sizeof(error))) {
        throw_state(env, error);
        return;
    }
    lucent_retro_destroy(session->host);
    lucent_android_gles_destroy(session->backend);
    free(session);
}
