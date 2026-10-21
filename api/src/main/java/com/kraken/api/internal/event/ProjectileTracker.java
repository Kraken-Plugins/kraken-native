package com.kraken.api.internal.event;

import com.kraken.api.actor.Actor;
import com.kraken.api.coords.LocalPoint;
import com.kraken.api.events.ProjectileMoved;
import com.kraken.api.events.ProjectileSpawned;
import com.kraken.api.internal.ProjectileEndpoint;
import com.kraken.api.scene.Projectile;
import com.kraken.api.scene.WorldView;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link ProjectileMoved} once per projectile, the first poll it is in the client's list. A projectile is known by
 * what the server sent for it (spot anim, endpoints, cycles, slope, start distance); identical ones are counted.
 */
final class ProjectileTracker extends Tracker {

    private Map<Flight, Integer> last = Map.of();

    ProjectileTracker(TrackerContext context) {
        super(context, ProjectileSpawned.class, ProjectileMoved.class);
    }

    @Override
    Set<String> triggerNames() {
        return Set.of(NativeEvent.PROJECTILE_CTOR, NativeEvent.PROJECTILE_STEP);
    }

    @Override
    boolean baseline(boolean replay) {
        last = count(client.getProjectiles(), null);
        return true;
    }

    @Override
    void update(boolean ticked) {
        List<Projectile> fresh = new ArrayList<>();
        last = count(client.getProjectiles(), fresh);
        fresh.forEach(this::post);
    }

    @Override
    void forget() {
        last = Map.of();
    }

    /**
     * Counts the projectiles by flight, noting those beyond what the last read counted.
     *
     * @param projectiles this read's projectiles
     * @param fresh       where to add the new ones, or null
     * @return the counts
     */
    private Map<Flight, Integer> count(Collection<Projectile> projectiles, List<Projectile> fresh) {
        Map<Flight, Integer> counts = new HashMap<>();
        for (Projectile projectile : projectiles) {
            int seen = counts.merge(Flight.of(projectile), 1, Integer::sum);
            if (fresh != null && seen > last.getOrDefault(Flight.of(projectile), 0)) {
                fresh.add(projectile);
            }
        }
        return counts;
    }

    /**
     * Posts a new projectile: {@link ProjectileSpawned}, then {@link ProjectileMoved} with where its target is
     * now, the target actor's location if it is loaded, else the centre of the target tile.
     *
     * @param projectile the projectile
     */
    private void post(Projectile projectile) {
        ProjectileSpawned spawned = new ProjectileSpawned();
        spawned.setProjectile(projectile);
        bus.post(spawned);
        ProjectileEndpoint target = projectile.getTarget();
        LocalPoint position = null;
        Actor actor = client.actor(target.actorType(), target.actorIndex());
        if (actor != null) {
            position = actor.getLocalLocation();
        }
        WorldView view = client.getTopLevelWorldView();
        if (position == null) {
            position = LocalPoint.fromWorld(view, target.tile().x(), target.tile().z());
        }
        ProjectileMoved event = new ProjectileMoved();
        event.setProjectile(projectile);
        event.setPosition(position);
        if (position != null) {
            event.setZ(view.getTileHeight(position.getX(), position.getY(), target.tile().level())
                    - target.height());
        }
        bus.post(event);
    }

    /**
     * What identifies a projectile from one read to the next: everything the server sent for it.
     *
     * @param id            the spot anim
     * @param source        where it comes from
     * @param target        where it goes
     * @param startCycle    its start cycle
     * @param endCycle      its end cycle
     * @param slope         its slope
     * @param startDistance its start distance
     */
    private record Flight(int id, ProjectileEndpoint source, ProjectileEndpoint target, int startCycle, int endCycle,
                          int slope, int startDistance) {

        static Flight of(Projectile p) {
            return new Flight(p.getId(), p.getSource(), p.getTarget(), p.getStartCycle(), p.getEndCycle(),
                    p.getSlope(), p.getStartPos());
        }
    }
}
