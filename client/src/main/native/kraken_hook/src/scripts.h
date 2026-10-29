// The script hooks. When the handshake names the script runtime's functions and layout, four detours
// report each script the client runs: a root run as it starts (with the listener's request, if a
// listener ran it), each gosub as the callee is looked up, and each return, with the top of the int
// and object stacks as they are at that moment. They only send while the Kraken client asks for them
// (docs/event-hooks-design.md section 2.6).
#pragma once

#include "core/frame_reader.h"

namespace kraken::scripts {

/**
 * Parses the handshake's script block and hooks the script runtime's four functions, from the first
 * handshake that names them; a later one only retries the hooks that failed, with the first layout.
 *
 * @param block the script block, NativeProtocol.scriptLayout's layout
 */
void install(FrameReader block);

/**
 * Whether all four script hooks are live.
 *
 * @return true once they all installed
 */
bool hooked();

/**
 * Sets which script events to send from a SCRIPTS command; ignored while the script hooks aren't live.
 *
 * @param body the frame's body after the type byte
 */
void setCapture(FrameReader body);

/**
 * Stops sending script events, as when the connection drops.
 */
void stopCapture();

} // namespace kraken::scripts
