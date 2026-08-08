// SPDX-License-Identifier: GPL-3.0-or-later
//
// Lucent Phase 3 native adapter for the Eden Switch engine.
//
// This translation unit is compiled INTO Eden's own libyuzu-android.so and is
// the only symbol Lucent calls: lucent_native_adapter_entry(). It drives
// EmulationSession directly, so the engine runs inside Lucent's process, in
// Lucent's window, with no Eden Activity, no second task, and no emulator UI.
//
// Eden owns its emulation loop (RunEmulation blocks until halted), so the
// adapter runs it on one engine thread and reports liveness from run_frame().
// That satisfies the ABI contract -- Lucent still owns the surface, audio sink
// ownership, pause/resume, save flushing, and teardown ordering.
//
// Firmware and keys are never bundled: load() fails closed unless Lucent hands
// over a validated system directory that Eden can read them from.

#include <atomic>
#include <cstring>
#include <string>
#include <thread>

#include <android/native_window.h>

#include "lucent_native_adapter.h"

#include "common/fs/path_util.h"
#include "core/core.h"
#include "core/perf_stats.h"
#include "jni/native.h"

namespace {

constexpr const char* kEngineId = "eden";
constexpr const char* kEngineVersion = "eden-v0.2.0";

} // namespace

struct lucent_native_engine {
    std::thread emulation_thread;
    std::atomic<bool> started{false};
    std::atomic<bool> loaded{false};
    ANativeWindow* window{nullptr};
};

static void adapter_describe(lucent_native_capabilities* out) {
    if (out == nullptr) {
        return;
    }
    std::memset(out, 0, sizeof(*out));
    out->abi_version = LUCENT_NATIVE_ADAPTER_ABI_VERSION;
    out->engine_id = kEngineId;
    out->engine_version = kEngineVersion;
    // Eden exposes no deterministic, migration-safe full-state API, so Lucent
    // must not advertise Quick Resume for Switch. Held-Stop flushes the game's
    // own saves and exits instead of promising a snapshot it cannot restore.
    out->has_quick_resume = false;
    out->has_persistent_save = true;
    // The Switch is a single-screen target on the Thor; the GamePad/lower
    // display is not used by this engine.
    out->dual_screen = false;
    // Console keys plus a system firmware archive, both user-supplied.
    out->required_firmware = 2;
}

static lucent_native_engine* adapter_create(void) {
    return new (std::nothrow) lucent_native_engine();
}

static bool adapter_load(lucent_native_engine* engine,
                         const lucent_native_load_request* request,
                         char* error, size_t error_size) {
    const auto fail = [&](const char* message) {
        if (error != nullptr && error_size > 0) {
            std::snprintf(error, error_size, "%s", message);
        }
        return false;
    };
    if (engine == nullptr || request == nullptr) {
        return fail("adapter load received no engine or request");
    }
    if (request->content_path == nullptr || request->content_path[0] == '\0') {
        return fail("no Switch content path was supplied");
    }
    if (request->system_directory == nullptr || request->system_directory[0] == '\0') {
        // Keys/firmware live under this root. Refuse rather than booting into
        // an undecryptable state that would look like a hang.
        return fail("no validated key/firmware directory was supplied");
    }

    // Point Eden at Lucent's validated per-engine root before anything reads
    // keys, NAND, or save data.
    Common::FS::SetAppDirectory(std::string(request->system_directory));

    auto& session = EmulationSession::GetInstance();
    session.ConfigureFilesystemProvider(std::string(request->content_path));
    session.InitializeSystem(false);
    const auto status =
        session.InitializeEmulation(std::string(request->content_path), 0, true);
    if (status != Core::SystemResultStatus::Success) {
        return fail("Eden could not initialize this Switch title");
    }
    engine->loaded.store(true);
    return true;
}

static bool adapter_start(lucent_native_engine* engine, const lucent_native_io* io,
                          char* error, size_t error_size) {
    const auto fail = [&](const char* message) {
        if (error != nullptr && error_size > 0) {
            std::snprintf(error, error_size, "%s", message);
        }
        return false;
    };
    if (engine == nullptr || io == nullptr) {
        return fail("adapter start received no engine or io");
    }
    if (!engine->loaded.load()) {
        return fail("adapter start called before a title was loaded");
    }
    if (io->primary_window == nullptr) {
        return fail("Lucent supplied no render window");
    }
    if (engine->started.load()) {
        return true;
    }

    engine->window = static_cast<ANativeWindow*>(io->primary_window);
    auto& session = EmulationSession::GetInstance();
    session.SetNativeWindow(engine->window);

    // Eden's loop blocks until HaltEmulation, so it gets its own thread. Lucent
    // keeps ownership of the surface lifetime and stops us before detaching it.
    engine->started.store(true);
    engine->emulation_thread = std::thread([]() {
        EmulationSession::GetInstance().RunEmulation();
    });
    return true;
}

static bool adapter_run_frame(lucent_native_engine* engine) {
    if (engine == nullptr || !engine->started.load()) {
        return false;
    }
    // Eden presents from its own loop. Report liveness so Lucent can fail the
    // session instead of leaving a frozen picture on screen.
    return EmulationSession::GetInstance().IsRunning();
}

static void adapter_set_control(lucent_native_engine* engine,
                                lucent_native_control control, float value) {
    // Input is delivered through Eden's own Android input subsystem, which
    // Lucent feeds via the JNI input path; nothing to translate here yet.
    (void)engine;
    (void)control;
    (void)value;
}

static void adapter_pause(lucent_native_engine* engine) {
    if (engine == nullptr || !engine->started.load()) {
        return;
    }
    auto& session = EmulationSession::GetInstance();
    if (session.IsRunning() && !session.IsPaused()) {
        session.PauseEmulation();
    }
}

static void adapter_resume(lucent_native_engine* engine) {
    if (engine == nullptr || !engine->started.load()) {
        return;
    }
    auto& session = EmulationSession::GetInstance();
    if (session.IsPaused()) {
        session.UnPauseEmulation();
    }
}

static bool adapter_flush_save(lucent_native_engine* engine) {
    if (engine == nullptr || !engine->loaded.load()) {
        return false;
    }
    // Pausing quiesces the guest and lets Eden's filesystem layer settle its
    // pending save writes before Lucent reports a clean exit.
    auto& session = EmulationSession::GetInstance();
    if (session.IsRunning() && !session.IsPaused()) {
        session.PauseEmulation();
    }
    return true;
}

static size_t adapter_serialize_size(lucent_native_engine* engine) {
    (void)engine;
    return 0;
}

static size_t adapter_serialize(lucent_native_engine* engine, void* out, size_t capacity) {
    (void)engine;
    (void)out;
    (void)capacity;
    return 0;
}

static bool adapter_unserialize(lucent_native_engine* engine, const void* data, size_t size) {
    (void)engine;
    (void)data;
    (void)size;
    return false;
}

static bool adapter_surface_recreated(lucent_native_engine* engine,
                                      const lucent_native_io* io) {
    if (engine == nullptr || io == nullptr || io->primary_window == nullptr) {
        return false;
    }
    engine->window = static_cast<ANativeWindow*>(io->primary_window);
    auto& session = EmulationSession::GetInstance();
    session.SetNativeWindow(engine->window);
    session.SurfaceChanged();
    return true;
}

static void adapter_stop(lucent_native_engine* engine) {
    if (engine == nullptr) {
        return;
    }
    auto& session = EmulationSession::GetInstance();
    if (engine->started.load()) {
        session.HaltEmulation();
        if (engine->emulation_thread.joinable()) {
            engine->emulation_thread.join();
        }
        engine->started.store(false);
    }
    if (engine->loaded.load()) {
        session.ShutdownEmulation();
        engine->loaded.store(false);
    }
    engine->window = nullptr;
}

static void adapter_destroy(lucent_native_engine* engine) {
    if (engine == nullptr) {
        return;
    }
    adapter_stop(engine);
    delete engine;
}

static const lucent_native_adapter kLucentEdenAdapter = {
    /* abi_version      */ LUCENT_NATIVE_ADAPTER_ABI_VERSION,
    /* describe         */ adapter_describe,
    /* create           */ adapter_create,
    /* load             */ adapter_load,
    /* start            */ adapter_start,
    /* run_frame        */ adapter_run_frame,
    /* set_control      */ adapter_set_control,
    /* pause            */ adapter_pause,
    /* resume           */ adapter_resume,
    /* flush_save       */ adapter_flush_save,
    /* serialize_size   */ adapter_serialize_size,
    /* serialize        */ adapter_serialize,
    /* unserialize      */ adapter_unserialize,
    /* surface_recreated*/ adapter_surface_recreated,
    /* stop             */ adapter_stop,
    /* destroy          */ adapter_destroy,
};

extern "C" __attribute__((visibility("default")))
const lucent_native_adapter* lucent_native_adapter_entry(void) {
    return &kLucentEdenAdapter;
}

// Lucent reads live speed through this symbol during qualification so a
// "runs at 60fps" claim is measured from the engine, not inferred.
extern "C" __attribute__((visibility("default")))
double lucent_eden_average_game_fps(void) {
    return EmulationSession::GetInstance().PerfStats().average_game_fps;
}
