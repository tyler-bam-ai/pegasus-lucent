#define _XOPEN_SOURCE 700

#include "include/lucent_native_adapter_host.h"

#include <dlfcn.h>
#include <errno.h>
#include <limits.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#if defined(__ANDROID__)
#include <android/log.h>
#endif

struct lucent_native_adapter_host {
    void *library;
    const lucent_native_adapter *vtable;
    lucent_native_engine *engine;
    lucent_native_capabilities capabilities;
    bool loaded;
    bool started;
    bool stopped;
};

static void set_error(char *buffer, size_t size, const char *format, ...) {
    va_list args;
    if (!buffer || !size) return;
    va_start(args, format);
    vsnprintf(buffer, size, format, args);
    va_end(args);
}

/* True when path is the trusted root itself or lies beneath it. Both arguments
 * must already be canonical (realpath) absolute paths. */
static bool path_is_inside(const char *path, const char *root) {
    size_t length = strlen(root);
    if (length == 0) return false;
    if (strcmp(path, root) == 0) return true;
    return strncmp(path, root, length) == 0 &&
           (root[length - 1] == '/' || path[length] == '/');
}

/* Every vtable function pointer is required. A no-op is expressed by a function
 * that returns the documented failure value, never by a NULL slot. */
static bool vtable_is_complete(const lucent_native_adapter *vtable) {
    return vtable->describe && vtable->create && vtable->load &&
           vtable->start && vtable->run_frame && vtable->set_control &&
           vtable->pause && vtable->resume && vtable->flush_save &&
           vtable->serialize_size && vtable->serialize && vtable->unserialize &&
           vtable->surface_recreated && vtable->stop && vtable->destroy;
}

lucent_native_adapter_host *lucent_native_adapter_open(
        const char *adapter_path, const char *trusted_root,
        char *error, size_t error_size) {
    char resolved_adapter[PATH_MAX];
    char resolved_root[PATH_MAX];
    lucent_native_adapter_entry_fn entry;
    const lucent_native_adapter *vtable;
    lucent_native_adapter_host *host;
    void *library;
    void *symbol;
    lucent_native_capabilities capabilities;

    if (!adapter_path || !trusted_root) {
        set_error(error, error_size, "adapter path and trusted root are required");
        return NULL;
    }
    if (!realpath(adapter_path, resolved_adapter) ||
            !realpath(trusted_root, resolved_root)) {
        set_error(error, error_size, "cannot resolve adapter path: %s",
                  strerror(errno));
        return NULL;
    }
    if (strcmp(resolved_root, "/") == 0 ||
            !path_is_inside(resolved_adapter, resolved_root)) {
        set_error(error, error_size,
                  "adapter must be inside Lucent's trusted directory");
        return NULL;
    }

    library = dlopen(resolved_adapter, RTLD_NOW | RTLD_LOCAL);
    if (!library) {
        set_error(error, error_size, "cannot load adapter: %s", dlerror());
        return NULL;
    }
    dlerror();
    symbol = dlsym(library, LUCENT_NATIVE_ADAPTER_ENTRY_SYMBOL);
    if (!symbol || dlerror()) {
        set_error(error, error_size, "adapter is missing required symbol %s",
                  LUCENT_NATIVE_ADAPTER_ENTRY_SYMBOL);
        dlclose(library);
        return NULL;
    }
    memcpy(&entry, &symbol, sizeof(entry));
    vtable = entry();
    if (!vtable) {
        set_error(error, error_size, "adapter entry returned no vtable");
        dlclose(library);
        return NULL;
    }
    if (vtable->abi_version != LUCENT_NATIVE_ADAPTER_ABI_VERSION) {
        set_error(error, error_size,
                  "unsupported adapter ABI %u (expected %u)",
                  vtable->abi_version, LUCENT_NATIVE_ADAPTER_ABI_VERSION);
        dlclose(library);
        return NULL;
    }
    if (!vtable_is_complete(vtable)) {
        set_error(error, error_size, "adapter vtable has a null function pointer");
        dlclose(library);
        return NULL;
    }

    /* describe() is pure and callable before create(); validate its honest
     * report now and cache it so capability gating never re-enters the adapter. */
    memset(&capabilities, 0, sizeof(capabilities));
    vtable->describe(&capabilities);
    if (capabilities.abi_version != LUCENT_NATIVE_ADAPTER_ABI_VERSION ||
            !capabilities.engine_id || !capabilities.engine_id[0]) {
        set_error(error, error_size,
                  "adapter describe() reported an invalid capability record");
        dlclose(library);
        return NULL;
    }

    host = (lucent_native_adapter_host *)calloc(1, sizeof(*host));
    if (!host) {
        set_error(error, error_size, "out of memory creating adapter host");
        dlclose(library);
        return NULL;
    }
    host->library = library;
    host->vtable = vtable;
    host->capabilities = capabilities;
#if defined(__ANDROID__)
    __android_log_print(ANDROID_LOG_INFO, "LucentNativeAdapter",
            "adapter loaded engine=%s version=%s abi=%u quickResume=%d "
            "persistentSave=%d dualScreen=%d",
            capabilities.engine_id,
            capabilities.engine_version ? capabilities.engine_version : "",
            capabilities.abi_version, capabilities.has_quick_resume ? 1 : 0,
            capabilities.has_persistent_save ? 1 : 0,
            capabilities.dual_screen ? 1 : 0);
#endif
    return host;
}

bool lucent_native_adapter_describe(const lucent_native_adapter_host *host,
                                    lucent_native_capabilities *out,
                                    char *error, size_t error_size) {
    if (!host || !out) {
        set_error(error, error_size, "host and output record are required");
        return false;
    }
    *out = host->capabilities;
    return true;
}

bool lucent_native_adapter_create(lucent_native_adapter_host *host,
                                  char *error, size_t error_size) {
    if (!host) {
        set_error(error, error_size, "host is required");
        return false;
    }
    if (host->engine) {
        set_error(error, error_size, "adapter session already created");
        return false;
    }
    host->engine = host->vtable->create();
    if (!host->engine) {
        set_error(error, error_size, "adapter could not allocate a session");
        return false;
    }
    host->loaded = false;
    host->started = false;
    host->stopped = false;
    return true;
}

bool lucent_native_adapter_load(lucent_native_adapter_host *host,
                                const lucent_native_load_request *request,
                                char *error, size_t error_size) {
    char adapter_error[256] = {0};
    if (!host || !host->engine) {
        set_error(error, error_size, "adapter session is not created");
        return false;
    }
    /* Fail closed before the adapter is consulted: content is mandatory. */
    if (!request || !request->content_path || !request->content_path[0]) {
        set_error(error, error_size, "adapter load requires a content path");
        return false;
    }
    if (!host->vtable->load(host->engine, request, adapter_error,
                            sizeof(adapter_error))) {
        set_error(error, error_size, "adapter rejected content: %s",
                  adapter_error[0] ? adapter_error : "unsupported or missing firmware");
        return false;
    }
    host->loaded = true;
    return true;
}

bool lucent_native_adapter_start(lucent_native_adapter_host *host,
                                 const lucent_native_io *io,
                                 char *error, size_t error_size) {
    char adapter_error[256] = {0};
    if (!host || !host->engine || !host->loaded) {
        set_error(error, error_size, "adapter content is not loaded");
        return false;
    }
    if (!io || !io->primary_window) {
        set_error(error, error_size, "adapter start requires a primary window");
        return false;
    }
    if (!host->vtable->start(host->engine, io, adapter_error,
                             sizeof(adapter_error))) {
        set_error(error, error_size, "adapter could not start: %s",
                  adapter_error[0] ? adapter_error : "render device init failed");
        return false;
    }
    host->started = true;
    host->stopped = false;
    return true;
}

bool lucent_native_adapter_run_frame(lucent_native_adapter_host *host,
                                     char *error, size_t error_size) {
    if (!host || !host->engine || !host->started || host->stopped) {
        set_error(error, error_size, "adapter is not running");
        return false;
    }
    if (!host->vtable->run_frame(host->engine)) {
        set_error(error, error_size, "adapter reported a fatal frame error");
        return false;
    }
    return true;
}

bool lucent_native_adapter_set_control(lucent_native_adapter_host *host,
                                       lucent_native_control control,
                                       float value,
                                       char *error, size_t error_size) {
    if (!host || !host->engine || !host->started) {
        set_error(error, error_size, "adapter is not running");
        return false;
    }
    host->vtable->set_control(host->engine, control, value);
    return true;
}

bool lucent_native_adapter_pause(lucent_native_adapter_host *host,
                                 char *error, size_t error_size) {
    if (!host || !host->engine || !host->started) {
        set_error(error, error_size, "adapter is not running");
        return false;
    }
    host->vtable->pause(host->engine);
    return true;
}

bool lucent_native_adapter_resume(lucent_native_adapter_host *host,
                                  char *error, size_t error_size) {
    if (!host || !host->engine || !host->started) {
        set_error(error, error_size, "adapter is not running");
        return false;
    }
    host->vtable->resume(host->engine);
    return true;
}

bool lucent_native_adapter_flush_save(lucent_native_adapter_host *host,
                                      char *error, size_t error_size) {
    if (!host || !host->engine || !host->loaded) {
        set_error(error, error_size, "adapter content is not loaded");
        return false;
    }
    if (!host->vtable->flush_save(host->engine)) {
        set_error(error, error_size, "adapter could not commit a durable save");
        return false;
    }
    return true;
}

size_t lucent_native_adapter_serialize_size(lucent_native_adapter_host *host) {
    if (!host || !host->engine || !host->started) return 0;
    /* Never advertise a Quick Resume the engine cannot guarantee. */
    if (!host->capabilities.has_quick_resume) return 0;
    return host->vtable->serialize_size(host->engine);
}

bool lucent_native_adapter_serialize(lucent_native_adapter_host *host,
                                     void *out, size_t capacity,
                                     size_t *written,
                                     char *error, size_t error_size) {
    size_t count;
    if (written) *written = 0;
    if (!host || !host->engine || !host->started) {
        set_error(error, error_size, "adapter is not running");
        return false;
    }
    if (!host->capabilities.has_quick_resume) {
        set_error(error, error_size,
                  "adapter does not support Quick Resume serialization");
        return false;
    }
    /* A NULL out with zero capacity is the documented size query. */
    if (!out && capacity != 0) {
        set_error(error, error_size, "serialize buffer is required");
        return false;
    }
    count = host->vtable->serialize(host->engine, out, capacity);
    if (count == 0) {
        set_error(error, error_size, "adapter produced no serialized state");
        return false;
    }
    if (out && count > capacity) {
        set_error(error, error_size, "adapter overran the serialize buffer");
        return false;
    }
    if (written) *written = count;
    return true;
}

bool lucent_native_adapter_unserialize(lucent_native_adapter_host *host,
                                       const void *data, size_t size,
                                       char *error, size_t error_size) {
    if (!host || !host->engine || !host->started) {
        set_error(error, error_size, "adapter is not running");
        return false;
    }
    if (!host->capabilities.has_quick_resume) {
        set_error(error, error_size,
                  "adapter does not support Quick Resume restore");
        return false;
    }
    if (!data || size == 0) {
        set_error(error, error_size, "serialized state is required");
        return false;
    }
    if (!host->vtable->unserialize(host->engine, data, size)) {
        set_error(error, error_size, "adapter rejected the serialized state");
        return false;
    }
    return true;
}

bool lucent_native_adapter_surface_recreated(lucent_native_adapter_host *host,
                                             const lucent_native_io *io,
                                             char *error, size_t error_size) {
    if (!host || !host->engine || !host->started) {
        set_error(error, error_size, "adapter is not running");
        return false;
    }
    if (!io || !io->primary_window) {
        set_error(error, error_size, "surface_recreated requires a primary window");
        return false;
    }
    if (!host->vtable->surface_recreated(host->engine, io)) {
        set_error(error, error_size, "adapter could not rebind its render surface");
        return false;
    }
    return true;
}

bool lucent_native_adapter_stop(lucent_native_adapter_host *host,
                                char *error, size_t error_size) {
    if (!host || !host->engine) {
        set_error(error, error_size, "adapter session is not created");
        return false;
    }
    if (host->stopped) return true;
    host->vtable->stop(host->engine);
    host->started = false;
    host->stopped = true;
    return true;
}

void lucent_native_adapter_destroy(lucent_native_adapter_host *host) {
    if (!host) return;
    if (host->engine) {
        if (host->started && !host->stopped) host->vtable->stop(host->engine);
        host->vtable->destroy(host->engine);
        host->engine = NULL;
    }
    if (host->library) dlclose(host->library);
    free(host);
}

bool lucent_native_adapter_has_quick_resume(
        const lucent_native_adapter_host *host) {
    return host && host->capabilities.has_quick_resume;
}

bool lucent_native_adapter_has_persistent_save(
        const lucent_native_adapter_host *host) {
    return host && host->capabilities.has_persistent_save;
}

bool lucent_native_adapter_dual_screen(
        const lucent_native_adapter_host *host) {
    return host && host->capabilities.dual_screen;
}
