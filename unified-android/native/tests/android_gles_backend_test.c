#include "../include/lucent_android_gles_backend.h"
#include "../include/libretro.h"
#include <android/native_window.h>
#include <dlfcn.h>
#include <pthread.h>
#include <stdio.h>
#include <string.h>

#define CHECK(value, message) do { \
    if (!(value)) { fprintf(stderr, "%s: %s\n", message, error); return 1; } \
} while (0)

typedef unsigned (*fn_count)(void);
void lucent_fake_egl_lose_next_swap(void);
unsigned lucent_fake_egl_swap_count(void);
unsigned lucent_fake_gles_blit_count(void);
unsigned lucent_fake_gles_opaque_alpha_clear_count(void);
int lucent_fake_gles_last_blit_source_x1(void);
int lucent_fake_gles_last_blit_source_y1(void);
void lucent_fake_gles_set_scissor_enabled(int enabled);
int lucent_fake_gles_scissor_enabled(void);
unsigned lucent_fake_gles_bound_framebuffer(void);
void lucent_fake_gles_set_viewport(int x, int y, int width, int height);
unsigned lucent_fake_window_acquire_count(void);
unsigned lucent_fake_window_release_count(void);

typedef struct thread_probe {
    lucent_android_gles_backend *backend;
    bool accepted;
} thread_probe;

static void *wrong_thread_make_current(void *userdata) {
    thread_probe *probe = (thread_probe *)userdata;
    char ignored[128] = {0};
    probe->accepted = lucent_android_gles_make_current(
            probe->backend, ignored, sizeof(ignored));
    return NULL;
}

int main(int argc, char **argv) {
    char error[512] = {0};
    lucent_android_gles_backend *backend;
    lucent_retro_hw_options options;
    lucent_retro_hw_info hw;
    lucent_android_gles_info backend_info;
    lucent_retro_host *host;
    ANativeWindow window = {1};
    bool presented = false;
    pthread_t thread;
    thread_probe probe;
    void *core_library;
    fn_count reset_count;
    fn_count destroy_count;
    fn_count variable_default_ok;
    fn_count frontend_framebuffer;
    lucent_retro_av_info av;
    uint32_t state = 0;
    uint32_t restored = 42;
    int16_t audio_samples[4] = {1, 1, 1, 1};
    uint8_t save_ram[8] = {1, 2, 3, 4, 5, 6, 7, 8};
    uint8_t save_copy[8] = {0};
    unsigned direct_swap_baseline;
    unsigned direct_blit_baseline;
    if (argc != 7) {
        fprintf(stderr, "usage: android_gles_backend_test CORE MUPEN_CORE "
                        "ROOT SYSTEM SAVE GAME\n");
        return 2;
    }
    backend = lucent_android_gles_create(error, sizeof(error));
    CHECK(backend, "Android GLES backend probe failed");
    CHECK(lucent_android_gles_get_info(backend, &backend_info) &&
          backend_info.display_ready && backend_info.max_gles_major == 3 &&
          backend_info.max_gles_minor == 2 && !backend_info.surface_attached,
          "probed Android GLES metadata mismatch");
    CHECK(lucent_android_gles_get_host_options(backend, &options,
                                               error, sizeof(error)),
          "Android GLES host options failed");
    CHECK((options.context_capabilities & LUCENT_RETRO_HW_GLES_VERSION) &&
          !(options.context_capabilities & LUCENT_RETRO_HW_VULKAN) &&
          (options.feature_capabilities & LUCENT_RETRO_HW_CACHE_CONTEXT) &&
          !(options.feature_capabilities & (LUCENT_RETRO_HW_DEBUG_CONTEXT |
                                            LUCENT_RETRO_HW_SHARED_CONTEXT)),
          "Android GLES capability gates mismatch");
    core_library = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
    CHECK(core_library, "hardware mock core could not be inspected");
    reset_count = (fn_count)dlsym(core_library, "lucent_hw_mock_reset_count");
    destroy_count = (fn_count)dlsym(core_library, "lucent_hw_mock_destroy_count");
    variable_default_ok = (fn_count)dlsym(
            core_library, "lucent_hw_mock_variable_default_ok");
    frontend_framebuffer = (fn_count)dlsym(
            core_library, "lucent_hw_mock_frontend_framebuffer");
    CHECK(reset_count && destroy_count && variable_default_ok &&
          frontend_framebuffer,
          "hardware lifecycle probes missing");
    host = lucent_retro_create_with_options(argv[1], argv[3], argv[4], argv[5],
                                            &options, error, sizeof(error));
    CHECK(host, "hardware host creation failed");
    CHECK(variable_default_ok() == 1,
          "hardware core option defaults were not retained");
    CHECK(lucent_retro_load_game(host, argv[6], error, sizeof(error)),
          "hardware mock game load failed");
    CHECK(lucent_retro_get_av_info(host, &av) && av.frames_per_second == 60.0 &&
          av.sample_rate == 48000.0 && av.base_width == 4 && av.base_height == 4,
          "hardware AV information was unavailable");
    CHECK(lucent_retro_serialize_size(host) == sizeof(state) &&
          lucent_retro_serialize(host, &state, sizeof(state), error, sizeof(error)) &&
          state == 7,
          "hardware state serialization before attach failed");
    CHECK(lucent_retro_unserialize(host, &restored, sizeof(restored),
                                   error, sizeof(error)) &&
          lucent_retro_serialize(host, &state, sizeof(state), error, sizeof(error)) &&
          state == restored,
          "hardware state restore before attach failed");
    CHECK(lucent_retro_save_ram_size(host) == sizeof(save_ram) &&
          lucent_retro_write_save_ram(host, save_ram, sizeof(save_ram)) &&
          lucent_retro_read_save_ram(host, save_copy, sizeof(save_copy)) &&
          memcmp(save_ram, save_copy, sizeof(save_ram)) == 0,
          "hardware save RAM round-trip failed");
    CHECK(lucent_android_gles_attach(backend, host, &window, error, sizeof(error)),
          "Android GLES window attach failed");
    CHECK(reset_count() == 1 && frontend_framebuffer() == 42 &&
          lucent_android_gles_get_info(backend, &backend_info) &&
          backend_info.surface_attached,
          "Android GLES reset/attach lifecycle mismatch");
    probe.backend = backend;
    probe.accepted = true;
    CHECK(pthread_create(&thread, NULL, wrong_thread_make_current, &probe) == 0 &&
          pthread_join(thread, NULL) == 0 && !probe.accepted,
          "wrong-thread EGL access was not rejected");
    CHECK(lucent_retro_run_frame(host, error, sizeof(error)),
          "hardware core did not produce its first frame");
    /* Model Dolphin's integer-scaled core viewport inside a larger frontend
     * allocation and its normal OGL state, which leaves scissor testing on.
     * Presentation must blit only the complete written region without letting
     * the core's scissor clip the Android destination, then restore it. */
    lucent_fake_gles_set_viewport(0, 0, 720, 1000);
    lucent_fake_gles_set_scissor_enabled(1);
    CHECK(lucent_android_gles_present_if_ready(backend, &presented,
                                               error, sizeof(error)) &&
          presented && lucent_fake_egl_swap_count() == 1 &&
          lucent_fake_gles_blit_count() == 1 &&
          lucent_fake_gles_opaque_alpha_clear_count() == 1 &&
          lucent_fake_gles_last_blit_source_x1() == 720 &&
          lucent_fake_gles_last_blit_source_y1() == 1000 &&
          lucent_fake_gles_scissor_enabled() == 1 &&
          lucent_fake_gles_bound_framebuffer() == 0,
          "new hardware frame was not presented exactly once");
    CHECK(lucent_retro_drain_audio(host, audio_samples, 2) == 2 &&
          audio_samples[0] == 0 && audio_samples[1] == 0 &&
          audio_samples[2] == 0 && audio_samples[3] == 0,
          "hardware audio transport did not drain stereo PCM");
    CHECK(lucent_android_gles_present_if_ready(backend, &presented,
                                               error, sizeof(error)) &&
          !presented && lucent_fake_egl_swap_count() == 1,
          "stale hardware frame triggered a duplicate swap");
    lucent_fake_egl_lose_next_swap();
    CHECK(lucent_retro_run_frame(host, error, sizeof(error)) &&
          !lucent_android_gles_present_if_ready(backend, &presented,
                                                error, sizeof(error)) &&
          lucent_retro_get_hw_info(host, &hw) && !hw.context_ready &&
          destroy_count() == 0,
          "EGL_CONTEXT_LOST did not fail closed without core cleanup");
    CHECK(lucent_android_gles_detach(backend, error, sizeof(error)),
          "lost Android GLES surface did not detach cleanly");
    CHECK(lucent_android_gles_attach(backend, host, &window, error, sizeof(error)) &&
          reset_count() == 2,
          "replacement Android GLES context did not reset the core");
    CHECK(lucent_retro_run_frame(host, error, sizeof(error)) &&
          lucent_android_gles_present_if_ready(backend, &presented,
                                               error, sizeof(error)) && presented,
          "replacement Android GLES context did not present");
    CHECK(lucent_android_gles_detach(backend, error, sizeof(error)) &&
          frontend_framebuffer() == 0 &&
          destroy_count() == 1 &&
          lucent_fake_window_acquire_count() == 2 &&
          lucent_fake_window_release_count() == 2,
          "orderly EGL detach/window ownership mismatch");
    lucent_retro_destroy(host);
    lucent_android_gles_destroy(backend);

    /* A direct-window engine must receive framebuffer zero and swap without
     * Lucent overwriting the core's window image. */
    direct_swap_baseline = lucent_fake_egl_swap_count();
    direct_blit_baseline = lucent_fake_gles_blit_count();
    backend = lucent_android_gles_create(error, sizeof(error));
    CHECK(backend && lucent_android_gles_set_presentation_policy(
                    backend, LUCENT_ANDROID_GLES_PRESENT_DIRECT_WINDOW,
                    error, sizeof(error)) &&
          lucent_android_gles_get_host_options(backend, &options,
                                               error, sizeof(error)),
          "direct-window GLES policy setup failed");
    host = lucent_retro_create_with_options(argv[1], argv[3], argv[4], argv[5],
                                            &options, error, sizeof(error));
    CHECK(host && lucent_retro_load_game(host, argv[6], error, sizeof(error)) &&
          lucent_android_gles_attach(backend, host, &window, error, sizeof(error)) &&
          lucent_retro_run_frame(host, error, sizeof(error)) &&
          lucent_android_gles_present_if_ready(backend, &presented,
                                               error, sizeof(error)) &&
          presented && lucent_fake_egl_swap_count() == direct_swap_baseline + 1 &&
          lucent_fake_gles_blit_count() == direct_blit_baseline,
          "direct-window core was not presented without a frontend blit");
    CHECK(lucent_android_gles_detach(backend, error, sizeof(error)),
          "direct-window GLES detach failed");
    lucent_retro_destroy(host);
    lucent_android_gles_destroy(backend);

    /* Mupen64Plus-Next's GLideN64 path asks for GLES3 + depth +
     * cache_context. Prove that the cache flag is the only additional gate,
     * then exercise the complete attach/frame/detach lifecycle. This keeps the
     * production host fail-closed for debug/shared contexts rather than
     * weakening negotiation generically. */
    backend = lucent_android_gles_create(error, sizeof(error));
    CHECK(backend && lucent_android_gles_get_host_options(
                    backend, &options, error, sizeof(error)),
          "Mupen GLES host options failed");
    options.preferred_context = LUCENT_RETRO_HW_GLES3;
    options.feature_capabilities &= ~LUCENT_RETRO_HW_CACHE_CONTEXT;
    host = lucent_retro_create_with_options(argv[2], argv[3], argv[4], argv[5],
                                            &options, error, sizeof(error));
    CHECK(host && !lucent_retro_load_game(host, argv[6], error, sizeof(error)),
          "Mupen cache-context request bypassed the capability gate");
    lucent_retro_destroy(host);

    options.feature_capabilities |= LUCENT_RETRO_HW_CACHE_CONTEXT;
    host = lucent_retro_create_with_options(argv[2], argv[3], argv[4], argv[5],
                                            &options, error, sizeof(error));
    CHECK(host && lucent_retro_load_game(host, argv[6], error, sizeof(error)) &&
          lucent_retro_get_hw_info(host, &hw) && hw.negotiated &&
          hw.context_type == RETRO_HW_CONTEXT_OPENGLES3 && hw.depth &&
          !hw.stencil && hw.cache_context && !hw.debug_context,
          "Mupen GLES3/depth/cache negotiation failed");
    CHECK(lucent_android_gles_attach(backend, host, &window, error, sizeof(error)) &&
          lucent_retro_run_frame(host, error, sizeof(error)) &&
          lucent_android_gles_present_if_ready(backend, &presented,
                                               error, sizeof(error)) &&
          presented,
          "Mupen-shaped GLES core did not attach and present");
    CHECK(lucent_android_gles_detach(backend, error, sizeof(error)),
          "Mupen-shaped orderly GLES detach failed");
    lucent_retro_destroy(host);
    lucent_android_gles_destroy(backend);
    /* Keep the first instrumented mock DSO resident until the second one is
     * gone. macOS ASan otherwise reuses the exact mapping while retaining the
     * first DSO's global-redzone metadata, producing a false positive in the
     * shared variadic log callback. */
    dlclose(core_library);
    puts("Android EGL/GLES attach, present, loss, recovery, and detach passed");
    return 0;
}
