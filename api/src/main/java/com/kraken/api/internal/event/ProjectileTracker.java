package com.kraken.api.internal.event;

import com.kraken.api.actor.Actor;
import com.kraken.api.coords.LocalPoint;
import com.kraken.api.events.ProjectileMoved;
import com.kraken.api.events.ProjectileSpawned;
import com.kraken.api.internal.ProjectileEndpoint;
import com.kraken.api.internal.reader.ProjectileReader;
import com.kraken.api.scene.Projectile;
import com.kraken.api.scene.WorldView;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link ProjectileSpawned} and {@link ProjectileMoved} once per projectile, the first poll it is in flight, and for a
 * keyed projectile another {@link ProjectileMoved} each time the server retargets it. A projectile from the client's
 * list is known by what the server sent for it (spot anim, endpoints, cycles, slope, start distance), and identical
 * ones are counted; a keyed projectile by its key, its place in memory, its spot anim and its source, so a new target
 * is the same projectile moving. A read of the keyed projectiles that fails keeps the last one.
 */
final class ProjectileTracker extends Tracker {

    private Map<Flight, Integer> last = Map.of();

    /**
     * The keyed projectiles of the last read, each with the target last posted for it.
     */
    private Map<Keyed, ProjectileEndpoint> lastKeyed = Map.of();

    ProjectileTracker(TrackerContext context) {
        super(context, ProjectileSpawned.class, ProjectileMoved.class);
    }

    @Override
    Set<String> triggerNames() {
        return Set.of(NativeEvent.PROJECTILE_CTOR, NativeEvent.PROJECTILE_STEP);
    }

    @Override
    boolean baseline(boolean replay) {
        List<Projectile> keyed = reader().keyed();
        if (keyed == null) {
            return false;
        }
        last = count(reader().listed(), null);
        lastKeyed = targets(keyed);
        return true;
    }

    @Override
    void update(boolean ticked) {
        List<Projectile> fresh = new ArrayList<>();
        last = count(reader().listed(), fresh);
        fresh.forEach(this::post);

        List<Projectile> keyed = reader().keyed();
        if (keyed == null) {
            return;
        }
        Map<Keyed, ProjectileEndpoint> previous = lastKeyed;
        lastKeyed = targets(keyed);
        for (Projectile projectile : keyed) {
            ProjectileEndpoint before = previous.get(Keyed.of(projectile));
            if (before == null) {
                post(projectile);
            } else if (!before.equals(projectile.getTarget())) {
                postMoved(projectile);
            }
        }
    }

    @Override
    void forget() {
        last = Map.of();
        lastKeyed = Map.of();
    }

    private ProjectileReader reader() {
        return client.getProjectileReader();
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
     * The keyed projectiles of a read, each with its target.
     *
     * @param projectiles the read
     * @return the targets by projectile
     */
    private static Map<Keyed, ProjectileEndpoint> targets(List<Projectile> projectiles) {
        Map<Keyed, ProjectileEndpoint> out = new HashMap<>();
        for (Projectile projectile : projectiles) {
            out.put(Keyed.of(projectile), projectile.getTarget());
        }
        return out;
    }

    /**
     * Posts a new projectile: {@link ProjectileSpawned}, then {@link ProjectileMoved}.
     *
     * @param projectile the projectile
     */
    private void post(Projectile projectile) {
        ProjectileSpawned spawned = new ProjectileSpawned();
        spawned.setProjectile(projectile);
        bus.post(spawned);
        postMoved(projectile);
    }

    /**
     * Posts {@link ProjectileMoved} with where a projectile's target is now: the target actor's location if it is
     * loaded, else the centre of the target tile, moved by the target's offsets.
     *
     * @param projectile the projectile
     */
    private void postMoved(Projectile projectile) {
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
        if (position != null && (target.offsetX() != 0 || target.offsetZ() != 0)) {
            position = new LocalPoint(position.getX() + target.offsetX(), position.getY() + target.offsetZ(),
                    position.getWorldView());
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
     * What identifies a projectile from the client's list from one read to the next: everything the server sent for
     * it.
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

    /**
     * What identifies a keyed projectile from one read to the next: what a retarget leaves alone.
     *
     * @param key     the key it was sent with
     * @param address where it is in the client's memory
     * @param id      the spot anim
     * @param source  where it comes from
     */
    private record Keyed(int key, long address, int id, ProjectileEndpoint source) {

        static Keyed of(Projectile p) {
            return new Keyed(p.getKey(), p.getAddress(), p.getId(), p.getSource());
        }
    }
}
