package com.kraken.client.devtools.tools;

import com.kraken.api.lua.ClientOp;
import com.kraken.api.lua.Entity;
import com.kraken.api.lua.FineCoord;

import java.util.StringJoiner;

/**
 * Formatting shared by the actor tools.
 */
final class Actors {
    private Actors() {
    }

    /**
     * What an entity is interacting with, e.g. "NPC 1234 Goblin", or "" if nothing.
     *
     * @param client reads the target's name
     * @param entity the NPC or player
     * @return a label such as "NPC 1234 Goblin", or "" if it isn't interacting
     */
    static String target(ClientOp client, Entity entity) {
        return target(client, entity.getInteractingType(), entity.isInteracting());
    }

    static String target(ClientOp client, int type, int id) {
        if (id < 0) {
            return "";
        }
        return switch (type) {
            case Entity.TARGET_NPC -> "NPC " + id + named(client.npcName(id));
            case Entity.TARGET_PLAYER -> "Player " + id + named(client.playerName(id));
            case Entity.TARGET_WORLD_ENTITY -> "World entity " + id;
            default -> "";
        };
    }

    /**
     * "dead", "health bar" or "".
     *
     * @param entity the NPC or player
     * @return "dead", "health bar" or ""
     */
    static String health(Entity entity) {
        if (entity.isDead()) {
            return "dead";
        }
        return entity.isHealthbarRendered() ? "health bar" : "";
    }

    /**
     * Which parts of the entity are hidden, e.g. "model", or "".
     *
     * @param entity the NPC or player
     * @return "model", "2D", both comma-separated, or ""
     */
    static String hidden(Entity entity) {
        StringJoiner hidden = new StringJoiner(", ");
        if (!entity.isRendered3D()) {
            hidden.add("model");
        }
        if (!entity.isRendered2D()) {
            hidden.add("2D");
        }
        return hidden.toString();
    }

    /**
     * The ground height of a fine coordinate, or null if it's missing.
     *
     * @param fine a fine coordinate, possibly {@link FineCoord#MISSING} or null
     * @return its height ({@code y}), or null
     */
    static Integer height(FineCoord fine) {
        return fine == null || fine.isMissing() ? null : fine.y();
    }

    private static String named(String name) {
        return name == null || name.isEmpty() ? "" : " " + name;
    }
}
