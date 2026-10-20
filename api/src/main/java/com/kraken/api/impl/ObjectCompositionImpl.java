package com.kraken.api.impl;

import com.kraken.api.ObjectComposition;
import com.kraken.api.lua.types.LocType;
import com.kraken.api.lua.types.ObjType;

/**
 * {@link ObjectComposition} over a decoded {@link LocType}. Actions resolve against the live vars each call.
 */
final class ObjectCompositionImpl implements ObjectComposition {

    private final LocType type;
    private final ObjType.VarReader vars;

    ObjectCompositionImpl(LocType type, ObjType.VarReader vars) {
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
    public String[] getActions() {
        return Definitions.actions(type.getOps(vars::varp, vars::varbit));
    }

    @Override
    public int getSizeX() {
        return type.getWidth();
    }

    @Override
    public int getSizeY() {
        return type.getLength();
    }

    @Override
    public boolean blocksWalk() {
        return type.blocksWalk();
    }

    @Override
    public boolean blocksRange() {
        return type.blocksRanged();
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
        return "ObjectComposition[" + getId() + " " + getName() + "]";
    }
}
