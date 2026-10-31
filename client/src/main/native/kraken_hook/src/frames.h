// Frame sync (docs/developer/in-game-drawing.md, "Frame Sync"). The GL device records each frame on the
// game thread, and endFrame (functions.renderEndFrame) queues it for a render thread that presents it a
// frame or so later, while the game thread is already updating the next. While Java asks for it
// (FRAME_SYNC), the endFrame detour tags the frame it is about to queue, has the writer send DRAW with
// the tag, queues the frame, and holds the game thread until Java reports the overlay for that tag in
// the surface (FRAME_DONE), for at most FRAME_WAIT_MS. Java draws while the game is held, so the
// overlay comes from the same state the frame was recorded from, and the render thread runs the frame
// meanwhile. The present detour looks up the tag of the frame it presents, waits for that overlay if
// Java is still drawing it, and draws the slot Java filled for it.
#pragma once

#include <cstdint>

#include "core/frame_reader.h"

namespace kraken::frames {

/**
 * Creates the events that wake the game thread and the present thread when Java reports a frame
 * done. Call once, before any hook is installed.
 *
 * @return false if an event could not be created
 */
bool init();

/**
 * Parses the handshake's frame block, {u64 endFrame rva, u32 GlDevice.context, u32
 * GlDevice.recordingFrame, u32 GlDevice.renderingFrame}, and hooks endFrame, from the first
 * handshake that names a complete layout.
 *
 * @param block the frame block, NativeProtocol.frameLayout's layout
 */
void install(FrameReader block);

/**
 * Whether FRAME_SYNC takes effect: endFrame and the present are hooked and the layout is known.
 *
 * @return true while frame sync can be turned on
 */
bool live();

/**
 * Whether frame sync is on: live, and the last FRAME_SYNC asked for it.
 *
 * @return true while endFrame waits for Java's overlays
 */
bool syncing();

/**
 * Turns frame sync on or off, as a FRAME_SYNC command asks. Turning it off releases a game thread
 * waiting for a frame. Reader thread.
 *
 * @param body the frame's body after the type byte, {u8 on}
 */
void setSync(FrameReader body);

/**
 * Records a FRAME_DONE: the overlay for a frame is in the surface. Wakes the game thread waiting for
 * it. Reader thread.
 *
 * @param body the frame's body after the type byte, {u32 frame}
 */
void done(FrameReader body);

/**
 * Turns frame sync off, as when the connection drops, so the game never waits for a Java that has
 * gone.
 */
void clear();

/**
 * The tag of the last frame the game thread asked Java to draw.
 *
 * @return the tag, 0 before the first
 */
uint32_t requested();

/**
 * The tag of the frame the GL device is presenting. Present thread.
 *
 * @param context present's argument, the device's GlContext
 * @return the tag endFrame gave it, or 0 if frame sync is off or the frame wasn't tagged
 */
uint32_t tagOf(const void *context);

/**
 * Waits for the overlay of a tagged frame about to be presented, if Java was asked for it and is
 * still drawing it: until Java reports it done, the game thread gives up on it, frame sync turns
 * off, or FRAME_WAIT_MS pass. Present thread.
 *
 * @param tag the frame's tag, 0 for an untagged frame (no wait)
 */
void awaitOverlay(uint32_t tag);

} // namespace kraken::frames
