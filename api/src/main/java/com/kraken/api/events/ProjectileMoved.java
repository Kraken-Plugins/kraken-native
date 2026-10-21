package com.kraken.api.events;

import com.kraken.api.coords.LocalPoint;
import com.kraken.api.scene.Projectile;
import lombok.Data;

/**
 * A projectile is heading for a point. It is posted when the projectile first appears, with where its target is then,
 * and again each time the server retargets a keyed projectile ({@code Projectile.getKey()}), with where the new target
 * is. The API doesn't post it as the client re-aims a projectile on later cycles (every cycle for one following an
 * actor).
 */
@Data
public class ProjectileMoved {

    /**
     * The projectile, as read when it appeared.
     */
    private Projectile projectile;

    /**
     * Where it is heading: its target actor's location, or the centre of its target tile, moved by the target's offset
     * for a keyed projectile.
     */
    private LocalPoint position;

    /**
     * The height it is heading for: the ground there less the projectile's end height.
     */
    private int z;
}
