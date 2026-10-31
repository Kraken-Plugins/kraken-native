package com.kraken.api.action;

import com.kraken.api.Client;
import com.kraken.api.coords.LocalPoint;
import com.kraken.api.coords.WorldPoint;
import com.kraken.api.gameval.InterfaceID;
import com.kraken.api.gameval.VarPlayerID;
import com.kraken.api.internal.action.Toggle;
import com.kraken.api.scene.WorldView;
import com.kraken.api.widgets.Widget;

import java.util.logging.Logger;

/**
 * Walking and running. Get it from {@code client.getMovement()} or inject it.
 *
 * <p>Walking reaches tiles of the loaded scene only; walking further needs a pathfinder, and the minimap walk isn't
 * mapped.
 */
public final class Movement {

    private static final Logger log = Logger.getLogger("kraken.api");

    /**
     * The run orbs, the one with the minimap first, then the one without.
     */
    private static final int[] RUN_ORBS = {InterfaceID.Orbs.RUNBUTTON, InterfaceID.OrbsNomap.RUNBUTTON};

    private final Client client;
    private final Toggle runToggle;

    /**
     * Creates the service. {@code Client} makes the one plugins use.
     *
     * @param client the client
     */
    public Movement(Client client) {
        this.client = client;
        this.runToggle = new Toggle(client, () -> client.getVarpValue(VarPlayerID.OPTION_RUN), value -> value == 1);
    }

    /**
     * Walks to a tile, as Walk here on it does. The client skips a tile equal to its last walk target.
     *
     * @param tile the world tile
     * @return {@link ActionResult#SENT}, or why nothing was sent; {@link ActionResult#OUT_OF_SCENE} if the tile is
     *         outside the loaded scene
     */
    public ActionResult walk(WorldPoint tile) {
        return client.getInteractions().walk(tile);
    }

    /**
     * Walks to a local point's tile; see {@link #walk(WorldPoint)}.
     *
     * @param point the local point, in the top-level world
     * @return {@link ActionResult#SENT}, or why nothing was sent; {@link ActionResult#OUT_OF_SCENE} if it is outside
     *         the loaded scene
     */
    public ActionResult walk(LocalPoint point) {
        WorldView top = client.getTopLevelWorldView();
        if (point.getWorldView() != top.getId()) {
            log.fine(() -> "the local point " + point + " is not in the top-level world");
            return ActionResult.OUT_OF_SCENE;
        }
        return walk(WorldPoint.fromLocal(top, point.getX(), point.getY(), top.getPlane()));
    }

    /**
     * Whether the player runs: varp {@code OPTION_RUN} (173), or what {@link #setRunning} last sent while the server's
     * answer is due.
     *
     * @return true when running
     */
    public boolean isRunning() {
        return runToggle.isOn();
    }

    /**
     * Turns running on or off with the run orb by the minimap, only when the state differs.
     *
     * @param running true to run
     * @return {@link ActionResult#SENT}, also when the state is already the one asked for (nothing is sent then); or
     *         why nothing was sent, {@link ActionResult#NOT_FOUND} if the run orb isn't loaded
     */
    public ActionResult setRunning(boolean running) {
        Widget orb = runOrb();
        if (orb == null) {
            log.fine("no run orb is loaded, so running isn't changed");
            return ActionResult.NOT_FOUND;
        }
        return runToggle.set(running, orb::click);
    }

    /**
     * Where the player is walking to: {@link Client#getLocalDestinationLocation()}.
     *
     * @return the destination tile's centre, or null when there is none
     */
    public LocalPoint getDestination() {
        return client.getLocalDestinationLocation();
    }

    /**
     * The run orb to click: the first of the run orbs that is loaded and not hidden, else the first one loaded.
     *
     * @return the orb's component, or null if neither run orb is loaded
     */
    private Widget runOrb() {
        Widget loaded = null;
        for (int id : RUN_ORBS) {
            Widget orb = client.getWidget(id);
            if (orb == null) {
                continue;
            }
            if (!orb.isHidden()) {
                return orb;
            }
            if (loaded == null) {
                loaded = orb;
            }
        }
        return loaded;
    }
}
