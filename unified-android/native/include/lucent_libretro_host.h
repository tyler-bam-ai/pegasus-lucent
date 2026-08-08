#ifndef LUCENT_LIBRETRO_HOST_H
#define LUCENT_LIBRETRO_HOST_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

typedef struct lucent_retro_host lucent_retro_host;

#define LUCENT_RETRO_HW_OPTIONS_VERSION 1u

enum lucent_retro_hw_context_capability {
    LUCENT_RETRO_HW_GLES2 = 1u << 0,
    LUCENT_RETRO_HW_GLES3 = 1u << 1,
    LUCENT_RETRO_HW_GLES_VERSION = 1u << 2,
    LUCENT_RETRO_HW_VULKAN = 1u << 3
};

enum lucent_retro_hw_feature {
    LUCENT_RETRO_HW_DEPTH = 1u << 0,
    LUCENT_RETRO_HW_STENCIL = 1u << 1,
    LUCENT_RETRO_HW_CACHE_CONTEXT = 1u << 2,
    LUCENT_RETRO_HW_DEBUG_CONTEXT = 1u << 3,
    LUCENT_RETRO_HW_SHARED_CONTEXT = 1u << 4
};

typedef void (*lucent_retro_proc_address)(void);
typedef uintptr_t (*lucent_retro_get_current_framebuffer)(void *userdata);
typedef lucent_retro_proc_address (*lucent_retro_get_proc_address)(
        void *userdata, const char *symbol);

/* Copied at host creation; callers retain ownership of userdata and the
 * optional render_interface. A non-NULL options block is still fail-closed
 * unless every capability needed by a core has an explicit backend hook. */
typedef struct lucent_retro_hw_options {
    size_t struct_size;
    unsigned api_version;
    uint32_t context_capabilities;
    uint32_t feature_capabilities;
    uint32_t preferred_context;
    unsigned max_gles_major;
    unsigned max_gles_minor;
    void *userdata;
    lucent_retro_get_current_framebuffer get_current_framebuffer;
    lucent_retro_get_proc_address get_proc_address;
    const void *render_interface;
} lucent_retro_hw_options;

typedef struct lucent_retro_hw_info {
    bool negotiated;
    bool context_ready;
    unsigned context_type;
    unsigned version_major;
    unsigned version_minor;
    bool depth;
    bool stencil;
    bool bottom_left_origin;
    bool cache_context;
    bool debug_context;
    uint64_t frame_sequence;
    unsigned frame_width;
    unsigned frame_height;
} lucent_retro_hw_info;

typedef struct lucent_retro_video_info {
    unsigned width;
    unsigned height;
    size_t pitch;
    unsigned pixel_format;
    size_t byte_size;
    uint64_t sequence;
} lucent_retro_video_info;

typedef struct lucent_retro_av_info {
    unsigned base_width;
    unsigned base_height;
    float aspect_ratio;
    double frames_per_second;
    double sample_rate;
} lucent_retro_av_info;

lucent_retro_host *lucent_retro_create(const char *core_path,
                                       const char *trusted_root,
                                       const char *system_directory,
                                       const char *save_directory,
                                       char *error, size_t error_size);
lucent_retro_host *lucent_retro_create_with_options(
        const char *core_path, const char *trusted_root,
        const char *system_directory, const char *save_directory,
        const lucent_retro_hw_options *hw_options,
        char *error, size_t error_size);
bool lucent_retro_load_game(lucent_retro_host *host, const char *game_path,
                            char *error, size_t error_size);
bool lucent_retro_unload_game(lucent_retro_host *host,
                              char *error, size_t error_size);
bool lucent_retro_game_loaded(const lucent_retro_host *host);
bool lucent_retro_run_frame(lucent_retro_host *host, char *error,
                            size_t error_size);
void lucent_retro_set_paused(lucent_retro_host *host, bool paused);
bool lucent_retro_set_joypad_button(lucent_retro_host *host, unsigned port,
                                    unsigned button, bool pressed);
bool lucent_retro_set_analog_axis(lucent_retro_host *host, unsigned port,
                                  unsigned index, unsigned id, int16_t value);
bool lucent_retro_set_pointer(lucent_retro_host *host, unsigned port,
                              int16_t x, int16_t y, bool pressed);
bool lucent_retro_latest_video_info(const lucent_retro_host *host,
                                    lucent_retro_video_info *info);
bool lucent_retro_copy_video_frame(const lucent_retro_host *host, void *buffer,
                                   size_t size);
size_t lucent_retro_drain_audio(lucent_retro_host *host, int16_t *samples,
                                size_t max_frames);
bool lucent_retro_get_av_info(lucent_retro_host *host,
                              lucent_retro_av_info *info);
bool lucent_retro_get_hw_info(lucent_retro_host *host,
                              lucent_retro_hw_info *info);
/* Returns the core-owned context negotiation interface registered during
 * load_game. The pointer remains owned by the core and is only valid for the
 * lifetime of the loaded session. */
const void *lucent_retro_hw_context_negotiation_interface(
        lucent_retro_host *host);
/* Supplies Android's process JavaVM to a core that embeds native Android
 * helpers. Most libretro cores do not need it; the exact pinned Play! build
 * exports its Framework setter but is loaded with dlopen, so Android cannot
 * invoke an application-style JNI_OnLoad on its behalf. */
bool lucent_retro_supply_android_java_vm(lucent_retro_host *host, void *java_vm,
                                         char *error, size_t error_size);
/* Supplies the current frontend surface size to cores that expose Lucent's
 * optional direct-framebuffer sizing hook. Normal libretro cores ignore it. */
bool lucent_retro_supply_output_size(lucent_retro_host *host,
                                     unsigned width, unsigned height,
                                     char *error, size_t error_size);
/* Supplies a frontend-owned OpenGL framebuffer to an exact core adapter that
 * exports Lucent's optional lifecycle hook. Normal libretro cores ignore it. */
bool lucent_retro_supply_frontend_framebuffer(lucent_retro_host *host,
                                              unsigned framebuffer,
                                              char *error,
                                              size_t error_size);
bool lucent_retro_hw_context_reset(lucent_retro_host *host,
                                   char *error, size_t error_size);
bool lucent_retro_hw_context_destroy(lucent_retro_host *host,
                                     char *error, size_t error_size);
/* Marks an unexpectedly lost GPU context unavailable without invoking the
 * core's context_destroy callback against a dead EGL/Vulkan context. The next
 * successfully-created context must be followed by hw_context_reset(). */
bool lucent_retro_hw_context_lost(lucent_retro_host *host,
                                  char *error, size_t error_size);
size_t lucent_retro_serialize_size(lucent_retro_host *host);
/* Allocates and serializes a state while holding the host lock across the
 * core's size query and serialization call. Some otherwise usable cores lazily
 * change their advertised state size between independent calls; JNI callers
 * must use this atomic form instead of query/allocate/serialize. The caller
 * owns the returned buffer and must free it. */
bool lucent_retro_serialize_alloc(lucent_retro_host *host, void **buffer,
                                  size_t *size, char *error,
                                  size_t error_size);
bool lucent_retro_serialize(lucent_retro_host *host, void *buffer, size_t size,
                            char *error, size_t error_size);
bool lucent_retro_unserialize(lucent_retro_host *host, const void *buffer,
                              size_t size, char *error, size_t error_size);
const char *lucent_retro_library_name(const lucent_retro_host *host);
const char *lucent_retro_library_version(const lucent_retro_host *host);
unsigned lucent_retro_api_version(const lucent_retro_host *host);
size_t lucent_retro_save_ram_size(lucent_retro_host *host);
bool lucent_retro_read_save_ram(lucent_retro_host *host, void *buffer,
                                size_t size);
bool lucent_retro_write_save_ram(lucent_retro_host *host, const void *buffer,
                                 size_t size);
void lucent_retro_destroy(lucent_retro_host *host);

#endif
