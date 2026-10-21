package com.kraken.api.highlight;

import com.kraken.api.actor.NPC;
import com.kraken.api.coords.WorldPoint;
import com.kraken.api.scene.TileItem;
import com.kraken.api.scene.TileObject;

import java.util.Objects;

/**
 * Something a highlight lights up: the key the client's highlight store keeps it under. A value, so the same target
 * built twice is equal, and a channel can be set to a collection of them. Build one from an API object with the
 * static factories, or directly from ids and tiles.
 */
public sealed interface HighlightTarget {

    /**
     * The category whose channels can light the target.
     *
     * @return the category
     */
    HighlightCategory category();

    /**
     * One loaded NPC.
     *
     * @param npc the NPC
     * @return the target, keyed by its index and creation cycle as they are now
     */
    static Npc npc(NPC npc) {
        return new Npc(npc.getIndex(), npc.getSpawnCycle());
    }

    /**
     * Every NPC of a type.
     *
     * @param id the NPC id, as in {@code gameval.NpcID}
     * @return the target
     */
    static NpcType npcType(int id) {
        return new NpcType(id);
    }

    /**
     * One player.
     *
     * @param player the player
     * @return the target, keyed by the player's display name as it is now
     */
    static Player player(com.kraken.api.actor.Player player) {
        return new Player(player.getName());
    }

    /**
     * One player by display name.
     *
     * @param name the display name, as the client shows it
     * @return the target
     */
    static Player player(String name) {
        return new Player(name);
    }

    /**
     * One object (scenery).
     *
     * @param object the object
     * @return the target, keyed by its id and the tile it was placed on (a game object's south-west tile)
     */
    static Loc loc(TileObject object) {
        return new Loc(object.getId(), object.getTile().getWorldLocation());
    }

    /**
     * Every object of a type.
     *
     * @param id the object id, as in {@code gameval.ObjectID}
     * @return the target
     */
    static LocType locType(int id) {
        return new LocType(id);
    }

    /**
     * One ground item stack.
     *
     * @param item the stack
     * @return the target, keyed by its item id and tile
     */
    static Obj obj(TileItem item) {
        return new Obj(item.getId(), item.getTile().getWorldLocation());
    }

    /**
     * Every ground item of a type.
     *
     * @param id the item id, as in {@code gameval.ItemID}
     * @return the target
     */
    static ObjType objType(int id) {
        return new ObjType(id);
    }

    /**
     * One tile.
     *
     * @param location the world tile
     * @return the target
     */
    static Tile tile(WorldPoint location) {
        return new Tile(location);
    }

    /**
     * One NPC. The client tells NPCs apart by index and creation cycle, so a new NPC that takes the index isn't lit.
     *
     * @param index         the NPC's index
     * @param creationCycle the client cycle it was created on ({@code NPC.getSpawnCycle()})
     */
    record Npc(int index, int creationCycle) implements HighlightTarget {

        @Override
        public HighlightCategory category() {
            return HighlightCategory.NPC;
        }
    }

    /**
     * Every NPC of a type.
     *
     * @param id the NPC id
     */
    record NpcType(int id) implements HighlightTarget {

        @Override
        public HighlightCategory category() {
            return HighlightCategory.NPC_TYPE;
        }
    }

    /**
     * One player, by display name.
     *
     * @param name the display name
     */
    record Player(String name) implements HighlightTarget {

        /**
         * Checks the name is set.
         *
         * @param name the display name
         */
        public Player {
            Objects.requireNonNull(name, "name");
        }

        @Override
        public HighlightCategory category() {
            return HighlightCategory.PLAYER;
        }
    }

    /**
     * One object (scenery), by id and the tile it was placed on.
     *
     * @param id       the object id
     * @param location the world tile it was placed on (a game object's south-west tile)
     */
    record Loc(int id, WorldPoint location) implements HighlightTarget {

        /**
         * Checks the tile is set.
         *
         * @param id       the object id
         * @param location the tile
         */
        public Loc {
            Objects.requireNonNull(location, "location");
        }

        @Override
        public HighlightCategory category() {
            return HighlightCategory.LOC;
        }
    }

    /**
     * Every object of a type.
     *
     * @param id the object id
     */
    record LocType(int id) implements HighlightTarget {

        @Override
        public HighlightCategory category() {
            return HighlightCategory.LOC_TYPE;
        }
    }

    /**
     * One ground item stack, by item id and tile.
     *
     * @param id       the item id
     * @param location the world tile it lies on
     */
    record Obj(int id, WorldPoint location) implements HighlightTarget {

        /**
         * Checks the tile is set.
         *
         * @param id       the item id
         * @param location the tile
         */
        public Obj {
            Objects.requireNonNull(location, "location");
        }

        @Override
        public HighlightCategory category() {
            return HighlightCategory.OBJ;
        }
    }

    /**
     * Every ground item of a type.
     *
     * @param id the item id
     */
    record ObjType(int id) implements HighlightTarget {

        @Override
        public HighlightCategory category() {
            return HighlightCategory.OBJ_TYPE;
        }
    }

    /**
     * One tile.
     *
     * @param location the world tile
     */
    record Tile(WorldPoint location) implements HighlightTarget {

        /**
         * Checks the tile is set.
         *
         * @param location the tile
         */
        public Tile {
            Objects.requireNonNull(location, "location");
        }

        @Override
        public HighlightCategory category() {
            return HighlightCategory.TILE;
        }
    }
}
