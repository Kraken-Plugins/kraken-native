package com.kraken.api.action;

import com.kraken.api.Client;
import com.kraken.api.coords.LocalPoint;
import com.kraken.api.coords.WorldPoint;

/**
 * Walking and running. Get it from {@code client.getMovement()} or inject it.
 *
 * <p>Walking reaches tiles of the loaded scene only; walking further needs a pathfinder, and the minimap walk isn't
 * mapped.
 */
public final class Movement {

    private final Client client;

    /**
     * Creates the service. {@code Client} makes the one plugins use.
     *
     * @param client the client
     */
    public Movement(Client client) {
        this.client = client;
    }

    /**
     * Walks to a tile, as Walk here on it does. The client skips a tile equal to its last walk target.
     *
     * @param tile the world tile
     * @return {@link ActionResult#SENT}, or why nothing was sent; {@link ActionResult#OUT_OF_SCENE} if the tile is
     *         outside the loaded scene
     */
    public ActionResult walk(WorldPoint tile) {
        throw new UnsupportedOperationException("not built yet");
    }

    /**
     * Walks to a local point's tile; see {@link #walk(WorldPoint)}.
     *
     * @param point the local point, in the top-level world
     * @return {@link ActionResult#SENT}, or why nothing was sent; {@link ActionResult#OUT_OF_SCENE} if it is outside
     *         the loaded scene
     */
    public ActionResult walk(LocalPoint point) {
        throw new UnsupportedOperationException("not built yet");
    }

    /**
     * Whether the player runs: varp {@code OPTION_RUN} (173), or what {@link #setRunning} last sent while the server's
     * answer is due.
     *
     * @return true when running
     */
    public boolean isRunning() {
        throw new UnsupportedOperationException("not built yet");
    }

    /**
     * Turns running on or off with the run orb by the minimap, only when the state differs.
     *
     * @param running true to run
     * @return {@link ActionResult#SENT}, also when the state is already the one asked for (nothing is sent then); or
     *         why nothing was sent, {@link ActionResult#NOT_FOUND} if the run orb isn't loaded
     */
    public ActionResult setRunning(boolean running) {
        throw new UnsupportedOperationException("not built yet");
    }

    /**
     * Where the player is walking to: {@link Client#getLocalDestinationLocation()}.
     *
     * @return the destination tile's centre, or null when there is none
     */
    public LocalPoint getDestination() {
        throw new UnsupportedOperationException("not built yet");
    }
}
