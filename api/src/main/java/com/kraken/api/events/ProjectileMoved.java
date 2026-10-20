package com.kraken.api.events;

import com.kraken.api.coords.LocalPoint;
import com.kraken.api.scene.Projectile;
import lombok.Data;

/**
 * A projectile is heading for a point, as RuneLite's {@code ProjectileMoved}. RuneLite posts it each time the client
 * aims a projectile (every cycle for one following an actor); Kraken can't see the client re-aim, so it posts it once,
 * when the projectile first appears in the client's list, with where its target is then. RuneLite plugins that act on
 * a projectile once (the usual case) work unchanged.
 */
@Data
public class ProjectileMoved {

    /**
     * The projectile, as read when it appeared.
     */
    private Projectile projectile;

    /**
     * Where it is heading: its target actor's location, or the centre of its target tile.
     */
    private LocalPoint position;

    /**
     * The height it is heading for: the ground there less the projectile's end height.
     */
    private int z;
}
