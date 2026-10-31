package com.kraken.api.internal.render;

import java.util.Arrays;
import java.util.List;

/**
 * The visibility rules as the native hook takes them: every plugin's rules combined into what to hide by kind of
 * entity, per-actor decisions by address, and graphics object ids. Internal: {@code EntityVisibility} builds these and
 * hands them to the {@link VisibilityHook}.
 *
 * @param classes   the {@code HIDE_*} bits: which kinds of entity are hidden unless an actor's decision says otherwise
 * @param actors    the actors decided apart from their kind
 * @param graphics  the spot anim ids of the graphics objects to hide
 */
public record WireVisibility(int classes, List<ActorRule> actors, int[] graphics) {

    /**
     * Hides the local player's model.
     */
    public static final int HIDE_LOCAL_3D = 1;

    /**
     * Hides the local player's 2D elements: name, head bars, overhead text.
     */
    public static final int HIDE_LOCAL_2D = 2;

    /**
     * Hides the other players' models.
     */
    public static final int HIDE_PLAYERS_3D = 4;

    /**
     * Hides the other players' 2D elements.
     */
    public static final int HIDE_PLAYERS_2D = 8;

    /**
     * Hides the NPCs' models.
     */
    public static final int HIDE_NPCS_3D = 16;

    /**
     * Hides the NPCs' 2D elements.
     */
    public static final int HIDE_NPCS_2D = 32;

    /**
     * Hides every projectile.
     */
    public static final int HIDE_PROJECTILES = 64;

    /**
     * An actor rule's layer bit for the model.
     */
    public static final int LAYER_3D = 1;

    /**
     * An actor rule's layer bit for the 2D elements.
     */
    public static final int LAYER_2D = 2;

    /**
     * Nothing hidden.
     */
    public static final WireVisibility NONE = new WireVisibility(0, List.of(), new int[0]);

    /**
     * Copies the lists, so the rules can't change after they are built.
     *
     * @param classes   the kinds hidden
     * @param actors    the actors decided apart
     * @param graphics  the graphics object ids hidden
     */
    public WireVisibility {
        actors = List.copyOf(actors);
        graphics = graphics.clone();
    }

    /**
     * The graphics object ids.
     *
     * @return a copy
     */
    @Override
    public int[] graphics() {
        return graphics.clone();
    }

    /**
     * Whether these rules hide nothing.
     *
     * @return true for no kinds, actors or graphics objects
     */
    public boolean isEmpty() {
        return classes == 0 && actors.isEmpty() && graphics.length == 0;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof WireVisibility rules && classes == rules.classes && actors.equals(rules.actors)
                && Arrays.equals(graphics, rules.graphics);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * classes + actors.hashCode()) + Arrays.hashCode(graphics);
    }

    /**
     * One actor's decision.
     *
     * @param address the actor's address in the game
     * @param decided the {@code LAYER_*} bits this decision sets, apart from the actor's kind
     * @param hidden  which of those it hides
     */
    public record ActorRule(long address, int decided, int hidden) {
    }
}
