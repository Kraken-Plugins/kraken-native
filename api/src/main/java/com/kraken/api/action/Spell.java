package com.kraken.api.action;

import com.kraken.api.gameval.InterfaceID;

/**
 * The spells, each by its component in the spellbook interface ({@link InterfaceID.MagicSpellbook}) and its
 * spellbook. Referring to the gameval constants turns a rename in a gameval update into a compile error rather than a
 * wrong click.
 */
public enum Spell {
    ;

    private final int componentId;
    private final Spellbook spellbook;

    Spell(int componentId, Spellbook spellbook) {
        this.componentId = componentId;
        this.spellbook = spellbook;
    }

    /**
     * The spell's component in the spellbook interface.
     *
     * @return the packed component id, an {@link InterfaceID.MagicSpellbook} constant
     */
    public int getComponentId() {
        return componentId;
    }

    /**
     * The spellbook the spell is in.
     *
     * @return the spellbook
     */
    public Spellbook getSpellbook() {
        return spellbook;
    }
}
