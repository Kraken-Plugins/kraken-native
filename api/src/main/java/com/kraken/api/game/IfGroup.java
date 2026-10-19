package com.kraken.api.game;

import java.util.List;

/**
 * Mirrors the Lua {@code IfGroup} that {@link InterfaceQuery#retrieveIfGroup} returns: an interface (the chatbox, the
 * inventory…) as its static components and where it is drawn.
 *
 * @param id          the group id (not in Lua)
 * @param components  every static component in component order, so index {@code i} is component {@code i} (Lua's
 *                    {@code components[i + 1]}); an entry is null where the client holds no component. Unmodifiable
 * @param coordinates where the group is drawn; {@link InterfaceCoordinates#NONE} when it isn't open, as in the client
 */
public record IfGroup(int id, List<IfType> components, InterfaceCoordinates coordinates) {
}
