#include "include/lucent_android_gles_backend.h"
#include "include/libretro.h"

#if !defined(__ANDROID__)
#error "lucent_android_gles_backend.c is Android-only"
#endif

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <android/log.h>
#include <android/native_window.h>
#include <dlfcn.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

struct lucent_android_gles_backend {
    EGLDisplay display;
    EGLConfig config;
    EGLContext context;
    EGLSurface surface;
    ANativeWindow *window;
    lucent_retro_host *host;
    pthread_t render_thread;
    bool render_thread_set;
    unsigned max_major;
    unsigned max_minor;
    uint32_t context_capabilities;
    uint32_t feature_capabilities;
    unsigned active_major;
    unsigned active_minor;
    unsigned depth_bits;
    unsigned stencil_bits;
    GLuint framebuffer;
    GLuint color_texture;
    GLuint depth_stencil_buffer;
    unsigned framebuffer_width;
    unsigned framebuffer_height;
    unsigned content_width;
    unsigned content_height;
    unsigned destination_x;
    unsigned destination_y;
    unsigned destination_width;
    unsigned destination_height;
    unsigned configured_presentation_path;
    unsigned presentation_path;
    uint64_t presented_sequence;
    unsigned framebuffer_callback_diagnostics;
    unsigned frame_pixel_diagnostics;
    unsigned framebuffer_blit_diagnostics;
    bool presentation_geometry_logged;
};

enum {
    LUCENT_GLES_PRESENTATION_UNKNOWN = LUCENT_ANDROID_GLES_PRESENT_AUTO,
    LUCENT_GLES_PRESENTATION_FRONTEND_FBO =
            LUCENT_ANDROID_GLES_PRESENT_FRONTEND_FBO,
    LUCENT_GLES_PRESENTATION_DIRECT_WINDOW =
            LUCENT_ANDROID_GLES_PRESENT_DIRECT_WINDOW
};

static const GLubyte framebuffer_sentinel[4] = {23u, 179u, 241u, 255u};

static void set_error(char *buffer, size_t size, const char *format, ...) {
    va_list arguments;
    if (!buffer || !size) return;
    va_start(arguments, format);
    vsnprintf(buffer, size, format, arguments);
    va_end(arguments);
}

static bool on_render_thread(const lucent_android_gles_backend *backend) {
    return backend && backend->render_thread_set &&
            pthread_equal(backend->render_thread, pthread_self());
}

static bool claim_render_thread(lucent_android_gles_backend *backend,
                                char *error, size_t error_size) {
    if (!backend) {
        set_error(error, error_size, "GLES backend is required");
        return false;
    }
    if (!backend->render_thread_set) {
        backend->render_thread = pthread_self();
        backend->render_thread_set = true;
        return true;
    }
    if (!on_render_thread(backend)) {
        set_error(error, error_size, "GLES lifecycle must stay on its render thread");
        return false;
    }
    return true;
}

static bool choose_config(lucent_android_gles_backend *backend,
                          unsigned major, unsigned depth, unsigned stencil,
                          EGLConfig *config, char *error, size_t error_size) {
    EGLint count = 0;
    EGLint renderable = major >= 3 ? EGL_OPENGL_ES3_BIT_KHR : EGL_OPENGL_ES2_BIT;
    const EGLint attributes[] = {
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT | EGL_PBUFFER_BIT,
        EGL_RENDERABLE_TYPE, renderable,
        EGL_RED_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE, 8,
        EGL_ALPHA_SIZE, 8,
        EGL_DEPTH_SIZE, (EGLint)depth,
        EGL_STENCIL_SIZE, (EGLint)stencil,
        EGL_NONE
    };
    if (!eglChooseConfig(backend->display, attributes, config, 1, &count) || count != 1) {
        set_error(error, error_size,
                  "no Android EGL config for GLES %u depth=%u stencil=%u (0x%x)",
                  major, depth, stencil, eglGetError());
        return false;
    }
    return true;
}

static EGLContext create_context(EGLDisplay display, EGLConfig config,
                                 unsigned major, unsigned minor) {
    if (minor) {
        const EGLint versioned[] = {
            EGL_CONTEXT_MAJOR_VERSION_KHR, (EGLint)major,
            EGL_CONTEXT_MINOR_VERSION_KHR, (EGLint)minor,
            EGL_NONE
        };
        return eglCreateContext(display, config, EGL_NO_CONTEXT, versioned);
    }
    {
        const EGLint basic[] = {
            EGL_CONTEXT_CLIENT_VERSION, (EGLint)major,
            EGL_NONE
        };
        return eglCreateContext(display, config, EGL_NO_CONTEXT, basic);
    }
}

static void parse_gles_version(const char *version, unsigned fallback_major,
                               unsigned *major, unsigned *minor) {
    unsigned parsed_major = 0;
    unsigned parsed_minor = 0;
    if (version) {
        const char *digits = version;
        while (*digits && (*digits < '0' || *digits > '9')) digits++;
        if (sscanf(digits, "%u.%u", &parsed_major, &parsed_minor) != 2)
            parsed_major = 0;
    }
    *major = parsed_major ? parsed_major : fallback_major;
    *minor = parsed_major ? parsed_minor : 0;
}

static bool probe_context(lucent_android_gles_backend *backend,
                          unsigned requested_major, unsigned requested_minor,
                          unsigned *actual_major, unsigned *actual_minor) {
    EGLConfig config = NULL;
    EGLContext context = EGL_NO_CONTEXT;
    EGLSurface surface = EGL_NO_SURFACE;
    const EGLint pbuffer[] = { EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE };
    const GLubyte *version;
    if (!choose_config(backend, requested_major, 0, 0, &config, NULL, 0))
        return false;
    context = create_context(backend->display, config, requested_major,
                             requested_minor);
    if (context == EGL_NO_CONTEXT) return false;
    surface = eglCreatePbufferSurface(backend->display, config, pbuffer);
    if (surface == EGL_NO_SURFACE ||
            !eglMakeCurrent(backend->display, surface, surface, context)) goto fail;
    version = glGetString(GL_VERSION);
    parse_gles_version((const char *)version, requested_major,
                       actual_major, actual_minor);
    eglMakeCurrent(backend->display, EGL_NO_SURFACE, EGL_NO_SURFACE,
                   EGL_NO_CONTEXT);
    eglDestroySurface(backend->display, surface);
    eglDestroyContext(backend->display, context);
    return true;
fail:
    eglMakeCurrent(backend->display, EGL_NO_SURFACE, EGL_NO_SURFACE,
                   EGL_NO_CONTEXT);
    if (surface != EGL_NO_SURFACE) eglDestroySurface(backend->display, surface);
    eglDestroyContext(backend->display, context);
    return false;
}

static uintptr_t current_framebuffer(void *userdata) {
    lucent_android_gles_backend *backend =
            (lucent_android_gles_backend *)userdata;
    uintptr_t result;
    /* Some valid hardware cores (notably PPSSPP) ask for the frontend FBO
     * while configuring a deferred render target, before that worker has made
     * the frontend EGL context current.  A framebuffer name is context-owned
     * data rather than an operation; returning the already-created name is
     * safe here.  Every operation that creates, reads, blits, or destroys that
     * FBO remains render-thread/current-context guarded.
     */
    if (!backend) return 0;
    result = (!backend->host || backend->surface == EGL_NO_SURFACE ||
            backend->configured_presentation_path ==
                    LUCENT_GLES_PRESENTATION_DIRECT_WINDOW) ?
            0 : (uintptr_t)backend->framebuffer;
    if (backend->framebuffer_callback_diagnostics < 4u) {
        __android_log_print(ANDROID_LOG_INFO, "LucentGlesBackend",
                "frontend framebuffer callback value=%lu policy=%u "
                "surface=%s renderThread=%s",
                (unsigned long)result,
                backend->configured_presentation_path,
                backend->surface == EGL_NO_SURFACE ? "none" : "ready",
                on_render_thread(backend) ? "yes" : "no");
        backend->framebuffer_callback_diagnostics++;
    }
    return result;
}

static void abandon_framebuffer(lucent_android_gles_backend *backend) {
    if (!backend) return;
    backend->framebuffer = 0;
    backend->color_texture = 0;
    backend->depth_stencil_buffer = 0;
    backend->framebuffer_width = 0;
    backend->framebuffer_height = 0;
    backend->content_width = 0;
    backend->content_height = 0;
    backend->destination_x = 0;
    backend->destination_y = 0;
    backend->destination_width = 0;
    backend->destination_height = 0;
    backend->presentation_path = backend->configured_presentation_path;
    backend->presentation_geometry_logged = false;
}

static void delete_framebuffer(lucent_android_gles_backend *backend) {
    if (!backend) return;
    if (backend->depth_stencil_buffer)
        glDeleteRenderbuffers(1, &backend->depth_stencil_buffer);
    if (backend->color_texture)
        glDeleteTextures(1, &backend->color_texture);
    if (backend->framebuffer)
        glDeleteFramebuffers(1, &backend->framebuffer);
    abandon_framebuffer(backend);
}

static unsigned align_up_16(unsigned value) {
    return value > UINT32_MAX - 15u ? value : (value + 15u) & ~15u;
}

/* Hardware libretro cores render into a frontend-owned framebuffer.  Rendering
 * directly into Android's window framebuffer leaves a native-size image in
 * one corner because the core is not responsible for presentation scaling.
 * Keep the core target at the largest aspect-correct 1080p size and blit it
 * into a centered Android window rectangle when a new frame arrives. */
static bool create_framebuffer(lucent_android_gles_backend *backend,
                               lucent_retro_host *host,
                               unsigned window_width, unsigned window_height,
                               char *error, size_t error_size) {
    lucent_retro_av_info av;
    float aspect;
    GLenum attachment;
    GLenum format;
    if (!backend || !host) {
        set_error(error, error_size, "GLES framebuffer scaling requires a live host");
        return false;
    }
    if (!window_width || !window_height) {
        set_error(error, error_size, "Android window reported an empty surface");
        return false;
    }
    if (backend->active_major < 3) {
        set_error(error, error_size,
                  "GLES framebuffer scaling requires GLES3 (active %u.%u)",
                  backend->active_major, backend->active_minor);
        return false;
    }
    memset(&av, 0, sizeof(av));
    if (!lucent_retro_get_av_info(host, &av)) {
        set_error(error, error_size, "core AV geometry is unavailable");
        return false;
    }
    aspect = av.aspect_ratio > 0.0f ? av.aspect_ratio :
            (av.base_height ? (float)av.base_width / (float)av.base_height : 1.0f);
    backend->content_height = window_height;
    backend->content_width = (unsigned)(window_height * aspect + 0.5f);
    if (!backend->content_width) backend->content_width = 1;
    if (backend->content_width > window_width) {
        backend->content_width = window_width;
        backend->content_height = (unsigned)(window_width / aspect + 0.5f);
        if (!backend->content_height) backend->content_height = 1;
    }
    backend->destination_width = backend->content_width;
    backend->destination_height = backend->content_height;
    backend->destination_x = (window_width - backend->destination_width) / 2u;
    backend->destination_y = (window_height - backend->destination_height) / 2u;
    backend->framebuffer_width = backend->content_width;
    backend->framebuffer_height = align_up_16(backend->content_height);

    glGenTextures(1, &backend->color_texture);
    glBindTexture(GL_TEXTURE_2D, backend->color_texture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8,
                 (GLsizei)backend->framebuffer_width,
                 (GLsizei)backend->framebuffer_height, 0,
                 GL_RGBA, GL_UNSIGNED_BYTE, NULL);
    glGenFramebuffers(1, &backend->framebuffer);
    glBindFramebuffer(GL_FRAMEBUFFER, backend->framebuffer);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                           GL_TEXTURE_2D, backend->color_texture, 0);
    if (backend->depth_bits || backend->stencil_bits) {
        glGenRenderbuffers(1, &backend->depth_stencil_buffer);
        glBindRenderbuffer(GL_RENDERBUFFER, backend->depth_stencil_buffer);
        if (backend->depth_bits && backend->stencil_bits) {
            format = GL_DEPTH24_STENCIL8;
            attachment = GL_DEPTH_STENCIL_ATTACHMENT;
        } else if (backend->depth_bits) {
            format = GL_DEPTH_COMPONENT16;
            attachment = GL_DEPTH_ATTACHMENT;
        } else {
            format = GL_STENCIL_INDEX8;
            attachment = GL_STENCIL_ATTACHMENT;
        }
        glRenderbufferStorage(GL_RENDERBUFFER, format,
                              (GLsizei)backend->framebuffer_width,
                              (GLsizei)backend->framebuffer_height);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, attachment,
                                  GL_RENDERBUFFER,
                                  backend->depth_stencil_buffer);
    }
    if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
        set_error(error, error_size, "cannot create complete GLES core framebuffer");
        delete_framebuffer(backend);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        return false;
    }
    /* A hardware core may either honor get_current_framebuffer() or render
     * directly into Android framebuffer zero. Seed the private FBO with an
     * unlikely marker so the first submitted frame can distinguish those two
     * valid libretro behaviors without a per-engine name check. */
    glViewport(0, 0, (GLsizei)backend->framebuffer_width,
               (GLsizei)backend->framebuffer_height);
    glClearColor((GLfloat)framebuffer_sentinel[0] / 255.0f,
                 (GLfloat)framebuffer_sentinel[1] / 255.0f,
                 (GLfloat)framebuffer_sentinel[2] / 255.0f,
                 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glBindTexture(GL_TEXTURE_2D, 0);
    glBindRenderbuffer(GL_RENDERBUFFER, 0);
    return true;
}

static bool frontend_framebuffer_was_rendered(
        const lucent_android_gles_backend *backend) {
    static const unsigned sample_numerators[][2] = {
        {1, 1}, {1, 2}, {1, 3}, {2, 1}, {2, 2},
        {2, 3}, {3, 1}, {3, 2}, {3, 3}
    };
    GLubyte pixel[4];
    size_t i;
    if (!backend || !backend->framebuffer_width ||
            !backend->content_height) return false;
    for (i = 0; i < sizeof(sample_numerators) / sizeof(sample_numerators[0]); i++) {
        GLint x = (GLint)((uint64_t)backend->framebuffer_width *
                sample_numerators[i][0] / 4u);
        GLint y = (GLint)((uint64_t)backend->content_height *
                sample_numerators[i][1] / 4u);
        memset(pixel, 0, sizeof(pixel));
        glReadPixels(x, y, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
        if (memcmp(pixel, framebuffer_sentinel, sizeof(pixel)) != 0) return true;
    }
    return false;
}

/* Bounded qualification telemetry.  This samples what the frontend is about
 * to present; it does not alter the screenshot oracle or infer success from a
 * core callback.  Sampling at startup and again near three and six seconds
 * catches cores whose first video callback arrives after initial boot work,
 * while remaining bounded to three records per surface. */
static void log_framebuffer_pixels(lucent_android_gles_backend *backend,
                                   uint64_t frame_sequence) {
    GLubyte window_pixel[4] = {0, 0, 0, 0};
    GLubyte frontend_pixel[4] = {0, 0, 0, 0};
    GLint window_x;
    GLint window_y;
    GLint frontend_x;
    GLint frontend_y;
    if (!backend || backend->frame_pixel_diagnostics >= 3u || !backend->window)
        return;
    if ((backend->frame_pixel_diagnostics == 1u && frame_sequence < 180u) ||
            (backend->frame_pixel_diagnostics == 2u && frame_sequence < 360u))
        return;
    window_x = ANativeWindow_getWidth(backend->window) / 2;
    window_y = ANativeWindow_getHeight(backend->window) / 2;
    glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
    glReadPixels(window_x, window_y, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE,
                 window_pixel);
    if (backend->framebuffer && backend->framebuffer_width &&
            backend->content_height) {
        frontend_x = (GLint)(backend->framebuffer_width / 2u);
        frontend_y = (GLint)(backend->content_height / 2u);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, backend->framebuffer);
        glReadPixels(frontend_x, frontend_y, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE,
                     frontend_pixel);
    }
    __android_log_print(ANDROID_LOG_INFO, "LucentGlesBackend",
            "pre-swap pixels sequence=%llu policy=%u path=%u window=%u,%u,%u,%u "
            "frontend=%u,%u,%u,%u fbo=%u",
            (unsigned long long)frame_sequence,
            backend->configured_presentation_path, backend->presentation_path,
            window_pixel[0], window_pixel[1], window_pixel[2], window_pixel[3],
            frontend_pixel[0], frontend_pixel[1], frontend_pixel[2],
            frontend_pixel[3], backend->framebuffer);
    backend->frame_pixel_diagnostics++;
}

/* TextureView is an Android-composited layer even when Java marks it opaque.
 * PPSSPP legitimately writes RGB with alpha zero, which a dedicated opaque
 * Activity surface displays correctly but SurfaceTexture composition treats
 * as transparent.  Gameplay owns this complete layer, so normalize only the
 * destination alpha channel after presentation while preserving RGB and the
 * core's relevant GL state. */
static void force_opaque_surface_alpha(void) {
    GLfloat prior_clear[4];
    GLboolean prior_mask[4];
    GLboolean scissor_enabled;
    glGetFloatv(GL_COLOR_CLEAR_VALUE, prior_clear);
    glGetBooleanv(GL_COLOR_WRITEMASK, prior_mask);
    scissor_enabled = glIsEnabled(GL_SCISSOR_TEST);
    if (scissor_enabled) glDisable(GL_SCISSOR_TEST);
    glColorMask(GL_FALSE, GL_FALSE, GL_FALSE, GL_TRUE);
    glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);
    glClearColor(prior_clear[0], prior_clear[1], prior_clear[2], prior_clear[3]);
    glColorMask(prior_mask[0], prior_mask[1], prior_mask[2], prior_mask[3]);
    if (scissor_enabled) glEnable(GL_SCISSOR_TEST);
}

static lucent_retro_proc_address get_proc_address(void *userdata,
                                                  const char *symbol) {
    lucent_android_gles_backend *backend =
            (lucent_android_gles_backend *)userdata;
    __eglMustCastToProperFunctionPointerType address;
    void *fallback;
    lucent_retro_proc_address result = NULL;
    if (!backend || !backend->host || !on_render_thread(backend) ||
            backend->surface == EGL_NO_SURFACE || !symbol) return NULL;
    address = eglGetProcAddress(symbol);
    if (address) memcpy(&result, &address, sizeof(result));
    if (result) return result;
    fallback = dlsym(RTLD_DEFAULT, symbol);
    if (fallback) memcpy(&result, &fallback, sizeof(result));
    return result;
}

lucent_android_gles_backend *lucent_android_gles_create(
        char *error, size_t error_size) {
    lucent_android_gles_backend *backend =
            (lucent_android_gles_backend *)calloc(1, sizeof(*backend));
    unsigned major = 0, minor = 0;
    unsigned gles3_major = 0, gles3_minor = 0;
    bool has_gles2;
    bool has_gles3;
    if (!backend) {
        set_error(error, error_size, "out of memory creating Android GLES backend");
        return NULL;
    }
    backend->display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    backend->context = EGL_NO_CONTEXT;
    backend->surface = EGL_NO_SURFACE;
    if (backend->display == EGL_NO_DISPLAY ||
            !eglInitialize(backend->display, NULL, NULL) ||
            !eglBindAPI(EGL_OPENGL_ES_API)) {
        set_error(error, error_size, "cannot initialize Android EGL (0x%x)",
                  eglGetError());
        lucent_android_gles_destroy(backend);
        return NULL;
    }
    has_gles2 = probe_context(backend, 2, 0, &major, &minor) && major >= 2;
    has_gles3 = probe_context(backend, 3, 0, &gles3_major, &gles3_minor) &&
            gles3_major >= 3;
    if (has_gles2)
        backend->context_capabilities |= LUCENT_RETRO_HW_GLES2;
    if (has_gles3) {
        backend->context_capabilities |= LUCENT_RETRO_HW_GLES3;
        backend->max_major = gles3_major;
        /* A GLES 3 context created with CLIENT_VERSION may expose 3.1/3.2,
         * but Lucent advertises versioned GLES only after the exact KHR
         * major/minor creation path is independently proven. */
        backend->max_minor = 0;
        minor = gles3_minor;
        if (gles3_minor >= 2 && probe_context(backend, 3, 2, &major, &minor) &&
                (major > 3 || (major == 3 && minor >= 2))) {
            backend->max_major = major;
            backend->max_minor = minor;
            backend->context_capabilities |= LUCENT_RETRO_HW_GLES_VERSION;
        } else if (gles3_minor >= 1 &&
                probe_context(backend, 3, 1, &major, &minor) &&
                (major > 3 || (major == 3 && minor >= 1))) {
            backend->max_major = major;
            backend->max_minor = minor;
            backend->context_capabilities |= LUCENT_RETRO_HW_GLES_VERSION;
        }
    } else if (has_gles2) {
        backend->max_major = major;
        backend->max_minor = minor;
    } else {
        set_error(error, error_size, "Android EGL exposes no usable GLES2 context");
        lucent_android_gles_destroy(backend);
        return NULL;
    }
    {
        EGLConfig feature_config = NULL;
        bool depth = choose_config(backend, backend->max_major, 16, 0,
                                   &feature_config, NULL, 0);
        bool stencil = depth && choose_config(backend, backend->max_major,
                                              24, 8, &feature_config, NULL, 0);
        if ((backend->context_capabilities & LUCENT_RETRO_HW_GLES2) &&
                backend->max_major >= 3) {
            depth = depth && choose_config(backend, 2, 16, 0,
                                           &feature_config, NULL, 0);
            stencil = stencil && choose_config(backend, 2, 24, 8,
                                               &feature_config, NULL, 0);
        }
        if (depth) backend->feature_capabilities |= LUCENT_RETRO_HW_DEPTH;
        if (stencil) backend->feature_capabilities |= LUCENT_RETRO_HW_STENCIL;
    }
    /* RETRO_HW_RENDER's cache_context flag is a lifecycle policy request, not
     * a demand that an EGLContext survive an unrecoverable Android surface or
     * driver loss. Lucent keeps the in-window Surface and its EGLContext alive
     * for the entire foreground game session. On an actual Surface replacement
     * it performs the required ordered context_destroy -> EGL teardown -> new
     * EGL context -> context_reset transition; on EGL_CONTEXT_LOST it omits the
     * unsafe destroy callback and resets the core after recovery. That is the
     * complete libretro contract used by GLideN64/Mupen64Plus-Next, so advertise
     * cache_context while continuing to reject debug/shared-context requests
     * that the backend does not implement. */
    backend->feature_capabilities |= LUCENT_RETRO_HW_CACHE_CONTEXT;
    return backend;
}

bool lucent_android_gles_get_host_options(
        lucent_android_gles_backend *backend,
        lucent_retro_hw_options *options,
        char *error, size_t error_size) {
    if (!backend || !options || backend->display == EGL_NO_DISPLAY ||
            backend->host || backend->surface != EGL_NO_SURFACE) {
        set_error(error, error_size, "idle initialized GLES backend is required");
        return false;
    }
    memset(options, 0, sizeof(*options));
    options->struct_size = sizeof(*options);
    options->api_version = LUCENT_RETRO_HW_OPTIONS_VERSION;
    options->context_capabilities = backend->context_capabilities;
    options->preferred_context =
            (backend->context_capabilities & LUCENT_RETRO_HW_GLES_VERSION) ?
                    LUCENT_RETRO_HW_GLES_VERSION :
            (backend->context_capabilities & LUCENT_RETRO_HW_GLES3) ?
                    LUCENT_RETRO_HW_GLES3 : LUCENT_RETRO_HW_GLES2;
    /* A matching depth/stencil config is required again during attach. */
    options->feature_capabilities = backend->feature_capabilities;
    options->max_gles_major = backend->max_major;
    options->max_gles_minor = backend->max_minor;
    options->userdata = backend;
    options->get_current_framebuffer = current_framebuffer;
    options->get_proc_address = get_proc_address;
    return true;
}

bool lucent_android_gles_set_presentation_policy(
        lucent_android_gles_backend *backend,
        unsigned policy,
        char *error, size_t error_size) {
    if (!backend || backend->host || backend->surface != EGL_NO_SURFACE) {
        set_error(error, error_size,
                  "presentation policy requires an idle GLES backend");
        return false;
    }
    if (policy != LUCENT_ANDROID_GLES_PRESENT_AUTO &&
            policy != LUCENT_ANDROID_GLES_PRESENT_FRONTEND_FBO &&
            policy != LUCENT_ANDROID_GLES_PRESENT_DIRECT_WINDOW) {
        set_error(error, error_size, "unknown GLES presentation policy %u", policy);
        return false;
    }
    backend->configured_presentation_path = policy;
    backend->presentation_path = policy;
    return true;
}

bool lucent_android_gles_attach(
        lucent_android_gles_backend *backend,
        lucent_retro_host *host,
        void *native_window,
        char *error, size_t error_size) {
    lucent_retro_hw_info info;
    EGLint visual_id = 0;
    unsigned major;
    unsigned minor;
    unsigned depth;
    unsigned stencil;
    if (!claim_render_thread(backend, error, error_size) || !host || !native_window ||
            backend->host || backend->surface != EGL_NO_SURFACE) {
        if (backend && (backend->host || backend->surface != EGL_NO_SURFACE))
            set_error(error, error_size, "GLES backend already has an attached surface");
        else if (backend && (!host || !native_window))
            set_error(error, error_size, "host and ANativeWindow are required");
        return false;
    }
    if (!lucent_retro_get_hw_info(host, &info) || !info.negotiated ||
            info.context_ready ||
            (info.context_type != RETRO_HW_CONTEXT_OPENGLES2 &&
             info.context_type != RETRO_HW_CONTEXT_OPENGLES3 &&
             info.context_type != RETRO_HW_CONTEXT_OPENGLES_VERSION)) {
        set_error(error, error_size, "loaded core did not negotiate an unattached GLES context");
        return false;
    }
    major = info.context_type == RETRO_HW_CONTEXT_OPENGLES2 ? 2 :
            (info.version_major ? info.version_major : 3);
    minor = info.context_type == RETRO_HW_CONTEXT_OPENGLES_VERSION ?
            info.version_minor : 0;
    if (major > backend->max_major ||
            (major == backend->max_major && minor > backend->max_minor)) {
        set_error(error, error_size, "core requested GLES %u.%u beyond probed %u.%u",
                  major, minor, backend->max_major, backend->max_minor);
        return false;
    }
    depth = info.depth ? (info.stencil ? 24 : 16) : 0;
    stencil = info.stencil ? 8 : 0;
    if (!choose_config(backend, major, depth, stencil, &backend->config,
                       error, error_size)) return false;
    if (!eglGetConfigAttrib(backend->display, backend->config,
                            EGL_NATIVE_VISUAL_ID, &visual_id)) {
        set_error(error, error_size, "cannot query Android EGL visual (0x%x)",
                  eglGetError());
        return false;
    }
    backend->window = (ANativeWindow *)native_window;
    ANativeWindow_acquire(backend->window);
    if (ANativeWindow_setBuffersGeometry(backend->window, 0, 0, visual_id) != 0) {
        set_error(error, error_size, "ANativeWindow rejected EGL visual %d", visual_id);
        goto fail;
    }
    backend->surface = eglCreateWindowSurface(backend->display, backend->config,
                                              backend->window, NULL);
    backend->context = create_context(backend->display, backend->config,
                                      major, minor);
    if (backend->surface == EGL_NO_SURFACE || backend->context == EGL_NO_CONTEXT ||
            !eglMakeCurrent(backend->display, backend->surface, backend->surface,
                            backend->context)) {
        set_error(error, error_size, "cannot create/make-current Android GLES %u.%u (0x%x)",
                  major, minor, eglGetError());
        goto fail;
    }
    backend->host = host;
    parse_gles_version((const char *)glGetString(GL_VERSION), major,
                       &backend->active_major, &backend->active_minor);
    backend->depth_bits = depth;
    backend->stencil_bits = stencil;
    backend->presented_sequence = info.frame_sequence;
    if (backend->configured_presentation_path !=
            LUCENT_GLES_PRESENTATION_DIRECT_WINDOW &&
            !create_framebuffer(
                    backend, host,
                    (unsigned)ANativeWindow_getWidth(backend->window),
                    (unsigned)ANativeWindow_getHeight(backend->window),
                    error, error_size)) goto fail;
    if (!lucent_retro_supply_output_size(
            host, (unsigned)ANativeWindow_getWidth(backend->window),
            (unsigned)ANativeWindow_getHeight(backend->window),
            error, error_size)) goto fail;
    if (!lucent_retro_hw_context_reset(host, error, error_size)) goto fail;
    if (!lucent_retro_supply_frontend_framebuffer(
            host, backend->framebuffer, error, error_size)) goto fail;
    __android_log_print(ANDROID_LOG_INFO, "LucentGlesBackend",
            "hardware context reset complete type=%u GLES=%u.%u depth=%u stencil=%u "
            "cache=%u presentation=%u surface=%dx%d",
            info.context_type, backend->active_major, backend->active_minor,
            backend->depth_bits, backend->stencil_bits,
            info.cache_context ? 1u : 0u, backend->presentation_path,
            ANativeWindow_getWidth(backend->window),
            ANativeWindow_getHeight(backend->window));
    return true;
fail:
    backend->host = NULL;
    delete_framebuffer(backend);
    eglMakeCurrent(backend->display, EGL_NO_SURFACE, EGL_NO_SURFACE,
                   EGL_NO_CONTEXT);
    if (backend->context != EGL_NO_CONTEXT)
        eglDestroyContext(backend->display, backend->context);
    if (backend->surface != EGL_NO_SURFACE)
        eglDestroySurface(backend->display, backend->surface);
    backend->context = EGL_NO_CONTEXT;
    backend->surface = EGL_NO_SURFACE;
    if (backend->window) ANativeWindow_release(backend->window);
    backend->window = NULL;
    backend->active_major = backend->active_minor = 0;
    return false;
}

bool lucent_android_gles_make_current(
        lucent_android_gles_backend *backend,
        char *error, size_t error_size) {
    if (!claim_render_thread(backend, error, error_size) || !backend->host ||
            backend->surface == EGL_NO_SURFACE || backend->context == EGL_NO_CONTEXT) {
        if (backend && !backend->host)
            set_error(error, error_size, "GLES surface is not attached");
        return false;
    }
    if (!eglMakeCurrent(backend->display, backend->surface, backend->surface,
                        backend->context)) {
        EGLint failure = eglGetError();
        if (failure == EGL_CONTEXT_LOST) {
            lucent_retro_hw_context_lost(backend->host, NULL, 0);
            set_error(error, error_size,
                      "Android EGL context lost while making GLES current");
        } else {
            set_error(error, error_size,
                      "cannot make Android GLES context current (0x%x)", failure);
        }
        return false;
    }
    return true;
}

bool lucent_android_gles_present_if_ready(
        lucent_android_gles_backend *backend,
        bool *presented,
        char *error, size_t error_size) {
    lucent_retro_hw_info info;
    unsigned source_width;
    unsigned source_height;
    GLint core_viewport[4] = {0, 0, 0, 0};
    EGLint failure;
    if (presented) *presented = false;
    if (!presented || !lucent_android_gles_make_current(backend, error, error_size))
        return false;
    if (!lucent_retro_get_hw_info(backend->host, &info) || !info.context_ready) {
        set_error(error, error_size, "core hardware context is not ready");
        return false;
    }
    if (info.frame_sequence == backend->presented_sequence) return true;
    log_framebuffer_pixels(backend, info.frame_sequence);
    if (backend->presentation_path == LUCENT_GLES_PRESENTATION_UNKNOWN) {
        glBindFramebuffer(GL_READ_FRAMEBUFFER, backend->framebuffer);
        backend->presentation_path = frontend_framebuffer_was_rendered(backend) ?
                LUCENT_GLES_PRESENTATION_FRONTEND_FBO :
                LUCENT_GLES_PRESENTATION_DIRECT_WINDOW;
    }
    glGetIntegerv(GL_VIEWPORT, core_viewport);
    /* A hardware core's video callback dimensions are not uniformly defined.
     * Flycast reports the actual rendered target, while PPSSPP reports the
     * guest's logical 480x272 image even after rendering its final image into
     * the frontend-provided 1920x1080 target.  Treat a substantially smaller
     * callback size as logical geometry and blit the complete frontend target;
     * otherwise retain the exact reported dimensions (important for cores
     * whose internal render target is deliberately smaller than the backing
     * texture). */
    source_width = info.frame_width && backend->framebuffer_width &&
            info.frame_width <= backend->framebuffer_width &&
            info.frame_width * 2u >= backend->content_width ?
            info.frame_width : backend->content_width;
    source_height = info.frame_height && backend->framebuffer_height &&
            info.frame_height <= backend->framebuffer_height &&
            info.frame_height * 2u >= backend->content_height ?
            info.frame_height : backend->content_height;
    /* Integer-scaled hardware cores can report logical callback geometry
     * while drawing a larger image into the lower-left of the frontend FBO.
     * Dolphin at 2x, for example, reports 640x528 but leaves a 1280x1056 GL
     * viewport. Blitting the complete 1920x1080 allocation includes unwritten
     * padding and clips the game's right edge. Prefer the actual post-run
     * viewport when it is a valid origin-anchored region of our FBO. */
    if (backend->presentation_path == LUCENT_GLES_PRESENTATION_FRONTEND_FBO &&
            core_viewport[0] == 0 && core_viewport[1] == 0 &&
            core_viewport[2] > 0 && core_viewport[3] > 0 &&
            (unsigned)core_viewport[2] <= backend->framebuffer_width &&
            (unsigned)core_viewport[3] <= backend->framebuffer_height &&
            (!info.frame_width || (unsigned)core_viewport[2] >= info.frame_width) &&
            (!info.frame_height || (unsigned)core_viewport[3] >= info.frame_height)) {
        source_width = (unsigned)core_viewport[2];
        source_height = (unsigned)core_viewport[3];
    }
    if (!backend->presentation_geometry_logged) {
        bool source_padding = backend->presentation_path ==
                LUCENT_GLES_PRESENTATION_FRONTEND_FBO &&
                core_viewport[0] == 0 && core_viewport[1] == 0 &&
                core_viewport[2] > 0 && core_viewport[3] > 0 &&
                (source_width > (unsigned)core_viewport[2] ||
                 source_height > (unsigned)core_viewport[3]);
        __android_log_print(ANDROID_LOG_INFO, "LucentGlesBackend",
                "presentation geometry source=%ux%u viewport=%d,%d,%dx%d "
                "fbo=%ux%u destination=%u,%u,%ux%u sourcePadding=%u",
                source_width, source_height, core_viewport[0], core_viewport[1],
                core_viewport[2], core_viewport[3], backend->framebuffer_width,
                backend->framebuffer_height, backend->destination_x,
                backend->destination_y, backend->destination_width,
                backend->destination_height, source_padding ? 1u : 0u);
        backend->presentation_geometry_logged = true;
    }
    if (backend->presentation_path == LUCENT_GLES_PRESENTATION_FRONTEND_FBO) {
        GLfloat prior_clear[4];
        GLboolean prior_mask[4];
        GLboolean scissor_enabled;
        GLenum read_status;
        GLenum draw_status;
        GLenum preexisting_error = GL_NO_ERROR;
        GLenum blit_error;
        GLenum pending_error;
        GLubyte source_left[4] = {0, 0, 0, 0};
        GLubyte source_center[4] = {0, 0, 0, 0};
        GLubyte source_right[4] = {0, 0, 0, 0};

        /* Dolphin's OGL backend deliberately leaves scissor testing enabled.
         * glBlitFramebuffer obeys that scissor, so presenting without
         * neutralizing it clips the 1440-wide destination at the core's
         * 1280px backbuffer boundary and exposes uninitialized window rows.
         * Clear all of framebuffer zero and disable the scissor only for the
         * ownership handoff, then restore every state value we touched. */
        glGetFloatv(GL_COLOR_CLEAR_VALUE, prior_clear);
        glGetBooleanv(GL_COLOR_WRITEMASK, prior_mask);
        scissor_enabled = glIsEnabled(GL_SCISSOR_TEST);
        while ((pending_error = glGetError()) != GL_NO_ERROR)
            preexisting_error = pending_error;
        if (scissor_enabled) glDisable(GL_SCISSOR_TEST);
        glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, backend->framebuffer);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
        read_status = glCheckFramebufferStatus(GL_READ_FRAMEBUFFER);
        draw_status = glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER);
        if (backend->framebuffer_blit_diagnostics < 3u) {
            GLint sample_y = source_height > 1u ?
                    (GLint)(source_height / 2u) : 0;
            glReadPixels(1, sample_y, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE,
                         source_left);
            glReadPixels((GLint)(source_width / 2u), sample_y, 1, 1,
                         GL_RGBA, GL_UNSIGNED_BYTE, source_center);
            glReadPixels(source_width > 1u ? (GLint)source_width - 2 : 0,
                         sample_y, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE,
                         source_right);
        }
        glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT);
        glBlitFramebuffer(0, 0,
                          (GLint)source_width,
                          (GLint)source_height,
                          (GLint)backend->destination_x,
                          (GLint)backend->destination_y,
                          (GLint)(backend->destination_x +
                                  backend->destination_width),
                          (GLint)(backend->destination_y +
                                  backend->destination_height),
                          GL_COLOR_BUFFER_BIT, GL_LINEAR);
        blit_error = glGetError();
        glClearColor(prior_clear[0], prior_clear[1], prior_clear[2], prior_clear[3]);
        glColorMask(prior_mask[0], prior_mask[1], prior_mask[2], prior_mask[3]);
        if (scissor_enabled) glEnable(GL_SCISSOR_TEST);
        if (read_status != GL_FRAMEBUFFER_COMPLETE ||
                draw_status != GL_FRAMEBUFFER_COMPLETE ||
                blit_error != GL_NO_ERROR) {
            __android_log_print(ANDROID_LOG_ERROR, "LucentGlesBackend",
                    "frontend blit failed readStatus=0x%x drawStatus=0x%x "
                    "glError=0x%x scissorWasEnabled=%u",
                    read_status, draw_status, blit_error,
                    scissor_enabled ? 1u : 0u);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            set_error(error, error_size,
                    "frontend FBO presentation failed (read=0x%x draw=0x%x gl=0x%x)",
                    read_status, draw_status, blit_error);
            return false;
        }
        if (backend->framebuffer_blit_diagnostics < 3u) {
            __android_log_print(ANDROID_LOG_INFO, "LucentGlesBackend",
                    "frontend blit complete sequence=%llu scissorWasEnabled=%u "
                    "readStatus=0x%x drawStatus=0x%x preexistingGlError=0x%x "
                    "glError=0x%x "
                    "sourceLeft=%u,%u,%u,%u sourceCenter=%u,%u,%u,%u "
                    "sourceRight=%u,%u,%u,%u",
                    (unsigned long long)info.frame_sequence,
                    scissor_enabled ? 1u : 0u, read_status, draw_status,
                    preexisting_error, blit_error,
                    source_left[0], source_left[1], source_left[2], source_left[3],
                    source_center[0], source_center[1], source_center[2],
                    source_center[3], source_right[0], source_right[1],
                    source_right[2], source_right[3]);
            backend->framebuffer_blit_diagnostics++;
        }
    }
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    force_opaque_surface_alpha();
    if (!eglSwapBuffers(backend->display, backend->surface)) {
        failure = eglGetError();
        if (failure == EGL_CONTEXT_LOST) {
            lucent_retro_hw_context_lost(backend->host, NULL, 0);
            set_error(error, error_size, "Android EGL context lost during swap");
        } else {
            set_error(error, error_size, "Android EGL swap failed (0x%x)", failure);
        }
        return false;
    }
    backend->presented_sequence = info.frame_sequence;
    *presented = true;
    return true;
}

bool lucent_android_gles_detach(
        lucent_android_gles_backend *backend,
        char *error, size_t error_size) {
    bool ok = true;
    if (!claim_render_thread(backend, error, error_size)) return false;
    if (!backend->host) return true;
    if (!lucent_android_gles_make_current(backend, error, error_size)) {
        lucent_retro_hw_context_lost(backend->host, NULL, 0);
        abandon_framebuffer(backend);
        ok = false;
    } else {
        if (!lucent_retro_hw_context_destroy(backend->host, error, error_size))
            ok = false;
        if (!lucent_retro_supply_frontend_framebuffer(
                backend->host, 0, error, error_size)) ok = false;
    }
    if (eglGetCurrentContext() == backend->context)
        delete_framebuffer(backend);
    eglMakeCurrent(backend->display, EGL_NO_SURFACE, EGL_NO_SURFACE,
                   EGL_NO_CONTEXT);
    if (backend->surface != EGL_NO_SURFACE)
        eglDestroySurface(backend->display, backend->surface);
    if (backend->context != EGL_NO_CONTEXT)
        eglDestroyContext(backend->display, backend->context);
    backend->surface = EGL_NO_SURFACE;
    backend->context = EGL_NO_CONTEXT;
    backend->host = NULL;
    if (backend->window) ANativeWindow_release(backend->window);
    backend->window = NULL;
    backend->config = NULL;
    backend->active_major = backend->active_minor = 0;
    backend->depth_bits = backend->stencil_bits = 0;
    return ok;
}

bool lucent_android_gles_get_info(
        const lucent_android_gles_backend *backend,
        lucent_android_gles_info *info) {
    if (!backend || !info) return false;
    memset(info, 0, sizeof(*info));
    info->display_ready = backend->display != EGL_NO_DISPLAY;
    info->surface_attached = backend->host &&
            backend->surface != EGL_NO_SURFACE &&
            backend->context != EGL_NO_CONTEXT;
    info->max_gles_major = backend->max_major;
    info->max_gles_minor = backend->max_minor;
    info->active_gles_major = backend->active_major;
    info->active_gles_minor = backend->active_minor;
    info->depth_bits = backend->depth_bits;
    info->stencil_bits = backend->stencil_bits;
    info->presented_sequence = backend->presented_sequence;
    return true;
}

void lucent_android_gles_destroy(lucent_android_gles_backend *backend) {
    if (!backend) return;
    /* A wrong-thread destructor cannot safely invalidate callback userdata or
     * terminate a display that is current elsewhere. Fail closed by retaining
     * the backend; integration must return to the render thread and detach. */
    if (backend->host && !on_render_thread(backend)) return;
    if (backend->host) lucent_android_gles_detach(backend, NULL, 0);
    if (backend->display != EGL_NO_DISPLAY) eglTerminate(backend->display);
    free(backend);
}
