package com.kraken.api.events;

import com.kraken.api.coords.LocalPoint;
import com.kraken.api.scene.Projectile;
import lombok.Data;

/**
 * A projectile is heading for a point. It is posted once, when the projectile first appears in the client's list, with
 * where its target is then. The API can't see the client re-aim the projectile on later cycles (every cycle for one
 * following an actor).
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
