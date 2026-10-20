package com.kraken.api.scene;

import com.kraken.api.Client;
import com.kraken.api.internal.Loc;

/**
 * A wall, fence or door on a tile's edge. Its orientation and config bits aren't read.
 */
public final class WallObject extends TileObject {

    /**
     * A wall as the scene listing found it.
     *
     * @param client the client
     * @param loc    the wall
     * @param baseX  the map base x the scene was read with
     * @param baseY  the map base y
     */
    WallObject(Client client, Loc loc, int baseX, int baseY) {
        super(client, loc, baseX, baseY);
    }
}
