package com.kraken.api.scene;

import com.kraken.api.Client;
import com.kraken.api.internal.Loc;

/**
 * A decoration hung on a wall, such as a painting or a torch, as RuneLite's {@code DecorativeObject}. Its offsets and
 * config bits aren't read, so RuneLite's {@code getXOffset}, {@code getYOffset} and {@code getConfig} are left out.
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
