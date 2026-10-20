package com.kraken.client.ui.devtools.tools;

import com.kraken.api.Client;
import com.kraken.api.Perspective;
import com.kraken.api.actor.Actor;
import com.kraken.api.actor.NPC;
import com.kraken.api.actor.Player;
import com.kraken.api.coords.LocalPoint;
import com.kraken.api.coords.WorldPoint;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.StringJoiner;

/**
 * Formatting shared by the actor tools.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class Actors {
    /**
     * What an actor is interacting with, e.g. "NPC 1234 Goblin", or "" if nothing.
     *
     * @param actor the NPC or player
     * @return a label such as "NPC 1234 Goblin", or "" if it isn't interacting
     */
    static String target(Actor actor) {
        if (!actor.isInteracting()) {
            return "";
        }
        Actor other = actor.getInteracting();
        int id = actor.getInteractingIndex();
        return switch (actor.getInteractingType()) {
            case Actor.INTERACTING_NPC -> "NPC " + id + named(other == null ? null : other.getName());
            case Actor.INTERACTING_PLAYER -> "Player " + id + named(other == null ? null : other.getName());
            case Actor.INTERACTING_WORLD_ENTITY -> "World entity " + id;
            default -> "";
        };
    }

    /**
     * A label for an actor: its kind, index or id, and name, e.g. "NPC 1234 Goblin".
     *
     * @param actor the NPC or player
     * @return the label, or "" for anything else
     */
    static String label(Actor actor) {
        if (actor instanceof NPC npc) {
            return "NPC " + npc.getIndex() + named(npc.getName());
        }
        if (actor instanceof Player player) {
            return "Player " + player.getId() + named(player.getName());
        }
        return "";
    }

    /**
     * "dead", "health bar" or "".
     *
     * @param actor the NPC or player
     * @return "dead", "health bar" or ""
     */
    static String health(Actor actor) {
        if (actor.isDead()) {
            return "dead";
        }
        return actor.isHealthbarRendered() ? "health bar" : "";
    }

    /**
     * Which parts of the actor are hidden, e.g. "model", or "".
     *
     * @param actor the NPC or player
     * @return "model", "2D", both comma-separated, or ""
     */
    static String hidden(Actor actor) {
        StringJoiner hidden = new StringJoiner(", ");
        if (actor.isHidden()) {
            hidden.add("model");
        }
        if (!actor.isRendered2D()) {
            hidden.add("2D");
        }
        return hidden.toString();
    }

    /**
     * The ground height under an actor, or null if its location is unknown.
     *
     * @param client the client
     * @param actor  the NPC or player
     * @return the height (more negative is higher), or null
     */
    static Integer height(Client client, Actor actor) {
        LocalPoint local = actor.getLocalLocation();
        WorldPoint world = actor.getWorldLocation();
        return local == null || world == null ? null : Perspective.getTileHeight(client, local, world.getPlane());
    }

    private static String named(String name) {
        return name == null || name.isEmpty() ? "" : " " + name;
    }
}
