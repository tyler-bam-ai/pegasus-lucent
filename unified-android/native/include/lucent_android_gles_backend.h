#ifndef LUCENT_ANDROID_GLES_BACKEND_H
#define LUCENT_ANDROID_GLES_BACKEND_H

#include "lucent_libretro_host.h"

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

typedef struct lucent_android_gles_backend lucent_android_gles_backend;

#define LUCENT_ANDROID_GLES_BACKEND_API_VERSION 2u
#define LUCENT_ANDROID_GLES_PRESENT_AUTO 0u
#define LUCENT_ANDROID_GLES_PRESENT_FRONTEND_FBO 1u
#define LUCENT_ANDROID_GLES_PRESENT_DIRECT_WINDOW 2u

typedef struct lucent_android_gles_info {
    bool display_ready;
    bool surface_attached;
    unsigned max_gles_major;
    unsigned max_gles_minor;
    unsigned active_gles_major;
    unsigned active_gles_minor;
    unsigned depth_bits;
    unsigned stencil_bits;
    uint64_t presented_sequence;
} lucent_android_gles_info;

/* Creates and probes an Android EGL display without claiming a Java Surface.
 * The returned host options advertise only capabilities proven by that probe.
 * Vulkan is deliberately absent until Lucent owns a complete Vulkan interface.
 */
lucent_android_gles_backend *lucent_android_gles_create(
        char *error, size_t error_size);
bool lucent_android_gles_set_presentation_policy(
        lucent_android_gles_backend *backend,
        unsigned policy,
        char *error, size_t error_size);
bool lucent_android_gles_get_host_options(
        lucent_android_gles_backend *backend,
        lucent_retro_hw_options *options,
        char *error, size_t error_size);

/* native_window must be an ANativeWindow*. The loaded core must already have
 * negotiated a GLES context through the options returned above. All attach,
 * frame, present, detach, and destroy calls are render-thread-affine.
 */
bool lucent_android_gles_attach(
        lucent_android_gles_backend *backend,
        lucent_retro_host *host,
        void *native_window,
        char *error, size_t error_size);
bool lucent_android_gles_make_current(
        lucent_android_gles_backend *backend,
        char *error, size_t error_size);
bool lucent_android_gles_present_if_ready(
        lucent_android_gles_backend *backend,
        bool *presented,
        char *error, size_t error_size);
bool lucent_android_gles_detach(
        lucent_android_gles_backend *backend,
        char *error, size_t error_size);
bool lucent_android_gles_get_info(
        const lucent_android_gles_backend *backend,
        lucent_android_gles_info *info);
void lucent_android_gles_destroy(lucent_android_gles_backend *backend);

#endif
