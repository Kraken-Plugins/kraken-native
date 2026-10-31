package com.kraken.api.render;

import com.kraken.api.actor.Actor;
import com.kraken.api.internal.render.WireVisibility;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One plugin's rules for what the client leaves undrawn, from {@link EntityVisibility#createRules()}. The rules hide
 * whole kinds of entity (the local player, the other players, NPCs, every projectile, graphics objects by id) and
 * decide single actors apart from their kind, each in two layers: 3D, the model, and 2D, the name, head bars and
 * overhead text. A new set hides nothing.
 *
 * <p>Changes take effect at the end of the client thread's pass, a frame or so later. A decision about an actor holds
 * while the actor is loaded and is dropped once it is gone. Closing the rules shows again everything they hid; a
 * closed set ignores further changes.
 *
 * <p>Thread-safe.
 */
public final class VisibilityRules implements AutoCloseable {

    private final EntityVisibility owner;

    /**
     * Guarded by the owner's lock.
     */
    private int classes;
    private Set<Integer> graphics = Set.of();
    private final Map<Actor, Integer> actors = new HashMap<>();
    private boolean closed;

    /**
     * Empty rules.
     *
     * @param owner the client's visibility, which sends them
     */
    VisibilityRules(EntityVisibility owner) {
        this.owner = owner;
    }

    /**
     * Sets whether the local player is hidden, unless it is decided apart ({@link #setHidden}).
     *
     * @param hide3D true to hide its model
     * @param hide2D true to hide its name, head bars and overhead text
     */
    public void setLocalPlayerHidden(boolean hide3D, boolean hide2D) {
        setClasses(WireVisibility.HIDE_LOCAL_3D, hide3D, WireVisibility.HIDE_LOCAL_2D, hide2D);
    }

    /**
     * Sets whether every other player is hidden, in every world, unless decided apart.
     *
     * @param hide3D true to hide their models
     * @param hide2D true to hide their names, head bars and overhead text
     */
    public void setPlayersHidden(boolean hide3D, boolean hide2D) {
        setClasses(WireVisibility.HIDE_PLAYERS_3D, hide3D, WireVisibility.HIDE_PLAYERS_2D, hide2D);
    }

    /**
     * Sets whether every NPC is hidden, in every world, unless decided apart.
     *
     * @param hide3D true to hide their models
     * @param hide2D true to hide their names, head bars and overhead text
     */
    public void setNpcsHidden(boolean hide3D, boolean hide2D) {
        setClasses(WireVisibility.HIDE_NPCS_3D, hide3D, WireVisibility.HIDE_NPCS_2D, hide2D);
    }

    /**
     * Sets whether every projectile in flight is hidden, keyed or not.
     *
     * @param hidden true to hide them
     */
    public void setProjectilesHidden(boolean hidden) {
        setClasses(WireVisibility.HIDE_PROJECTILES, hidden, 0, false);
    }

    /**
     * Sets which graphics objects (spot anims played on a tile) are hidden, by spot anim id, replacing the ids set
     * before. The client's native hook takes {@value EntityVisibility#MAX_GRAPHICS} ids from all plugins together.
     *
     * @param spotAnimIds the ids; empty to hide none
     */
    public void setGraphicsObjectsHidden(Collection<Integer> spotAnimIds) {
        Set<Integer> ids = Set.copyOf(spotAnimIds);
        synchronized (owner.lock()) {
            if (closed || ids.equals(graphics)) {
                return;
            }
            graphics = ids;
            owner.changed();
        }
    }

    /**
     * Decides one actor apart from its kind, while it is loaded.
     *
     * @param actor  the player or NPC
     * @param hide3D true to hide its model
     * @param hide2D true to hide its name, head bars and overhead text
     */
    public void setHidden(Actor actor, boolean hide3D, boolean hide2D) {
        Objects.requireNonNull(actor, "actor");
        int hidden = (hide3D ? WireVisibility.LAYER_3D : 0) | (hide2D ? WireVisibility.LAYER_2D : 0);
        synchronized (owner.lock()) {
            if (closed || Integer.valueOf(hidden).equals(actors.get(actor))) {
                return;
            }
            actors.put(actor, hidden);
            owner.changed();
        }
    }

    /**
     * Lets one actor follow its kind again.
     *
     * @param actor the player or NPC
     */
    public void reset(Actor actor) {
        synchronized (owner.lock()) {
            if (actors.remove(actor) != null) {
                owner.changed();
            }
        }
    }

    /**
     * Lets every actor follow its kind again.
     */
    public void resetActors() {
        synchronized (owner.lock()) {
            if (!actors.isEmpty()) {
                actors.clear();
                owner.changed();
            }
        }
    }

    /**
     * Whether the rules have been closed.
     *
     * @return true once closed
     */
    public boolean isClosed() {
        synchronized (owner.lock()) {
            return closed;
        }
    }

    /**
     * Drops the rules, showing again what they hid. Idempotent.
     */
    @Override
    public void close() {
        synchronized (owner.lock()) {
            if (closed) {
                return;
            }
            closed = true;
            actors.clear();
            owner.closed(this);
        }
    }

    /**
     * The kinds these rules hide. Under the owner's lock.
     *
     * @return the {@code HIDE_*} bits
     */
    int classes() {
        return classes;
    }

    /**
     * The graphics object ids these rules hide. Under the owner's lock.
     *
     * @return the ids
     */
    Set<Integer> graphics() {
        return graphics;
    }

    /**
     * The actors these rules decide apart. Under the owner's lock.
     *
     * @return the actors
     */
    List<Actor> actors() {
        return List.copyOf(actors.keySet());
    }

    /**
     * The layers these rules hide of an actor: its decision here, or its kind's.
     *
     * @param actor the actor
     * @param kind  its kind's {@code HIDE_*} bits
     * @return the {@code LAYER_*} bits hidden
     */
    int hiddenLayers(Actor actor, int kind) {
        Integer decided = actors.get(actor);
        return decided != null ? decided : EntityVisibility.layers(classes, kind);
    }

    /**
     * Drops the decisions about actors that have gone. Under the owner's lock.
     *
     * @return true if any was dropped
     */
    boolean pruneGone() {
        return actors.keySet().removeIf(actor -> actor.getAddress() == 0);
    }

    private void setClasses(int first, boolean onFirst, int second, boolean onSecond) {
        synchronized (owner.lock()) {
            if (closed) {
                return;
            }
            int updated = (classes & ~(first | second)) | (onFirst ? first : 0) | (onSecond ? second : 0);
            if (updated != classes) {
                classes = updated;
                owner.changed();
            }
        }
    }
}
