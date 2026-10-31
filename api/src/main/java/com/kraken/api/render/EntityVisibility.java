package com.kraken.api.render;

import com.kraken.api.Client;
import com.kraken.api.actor.Actor;
import com.kraken.api.actor.NPC;
import com.kraken.api.actor.Player;
import com.kraken.api.internal.render.VisibilityHook;
import com.kraken.api.internal.render.WireVisibility;
import com.kraken.api.mapping.Mapping;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * What the client leaves undrawn: the players, NPCs, projectiles and graphics objects that plugins'
 * {@link VisibilityRules} hide. The client's scene draw skips an entity whose hidden flag is set, and draws an actor's
 * 2D elements (its name, head bars and overhead text) only while its 2D flag is set; these are the flags Lua's
 * {@code Entity.render3D}, {@code Entity.render2D} and {@code ClientOp.renderProjectiles} write. Every entity the
 * client creates starts shown, so the client's native hook applies the rules to each frame's entities anew, on the
 * game thread, at the start of the frame: one that appears is drawn for one frame before it is hidden.
 *
 * <p>Each plugin takes rules of its own ({@link #createRules()}). An entity is hidden if any plugin's rules hide it.
 * The hook remembers what it hid, and shows exactly that again once no rules hide it, when rules are closed, or when
 * the Kraken client disconnects; what something else hid is left alone.
 *
 * <p>Rules reach the client while its native hook is connected ({@link #isAvailable()}). They are remembered
 * regardless, sent whole whenever the hook (re)connects, and sent again at the end of each pass of the client thread
 * in which they changed ({@link #flush}). A decision about an actor is dropped once the actor is gone.
 *
 * <p>Thread-safe.
 */
public final class EntityVisibility {

    private static final Logger log = Logger.getLogger("kraken.api");

    /**
     * The most actors decided apart from their kind the hook takes; the rest follow their kind.
     */
    static final int MAX_ACTORS = 4096;

    /**
     * The most graphics object ids the hook takes.
     */
    static final int MAX_GRAPHICS = 64;

    /**
     * Client thread passes between looks for actors that have gone, about a second apart.
     */
    private static final int PRUNE_PASSES = 50;

    private final Client client;
    private final boolean mapped;
    private final List<VisibilityRules> rules = new ArrayList<>();

    private VisibilityHook hook;
    private WireVisibility sent;
    private boolean dirty;
    private boolean warned;
    private int passes;

    /**
     * Resolves whether this build can hide entities.
     *
     * @param client  the client, whose local player the rules tell apart
     * @param mapping the client build's mapping
     */
    public EntityVisibility(Client client, Mapping mapping) {
        this.client = Objects.requireNonNull(client, "client");
        this.mapped = mapping.has("Actor.hidden") && mapping.has("Actor.render2D");
    }

    /**
     * Takes a new, empty set of rules for a plugin, which hides nothing until told to. Close it when done, which
     * shows again what it hid.
     *
     * @return the rules
     */
    public synchronized VisibilityRules createRules() {
        VisibilityRules created = new VisibilityRules(this);
        rules.add(created);
        return created;
    }

    /**
     * Whether rules reach the client now: the hook is connected and can hide entities.
     *
     * @return true while rules take effect
     */
    public synchronized boolean isAvailable() {
        return hook != null && mapped;
    }

    /**
     * Whether this build's mapping has what the hook needs to hide actors: their hidden and 2D flags. Without it
     * nothing is sent, hook or not.
     *
     * @return true if rules can work once the hook connects
     */
    public boolean isSupported() {
        return mapped;
    }

    /**
     * Takes the connection to a hook that hides entities, or drops it; a new one is sent every rule at once.
     * Internal: {@code GameSession.setVisibilityHook} calls it.
     *
     * @param hook the connection, or null while there is none
     */
    public void setHook(VisibilityHook hook) {
        synchronized (this) {
            this.hook = hook;
            this.sent = null;
            this.dirty = true;
        }
        flush();
    }

    /**
     * Sends the rules if they changed since they were last sent, and once in a while drops the decisions about actors
     * that have gone. Called by the client thread at the end of each pass.
     */
    public void flush() {
        VisibilityHook target;
        WireVisibility wire;
        synchronized (this) {
            if (hook == null || !mapped) {
                return;
            }
            if (++passes >= PRUNE_PASSES) {
                passes = 0;
                for (VisibilityRules set : rules) {
                    dirty |= set.pruneGone();
                }
            }
            if (!dirty) {
                return;
            }
            dirty = false;
            wire = build();
            if (wire.equals(sent)) {
                return;
            }
            target = hook;
        }
        try {
            target.apply(wire);
            synchronized (this) {
                if (hook == target) {
                    sent = wire;
                }
            }
        } catch (RuntimeException e) {
            synchronized (this) {
                dirty = true;
            }
            log.log(Level.FINE, "Could not send the visibility rules to the native hook", e);
        }
    }

    /**
     * Notes that some rules changed, so the next {@link #flush} sends them.
     */
    synchronized void changed() {
        dirty = true;
    }

    /**
     * Drops closed rules, so what they hid shows again at the next {@link #flush}.
     *
     * @param closed the rules
     */
    synchronized void closed(VisibilityRules closed) {
        rules.remove(closed);
        dirty = true;
    }

    /**
     * The lock every set of rules changes under.
     *
     * @return the lock
     */
    Object lock() {
        return this;
    }

    /**
     * Combines every plugin's rules into the hook's: the kinds any of them hides, and for each actor some of them
     * decide apart, whether any of them hides it (by its decision there, or by its kind where a set doesn't decide
     * it), sent only where that differs from its kind.
     *
     * @return the combined rules
     */
    private WireVisibility build() {
        int classes = 0;
        Set<Integer> graphics = new TreeSet<>();
        Set<Actor> decided = new LinkedHashSet<>();
        for (VisibilityRules set : rules) {
            classes |= set.classes();
            graphics.addAll(set.graphics());
            decided.addAll(set.actors());
        }
        Player local = decided.isEmpty() ? null : client.getLocalPlayer();
        List<WireVisibility.ActorRule> actors = new ArrayList<>();
        for (Actor actor : decided) {
            int kind = kindBits(actor, local);
            int hidden = 0;
            for (VisibilityRules set : rules) {
                hidden |= set.hiddenLayers(actor, kind);
            }
            if (hidden == layers(classes, kind)) {
                continue;
            }
            long address = actor.getAddress();
            if (address == 0) {
                continue;
            }
            if (actors.size() == MAX_ACTORS) {
                warnOnce("More than " + MAX_ACTORS + " actors decided apart; the rest follow their kind");
                break;
            }
            actors.add(new WireVisibility.ActorRule(address, WireVisibility.LAYER_3D | WireVisibility.LAYER_2D,
                    hidden));
        }
        int[] ids = graphics.stream().limit(MAX_GRAPHICS).mapToInt(Integer::intValue).toArray();
        if (graphics.size() > MAX_GRAPHICS) {
            warnOnce("More than " + MAX_GRAPHICS + " graphics object ids hidden; the rest show");
        }
        return new WireVisibility(classes, actors, ids);
    }

    /**
     * The {@code HIDE_*} bits of an actor's kind, its model's first.
     *
     * @param actor the actor
     * @param local the local player, or null
     * @return the two bits for the actor's kind
     */
    static int kindBits(Actor actor, Player local) {
        if (actor instanceof NPC) {
            return WireVisibility.HIDE_NPCS_3D | WireVisibility.HIDE_NPCS_2D;
        }
        return actor == local ? WireVisibility.HIDE_LOCAL_3D | WireVisibility.HIDE_LOCAL_2D
                : WireVisibility.HIDE_PLAYERS_3D | WireVisibility.HIDE_PLAYERS_2D;
    }

    /**
     * The layers a kind's bits hide.
     *
     * @param classes the {@code HIDE_*} bits in force
     * @param kind    the kind's two bits, from {@link #kindBits}
     * @return the {@code LAYER_*} bits hidden
     */
    static int layers(int classes, int kind) {
        int model = Integer.lowestOneBit(kind);
        int twoD = kind & ~model;
        return ((classes & model) != 0 ? WireVisibility.LAYER_3D : 0)
                | ((classes & twoD) != 0 ? WireVisibility.LAYER_2D : 0);
    }

    private void warnOnce(String message) {
        if (!warned) {
            warned = true;
            log.warning(message);
        }
    }
}
