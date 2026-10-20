package com.kraken.api.scene;

import com.kraken.api.Client;
import com.kraken.api.internal.Loc;

/**
 * A decoration hung on a wall, such as a painting or a torch. Its offsets and config bits aren't read.
 */
public final class DecorativeObject extends TileObject {

    /**
     * A wall decoration as the scene listing found it.
     *
     * @param client the client
     * @param loc    the decoration
     * @param baseX  the map base x the scene was read with
     * @param baseY  the map base y
     */
    DecorativeObject(Client client, Loc loc, int baseX, int baseY) {
        super(client, loc, baseX, baseY);
    }
}
