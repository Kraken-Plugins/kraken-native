package com.kraken.api.actor;

import com.kraken.api.Client;
import com.kraken.api.definition.NPCComposition;

/**
 * A non-player character. {@link Client} hands out one handle per NPC, so identity equality is NPC equality; see
 * {@link Actor} for how a handle behaves once the NPC is gone.
 */
public final class NPC extends Actor {

    /**
     * The pinned NPC's creation cycle: a new NPC can be allocated where the old one was.
     */
    private final int pinnedCycle;
    private volatile int id = -1;
    private volatile int transformedId = -1;
    private volatile int spawnCycle = -1;

    /**
     * A handle pinned to the NPC at an index now.
     * Internal: used by the API's own packages; plugins don't need it.
     *
     * @param client        the client
     * @param index         the NPC's index
     * @param pinnedAddress the NPC's address
     * @param pinnedCycle   the NPC's creation cycle
     */
    public NPC(Client client, int index, long pinnedAddress, int pinnedCycle) {
        super(client, index, pinnedAddress);
        this.pinnedCycle = pinnedCycle;
    }

    @Override
    long address() {
        long address = actors.npc(index);
        return address != pinnedAddress || actors.npcCreationCycle(address) != pinnedCycle ? 0 : address;
    }

    @Override
    String readName(long address) {
        return actors.npcName(address);
    }

    /**
     * Whether this handle is for the NPC at an address with a creation cycle.
     * Internal: used by the API's own packages; plugins don't need it.
     *
     * @param address the NPC's address
     * @param cycle   its creation cycle
     * @return true if the handle is pinned to that NPC
     */
    public boolean isPinnedTo(long address, int cycle) {
        return pinnedAddress == address && pinnedCycle == cycle;
    }

    /**
     * The NPC's base type id (Lua's {@code npcTypeId}).
     *
     * @return the type id (the wiki id); the last one read once the NPC is gone; -1 if it was never read
     */
    public int getId() {
        long address = address();
        if (address == 0) {
            return id;
        }
        int live = actors.npcTypeId(address);
        id = live;
        return live;
    }

    /**
     * The NPC's index, the key the client keeps it under.
     *
     * @return the index
     */
    public int getIndex() {
        return index;
    }

    /**
     * The NPC's base type definition.
     *
     * @return the definition of {@link #getId()}, or null if the id is unknown or config types can't be read
     */
    public NPCComposition getComposition() {
        int typeId = getId();
        return typeId < 0 ? null : client.getNpcDefinition(typeId);
    }

    /**
     * The definition the NPC currently shows: for a multi-NPC, the child type its varbit or varp picks.
     *
     * @return the shown definition; the base definition if the type has no transform; null if the transform picks no
     *         type (the NPC is hidden) or it can't be read
     */
    public NPCComposition getTransformedComposition() {
        long address = address();
        int live = address == 0 ? transformedId : actors.npcShownTypeId(address);
        transformedId = live;
        return live < 0 ? null : client.getNpcDefinition(live);
    }

    /**
     * Whether the NPC is a follower (such as a pet) of any player. Mirrors Lua {@code Npc.isFollower}.
     *
     * @return true for a follower; false once the NPC is gone
     */
    public boolean isFollower() {
        return actors.npcIsFollower(address());
    }

    /**
     * Whether the NPC is the local player's follower: one of the varps the client tracks as follower slots holds its
     * index. Mirrors Lua {@code Npc.isLocalFollower}.
     *
     * @return true if the NPC is loaded and is the local player's follower
     */
    public boolean isLocalFollower() {
        return address() != 0 && actors.npcIsLocalFollower(index);
    }

    /**
     * The client cycle (20 ms units) the NPC was created on. Mirrors Lua {@code npcCreationCycle}.
     *
     * @return the creation cycle; the last one read once the NPC is gone; -1 if it was never read
     */
    public int getSpawnCycle() {
        long address = address();
        if (address == 0) {
            return spawnCycle;
        }
        int live = actors.npcCreationCycle(address);
        spawnCycle = live;
        return live;
    }

    /**
     * The category of the NPC's base type, read from the client's own type rather than decoded. Mirrors Lua
     * {@code Npc.getType}.
     *
     * @return the category id, or -1 if it has none or the NPC is gone
     */
    public int getCategory() {
        return actors.npcCategory(address());
    }

    /**
     * The NPC's uid: its type id × 65536 + its index. Mirrors Lua {@code ClientOp.npcUid}.
     *
     * @return the uid, or -1 once the NPC is gone
     */
    public int getUid() {
        return actors.npcUid(address());
    }

    /**
     * The base type's size, taken from {@code getComposition()}.
     *
     * @return the size in tiles; 1 if the definition can't be read
     */
    @Override
    public int getFootprintSize() {
        NPCComposition composition = getComposition();
        return composition == null ? 1 : composition.getSize();
    }

    /**
     * Also keeps the NPC's base and transformed type ids and its spawn cycle.
     */
    @Override
    public void remember() {
        super.remember();
        getSpawnCycle();
        long address = address();
        if (address != 0) {
            id = actors.npcTypeId(address);
            transformedId = actors.npcShownTypeId(address);
        }
    }

    @Override
    public String toString() {
        return "NPC[" + index + "]";
    }
}
