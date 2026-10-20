package com.kraken.api.impl;

import com.kraken.api.NPC;
import com.kraken.api.NPCComposition;
import com.kraken.api.lua.Npc;

/**
 * {@link NPC} over a pinned Lua {@link Npc} handle. {@link ClientImpl#npc} hands out one per NPC, so identity
 * equality is NPC equality.
 */
final class NpcImpl extends ActorImpl implements NPC {

    private final Npc lua;
    private volatile int id = -1;
    private volatile int transformedId = -1;
    private volatile int spawnCycle = -1;

    NpcImpl(ClientImpl client, Npc lua) {
        super(client, lua);
        this.lua = lua;
    }

    /**
     * The pinned Lua handle, which says which NPC this is.
     *
     * @return the handle this one reads through
     */
    Npc pin() {
        return lua;
    }

    /**
     * Mirrors Lua {@code Npc.isLocalFollower}.
     *
     * @return true if the NPC is loaded and is the local player's follower
     */
    boolean isLocalFollower() {
        return lua.isLocalFollower();
    }

    @Override
    public int getId() {
        int live = lua.getTypeId();
        if (live == -1 && !lua.exists()) {
            return id;
        }
        id = live;
        return live;
    }

    @Override
    public int getIndex() {
        return lua.index();
    }

    @Override
    public NPCComposition getComposition() {
        int typeId = getId();
        return typeId < 0 ? null : client.getNpcDefinition(typeId);
    }

    @Override
    public NPCComposition getTransformedComposition() {
        int live = lua.getTransformedTypeId();
        if (live == -1 && !lua.exists()) {
            live = transformedId;
        } else {
            transformedId = live;
        }
        return live < 0 ? null : client.getNpcDefinition(live);
    }

    @Override
    public boolean isFollower() {
        return lua.isFollower();
    }

    @Override
    public int getSpawnCycle() {
        int live = lua.getCreationCycle();
        if (live == -1 && !lua.exists()) {
            return spawnCycle;
        }
        spawnCycle = live;
        return live;
    }

    /**
     * The base type's size, as RuneLite takes it from {@code getComposition()}.
     *
     * @return the size in tiles; 1 if the definition can't be read
     */
    @Override
    public int getFootprintSize() {
        NPCComposition composition = getComposition();
        return composition == null ? 1 : composition.getSize();
    }

    /**
     * Also keeps the NPC's base and transformed type ids.
     */
    @Override
    void remember() {
        super.remember();
        getId();
        getSpawnCycle();
        int transformed = lua.getTransformedTypeId();
        if (transformed != -1 || lua.exists()) {
            transformedId = transformed;
        }
    }

    @Override
    public String toString() {
        return "NPC[" + getIndex() + "]";
    }
}
