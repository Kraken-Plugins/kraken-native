// Component writes. The API knows a component by its packed id and its index among its static parent's
// dynamic children, and the object behind them can be freed and replaced whenever the game closes an
// interface or a script rebuilds a layer's children, so a write can't name an address. COMPONENTS
// commands name components by id instead; the pump looks each one up on the game thread at the start
// of the next frame, the way the client's own lookup does but without loading an interface that isn't
// loaded, and only then writes a field or clears a listener. A component that has gone is skipped.
#pragma once

#include "core/frame_reader.h"

namespace kraken::components {

/**
 * Parses the handshake's component block, from the first handshake that names a complete layout.
 *
 * @param block the component block, NativeProtocol.componentLayout's layout
 */
void install(FrameReader block);

/**
 * Whether COMPONENTS commands take effect: the layout is known and the pump is hooked.
 *
 * @return true while ops are run
 */
bool live();

/**
 * Queues the ops of a COMPONENTS command for the pump. Reader thread; a malformed command queues
 * nothing, and ops past MAX_COMPONENT_OPS waiting are dropped.
 *
 * @param body the frame's body after the type byte
 */
void queue(FrameReader body);

/**
 * Runs every queued op, in order. Game thread only, in the pump detour.
 */
void runPending();

/**
 * Drops every queued op, as when the connection that sent them closes.
 */
void clear();

} // namespace kraken::components
