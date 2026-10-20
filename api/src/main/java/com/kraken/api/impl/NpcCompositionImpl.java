package com.kraken.api.impl;

import com.kraken.api.NPCComposition;
import com.kraken.api.lua.types.NpcType;
import com.kraken.api.lua.types.ObjType;

/**
 * {@link NPCComposition} over a decoded {@link NpcType}. Actions resolve against the live vars each call.
 */
final class NpcCompositionImpl implements NPCComposition {

    private final NpcType type;
    private final ObjType.VarReader vars;

    NpcCompositionImpl(NpcType type, ObjType.VarReader vars) {
        this.type = type;
        this.vars = vars;
    }

    @Override
    public int getId() {
        return type.getId();
    }

    @Override
    public String getName() {
        return type.getName();
    }

    @Override
    public int getSize() {
        return type.getSize();
    }

    @Override
    public String[] getActions() {
        return Definitions.actions(type.getOps(vars::varp, vars::varbit));
    }

    @Override
    public boolean isFollower() {
        return type.isFollower();
    }

    @Override
    public int getCombatLevel() {
        return type.getCombatLevel();
    }

    @Override
    public int getCategory() {
        return type.getType();
    }

    @Override
    public int[] getStats() {
        NpcType.Stats stats = type.getStats();
        return new int[]{stats.attack(), stats.defence(), stats.strength(), stats.hitpoints(), stats.ranged(),
                stats.magic()};
    }

    @Override
    public int getIntValue(int paramId) {
        return Definitions.intValue(type.getParams(), paramId);
    }

    @Override
    public String getStringValue(int paramId) {
        return Definitions.stringValue(type.getParams(), paramId);
    }

    @Override
    public String toString() {
        return "NPCComposition[" + getId() + " " + getName() + "]";
    }
}
