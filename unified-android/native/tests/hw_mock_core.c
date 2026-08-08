#include "../include/libretro.h"

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>

#ifndef LUCENT_MOCK_HW_VULKAN
#define LUCENT_MOCK_HW_VULKAN 0
#endif
#ifndef LUCENT_MOCK_ANDROID_GLES
#define LUCENT_MOCK_ANDROID_GLES 0
#endif
#ifndef LUCENT_MOCK_MUPEN_GLES
#define LUCENT_MOCK_MUPEN_GLES 0
#endif

/* The deliberately small host-test libretro header does not carry the full
 * optional descriptor definition.  This is its ABI shape and, importantly,
 * begins with integers rather than pointer fields. */
struct lucent_mock_input_descriptor {
    unsigned port;
    unsigned device;
    unsigned index;
    unsigned id;
    const char *description;
};

static retro_environment_t environment;
static retro_video_refresh_t video;
static retro_audio_sample_t audio;
static retro_audio_sample_batch_t audio_batch;
static retro_input_poll_t input_poll;
static retro_input_state_t input_state;
static struct retro_hw_render_callback hardware;
static const struct retro_hw_render_interface *render_interface;
static uint32_t counter;
static uint8_t save_ram[8];
static unsigned reset_count;
static unsigned destroy_count;
static bool preferred_ok;
static bool interface_ok;
static bool shared_ok;
static bool backend_hooks_ok;
static bool variable_default_ok;
static unsigned supplied_frontend_framebuffer;

static void context_reset(void) {
    reset_count++;
#if LUCENT_MOCK_HW_VULKAN
    backend_hooks_ok = render_interface &&
            render_interface->interface_type == RETRO_HW_RENDER_INTERFACE_VULKAN &&
            render_interface->interface_version == 1;
#else
    backend_hooks_ok = hardware.get_current_framebuffer &&
#if LUCENT_MOCK_ANDROID_GLES
            hardware.get_current_framebuffer() != 0 &&
#else
            hardware.get_current_framebuffer() == (uintptr_t)0x1234u &&
#endif
            hardware.get_proc_address &&
            hardware.get_proc_address("lucent_mock_symbol") != NULL;
#endif
}

static void context_destroy(void) { destroy_count++; }

unsigned lucent_hw_mock_reset_count(void) { return reset_count; }
unsigned lucent_hw_mock_destroy_count(void) { return destroy_count; }
bool lucent_hw_mock_preferred_ok(void) { return preferred_ok; }
bool lucent_hw_mock_interface_ok(void) { return interface_ok; }
bool lucent_hw_mock_shared_ok(void) { return shared_ok; }
bool lucent_hw_mock_backend_hooks_ok(void) { return backend_hooks_ok; }
bool lucent_hw_mock_variable_default_ok(void) { return variable_default_ok; }
unsigned lucent_hw_mock_frontend_framebuffer(void) {
    return supplied_frontend_framebuffer;
}
void lucent_set_frontend_framebuffer(unsigned framebuffer) {
    supplied_frontend_framebuffer = framebuffer;
}

void retro_init(void) {}
void retro_deinit(void) {}
unsigned retro_api_version(void) { return RETRO_API_VERSION; }
void retro_get_system_info(struct retro_system_info *info) {
    memset(info, 0, sizeof(*info));
    info->library_name = LUCENT_MOCK_HW_VULKAN
            ? "Lucent Vulkan Mock Core" : LUCENT_MOCK_MUPEN_GLES
            ? "Lucent Mupen GLES Mock Core" : "Lucent GLES Mock Core";
    info->library_version = "1";
    info->valid_extensions = "mock";
    info->need_fullpath = false;
}
void retro_get_system_av_info(struct retro_system_av_info *info) {
    memset(info, 0, sizeof(*info));
    info->geometry.base_width = 4;
    info->geometry.base_height = 4;
    info->geometry.max_width = 4;
    info->geometry.max_height = 4;
    info->timing.fps = 60.0;
    info->timing.sample_rate = 48000.0;
}
void retro_set_environment(retro_environment_t callback) {
    environment = callback;
#if LUCENT_MOCK_ANDROID_GLES || LUCENT_MOCK_MUPEN_GLES
    if (environment) {
        const struct lucent_mock_input_descriptor descriptors[] = {
            { 0, RETRO_DEVICE_JOYPAD, 0, 6,
              "D-Pad Left" },
            { 0 },
        };
        unsigned performance_level = 12;
        struct retro_log_callback logger = { NULL };
        struct retro_variable definitions[] = {
            { "lucent_hw_test", "Lucent hardware test; preferred|other" },
            { "ppsspp_internal_resolution",
              "Rendering Resolution; 480x272|960x544|1920x1088" },
            { "ppsspp_cropto16x9", "Crop to 16x9; disabled|enabled" },
            { "reicast_internal_resolution",
              "Internal Resolution; 640x480|1280x960|1440x1080" },
            { NULL, NULL },
        };
        struct retro_variable current = { "lucent_hw_test", NULL };
        struct retro_variable resolution = {
            "ppsspp_internal_resolution", NULL
        };
        struct retro_variable crop = { "ppsspp_cropto16x9", NULL };
        struct retro_variable dreamcast_resolution = {
            "reicast_internal_resolution", NULL
        };
        variable_default_ok = environment(RETRO_ENVIRONMENT_GET_LOG_INTERFACE,
                                          &logger) && logger.log != NULL;
        if (variable_default_ok)
            logger.log(RETRO_LOG_DEBUG, "Lucent mock core initialized: %d\n", 1);
        variable_default_ok = variable_default_ok && environment(
                    RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS,
                    (void *)descriptors) &&
                environment(RETRO_ENVIRONMENT_SET_PERFORMANCE_LEVEL,
                            &performance_level) &&
                environment(RETRO_ENVIRONMENT_SET_VARIABLES,
                                          definitions) &&
                environment(RETRO_ENVIRONMENT_GET_VARIABLE, &current) &&
                current.value && strcmp(current.value, "preferred") == 0 &&
                environment(RETRO_ENVIRONMENT_GET_VARIABLE, &resolution) &&
                resolution.value && strcmp(resolution.value, "1920x1088") == 0 &&
                environment(RETRO_ENVIRONMENT_GET_VARIABLE, &crop) &&
                crop.value && strcmp(crop.value, "enabled") == 0 &&
                environment(RETRO_ENVIRONMENT_GET_VARIABLE,
                            &dreamcast_resolution) &&
                dreamcast_resolution.value &&
                strcmp(dreamcast_resolution.value, "1440x1080") == 0;
    }
#endif
}
void retro_set_video_refresh(retro_video_refresh_t callback) { video = callback; }
void retro_set_audio_sample(retro_audio_sample_t callback) { audio = callback; }
void retro_set_audio_sample_batch(retro_audio_sample_batch_t callback) {
    audio_batch = callback;
}
void retro_set_input_poll(retro_input_poll_t callback) { input_poll = callback; }
void retro_set_input_state(retro_input_state_t callback) { input_state = callback; }
void retro_set_controller_port_device(unsigned port, unsigned device) {
    (void)port;
    (void)device;
}
void retro_reset(void) { counter = 0; }
void retro_run(void) {
    int16_t samples[2] = {0, 0};
    counter++;
    if (input_poll) input_poll();
    if (input_state) (void)input_state(0, RETRO_DEVICE_JOYPAD, 0, 0);
    if (video) video(RETRO_HW_FRAME_BUFFER_VALID, 4, 4, 0);
    if (audio) audio(0, 0);
    if (audio_batch) audio_batch(samples, 1);
}
size_t retro_serialize_size(void) { return sizeof(counter); }
bool retro_serialize(void *data, size_t size) {
    if (!data || size != sizeof(counter)) return false;
    memcpy(data, &counter, size);
    return true;
}
bool retro_unserialize(const void *data, size_t size) {
    if (!data || size != sizeof(counter)) return false;
    memcpy(&counter, data, size);
    return true;
}
void retro_cheat_reset(void) {}
void retro_cheat_set(unsigned index, bool enabled, const char *code) {
    (void)index;
    (void)enabled;
    (void)code;
}
bool retro_load_game(const struct retro_game_info *game) {
    enum retro_hw_context_type preferred = RETRO_HW_CONTEXT_NONE;
    if (!game || !game->data || !game->size || !environment) return false;
    memset(&hardware, 0, sizeof(hardware));
#if LUCENT_MOCK_HW_VULKAN
    hardware.context_type = RETRO_HW_CONTEXT_VULKAN;
#else
#if LUCENT_MOCK_MUPEN_GLES
    /* Exact GLideN64 negotiation shape in pinned Mupen64Plus-Next:
     * GLES3, depth, no stencil/debug/shared context, cache_context. */
    hardware.context_type = RETRO_HW_CONTEXT_OPENGLES3;
    hardware.depth = true;
    hardware.cache_context = true;
#elif LUCENT_MOCK_ANDROID_GLES
    hardware.context_type = RETRO_HW_CONTEXT_OPENGLES_VERSION;
    hardware.version_major = 3;
    hardware.version_minor = 1;
#else
    hardware.context_type = RETRO_HW_CONTEXT_OPENGLES3;
    hardware.depth = true;
    hardware.stencil = true;
    hardware.bottom_left_origin = true;
    hardware.cache_context = true;
    hardware.debug_context = true;
#endif
#endif
    hardware.context_reset = context_reset;
    hardware.context_destroy = context_destroy;
    preferred_ok = environment(RETRO_ENVIRONMENT_GET_PREFERRED_HW_RENDER, &preferred) &&
            preferred == hardware.context_type;
    shared_ok =
#if LUCENT_MOCK_ANDROID_GLES || LUCENT_MOCK_MUPEN_GLES
            true;
#else
            environment(RETRO_ENVIRONMENT_SET_HW_SHARED_CONTEXT, NULL);
#endif
    if (!preferred_ok || !shared_ok ||
            !environment(RETRO_ENVIRONMENT_SET_HW_RENDER, &hardware)) return false;
#if LUCENT_MOCK_HW_VULKAN
    interface_ok = environment(RETRO_ENVIRONMENT_GET_HW_RENDER_INTERFACE,
                               &render_interface) && render_interface &&
            render_interface->interface_type == RETRO_HW_RENDER_INTERFACE_VULKAN &&
            render_interface->interface_version == 1;
    if (!interface_ok) return false;
#else
    interface_ok = !environment(RETRO_ENVIRONMENT_GET_HW_RENDER_INTERFACE,
                                &render_interface);
#endif
    counter = ((const uint8_t *)game->data)[0];
    return interface_ok;
}
bool retro_load_game_special(unsigned type, const struct retro_game_info *games,
                             size_t count) {
    (void)type;
    (void)games;
    (void)count;
    return false;
}
void retro_unload_game(void) {}
unsigned retro_get_region(void) { return 0; }
void *retro_get_memory_data(unsigned id) {
    return id == RETRO_MEMORY_SAVE_RAM ? save_ram : NULL;
}
size_t retro_get_memory_size(unsigned id) {
    return id == RETRO_MEMORY_SAVE_RAM ? sizeof(save_ram) : 0;
}
