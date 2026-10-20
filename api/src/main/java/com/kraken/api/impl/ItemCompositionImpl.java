package com.kraken.api.impl;

import com.kraken.api.ItemComposition;
import com.kraken.api.lua.types.ObjType;

/**
 * {@link ItemComposition} over a decoded {@link ObjType}. Ground actions resolve against the live vars each call.
 */
final class ItemCompositionImpl implements ItemComposition {

    private final ObjType type;
    private final ObjType.VarReader vars;

    ItemCompositionImpl(ObjType type, ObjType.VarReader vars) {
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
    public int getPrice() {
        return type.getValue();
    }

    @Override
    public boolean isStackable() {
        return type.isStackable();
    }

    @Override
    public boolean isTradeable() {
        return type.isTradeable();
    }

    @Override
    public boolean isMembers() {
        return type.isMembers();
    }

    @Override
    public int getNote() {
        return type.getCertTemplateId();
    }

    @Override
    public int getLinkedNoteId() {
        return type.getCertId();
    }

    @Override
    public int getPlaceholderId() {
        return type.getPlaceholderId();
    }

    @Override
    public int getPlaceholderTemplateId() {
        return type.getPlaceholderTemplateId();
    }

    /**
     * The inventory ops, which the client binds to {@code ObjType.getOps} (the ground ops are its {@code getIOps}).
     *
     * @return five entries, null for an empty slot
     */
    @Override
    public String[] getInventoryActions() {
        return Definitions.actions(type.getOps());
    }

    @Override
    public int getWeight() {
        return type.getWeight();
    }

    @Override
    public String[] getGroundActions() {
        return Definitions.actions(type.getIOps(vars));
    }

    @Override
    public boolean isEquipable() {
        return type.isEquipable();
    }

    @Override
    public boolean isTwoHanded() {
        return type.isTwoHanded();
    }

    @Override
    public int getSlot() {
        return type.getSlot();
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
        return "ItemComposition[" + getId() + " " + getName() + "]";
    }
}
