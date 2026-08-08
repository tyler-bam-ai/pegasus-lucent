#define _XOPEN_SOURCE 700

#include "include/lucent_libretro_host.h"
#include "include/libretro.h"

#include <dlfcn.h>
#include <errno.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#if defined(__ANDROID__)
#include <android/log.h>
#endif

#define MAX_PORTS 8
#define MAX_JOYPAD_BUTTONS 16
#define MAX_ANALOG_INDEXES 3
#define MAX_ANALOG_IDS 16
#define AUDIO_RING_SAMPLES (262144u)
#define MAX_VIDEO_DIMENSION 8192u
#define MAX_VIDEO_PITCH_BYTES (128u * 1024u)
#define MAX_VIDEO_FRAME_BYTES (128u * 1024u * 1024u)
#define MAX_GAME_BYTES (1024ull * 1024ull * 1024ull)
#define MAX_STATE_BYTES (512u * 1024u * 1024u)
#define MAX_SAVE_RAM_BYTES (64u * 1024u * 1024u)
#define MAX_CORE_VARIABLES 256u
#define MAX_CORE_VARIABLE_KEY_BYTES 256u
#define MAX_CORE_VARIABLE_VALUE_BYTES 4096u

typedef struct lucent_core_variable {
    char *key;
    char *value;
} lucent_core_variable;

typedef void (*fn_void)(void);
typedef unsigned (*fn_api_version)(void);
typedef void (*fn_get_system_info)(struct retro_system_info *);
typedef void (*fn_get_system_av_info)(struct retro_system_av_info *);
typedef void (*fn_set_environment)(retro_environment_t);
typedef void (*fn_set_video_refresh)(retro_video_refresh_t);
typedef void (*fn_set_audio_sample)(retro_audio_sample_t);
typedef void (*fn_set_audio_sample_batch)(retro_audio_sample_batch_t);
typedef void (*fn_set_input_poll)(retro_input_poll_t);
typedef void (*fn_set_input_state)(retro_input_state_t);
typedef void (*fn_set_controller_port_device)(unsigned, unsigned);
typedef size_t (*fn_serialize_size)(void);
typedef bool (*fn_serialize)(void *, size_t);
typedef bool (*fn_unserialize)(const void *, size_t);
typedef void (*fn_cheat_set)(unsigned, bool, const char *);
typedef bool (*fn_load_game)(const struct retro_game_info *);
typedef bool (*fn_load_game_special)(unsigned, const struct retro_game_info *, size_t);
typedef bool (*fn_prepare_exit)(void);
typedef unsigned (*fn_get_region)(void);
typedef void *(*fn_get_memory_data)(unsigned);
typedef size_t (*fn_get_memory_size)(unsigned);
typedef void (*fn_set_android_java_vm)(void *);
typedef void (*fn_set_output_size)(unsigned, unsigned);
typedef void (*fn_set_frontend_framebuffer)(unsigned);

struct lucent_retro_host {
    void *library;
    char *core_path;
    char *system_directory;
    char *save_directory;
    char *game_bytes;
    size_t game_size;
    bool initialized;
    bool game_loaded;
    bool paused;
    bool support_no_game;
    bool hw_configured;
    bool hw_negotiated;
    bool hw_context_ready;
    bool hw_shared_requested;
    lucent_retro_hw_options hw_options;
    struct retro_hw_render_callback hw_callback;
    const struct retro_hw_render_context_negotiation_interface
            *hw_context_negotiation_interface;
    uint64_t hw_frame_sequence;
    unsigned hw_frame_width;
    unsigned hw_frame_height;
    enum retro_pixel_format pixel_format;
    struct retro_system_info system_info;
    struct retro_system_av_info current_av_info;
    bool has_av_info;
    uint8_t *video_bytes;
    size_t video_size;
    unsigned video_width;
    unsigned video_height;
    size_t video_pitch;
    uint64_t video_sequence;
    int16_t *audio_ring;
    size_t audio_read;
    size_t audio_count;
    double audio_input_rate;
    double audio_output_rate;
    double audio_resample_accumulator;
    int64_t audio_resample_left_sum;
    int64_t audio_resample_right_sum;
    size_t audio_resample_sample_count;
    uint32_t joypad_mask[MAX_PORTS];
    int16_t analog_state[MAX_PORTS][MAX_ANALOG_INDEXES][MAX_ANALOG_IDS];
    int16_t pointer_x[MAX_PORTS];
    int16_t pointer_y[MAX_PORTS];
    bool pointer_pressed[MAX_PORTS];
    lucent_core_variable core_variables[MAX_CORE_VARIABLES];
    size_t core_variable_count;

    fn_void init;
    fn_void deinit;
    fn_api_version api_version;
    fn_get_system_info get_system_info;
    fn_get_system_av_info get_system_av_info;
    fn_set_environment set_environment;
    fn_set_video_refresh set_video_refresh;
    fn_set_audio_sample set_audio_sample;
    fn_set_audio_sample_batch set_audio_sample_batch;
    fn_set_input_poll set_input_poll;
    fn_set_input_state set_input_state;
    fn_set_controller_port_device set_controller_port_device;
    fn_void reset;
    fn_void run;
    fn_serialize_size serialize_size;
    fn_serialize serialize;
    fn_unserialize unserialize;
    fn_void cheat_reset;
    fn_cheat_set cheat_set;
    fn_load_game load_game;
    fn_load_game_special load_game_special;
    fn_void unload_game;
    fn_prepare_exit prepare_exit;
    fn_get_region get_region;
    fn_get_memory_data get_memory_data;
    fn_get_memory_size get_memory_size;
};

/* A single in-process game session is intentional for the initial host. Core
 * callbacks have no userdata pointer, so the active host owns their context. */
static lucent_retro_host *active_host;
static pthread_mutex_t host_mutex;
static pthread_once_t host_mutex_once = PTHREAD_ONCE_INIT;

static void initialize_host_mutex(void) {
    pthread_mutexattr_t attributes;
    pthread_mutexattr_init(&attributes);
    pthread_mutexattr_settype(&attributes, PTHREAD_MUTEX_RECURSIVE);
    pthread_mutex_init(&host_mutex, &attributes);
    pthread_mutexattr_destroy(&attributes);
}

static void lock_host(void) {
    pthread_once(&host_mutex_once, initialize_host_mutex);
    pthread_mutex_lock(&host_mutex);
}

static void unlock_host(void) { pthread_mutex_unlock(&host_mutex); }

#define RETURN_UNLOCKED(value) do { unlock_host(); return (value); } while (0)

static void set_error(char *buffer, size_t size, const char *format, ...) {
    va_list args;
    if (!buffer || !size) return;
    va_start(args, format);
    vsnprintf(buffer, size, format, args);
    va_end(args);
}

/* A number of otherwise well-behaved cores, including PPSSPP, accept a
 * missing log interface but later call the callback unconditionally.  Supply
 * the standard libretro interface and keep it deliberately frontend-neutral.
 * Android native stderr is not reliably surfaced by logcat on production
 * builds, so route core diagnostics through the platform logger there. */
static void core_log_callback(enum retro_log_level level,
                              const char *format, ...) {
    va_list args;
    if (!format) return;
    va_start(args, format);
#if defined(__ANDROID__)
    {
        char message[4096];
        size_t i;
        int priority = ANDROID_LOG_INFO;
        if (level == RETRO_LOG_DEBUG) priority = ANDROID_LOG_DEBUG;
        else if (level == RETRO_LOG_WARN) priority = ANDROID_LOG_WARN;
        else if (level == RETRO_LOG_ERROR) priority = ANDROID_LOG_ERROR;
        vsnprintf(message, sizeof(message), format, args);
        /* Core metadata occasionally contains raw cartridge bytes (for
         * example an invalid SNES game-code field). Android logcat transports
         * those bytes verbatim, which can corrupt UTF-8 consumers and obscure
         * the actual runtime evidence. Keep line controls and printable ASCII;
         * replace opaque bytes only in diagnostics, never in emulated data. */
        for (i = 0; message[i] != '\0'; i++) {
            unsigned char value = (unsigned char)message[i];
            if ((value < 0x20u && value != '\n' && value != '\r' &&
                    value != '\t') || value >= 0x7fu) message[i] = '?';
        }
        __android_log_print(priority, "LucentLibretroCore", "%s", message);
    }
#else
    (void)level;
    vfprintf(stderr, format, args);
#endif
    va_end(args);
}

static bool path_is_inside(const char *path, const char *root) {
    size_t length = strlen(root);
    return strncmp(path, root, length) == 0 &&
           (root[length - 1] == '/' || path[length] == '/');
}

static retro_proc_address_t hardware_get_proc_address(const char *symbol) {
    if (!active_host || !active_host->hw_negotiated ||
            !active_host->hw_context_ready ||
            !active_host->hw_options.get_proc_address) return NULL;
    return active_host->hw_options.get_proc_address(
            active_host->hw_options.userdata, symbol);
}

static uintptr_t hardware_get_current_framebuffer(void) {
    if (!active_host || !active_host->hw_negotiated ||
            !active_host->hw_context_ready ||
            !active_host->hw_options.get_current_framebuffer) return 0;
    return active_host->hw_options.get_current_framebuffer(
            active_host->hw_options.userdata);
}

static uint32_t context_capability(enum retro_hw_context_type type) {
    switch (type) {
        case RETRO_HW_CONTEXT_OPENGLES2: return LUCENT_RETRO_HW_GLES2;
        case RETRO_HW_CONTEXT_OPENGLES3: return LUCENT_RETRO_HW_GLES3;
        case RETRO_HW_CONTEXT_OPENGLES_VERSION: return LUCENT_RETRO_HW_GLES_VERSION;
        case RETRO_HW_CONTEXT_VULKAN: return LUCENT_RETRO_HW_VULKAN;
        default: return 0;
    }
}

static enum retro_hw_context_type preferred_context(const lucent_retro_host *host) {
    switch (host->hw_options.preferred_context) {
        case LUCENT_RETRO_HW_GLES2: return RETRO_HW_CONTEXT_OPENGLES2;
        case LUCENT_RETRO_HW_GLES3: return RETRO_HW_CONTEXT_OPENGLES3;
        case LUCENT_RETRO_HW_GLES_VERSION: return RETRO_HW_CONTEXT_OPENGLES_VERSION;
        case LUCENT_RETRO_HW_VULKAN: return RETRO_HW_CONTEXT_VULKAN;
        default: return RETRO_HW_CONTEXT_NONE;
    }
}

static const char *hardware_context_name(enum retro_hw_context_type type) {
    switch (type) {
        case RETRO_HW_CONTEXT_OPENGLES2: return "OPENGLES2";
        case RETRO_HW_CONTEXT_OPENGLES3: return "OPENGLES3";
        case RETRO_HW_CONTEXT_OPENGLES_VERSION: return "OPENGLES_VERSION";
        case RETRO_HW_CONTEXT_VULKAN: return "VULKAN";
        default: return "UNSUPPORTED";
    }
}

static const char *hardware_request_rejection(
        lucent_retro_host *host, const struct retro_hw_render_callback *request) {
    uint32_t capability;
    uint32_t features;
    if (!host->hw_configured || host->hw_negotiated || !request ||
            !request->context_reset) return "host state or context_reset callback is invalid";
    features = host->hw_options.feature_capabilities;
    capability = context_capability(request->context_type);
    if (!capability || !(host->hw_options.context_capabilities & capability))
        return "requested graphics context is unavailable";
    if (request->context_type == RETRO_HW_CONTEXT_OPENGLES_VERSION) {
        if (request->version_major < 3 ||
                (request->version_major == 3 && request->version_minor < 1) ||
                request->version_major > host->hw_options.max_gles_major ||
                (request->version_major == host->hw_options.max_gles_major &&
                 request->version_minor > host->hw_options.max_gles_minor))
            return "requested GLES version is unavailable";
    }
    if (request->context_type != RETRO_HW_CONTEXT_VULKAN &&
            (!host->hw_options.get_current_framebuffer ||
             !host->hw_options.get_proc_address))
        return "GLES framebuffer/proc-address hooks are unavailable";
    if (request->depth && !(features & LUCENT_RETRO_HW_DEPTH))
        return "requested depth buffer is unavailable";
    if (request->stencil && !(features & LUCENT_RETRO_HW_STENCIL))
        return "requested stencil buffer is unavailable";
    if (request->stencil && !request->depth)
        return "stencil without depth is invalid";
    if (request->cache_context && !(features & LUCENT_RETRO_HW_CACHE_CONTEXT))
        return "requested cached-context lifecycle is unavailable";
    if (request->debug_context && !(features & LUCENT_RETRO_HW_DEBUG_CONTEXT))
        return "requested debug context is unavailable";
    return NULL;
}

static bool set_hardware_render(struct retro_hw_render_callback *request) {
    const char *rejection;
    if (!active_host) return false;
    rejection = hardware_request_rejection(active_host, request);
    if (rejection) {
        core_log_callback(RETRO_LOG_ERROR,
                "Lucent rejected hardware render request: %s "
                "(type=%u version=%u.%u depth=%u stencil=%u cache=%u debug=%u)\n",
                rejection, request ? (unsigned)request->context_type : 0u,
                request ? request->version_major : 0u,
                request ? request->version_minor : 0u,
                request && request->depth ? 1u : 0u,
                request && request->stencil ? 1u : 0u,
                request && request->cache_context ? 1u : 0u,
                request && request->debug_context ? 1u : 0u);
        return false;
    }
    active_host->hw_callback = *request;
    active_host->hw_callback.get_current_framebuffer =
            hardware_get_current_framebuffer;
    active_host->hw_callback.get_proc_address = hardware_get_proc_address;
    *request = active_host->hw_callback;
    active_host->hw_negotiated = true;
    active_host->hw_context_ready = false;
    active_host->hw_frame_sequence = 0;
    core_log_callback(RETRO_LOG_INFO,
            "hardware render request accepted context=%s type=%u "
            "version=%u.%u depth=%u stencil=%u cacheContext=%u debug=%u\n",
            hardware_context_name(request->context_type),
            (unsigned)request->context_type, request->version_major,
            request->version_minor, request->depth ? 1u : 0u,
            request->stencil ? 1u : 0u, request->cache_context ? 1u : 0u,
            request->debug_context ? 1u : 0u);
    return true;
}

static const char *find_core_variable(const lucent_retro_host *host,
                                      const char *key) {
    size_t index;
    if (!host || !key) return NULL;
    for (index = 0; index < host->core_variable_count; index++)
        if (strcmp(host->core_variables[index].key, key) == 0)
            return host->core_variables[index].value;
    return NULL;
}

static const char *find_option_token(const char *options, const char *wanted,
                                     size_t *size) {
    const char *start = options;
    size_t wanted_size;
    if (!options || !wanted || !size) return NULL;
    wanted_size = strlen(wanted);
    while (*start) {
        const char *end = strchr(start, '|');
        size_t current_size = end ? (size_t)(end - start) : strlen(start);
        if (current_size == wanted_size &&
                memcmp(start, wanted, wanted_size) == 0) {
            *size = current_size;
            return start;
        }
        if (!end) break;
        start = end + 1;
    }
    return NULL;
}

/* Core-options-v0 declarations put the default first after "; ". Lucent has
 * no RetroArch-style core-options UI, so both software and hardware cores need
 * deterministic values instead of NULL. Engine-specific overrides are kept
 * here as a deliberately tiny, reviewed profile rather than exposing hundreds
 * of emulator knobs to the user. */
static bool register_core_variable_defaults(
        lucent_retro_host *host, const struct retro_variable *variables) {
    const struct retro_variable *variable;
    if (!host || !variables) return true;
    for (variable = variables; variable->key; variable++) {
        const char *options;
        const char *end;
        size_t key_size;
        size_t value_size;
        lucent_core_variable *entry;
        if (!variable->value || find_core_variable(host, variable->key)) continue;
        if (host->core_variable_count >= MAX_CORE_VARIABLES) return false;
        key_size = strlen(variable->key);
        options = strstr(variable->value, "; ");
        if (!options) return false;
        options += 2;
        end = strchr(options, '|');
        value_size = end ? (size_t)(end - options) : strlen(options);
        /* Qualification profile for PPSSPP's direct-to-default-framebuffer
         * GLES path.  4x plus the core's exact-16:9 crop produces a
         * 1920x1080 render target on the Thor instead of a 480x272 image in
         * the lower-left.  This stays private to the hardware route and does
         * not expose a core-options UI. */
        if (strcmp(variable->key, "ppsspp_internal_resolution") == 0) {
            const char *profile = find_option_token(
                    options, "1920x1088", &value_size);
            if (profile) options = profile;
        } else if (strcmp(variable->key, "ppsspp_cropto16x9") == 0) {
            const char *profile = find_option_token(
                    options, "enabled", &value_size);
            if (profile) options = profile;
        } else if (strcmp(variable->key, "reicast_internal_resolution") == 0) {
            /* The Thor's top display is 1080 pixels tall.  Flycast's native
             * Dreamcast aspect therefore maps to a 1440x1080 core target;
             * the Android GLES backend centers that target without stretch. */
            const char *profile = find_option_token(
                    options, "1440x1080", &value_size);
            if (profile) options = profile;
        } else if (strcmp(variable->key, "mupen64plus-43screensize") == 0) {
            /* Preserve the original 4:3 composition while rendering at the
             * Thor top panel's full 1080-pixel height. */
            const char *profile = find_option_token(
                    options, "1440x1080", &value_size);
            if (profile) options = profile;
        } else if (strcmp(variable->key, "mupen64plus-rdp-plugin") == 0) {
            const char *profile = find_option_token(
                    options, "gliden64", &value_size);
            if (profile) options = profile;
        } else if (strcmp(variable->key, "mupen64plus-rsp-plugin") == 0) {
            const char *profile = find_option_token(options, "hle", &value_size);
            if (profile) options = profile;
        } else if (strcmp(variable->key, "mupen64plus-ThreadedRenderer") == 0) {
            /* One EGL context stays owned by Lucent's in-window render loop;
             * the core must not create a competing threaded GL context. */
            const char *profile = find_option_token(options, "False", &value_size);
            if (profile) options = profile;
        } else if (strcmp(variable->key, "citra_graphics_api") == 0) {
            const char *profile = find_option_token(
                    options, "Vulkan", &value_size);
            if (profile) options = profile;
        } else if (strcmp(variable->key, "citra_resolution_factor") == 0) {
            /* Four times native is 1600x960 for the 3DS top screen, the
             * closest stable integer scale below the Thor's 1920x1080 panel. */
            const char *profile = find_option_token(options, "4", &value_size);
            if (profile) options = profile;
        } else if (strcmp(variable->key, "dolphin_efb_scale") == 0) {
            /* Two times native is 1280x1056, the highest integer EFB scale
             * that fits wholly inside the Thor's 1920x1080 frontend target.
             * A 3x EFB is 1584 pixels tall and left the bottom of the Android
             * frontend FBO unwritten on the maintained libretro core. */
            const char *profile = find_option_token(options, "2", &value_size);
            if (profile) options = profile;
        } else if (strcmp(variable->key, "dolphin_main_cpu_thread") == 0) {
            /* Dolphin's dual-core libretro frame pump deadlocks in-process.
             * Each retro_run does Core::DoFrameStep() and only then enters
             * FifoManager::RunGpuLoop() on this thread. Whenever the emulated
             * CPU produced no new field during the previous call, DoFrameStep
             * takes its Running branch and calls Core::SetState(Paused) ->
             * CPUManager::SetStepping(true), which blocks on
             * m_state_cpu_idle_cvar until the separate CPU thread goes idle.
             * That CPU thread reaches its idle point only after the GPU thread
             * drains the FIFO or answers a blocking AsyncRequests event, and
             * the GPU thread is this thread, still parked inside SetStepping.
             * Dolphin documents the hazard itself in CPUManager::Break():
             * "We'll deadlock if we synchronize, the CPU may block waiting for
             * our caller to finish."  Both threads then sleep forever with no
             * further core output, which is the GameCube/Wii freeze.
             * Single-core keeps emulation on this one render thread:
             * retro_run runs CPUManager::RunSingleFrame() directly, and
             * GetInitializedVideoGuard puts AsyncRequests in passthrough, so
             * no cross-thread handoff exists to deadlock on. */
            const char *profile = find_option_token(
                    options, "disabled", &value_size);
            if (profile) options = profile;
        } else if (strcmp(variable->key,
                          "dolphin_shader_compilation_mode") == 0) {
            /* Lucent owns one EGL context. Do not select an asynchronous mode
             * whose worker requires a second shared context on Android. */
            const char *profile = find_option_token(
                    options, "0", &value_size);
            if (profile) options = profile;
        } else if (strcmp(variable->key, "dolphin_wait_for_shaders") == 0) {
            /* Prefer a bounded compile pause to visibly missing/black objects
             * while a title's first shaders are created. */
            const char *profile = find_option_token(
                    options, "enabled", &value_size);
            if (profile) options = profile;
        } else if (strstr(host->core_path, "lucent_core_melonds_ds") &&
                strcmp(variable->key, "melonds_number_of_screen_layouts") == 0) {
            const char *profile = find_option_token(options, "1", &value_size);
            if (profile) options = profile;
        } else if (strstr(host->core_path, "lucent_core_melonds_ds") &&
                strcmp(variable->key, "melonds_screen_layout1") == 0) {
            const char *profile = find_option_token(
                    options, "top-bottom", &value_size);
            if (profile) options = profile;
        } else if (strstr(host->core_path, "lucent_core_melonds_ds") &&
                strcmp(variable->key, "melonds_touch_mode") == 0) {
            const char *profile = find_option_token(options, "touch", &value_size);
            if (profile) options = profile;
        } else if (strstr(host->core_path, "lucent_core_azahar") &&
                strcmp(variable->key, "citra_layout_option") == 0) {
            /* The Thor frontend splits this exact top-bottom composite across
             * its two physical displays. A single-screen core layout would
             * make the lower display duplicate or crop the wrong source. */
            const char *profile = find_option_token(options, "default", &value_size);
            if (profile) options = profile;
        } else if (strstr(host->core_path, "lucent_core_azahar") &&
                strcmp(variable->key, "citra_enable_touch_touchscreen") == 0) {
            const char *profile = find_option_token(options, "enabled", &value_size);
            if (profile) options = profile;
        } else if (strstr(host->core_path, "lucent_core_puae") &&
                strcmp(variable->key, "puae_kickstart") == 0) {
            /* PUAE contains its GPL-compatible AROS replacement ROM. Make it
             * the explicit baseline instead of probing for or silently using
             * an unaudited user Kickstart. CD32 remains separately gated on
             * user firmware by the Java qualification catalog. */
            const char *profile = find_option_token(options, "aros", &value_size);
            if (profile) options = profile;
        }
        if (!key_size || key_size >= MAX_CORE_VARIABLE_KEY_BYTES ||
                !value_size || value_size >= MAX_CORE_VARIABLE_VALUE_BYTES)
            return false;
        entry = &host->core_variables[host->core_variable_count];
        entry->key = (char *)malloc(key_size + 1);
        entry->value = (char *)malloc(value_size + 1);
        if (!entry->key || !entry->value) {
            free(entry->key);
            free(entry->value);
            entry->key = NULL;
            entry->value = NULL;
            return false;
        }
        memcpy(entry->key, variable->key, key_size + 1);
        memcpy(entry->value, options, value_size);
        entry->value[value_size] = '\0';
        host->core_variable_count++;
    }
    return true;
}

static bool environment_callback(unsigned command, void *data) {
    if (!active_host) return false;
    if (!data && command != RETRO_ENVIRONMENT_SET_HW_SHARED_CONTEXT) return false;
    switch (command) {
        case RETRO_ENVIRONMENT_GET_CAN_DUPE:
            *(bool *)data = true;
            return true;
        case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
            *(const char **)data = active_host->system_directory;
            return true;
        case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
            *(const char **)data = active_host->save_directory;
            return true;
        case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: {
            enum retro_pixel_format format = *(const enum retro_pixel_format *)data;
            if (format != RETRO_PIXEL_FORMAT_0RGB1555 &&
                    format != RETRO_PIXEL_FORMAT_RGB565 &&
                    format != RETRO_PIXEL_FORMAT_XRGB8888) return false;
            active_host->pixel_format = format;
            return true;
        }
        case RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME:
            active_host->support_no_game = *(const bool *)data;
            return true;
        case RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO:
            active_host->current_av_info = *(const struct retro_system_av_info *)data;
            active_host->audio_input_rate =
                    active_host->current_av_info.timing.sample_rate;
            active_host->audio_output_rate = active_host->audio_input_rate > 192000.0
                    ? 48000.0 : active_host->audio_input_rate;
            active_host->audio_resample_accumulator = 0.0;
            active_host->audio_resample_left_sum = 0;
            active_host->audio_resample_right_sum = 0;
            active_host->audio_resample_sample_count = 0;
            active_host->current_av_info.timing.sample_rate =
                    active_host->audio_output_rate;
            active_host->has_av_info = true;
            return true;
        case RETRO_ENVIRONMENT_SET_GEOMETRY:
            active_host->current_av_info.geometry = *(const struct retro_game_geometry *)data;
            active_host->has_av_info = true;
            return true;
        case RETRO_ENVIRONMENT_GET_VARIABLE:
            ((struct retro_variable *)data)->value = find_core_variable(
                    active_host, ((struct retro_variable *)data)->key);
            return true;
        case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
            *(bool *)data = false;
            return true;
        case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION:
            *(unsigned *)data = 0;
            return true;
        case RETRO_ENVIRONMENT_GET_INPUT_BITMASKS:
            return true;
        case RETRO_ENVIRONMENT_GET_LOG_INTERFACE:
            ((struct retro_log_callback *)data)->log = core_log_callback;
            return true;
        case RETRO_ENVIRONMENT_GET_PREFERRED_HW_RENDER:
            if (!active_host->hw_configured) return false;
            *(enum retro_hw_context_type *)data = preferred_context(active_host);
            return *(enum retro_hw_context_type *)data != RETRO_HW_CONTEXT_NONE;
        case RETRO_ENVIRONMENT_SET_HW_RENDER:
            return set_hardware_render((struct retro_hw_render_callback *)data);
        case RETRO_ENVIRONMENT_GET_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE_SUPPORT: {
            struct retro_hw_render_context_negotiation_interface *interface =
                    (struct retro_hw_render_context_negotiation_interface *)data;
            if (!active_host->hw_configured ||
                    !(active_host->hw_options.context_capabilities &
                      LUCENT_RETRO_HW_VULKAN) ||
                    interface->interface_type !=
                      RETRO_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE_VULKAN)
                return false;
            /* Lucent implements Vulkan negotiation v1. A v2 core may still
             * register its newer struct; only the v1 prefix is consumed. */
            interface->interface_version = 1;
            return true;
        }
        case RETRO_ENVIRONMENT_SET_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE: {
            const struct retro_hw_render_context_negotiation_interface *interface =
                    (const struct retro_hw_render_context_negotiation_interface *)data;
            if (!active_host->hw_configured ||
                    !(active_host->hw_options.context_capabilities &
                      LUCENT_RETRO_HW_VULKAN) ||
                    interface->interface_type !=
                      RETRO_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE_VULKAN ||
                    interface->interface_version < 1)
                return false;
            active_host->hw_context_negotiation_interface = interface;
            return true;
        }
        case RETRO_ENVIRONMENT_GET_HW_RENDER_INTERFACE:
            if (!active_host->hw_negotiated ||
                    active_host->hw_callback.context_type != RETRO_HW_CONTEXT_VULKAN ||
                    !active_host->hw_options.render_interface) return false;
            *(const struct retro_hw_render_interface **)data =
                    (const struct retro_hw_render_interface *)
                    active_host->hw_options.render_interface;
            return true;
        case RETRO_ENVIRONMENT_SET_HW_SHARED_CONTEXT:
            if (!active_host->hw_configured ||
                    !(active_host->hw_options.feature_capabilities &
                      LUCENT_RETRO_HW_SHARED_CONTEXT)) return false;
            active_host->hw_shared_requested = true;
            return true;
        case RETRO_ENVIRONMENT_SET_PERFORMANCE_LEVEL:
        case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
            /* These commands use unsigned/input-descriptor payloads, not
             * retro_variable arrays.  Treating them as option declarations
             * corrupts the ABI and made PPSSPP's first input descriptor look
             * like the pointer 0x100000000 on ARM64. */
            return true;
        case RETRO_ENVIRONMENT_SET_VARIABLES:
            return register_core_variable_defaults(
                    active_host, (const struct retro_variable *)data);
        case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO:
        case RETRO_ENVIRONMENT_SET_MEMORY_MAPS:
        case RETRO_ENVIRONMENT_SET_SUPPORT_ACHIEVEMENTS:
            return true;
        case RETRO_ENVIRONMENT_GET_VFS_INTERFACE:
            return false;
        default:
            return false;
    }
}

static void video_callback(const void *data, unsigned width, unsigned height,
                           size_t pitch) {
    size_t size;
    size_t bytes_per_pixel;
    uint8_t *replacement;
    if (!active_host || !data || !width || !height)
        return;
    if (data == RETRO_HW_FRAME_BUFFER_VALID) {
        if (!active_host->hw_negotiated || !active_host->hw_context_ready ||
                width > MAX_VIDEO_DIMENSION || height > MAX_VIDEO_DIMENSION) return;
        active_host->hw_frame_width = width;
        active_host->hw_frame_height = height;
        active_host->hw_frame_sequence++;
        return;
    }
    if (width > MAX_VIDEO_DIMENSION || height > MAX_VIDEO_DIMENSION ||
            pitch > MAX_VIDEO_PITCH_BYTES) return;
    bytes_per_pixel = active_host->pixel_format == RETRO_PIXEL_FORMAT_XRGB8888 ? 4u : 2u;
    if (width > SIZE_MAX / bytes_per_pixel || pitch < width * bytes_per_pixel) return;
    if (pitch > SIZE_MAX / height) return;
    size = pitch * height;
    if (!size || size > MAX_VIDEO_FRAME_BYTES) return;
    if (size != active_host->video_size) {
        replacement = (uint8_t *)realloc(active_host->video_bytes, size);
        if (!replacement) return;
        active_host->video_bytes = replacement;
        active_host->video_size = size;
    }
    memcpy(active_host->video_bytes, data, size);
    active_host->video_width = width;
    active_host->video_height = height;
    active_host->video_pitch = pitch;
    active_host->video_sequence++;
}

static void push_audio_sample(int16_t sample) {
    size_t write;
    if (!active_host || !active_host->audio_ring) return;
    if (active_host->audio_count == AUDIO_RING_SAMPLES) {
        active_host->audio_read = (active_host->audio_read + 1) % AUDIO_RING_SAMPLES;
        active_host->audio_count--;
    }
    write = (active_host->audio_read + active_host->audio_count) % AUDIO_RING_SAMPLES;
    active_host->audio_ring[write] = sample;
    active_host->audio_count++;
}

static void push_audio_frame(int16_t left, int16_t right) {
    if (!active_host) return;
    if (active_host->audio_input_rate > active_host->audio_output_rate &&
            active_host->audio_output_rate >= 8000.0) {
        active_host->audio_resample_left_sum += left;
        active_host->audio_resample_right_sum += right;
        active_host->audio_resample_sample_count++;
        active_host->audio_resample_accumulator += active_host->audio_output_rate;
        if (active_host->audio_resample_accumulator < active_host->audio_input_rate)
            return;
        active_host->audio_resample_accumulator -= active_host->audio_input_rate;
        if (active_host->audio_resample_sample_count > 0) {
            left = (int16_t)(active_host->audio_resample_left_sum /
                    (int64_t)active_host->audio_resample_sample_count);
            right = (int16_t)(active_host->audio_resample_right_sum /
                    (int64_t)active_host->audio_resample_sample_count);
        }
        active_host->audio_resample_left_sum = 0;
        active_host->audio_resample_right_sum = 0;
        active_host->audio_resample_sample_count = 0;
    }
    push_audio_sample(left);
    push_audio_sample(right);
}

static void audio_sample_callback(int16_t left, int16_t right) {
    push_audio_frame(left, right);
}

static size_t audio_batch_callback(const int16_t *data, size_t frames) {
    size_t index;
    if (!data || frames > SIZE_MAX / 2) return 0;
    for (index = 0; index < frames; index++)
        push_audio_frame(data[index * 2], data[index * 2 + 1]);
    return frames;
}

static void input_poll_callback(void) {}

static int16_t input_state_callback(unsigned port, unsigned device,
                                    unsigned index, unsigned id) {
    if (!active_host || port >= MAX_PORTS) return 0;
    if (device == RETRO_DEVICE_JOYPAD) {
        if (id == RETRO_DEVICE_ID_JOYPAD_MASK)
            return (int16_t)(active_host->joypad_mask[port] & 0xffffu);
        if (id >= MAX_JOYPAD_BUTTONS) return 0;
        return (active_host->joypad_mask[port] & (1u << id)) ? 1 : 0;
    }
    if (device == RETRO_DEVICE_ANALOG && index < MAX_ANALOG_INDEXES &&
            id < MAX_ANALOG_IDS) {
        if (index < RETRO_DEVICE_INDEX_ANALOG_BUTTON && id > RETRO_DEVICE_ID_ANALOG_Y)
            return 0;
        return active_host->analog_state[port][index][id];
    }
    if (device == RETRO_DEVICE_POINTER) {
        if (id == RETRO_DEVICE_ID_POINTER_X) return active_host->pointer_x[port];
        if (id == RETRO_DEVICE_ID_POINTER_Y) return active_host->pointer_y[port];
        if (id == RETRO_DEVICE_ID_POINTER_PRESSED)
            return active_host->pointer_pressed[port] ? 1 : 0;
    }
    return 0;
}

static bool resolve_symbol(void *library, const char *name, void *target,
                           char *error, size_t error_size) {
    void *symbol;
    dlerror();
    symbol = dlsym(library, name);
    const char *failure = dlerror();
    if (failure || !symbol) {
        set_error(error, error_size, "required core symbol %s is missing", name);
        return false;
    }
    memcpy(target, &symbol, sizeof(symbol));
    return true;
}

static void resolve_optional_symbol(void *library, const char *name, void *target) {
    void *symbol;
    dlerror();
    symbol = dlsym(library, name);
    if (dlerror()) symbol = NULL;
    memcpy(target, &symbol, sizeof(symbol));
}

#define RESOLVE(host, field, symbol) \
    if (!resolve_symbol((host)->library, symbol, &(host)->field, error, error_size)) goto fail

static bool validate_hw_options(const lucent_retro_hw_options *options,
                                char *error, size_t error_size) {
    const uint32_t contexts = LUCENT_RETRO_HW_GLES2 | LUCENT_RETRO_HW_GLES3 |
            LUCENT_RETRO_HW_GLES_VERSION | LUCENT_RETRO_HW_VULKAN;
    const uint32_t features = LUCENT_RETRO_HW_DEPTH | LUCENT_RETRO_HW_STENCIL |
            LUCENT_RETRO_HW_CACHE_CONTEXT | LUCENT_RETRO_HW_DEBUG_CONTEXT |
            LUCENT_RETRO_HW_SHARED_CONTEXT;
    const struct retro_hw_render_interface *render_interface;
    if (!options) return true;
    if (options->struct_size != sizeof(*options) ||
            options->api_version != LUCENT_RETRO_HW_OPTIONS_VERSION) {
        set_error(error, error_size, "unsupported hardware options layout/version");
        return false;
    }
    if (!options->context_capabilities ||
            (options->context_capabilities & ~contexts) ||
            (options->feature_capabilities & ~features) ||
            !options->preferred_context ||
            (options->preferred_context & (options->preferred_context - 1u)) ||
            !(options->context_capabilities & options->preferred_context)) {
        set_error(error, error_size, "hardware capabilities and preferred context are invalid");
        return false;
    }
    if ((options->context_capabilities &
            (LUCENT_RETRO_HW_GLES2 | LUCENT_RETRO_HW_GLES3 |
             LUCENT_RETRO_HW_GLES_VERSION)) &&
            (!options->get_current_framebuffer || !options->get_proc_address)) {
        set_error(error, error_size, "GLES requires framebuffer and proc-address hooks");
        return false;
    }
    if ((options->context_capabilities & LUCENT_RETRO_HW_GLES_VERSION) &&
            (options->max_gles_major < 3 ||
             (options->max_gles_major == 3 && options->max_gles_minor < 1))) {
        set_error(error, error_size, "versioned GLES capability requires GLES 3.1 or newer");
        return false;
    }
    if (options->context_capabilities & LUCENT_RETRO_HW_VULKAN) {
        render_interface = (const struct retro_hw_render_interface *)
                options->render_interface;
        if (!render_interface ||
                render_interface->interface_type != RETRO_HW_RENDER_INTERFACE_VULKAN ||
                !render_interface->interface_version) {
            set_error(error, error_size, "Vulkan requires a versioned Vulkan render interface");
            return false;
        }
    }
    return true;
}

lucent_retro_host *lucent_retro_create_with_options(
        const char *core_path, const char *trusted_root,
        const char *system_directory, const char *save_directory,
        const lucent_retro_hw_options *hw_options,
        char *error, size_t error_size) {
    char resolved_core[4096];
    char resolved_root[4096];
    lucent_retro_host *host = NULL;
    lock_host();
    if (!core_path || !trusted_root || !system_directory || !save_directory) {
        set_error(error, error_size, "core, trusted root, system, and save paths are required");
        RETURN_UNLOCKED(NULL);
    }
    if (!validate_hw_options(hw_options, error, error_size)) RETURN_UNLOCKED(NULL);
    if (active_host) {
        set_error(error, error_size, "only one Lucent core session may be active");
        RETURN_UNLOCKED(NULL);
    }
    if (!realpath(core_path, resolved_core) || !realpath(trusted_root, resolved_root)) {
        set_error(error, error_size, "cannot resolve trusted core path: %s", strerror(errno));
        RETURN_UNLOCKED(NULL);
    }
    if (strcmp(resolved_root, "/") == 0 || !path_is_inside(resolved_core, resolved_root)) {
        set_error(error, error_size, "core must be inside Lucent's trusted app-private directory");
        RETURN_UNLOCKED(NULL);
    }
    host = (lucent_retro_host *)calloc(1, sizeof(*host));
    if (!host) {
        set_error(error, error_size, "out of memory creating core host");
        RETURN_UNLOCKED(NULL);
    }
    if (hw_options) {
        host->hw_options = *hw_options;
        host->hw_configured = true;
    }
    host->core_path = strdup(resolved_core);
    host->system_directory = strdup(system_directory);
    host->save_directory = strdup(save_directory);
    host->pixel_format = RETRO_PIXEL_FORMAT_0RGB1555;
    host->audio_ring = (int16_t *)calloc(AUDIO_RING_SAMPLES, sizeof(int16_t));
    if (!host->core_path || !host->system_directory || !host->save_directory ||
            !host->audio_ring) {
        set_error(error, error_size, "out of memory copying core paths");
        goto fail;
    }
    host->library = dlopen(host->core_path, RTLD_NOW | RTLD_LOCAL);
    if (!host->library) {
        set_error(error, error_size, "cannot load core: %s", dlerror());
        goto fail;
    }
#if defined(__ANDROID__)
    __android_log_print(ANDROID_LOG_INFO, "LucentNativeHost",
            "core library loaded path=%s", host->core_path);
#endif
    RESOLVE(host, init, "retro_init");
    RESOLVE(host, deinit, "retro_deinit");
    RESOLVE(host, api_version, "retro_api_version");
    RESOLVE(host, get_system_info, "retro_get_system_info");
    RESOLVE(host, get_system_av_info, "retro_get_system_av_info");
    RESOLVE(host, set_environment, "retro_set_environment");
    RESOLVE(host, set_video_refresh, "retro_set_video_refresh");
    RESOLVE(host, set_audio_sample, "retro_set_audio_sample");
    RESOLVE(host, set_audio_sample_batch, "retro_set_audio_sample_batch");
    RESOLVE(host, set_input_poll, "retro_set_input_poll");
    RESOLVE(host, set_input_state, "retro_set_input_state");
    RESOLVE(host, set_controller_port_device, "retro_set_controller_port_device");
    RESOLVE(host, reset, "retro_reset");
    RESOLVE(host, run, "retro_run");
    RESOLVE(host, serialize_size, "retro_serialize_size");
    RESOLVE(host, serialize, "retro_serialize");
    RESOLVE(host, unserialize, "retro_unserialize");
    RESOLVE(host, cheat_reset, "retro_cheat_reset");
    RESOLVE(host, cheat_set, "retro_cheat_set");
    RESOLVE(host, load_game, "retro_load_game");
    RESOLVE(host, load_game_special, "retro_load_game_special");
    RESOLVE(host, unload_game, "retro_unload_game");
    resolve_optional_symbol(host->library, "retro_lucent_prepare_exit_autosave",
                            &host->prepare_exit);
    RESOLVE(host, get_region, "retro_get_region");
    RESOLVE(host, get_memory_data, "retro_get_memory_data");
    RESOLVE(host, get_memory_size, "retro_get_memory_size");
    if (host->api_version() != RETRO_API_VERSION) {
        set_error(error, error_size, "unsupported libretro API %u (expected %u)",
                  host->api_version(), RETRO_API_VERSION);
        goto fail;
    }
    active_host = host;
    host->set_environment(environment_callback);
    host->get_system_info(&host->system_info);
    /*
     * Mesen and Mesen-S allocate the objects used by their callback setters in
     * retro_init(). Register the environment first (cores may query it during
     * initialization), initialize the core, and only then register the media
     * and input callbacks. The Phase 1 core probe uses the same ordering.
     */
#if defined(__ANDROID__)
    __android_log_print(ANDROID_LOG_INFO, "LucentNativeHost", "retro_init begin");
#endif
    host->init();
#if defined(__ANDROID__)
    __android_log_print(ANDROID_LOG_INFO, "LucentNativeHost", "retro_init complete");
#endif
    host->initialized = true;
    host->set_video_refresh(video_callback);
    host->set_audio_sample(audio_sample_callback);
    host->set_audio_sample_batch(audio_batch_callback);
    host->set_input_poll(input_poll_callback);
    host->set_input_state(input_state_callback);
    RETURN_UNLOCKED(host);

fail:
    lucent_retro_destroy(host);
    RETURN_UNLOCKED(NULL);
}

lucent_retro_host *lucent_retro_create(const char *core_path,
                                       const char *trusted_root,
                                       const char *system_directory,
                                       const char *save_directory,
                                       char *error, size_t error_size) {
    return lucent_retro_create_with_options(
            core_path, trusted_root, system_directory, save_directory,
            NULL, error, error_size);
}

bool lucent_retro_supply_android_java_vm(lucent_retro_host *host, void *java_vm,
                                         char *error, size_t error_size) {
    static const char play_setter_name[] = "lucent_set_android_java_vm";
    fn_set_android_java_vm setter = NULL;
    void *symbol;
    lock_host();
    if (!host || !host->library || !java_vm) {
        set_error(error, error_size, "valid host and JavaVM are required");
        RETURN_UNLOCKED(false);
    }
    dlerror();
    symbol = dlsym(host->library, play_setter_name);
    if (!symbol) {
        /* This hook is optional and deliberately invisible to normal cores. */
#if defined(__ANDROID__)
        __android_log_print(ANDROID_LOG_WARN, "LucentNativeHost",
                "optional Android JavaVM hook unavailable: %s", dlerror());
#else
        fprintf(stderr, "Lucent optional Android JavaVM hook unavailable: %s\n",
                dlerror());
#endif
        RETURN_UNLOCKED(true);
    }
    memcpy(&setter, &symbol, sizeof(setter));
    setter(java_vm);
#if defined(__ANDROID__)
    __android_log_print(ANDROID_LOG_INFO, "LucentNativeHost",
            "supplied Android JavaVM %p to optional core hook %p", java_vm, symbol);
#else
    fprintf(stderr, "Lucent supplied Android JavaVM %p to optional core hook %p\n",
            java_vm, symbol);
#endif
    RETURN_UNLOCKED(true);
}

bool lucent_retro_supply_output_size(lucent_retro_host *host,
                                     unsigned width, unsigned height,
                                     char *error, size_t error_size) {
    static const char setter_name[] = "lucent_set_output_size";
    fn_set_output_size setter = NULL;
    void *symbol;
    lock_host();
    if (!host || !host->library || !width || !height) {
        set_error(error, error_size, "valid host and output size are required");
        RETURN_UNLOCKED(false);
    }
    dlerror();
    symbol = dlsym(host->library, setter_name);
    if (!symbol) RETURN_UNLOCKED(true);
    memcpy(&setter, &symbol, sizeof(setter));
    setter(width, height);
#if defined(__ANDROID__)
    __android_log_print(ANDROID_LOG_INFO, "LucentNativeHost",
            "supplied output size %ux%u to optional core hook", width, height);
#endif
    RETURN_UNLOCKED(true);
}

bool lucent_retro_supply_frontend_framebuffer(lucent_retro_host *host,
                                              unsigned framebuffer,
                                              char *error,
                                              size_t error_size) {
    static const char setter_name[] = "lucent_set_frontend_framebuffer";
    fn_set_frontend_framebuffer setter = NULL;
    void *symbol;
    lock_host();
    if (!host || !host->library) {
        set_error(error, error_size, "valid host is required");
        RETURN_UNLOCKED(false);
    }
    dlerror();
    symbol = dlsym(host->library, setter_name);
    if (!symbol) RETURN_UNLOCKED(true);
    memcpy(&setter, &symbol, sizeof(setter));
    setter(framebuffer);
#if defined(__ANDROID__)
    __android_log_print(ANDROID_LOG_INFO, "LucentNativeHost",
            "supplied frontend framebuffer %u to optional core hook",
            framebuffer);
#endif
    RETURN_UNLOCKED(true);
}

static bool read_game(const char *path, char **bytes, size_t *size,
                      char *error, size_t error_size) {
    struct stat metadata;
    FILE *file;
    if (stat(path, &metadata) != 0 || metadata.st_size < 0) {
        set_error(error, error_size, "cannot stat game: %s", strerror(errno));
        return false;
    }
    if ((uintmax_t)metadata.st_size > SIZE_MAX ||
            (uintmax_t)metadata.st_size > MAX_GAME_BYTES) {
        set_error(error, error_size, "game is too large for this process");
        return false;
    }
    file = fopen(path, "rb");
    if (!file) {
        set_error(error, error_size, "cannot open game: %s", strerror(errno));
        return false;
    }
    *size = (size_t)metadata.st_size;
    *bytes = (char *)malloc(*size ? *size : 1);
    if (!*bytes || (*size && fread(*bytes, 1, *size, file) != *size)) {
        set_error(error, error_size, "cannot read game content");
        free(*bytes);
        *bytes = NULL;
        fclose(file);
        return false;
    }
    fclose(file);
    return true;
}

bool lucent_retro_load_game(lucent_retro_host *host, const char *game_path,
                            char *error, size_t error_size) {
    struct retro_game_info game;
    lock_host();
    if (!host || !game_path || host->game_loaded) {
        set_error(error, error_size, "host must be valid and unloaded before loading a game");
        RETURN_UNLOCKED(false);
    }
    memset(&game, 0, sizeof(game));
    game.path = game_path;
    if (!host->system_info.need_fullpath) {
        if (!read_game(game_path, &host->game_bytes, &host->game_size, error, error_size))
            RETURN_UNLOCKED(false);
        game.data = host->game_bytes;
        game.size = host->game_size;
    }
    active_host = host;
#if defined(__ANDROID__)
    __android_log_print(ANDROID_LOG_INFO, "LucentNativeHost",
            "retro_load_game begin path=%s needFullpath=%d", game_path,
            host->system_info.need_fullpath ? 1 : 0);
#endif
    if (!host->load_game(&game)) {
        set_error(error, error_size, "core rejected game content");
        free(host->game_bytes);
        host->game_bytes = NULL;
        host->game_size = 0;
        host->hw_negotiated = false;
        host->hw_context_ready = false;
        memset(&host->hw_callback, 0, sizeof(host->hw_callback));
        RETURN_UNLOCKED(false);
    }
#if defined(__ANDROID__)
    __android_log_print(ANDROID_LOG_INFO, "LucentNativeHost",
            "retro_load_game complete");
#endif
    host->game_loaded = true;
    host->set_controller_port_device(0, RETRO_DEVICE_JOYPAD);
    memset(&host->current_av_info, 0, sizeof(host->current_av_info));
    host->get_system_av_info(&host->current_av_info);
    host->audio_input_rate = host->current_av_info.timing.sample_rate;
    host->audio_output_rate = host->audio_input_rate > 192000.0
            ? 48000.0 : host->audio_input_rate;
    host->audio_resample_accumulator = 0.0;
    host->audio_resample_left_sum = 0;
    host->audio_resample_right_sum = 0;
    host->audio_resample_sample_count = 0;
    host->current_av_info.timing.sample_rate = host->audio_output_rate;
    host->has_av_info = true;
    RETURN_UNLOCKED(true);
}

bool lucent_retro_set_controller_port_device(lucent_retro_host *host,
                                             unsigned port, unsigned device,
                                             char *error, size_t error_size) {
    lock_host();
    if (!host || !host->game_loaded || !host->set_controller_port_device) {
        set_error(error, error_size, "no loaded core to configure a port on");
        RETURN_UNLOCKED(false);
    }
    if (port >= MAX_PORTS) {
        set_error(error, error_size, "controller port %u is out of range", port);
        RETURN_UNLOCKED(false);
    }
    host->set_controller_port_device(port, device);
#if defined(__ANDROID__)
    __android_log_print(ANDROID_LOG_INFO, "LucentNativeHost",
            "controller port %u device 0x%x", port, device);
#endif
    RETURN_UNLOCKED(true);
}

bool lucent_retro_unload_game(lucent_retro_host *host,
                              char *error, size_t error_size) {
    lock_host();
    if (!host || !host->game_loaded || !host->unload_game) {
        set_error(error, error_size, "a loaded core session is required");
        RETURN_UNLOCKED(false);
    }
    active_host = host;
    if (host->prepare_exit && !host->prepare_exit()) {
        set_error(error, error_size,
                  "core could not safely save progress; game remains loaded");
        RETURN_UNLOCKED(false);
    }
    host->unload_game();
    host->game_loaded = false;
    host->has_av_info = false;
    free(host->game_bytes);
    host->game_bytes = NULL;
    host->game_size = 0;
    RETURN_UNLOCKED(true);
}

bool lucent_retro_game_loaded(const lucent_retro_host *host) {
    bool loaded;
    lock_host();
    loaded = host && host->game_loaded;
    RETURN_UNLOCKED(loaded);
}

bool lucent_retro_run_frame(lucent_retro_host *host, char *error,
                            size_t error_size) {
    lock_host();
    if (!host || !host->game_loaded) {
        set_error(error, error_size, "cannot run without a loaded game");
        RETURN_UNLOCKED(false);
    }
    if (host->hw_negotiated && !host->hw_context_ready) {
        set_error(error, error_size, "hardware context is not ready");
        RETURN_UNLOCKED(false);
    }
    if (host->paused) RETURN_UNLOCKED(true);
    active_host = host;
    host->run();
    RETURN_UNLOCKED(true);
}

void lucent_retro_set_paused(lucent_retro_host *host, bool paused) {
    lock_host();
    if (host) host->paused = paused;
    unlock_host();
}

bool lucent_retro_set_joypad_button(lucent_retro_host *host, unsigned port,
                                    unsigned button, bool pressed) {
    lock_host();
    if (!host || port >= MAX_PORTS || button >= MAX_JOYPAD_BUTTONS)
        RETURN_UNLOCKED(false);
    if (pressed) host->joypad_mask[port] |= 1u << button;
    else host->joypad_mask[port] &= ~(1u << button);
    RETURN_UNLOCKED(true);
}

bool lucent_retro_set_analog_axis(lucent_retro_host *host, unsigned port,
                                  unsigned index, unsigned id, int16_t value) {
    lock_host();
    if (!host || port >= MAX_PORTS || index >= MAX_ANALOG_INDEXES ||
            id >= MAX_ANALOG_IDS) RETURN_UNLOCKED(false);
    if (index < RETRO_DEVICE_INDEX_ANALOG_BUTTON && id > RETRO_DEVICE_ID_ANALOG_Y)
        RETURN_UNLOCKED(false);
    host->analog_state[port][index][id] = value;
    RETURN_UNLOCKED(true);
}

bool lucent_retro_set_pointer(lucent_retro_host *host, unsigned port,
                              int16_t x, int16_t y, bool pressed) {
    lock_host();
    if (!host || port >= MAX_PORTS) RETURN_UNLOCKED(false);
    host->pointer_x[port] = x;
    host->pointer_y[port] = y;
    host->pointer_pressed[port] = pressed;
    RETURN_UNLOCKED(true);
}

bool lucent_retro_latest_video_info(const lucent_retro_host *host,
                                    lucent_retro_video_info *info) {
    lock_host();
    if (!host || !info || !host->video_bytes || !host->video_size)
        RETURN_UNLOCKED(false);
    info->width = host->video_width;
    info->height = host->video_height;
    info->pitch = host->video_pitch;
    info->pixel_format = (unsigned)host->pixel_format;
    info->byte_size = host->video_size;
    info->sequence = host->video_sequence;
    RETURN_UNLOCKED(true);
}

bool lucent_retro_copy_video_frame(const lucent_retro_host *host, void *buffer,
                                   size_t size) {
    lock_host();
    if (!host || !buffer || !host->video_bytes || size != host->video_size)
        RETURN_UNLOCKED(false);
    memcpy(buffer, host->video_bytes, size);
    RETURN_UNLOCKED(true);
}

size_t lucent_retro_drain_audio(lucent_retro_host *host, int16_t *samples,
                                size_t max_frames) {
    size_t available_samples;
    size_t index;
    lock_host();
    if (!host || !samples || !max_frames || max_frames > SIZE_MAX / 2)
        RETURN_UNLOCKED(0);
    available_samples = host->audio_count - (host->audio_count % 2);
    if (available_samples > max_frames * 2) available_samples = max_frames * 2;
    for (index = 0; index < available_samples; index++) {
        samples[index] = host->audio_ring[host->audio_read];
        host->audio_read = (host->audio_read + 1) % AUDIO_RING_SAMPLES;
    }
    host->audio_count -= available_samples;
    RETURN_UNLOCKED(available_samples / 2);
}

bool lucent_retro_get_av_info(lucent_retro_host *host,
                              lucent_retro_av_info *info) {
    lock_host();
    if (!host || !info || !host->game_loaded) RETURN_UNLOCKED(false);
    if (!host->has_av_info) RETURN_UNLOCKED(false);
    info->base_width = host->current_av_info.geometry.base_width;
    info->base_height = host->current_av_info.geometry.base_height;
    info->aspect_ratio = host->current_av_info.geometry.aspect_ratio;
    info->frames_per_second = host->current_av_info.timing.fps;
    info->sample_rate = host->current_av_info.timing.sample_rate;
    RETURN_UNLOCKED(true);
}

bool lucent_retro_get_hw_info(lucent_retro_host *host,
                              lucent_retro_hw_info *info) {
    lock_host();
    if (!host || !info) RETURN_UNLOCKED(false);
    memset(info, 0, sizeof(*info));
    info->negotiated = host->hw_negotiated;
    info->context_ready = host->hw_context_ready;
    if (host->hw_negotiated) {
        info->context_type = (unsigned)host->hw_callback.context_type;
        info->version_major = host->hw_callback.version_major;
        info->version_minor = host->hw_callback.version_minor;
        info->depth = host->hw_callback.depth;
        info->stencil = host->hw_callback.stencil;
        info->bottom_left_origin = host->hw_callback.bottom_left_origin;
        info->cache_context = host->hw_callback.cache_context;
        info->debug_context = host->hw_callback.debug_context;
        info->frame_sequence = host->hw_frame_sequence;
        info->frame_width = host->hw_frame_width;
        info->frame_height = host->hw_frame_height;
    }
    RETURN_UNLOCKED(true);
}

const void *lucent_retro_hw_context_negotiation_interface(
        lucent_retro_host *host) {
    const void *result;
    lock_host();
    result = host ? (const void *)host->hw_context_negotiation_interface : NULL;
    RETURN_UNLOCKED(result);
}

bool lucent_retro_hw_context_reset(lucent_retro_host *host,
                                   char *error, size_t error_size) {
    lock_host();
    if (!host || !host->game_loaded || !host->hw_negotiated ||
            !host->hw_callback.context_reset) {
        set_error(error, error_size, "a loaded negotiated hardware core is required");
        RETURN_UNLOCKED(false);
    }
    if (host->hw_context_ready) {
        set_error(error, error_size,
                  "hardware context must be destroyed or lost before reset");
        RETURN_UNLOCKED(false);
    }
    active_host = host;
    host->hw_context_ready = true;
    host->hw_callback.context_reset();
    RETURN_UNLOCKED(true);
}

bool lucent_retro_hw_context_destroy(lucent_retro_host *host,
                                     char *error, size_t error_size) {
    lock_host();
    if (!host || !host->hw_negotiated) {
        set_error(error, error_size, "a negotiated hardware core is required");
        RETURN_UNLOCKED(false);
    }
    if (!host->hw_context_ready) RETURN_UNLOCKED(true);
    active_host = host;
    if (host->hw_callback.context_destroy)
        host->hw_callback.context_destroy();
    host->hw_context_ready = false;
    RETURN_UNLOCKED(true);
}

bool lucent_retro_hw_context_lost(lucent_retro_host *host,
                                  char *error, size_t error_size) {
    lock_host();
    if (!host || !host->hw_negotiated) {
        set_error(error, error_size, "a negotiated hardware core is required");
        RETURN_UNLOCKED(false);
    }
    /* context_destroy may execute arbitrary GL/Vulkan work. Calling it after
     * EGL_CONTEXT_LOST (or the Vulkan equivalent) is unsafe, so loss is a
     * distinct transition from an orderly surface detach. */
    host->hw_context_ready = false;
    RETURN_UNLOCKED(true);
}

size_t lucent_retro_serialize_size(lucent_retro_host *host) {
    size_t size;
    lock_host();
    if (!host || !host->game_loaded) RETURN_UNLOCKED(0);
    active_host = host;
    size = host->serialize_size();
    RETURN_UNLOCKED(size <= MAX_STATE_BYTES ? size : 0);
}

bool lucent_retro_serialize_alloc(lucent_retro_host *host, void **buffer,
                                  size_t *size, char *error,
                                  size_t error_size) {
    void *bytes;
    size_t required;
    lock_host();
    if (buffer) *buffer = NULL;
    if (size) *size = 0;
    if (!host || !host->game_loaded || !buffer || !size) {
        set_error(error, error_size, "valid loaded host and output pointers are required");
        RETURN_UNLOCKED(false);
    }
    active_host = host;
    required = host->serialize_size();
    if (!required || required > MAX_STATE_BYTES) {
        set_error(error, error_size, "core does not expose a usable serialized state");
        RETURN_UNLOCKED(false);
    }
    bytes = malloc(required);
    if (!bytes) {
        set_error(error, error_size, "out of memory serializing core state");
        RETURN_UNLOCKED(false);
    }
    if (!host->serialize(bytes, required)) {
        free(bytes);
        set_error(error, error_size, "core failed to serialize state");
        RETURN_UNLOCKED(false);
    }
    *buffer = bytes;
    *size = required;
    RETURN_UNLOCKED(true);
}

bool lucent_retro_serialize(lucent_retro_host *host, void *buffer, size_t size,
                            char *error, size_t error_size) {
    lock_host();
    size_t required = lucent_retro_serialize_size(host);
    if (!required || !buffer || size != required) {
        set_error(error, error_size, "state buffer size mismatch");
        RETURN_UNLOCKED(false);
    }
    active_host = host;
    if (!host->serialize(buffer, size)) {
        set_error(error, error_size, "core failed to serialize state");
        RETURN_UNLOCKED(false);
    }
    RETURN_UNLOCKED(true);
}

bool lucent_retro_unserialize(lucent_retro_host *host, const void *buffer,
                              size_t size, char *error, size_t error_size) {
    lock_host();
    if (!host || !host->game_loaded || !buffer || !size || size > MAX_STATE_BYTES) {
        set_error(error, error_size, "valid loaded host and state are required");
        RETURN_UNLOCKED(false);
    }
    active_host = host;
    if (!host->unserialize(buffer, size)) {
        set_error(error, error_size, "core rejected serialized state");
        RETURN_UNLOCKED(false);
    }
    RETURN_UNLOCKED(true);
}

const char *lucent_retro_library_name(const lucent_retro_host *host) {
    const char *result;
    lock_host();
    result = host && host->system_info.library_name ? host->system_info.library_name : "";
    RETURN_UNLOCKED(result);
}

const char *lucent_retro_library_version(const lucent_retro_host *host) {
    const char *result;
    lock_host();
    result = host && host->system_info.library_version ? host->system_info.library_version : "";
    RETURN_UNLOCKED(result);
}

unsigned lucent_retro_api_version(const lucent_retro_host *host) {
    unsigned result;
    lock_host();
    result = host ? host->api_version() : 0;
    RETURN_UNLOCKED(result);
}

size_t lucent_retro_save_ram_size(lucent_retro_host *host) {
    size_t size;
    lock_host();
    if (!host || !host->game_loaded) RETURN_UNLOCKED(0);
    active_host = host;
    size = host->get_memory_size(RETRO_MEMORY_SAVE_RAM);
    RETURN_UNLOCKED(size <= MAX_SAVE_RAM_BYTES ? size : 0);
}

bool lucent_retro_read_save_ram(lucent_retro_host *host, void *buffer,
                                size_t size) {
    lock_host();
    size_t available = lucent_retro_save_ram_size(host);
    void *source;
    if (!available || available != size || !buffer) RETURN_UNLOCKED(false);
    source = host->get_memory_data(RETRO_MEMORY_SAVE_RAM);
    if (!source) RETURN_UNLOCKED(false);
    memcpy(buffer, source, size);
    RETURN_UNLOCKED(true);
}

bool lucent_retro_write_save_ram(lucent_retro_host *host, const void *buffer,
                                 size_t size) {
    lock_host();
    size_t available = lucent_retro_save_ram_size(host);
    void *destination;
    if (!available || available != size || !buffer) RETURN_UNLOCKED(false);
    destination = host->get_memory_data(RETRO_MEMORY_SAVE_RAM);
    if (!destination) RETURN_UNLOCKED(false);
    memcpy(destination, buffer, size);
    RETURN_UNLOCKED(true);
}

void lucent_retro_destroy(lucent_retro_host *host) {
    size_t variable_index;
    if (!host) return;
    lock_host();
    /* Android must call lucent_retro_hw_context_destroy while its EGL/Vulkan
     * context is current. Never invoke core GL/Vulkan cleanup opportunistically
     * here after the Java surface may already have disappeared. */
    host->hw_context_ready = false;
    if (host->game_loaded && host->unload_game) host->unload_game();
    if (host->initialized && host->deinit) host->deinit();
    if (host->library) dlclose(host->library);
    if (active_host == host) active_host = NULL;
    free(host->game_bytes);
    free(host->video_bytes);
    free(host->audio_ring);
    free(host->core_path);
    free(host->system_directory);
    free(host->save_directory);
    for (variable_index = 0; variable_index < host->core_variable_count;
            variable_index++) {
        free(host->core_variables[variable_index].key);
        free(host->core_variables[variable_index].value);
    }
    free(host);
    unlock_host();
}
