// The present hook (functions.present, which calls SwapBuffers). Before the original runs, it uploads
// the newest overlay frame from the surface and draws it over framebuffer 0 as one full-screen
// triangle with premultiplied-alpha blending (GLSL 330 core on GL 3.3+, 150 on 3.2, 130 on 3.0 and
// 3.1; nothing below 3.0). It saves every piece of GL state it changes and restores it after, under
// SEH guards; a fault turns the overlay off for the session. See docs/developer/in-game-drawing.md.
#pragma once

#include <cstdint>

namespace kraken::overlay {

/**
 * What the present thread last saw, for FRAME.
 */
struct FrameInfo {
    uint32_t width;      // the window's client width
    uint32_t height;     // its client height
    uint32_t glVersion;  // major << 16 | minor
    uint32_t generation; // the surface generation drawn, 0 for none
};

/**
 * Hooks the present function, unless it is hooked already.
 *
 * @param rva the function's rva, 0 for none
 */
void install(uint64_t rva);

/**
 * Whether the present hook is live.
 *
 * @return true once it installed
 */
bool hooked();

/**
 * How many presents the hook has seen, for FPS.
 *
 * @return the presents so far
 */
uint64_t presents();

/**
 * What the present thread last saw. All zero before the first present.
 *
 * @return the framebuffer size, GL version and surface generation drawn
 */
FrameInfo frameInfo();

} // namespace kraken::overlay
