package com.kraken.api.events;

import com.kraken.api.scene.Projectile;
import lombok.Data;

/**
 * A projectile has appeared in the client's list. The API posts a {@link ProjectileMoved} right after this one, with
 * where the projectile is heading; subscribe to this for the plain fact of a new projectile.
 */
@Data
public class ProjectileSpawned {

    /**
     * The projectile, as read when it appeared.
     */
    private Projectile projectile;
}
