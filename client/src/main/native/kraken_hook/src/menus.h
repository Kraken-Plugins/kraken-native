// Menu edits. While the minimenu is closed the client rebuilds its rows every cycle, on the game
// thread, so edits can't wait for a round trip to Java: the Kraken client sends rules, and the
// detour on the view rebuild applies them to the fresh rows each time, before the cycle's click
// handling and the menu's drawing see them. Rules swap and move rows, hide them, rewrite their text
// and add rows of Kraken's own, whose clicks come back as MENU_CLICK instead of running anything in
// the game (docs/developer/menu-edits.md).
#pragma once

#include <cstdint>

#include "core/frame_reader.h"

namespace kraken::menus {

/**
 * Parses the handshake's menu block and hooks the view rebuild, the run and the node destroy, from
 * the first handshake that names them; a later one only retries the hooks that failed.
 *
 * @param block the menu block, NativeProtocol.menuLayout's layout
 */
void install(FrameReader block);

/**
 * Whether the three minimenu hooks are live.
 *
 * @return true once they all installed
 */
bool hooked();

/**
 * Applies a MENU command: replaces the rules, adds or replaces some by id, or removes some. Reader
 * thread; ignored while the hooks aren't live.
 *
 * @param body the frame's body after the type byte
 */
void command(FrameReader body);

/**
 * Drops every rule, as when the connection that sent them closes. The next rebuild shows the
 * client's own menu.
 */
void clear();

} // namespace kraken::menus
