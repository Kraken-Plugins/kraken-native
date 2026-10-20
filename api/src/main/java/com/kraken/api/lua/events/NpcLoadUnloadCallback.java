package com.kraken.api.lua.events;

/**
 * Mirrors the Lua {@code OnNpcLoadUnloadCallback}: {@code (npcDisplayName, npcId) => void}, used for both
 * {@link Events#ON_NPC_LOAD} and {@link Events#ON_NPC_UNLOAD}.
 */
@FunctionalInterface
public interface NpcLoadUnloadCallback {

    /**
     * An NPC loaded or unloaded.
     *
     * @param npcDisplayName the NPC's display name
     * @param npcId          the NPC's index (what {@code ClientOp.npcName}/{@code getNpcObj} take)
     */
    void onNpcLoadUnload(String npcDisplayName, int npcId);
}
