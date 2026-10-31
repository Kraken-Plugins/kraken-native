// The overlay surface: a file mapping the Kraken client fills with three frames of premultiplied BGRA
// pixels (docs/developer/in-game-drawing.md, "The Overlay Surface"). The reader thread opens the one a
// SURFACE command names and hands it to the present thread through an atomic swap; the present thread
// unmaps the old one.
#pragma once

#include <windows.h>

#include <cstdint>

#include "core/frame_reader.h"

namespace kraken::overlay {

/**
 * One opened overlay surface: the mapping, its view and the header values checked when it opened.
 * A view of nullptr is a request to stop drawing.
 */
struct SurfaceView {
    HANDLE mapping = nullptr;
    uint8_t *view = nullptr;
    uint32_t generation = 0;
    uint32_t width = 0;
    uint32_t height = 0;
    uint32_t slots = 0;
};

/**
 * Opens the surface a SURFACE command names and hands it to the present thread. An empty name, or a
 * mapping that is missing or doesn't match the layout, stops drawing.
 *
 * @param body the frame's body after the type byte
 */
void openSurface(FrameReader body);

/**
 * Hands the present thread a stop request, as when the connection drops, since nothing would keep
 * the overlay current.
 */
void stopSurface();

/**
 * Takes the surface (or stop request) the reader thread handed over since the last call. Present
 * thread only.
 *
 * @return the surface, which the caller now owns, or nullptr if none is waiting
 */
SurfaceView *takePendingSurface();

/**
 * Unmaps and frees a surface.
 *
 * @param surface the surface, or nullptr
 */
void freeSurface(SurfaceView *surface);

} // namespace kraken::overlay
