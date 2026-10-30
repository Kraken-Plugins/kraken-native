// Key remapping. The game takes its keys from the WM_KEYDOWN, WM_KEYUP and WM_CHAR messages that reach the
// render view's window procedure, so a detour on it that rewrites or drops those messages changes what the
// game sees without a round trip: the Kraken client sends a table of modes, each a list of key rules, and
// says which mode is in force; a rule can also move to another mode as its key goes down, so Enter can
// start typing and Escape end it without waiting for Java (docs/developer/key-interception.md).
#pragma once

#include "core/frame_reader.h"

namespace kraken::keys {

/**
 * Parses the handshake's key block and hooks the window procedure, from the first handshake that names it;
 * a later one only retries a hook that failed.
 *
 * @param block the key block, NativeProtocol.keyLayout's layout
 */
void install(FrameReader block);

/**
 * Whether the window procedure is hooked.
 *
 * @return true once it installed
 */
bool hooked();

/**
 * Applies a KEYS command: the mode table, the mode in force, or whether to report keys. Reader thread;
 * ignored while the hook isn't live.
 *
 * @param body the frame's body after the type byte
 */
void command(FrameReader body);

/**
 * Drops the table and goes back to passing every key through, as when the connection that sent it closes.
 */
void clear();

} // namespace kraken::keys
