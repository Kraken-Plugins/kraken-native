package com.kraken.api.scene;

import com.kraken.api.Client;
import com.kraken.api.internal.Loc;

/**
 * A decoration on a tile's floor, such as flowers or a rug. Not an item lying on the ground: that is a
 * {@link TileItem}.
 */
public final class GroundObject extends TileObject {

    /**
     * A floor decoration as the scene listing found it.
     *
     * @param client the client
     * @param loc    the decoration
     * @param baseX  the map base x the scene was read with
     * @param baseY  the map base y
     */
    GroundObject(Client client, Loc loc, int baseX, int baseY) {
        super(client, loc, baseX, baseY);
    }
}
