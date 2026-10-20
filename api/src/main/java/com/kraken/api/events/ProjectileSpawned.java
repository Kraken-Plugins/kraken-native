package com.kraken.api.events;

import com.kraken.api.scene.Projectile;
import lombok.Data;

/**
 * A projectile has appeared in the client's list. A Kraken extra: RuneLite has no spawn event for projectiles and
 * plugins use the first {@link ProjectileMoved} instead, which Kraken posts right after this one. Subscribe to this
 * for the plain fact of a new projectile, to {@link ProjectileMoved} for where it is heading.
 */
@Data
public class ProjectileSpawned {

    /**
     * The projectile, as read when it appeared.
     */
    private Projectile projectile;
}
