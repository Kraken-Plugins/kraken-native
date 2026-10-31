// Entity visibility. The client's scene draw skips an entity whose hidden byte is set, and an actor's
// name, head bars and overhead text are drawn only while its render2D byte is set; the Lua API's
// Entity.render3D/render2D and ClientOp.renderProjectiles write the same bytes. The client sets neither
// again on its own, but every entity it creates starts shown, so the Kraken client sends rules rather
// than writes: which kinds of entity to hide (the local player, other players, NPCs, projectiles),
// per-actor decisions that override them, and graphics objects to hide by spot anim id. The pump
// applies them on the game thread at the start of every frame, so an entity that appears is hidden
// from the frame after. The module remembers what it hid and shows exactly that again when a rule no
// longer asks for it or the connection drops, leaving what anything else hid alone.
#pragma once

#include "core/frame_reader.h"

namespace kraken::visibility {

/**
 * Parses the handshake's visibility block, from the first handshake that names a complete layout.
 *
 * @param block the visibility block, NativeProtocol.visibilityLayout's layout
 */
void install(FrameReader block);

/**
 * Whether VISIBILITY commands take effect: the layout is known and the pump is hooked.
 *
 * @return true while rules are applied
 */
bool live();

/**
 * Takes the rules of a VISIBILITY command, which replace the ones held. Reader thread; a malformed
 * command changes nothing.
 *
 * @param body the frame's body after the type byte
 */
void command(FrameReader body);

/**
 * Applies the rules to every player, NPC, projectile and graphics object, and shows again what this
 * module hid that no rule hides now. Game thread only, in the pump detour.
 */
void apply();

/**
 * Drops the rules, as when the connection that sent them closes. The next frame shows again
 * everything this module hid.
 */
void clear();

} // namespace kraken::visibility
