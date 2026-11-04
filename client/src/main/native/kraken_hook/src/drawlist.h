// The anchored draw list (docs/developer/in-game-drawing.md, "The Anchored Draw List"). Java sends a
// list of items, each tied to an NPC, and the sprites they use; the list stays until the next one
// replaces it. Every frame, at endFrame on the game thread, resolve() finds each item's NPC, reads the
// camera and the ground the frame was recorded with, projects the item and builds its triangles into a
// batch keyed by the frame object being queued. The present detour takes the batch of the frame it
// presents and draws it (overlay/batch.cpp), so the items move with the scene in the same frame and
// the game never waits for Java.
#pragma once

#include <cstdint>
#include <vector>

#include "core/frame_reader.h"

namespace kraken::drawlist {

/**
 * One vertex of a resolved batch. Geometry is in window pixels (the client's screen size); a sprite's
 * corners all sit on the projected anchor and carry their offset from it in framebuffer pixels.
 */
struct Vertex {
    float x;
    float y;
    float offsetX;     // a sprite corner's offset from the anchor in framebuffer pixels, 0 for geometry
    float offsetY;
    float u;           // atlas coordinates, 0 to 1
    float v;
    uint32_t colour;   // premultiplied RGBA, red in the low byte
    float distance;    // signed distance across a stroke in window pixels, 0 elsewhere
    float halfWidth;   // a stroke's half width; large for fills, negative for sprite corners
};

/**
 * The triangles resolved for one frame.
 */
struct Batch {
    uint64_t frame = 0;        // the frame object endFrame queued them with, 0 without a render thread
    uint32_t seq = 0;          // publish order, 0 for an empty or taken slot
    uint32_t screenWidth = 0;  // the window size the vertices are in
    uint32_t screenHeight = 0;
    uint32_t atlasVersion = 0; // the atlas version the sprites need
    size_t spriteStart = 0;    // the first sprite vertex; the vertices before it are geometry
    std::vector<Vertex> vertices;
};

/**
 * Parses the handshake's draw block (NativeProtocol.drawLayout's layout), from the first handshake
 * that names a complete layout.
 *
 * @param block the draw block
 */
void install(FrameReader block);

/**
 * Whether DRAW_SPRITES and DRAW_LIST take effect: the layout is known, and endFrame and the present
 * are hooked.
 *
 * @return true while the draw list is drawn
 */
bool live();

/**
 * Copies a DRAW_SPRITES command's sprites into the atlas and bumps its version. Reader thread; a
 * malformed command copies the sprites before the bad one.
 *
 * @param body the frame's body after the type byte
 */
void sprites(FrameReader body);

/**
 * Takes a DRAW_LIST command's items, which replace the ones held from the next frame on. Reader
 * thread; a malformed command changes nothing.
 *
 * @param body the frame's body after the type byte
 */
void command(FrameReader body);

/**
 * Resolves the items against the state the frame was recorded from and publishes the batch, empty
 * when there is nothing to draw. Game thread, in the endFrame detour, before the frame is queued.
 *
 * @param frame the frame object being queued, 0 without a render thread
 */
void resolve(uint64_t frame);

/**
 * Drops the items, as when the connection that sent them closes. The next frame draws nothing.
 */
void clear();

/**
 * Takes the batch resolved for the frame being presented, or the newest one if none was. Present
 * thread.
 *
 * @param frame the frame object being presented, 0 without a render thread
 * @param out   receives the batch; its old storage goes back to the slot
 * @return false if there was no batch to take
 */
bool takeBatch(uint64_t frame, Batch &out);

/**
 * Copies the atlas rows changed since the last copy (or every row) out of the atlas, unless the reader
 * thread is writing it right now. Present thread.
 *
 * @param all     copy every row, as a new texture needs
 * @param pixels  receives the rows, BGRA, DRAW_ATLAS_SIZE pixels wide
 * @param top     receives the first row copied
 * @param bottom  receives one past the last row copied (top when nothing changed)
 * @param version receives the atlas version the rows bring the texture to
 * @return false if the atlas was busy; try again at the next present
 */
bool copyAtlas(bool all, std::vector<uint8_t> &pixels, uint32_t &top, uint32_t &bottom, uint32_t &version);

} // namespace kraken::drawlist
