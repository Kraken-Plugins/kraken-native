package com.kraken.api.lua;

import com.kraken.api.lua.types.ConfigTypes;
import com.kraken.api.lua.types.LocType;
import com.kraken.api.lua.types.NpcType;
import com.kraken.api.lua.types.ObjType;

/**
 * Mirrors the Lua {@code osrs.Engine} config decoders ({@code Engine.ConfigDecoders}): a type's whole definition by id,
 * decoded from the client's config archive as the client's own loaders build it (see {@link ConfigTypes}).
 *
 * <p>Each returns null only if config types can't be read; an id without a definition gets the client's default type,
 * as in the client. The database-row decoders ({@code decodeDBRow} and friends) aren't mirrored yet.
 */
public final class Engine {

    private final ClientOp client;

    public Engine(ClientOp client) {
        this.client = client;
    }

    /**
     * Mirrors {@code decodeObjType}: an item's definition, as on the world the client is on (members or free).
     *
     * @param objId the item id
     * @return the item's definition (the client's default type for an id without one), or null if config types can't
     *         be read
     */
    public ObjType decodeObjType(int objId) {
        return client.getObjType(objId);
    }

    /**
     * Mirrors {@code decodeNpcType}: an NPC type's definition.
     *
     * @param npcTypeId the NPC type id (the wiki id, not a loaded NPC's index)
     * @return the type's definition (the client's default type for an id without one), or null if config types can't
     *         be read
     */
    public NpcType decodeNpcType(int npcTypeId) {
        return client.configs().npc(npcTypeId);
    }

    /**
     * Mirrors {@code decodeLocType}: a loc's (scenery's) definition.
     *
     * @param locId the loc id
     * @return the loc's definition (the client's default type for an id without one), or null if config types can't be
     *         read
     */
    public LocType decodeLocType(int locId) {
        return client.configs().loc(locId);
    }
}
