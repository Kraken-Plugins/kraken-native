// Draws the anchored draw list's batches (drawlist.h) at the present: the triangles resolved for the
// frame being presented, coloured or sampled from the sprite atlas, with premultiplied-alpha blending.
// Its GL objects (program, VAO, vertex buffer, atlas texture) belong to one context; the overlay
// resets them when the context changes. Present thread only.
#pragma once

#include <cstdint>

#include "overlay/gl.h"

namespace kraken::overlay::batch {

/**
 * Forgets the GL objects, which belong to a context that is no longer current. The next draw builds
 * them again and uploads the whole atlas.
 */
void reset();

/**
 * Takes the batch resolved for the frame being presented, dropping the one taken before.
 *
 * @param frame the frame object being presented, 0 without a render thread
 * @return true if the batch has triangles to draw
 */
bool take(uint64_t frame);

/**
 * Draws the taken batch. The caller has bound framebuffer 0 with a full-window viewport, set
 * premultiplied blending, turned off the switches that would hide it, and saved the client's state;
 * this binds its own program, VAO, array buffer and texture on unit 0. Atlas rows that changed are
 * uploaded first; the sprites wait until the atlas holds what they need.
 *
 * @param f      the context's entry points
 * @param major  the context's GL major version
 * @param minor  its minor version
 * @param width  the window's client width
 * @param height its client height
 */
void draw(const GlFunctions &f, int major, int minor, uint32_t width, uint32_t height);

} // namespace kraken::overlay::batch
