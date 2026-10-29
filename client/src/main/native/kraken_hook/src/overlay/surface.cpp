#include "overlay/surface.h"

#include <atomic>
#include <cstring>

#include "overlay/overlay.h"
#include "protocol.h"

namespace kraken::overlay {
namespace {

// The surface the reader thread opened (or a stop request), waiting for the present thread.
std::atomic<SurfaceView *> g_pendingSurface{nullptr};

/**
 * Hands a surface (or a stop request) to the present thread, freeing one it never took.
 *
 * @param surface the opened surface, or a SurfaceView with no view to stop drawing
 */
void requestSurface(SurfaceView *surface) {
    SurfaceView *old = g_pendingSurface.exchange(surface, std::memory_order_acq_rel);
    freeSurface(old);
}

} // namespace

void freeSurface(SurfaceView *surface) {
    if (surface == nullptr) {
        return;
    }
    if (surface->view != nullptr) {
        UnmapViewOfFile(surface->view);
    }
    if (surface->mapping != nullptr) {
        CloseHandle(surface->mapping);
    }
    delete surface;
}

void openSurface(FrameReader r) {
    uint32_t generation = 0;
    uint16_t nameLen = 0;
    if (!r.get(generation) || !r.get(nameLen)) {
        return;
    }
    char name[512];
    if (nameLen >= sizeof(name) || !r.bytes(reinterpret_cast<uint8_t *>(name), nameLen)) {
        return;
    }
    name[nameLen] = 0;
    SurfaceView *surface = new SurfaceView();
    if (nameLen == 0 || !hooked()) {
        requestSurface(surface);
        return;
    }
    surface->mapping = OpenFileMappingA(FILE_MAP_READ | FILE_MAP_WRITE, FALSE, name);
    if (surface->mapping != nullptr) {
        surface->view = static_cast<uint8_t *>(MapViewOfFile(surface->mapping, FILE_MAP_READ | FILE_MAP_WRITE, 0, 0, 0));
    }
    bool valid = false;
    if (surface->view != nullptr) {
        MEMORY_BASIC_INFORMATION info{};
        uint32_t header[4] = {};
        std::memcpy(header, surface->view, sizeof(header));
        uint64_t need = SURFACE_PIXELS + uint64_t(header[1]) * header[2] * 4 * header[3];
        valid = VirtualQuery(surface->view, &info, sizeof(info)) == sizeof(info)
                && header[0] == SURFACE_MAGIC
                && header[1] > 0 && header[1] <= SURFACE_MAX_SIDE
                && header[2] > 0 && header[2] <= SURFACE_MAX_SIDE
                && header[3] > 0 && header[3] <= SURFACE_MAX_SLOTS
                && info.RegionSize >= need;
        surface->generation = generation;
        surface->width = header[1];
        surface->height = header[2];
        surface->slots = header[3];
    }
    if (!valid) {
        freeSurface(surface);
        surface = new SurfaceView(); // a stop request: never draw a surface we can't trust
    }
    requestSurface(surface);
}

void stopSurface() {
    requestSurface(new SurfaceView());
}

SurfaceView *takePendingSurface() {
    return g_pendingSurface.exchange(nullptr, std::memory_order_acq_rel);
}

} // namespace kraken::overlay
