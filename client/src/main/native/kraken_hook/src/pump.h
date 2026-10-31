// The pump hook on the client's per-frame step (functions.appUpdate). Before the original runs it
// holds the frame to the frame cap, if one is set, then on the game thread runs the queued calls and
// component ops and applies the visibility rules (docs/developer/native-hook.md, "The Pump Hook").
#pragma once

#include <cstdint>

#include "core/frame_reader.h"

namespace kraken::pump {

/**
 * Creates the event that wakes a pump waiting out a frame. Call once, before the pump is hooked.
 *
 * @return false if the event could not be created
 */
bool init();

/**
 * Hooks the per-frame pump function, unless it is hooked already.
 *
 * @param rva the function's rva, 0 for none
 */
void install(uint64_t rva);

/**
 * Whether the pump hook is live.
 *
 * @return true once it installed
 */
bool hooked();

/**
 * Sets the frame cap a FRAME_LIMIT command names and wakes a pump waiting out a frame.
 *
 * @param body the frame's body after the type byte
 */
void setFrameLimit(FrameReader body);

/**
 * Lifts the frame cap, as when the connection drops, so a closed Kraken client never leaves the game
 * capped.
 */
void liftFrameLimit();

/**
 * The frame cap in force.
 *
 * @return frames a second, 0 for none
 */
uint32_t frameLimit();

/**
 * How many frame steps the pump has seen, for FPS while present isn't hooked.
 *
 * @return the frame steps so far
 */
uint64_t frameSteps();

} // namespace kraken::pump
