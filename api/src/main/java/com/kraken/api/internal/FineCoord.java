package com.kraken.api.internal;

import com.kraken.api.internal.reader.ActorReader;

/**
 * A position in fine units as {@link ActorReader} returns it: {@code x}/{@code z} are 1/128 of a tile (the scene's
 * tiles times 128, a {@code LocalPoint}'s space) and {@code y} is the ground height there. Heights follow the game's
 * convention: more negative is higher.
 *
 * @param x east-west position in 1/128 tiles
 * @param y ground height at the position (more negative is higher)
 * @param z north-south position in 1/128 tiles
 */
public record FineCoord(int x, int y, int z) {

    /**
     * What the client returns when there's nothing to report.
     */
    public static final FineCoord MISSING = new FineCoord(-1, -1, -1);

    /**
     * Whether this is {@link #MISSING}.
     *
     * @return true if all three values are -1
     */
    public boolean isMissing() {
        return equals(MISSING);
    }
}
