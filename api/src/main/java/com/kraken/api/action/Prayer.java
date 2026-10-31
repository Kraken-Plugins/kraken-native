package com.kraken.api.action;

import com.kraken.api.gameval.InterfaceID;
import com.kraken.api.gameval.VarbitID;

/**
 * The prayers, each by its component in the prayer book ({@link InterfaceID.Prayerbook}) and the varbit that is 1
 * while it is on ({@link VarbitID}). The numbers are the components the prayer items of the game data name, so they
 * don't follow the varbit order: Sharp Eye is {@code PRAYER19} and Rigour {@code PRAYER25}. {@code PRAYER30} has no
 * prayer in the game data this build ships with.
 */
public enum Prayer {
    THICK_SKIN(InterfaceID.Prayerbook.PRAYER1, VarbitID.PRAYER_THICKSKIN),
    BURST_OF_STRENGTH(InterfaceID.Prayerbook.PRAYER2, VarbitID.PRAYER_BURSTOFSTRENGTH),
    CLARITY_OF_THOUGHT(InterfaceID.Prayerbook.PRAYER3, VarbitID.PRAYER_CLARITYOFTHOUGHT),
    ROCK_SKIN(InterfaceID.Prayerbook.PRAYER4, VarbitID.PRAYER_ROCKSKIN),
    SUPERHUMAN_STRENGTH(InterfaceID.Prayerbook.PRAYER5, VarbitID.PRAYER_SUPERHUMANSTRENGTH),
    IMPROVED_REFLEXES(InterfaceID.Prayerbook.PRAYER6, VarbitID.PRAYER_IMPROVEDREFLEXES),
    RAPID_RESTORE(InterfaceID.Prayerbook.PRAYER7, VarbitID.PRAYER_RAPIDRESTORE),
    RAPID_HEAL(InterfaceID.Prayerbook.PRAYER8, VarbitID.PRAYER_RAPIDHEAL),
    PROTECT_ITEM(InterfaceID.Prayerbook.PRAYER9, VarbitID.PRAYER_PROTECTITEM),
    STEEL_SKIN(InterfaceID.Prayerbook.PRAYER10, VarbitID.PRAYER_STEELSKIN),
    ULTIMATE_STRENGTH(InterfaceID.Prayerbook.PRAYER11, VarbitID.PRAYER_ULTIMATESTRENGTH),
    INCREDIBLE_REFLEXES(InterfaceID.Prayerbook.PRAYER12, VarbitID.PRAYER_INCREDIBLEREFLEXES),
    PROTECT_FROM_MAGIC(InterfaceID.Prayerbook.PRAYER13, VarbitID.PRAYER_PROTECTFROMMAGIC),
    PROTECT_FROM_MISSILES(InterfaceID.Prayerbook.PRAYER14, VarbitID.PRAYER_PROTECTFROMMISSILES),
    PROTECT_FROM_MELEE(InterfaceID.Prayerbook.PRAYER15, VarbitID.PRAYER_PROTECTFROMMELEE),
    RETRIBUTION(InterfaceID.Prayerbook.PRAYER16, VarbitID.PRAYER_RETRIBUTION),
    REDEMPTION(InterfaceID.Prayerbook.PRAYER17, VarbitID.PRAYER_REDEMPTION),
    SMITE(InterfaceID.Prayerbook.PRAYER18, VarbitID.PRAYER_SMITE),
    SHARP_EYE(InterfaceID.Prayerbook.PRAYER19, VarbitID.PRAYER_SHARPEYE),
    HAWK_EYE(InterfaceID.Prayerbook.PRAYER20, VarbitID.PRAYER_HAWKEYE),
    EAGLE_EYE(InterfaceID.Prayerbook.PRAYER21, VarbitID.PRAYER_EAGLEEYE),
    MYSTIC_WILL(InterfaceID.Prayerbook.PRAYER22, VarbitID.PRAYER_MYSTICWILL),
    MYSTIC_LORE(InterfaceID.Prayerbook.PRAYER23, VarbitID.PRAYER_MYSTICLORE),
    MYSTIC_MIGHT(InterfaceID.Prayerbook.PRAYER24, VarbitID.PRAYER_MYSTICMIGHT),
    RIGOUR(InterfaceID.Prayerbook.PRAYER25, VarbitID.PRAYER_RIGOUR),
    CHIVALRY(InterfaceID.Prayerbook.PRAYER26, VarbitID.PRAYER_CHIVALRY),
    PIETY(InterfaceID.Prayerbook.PRAYER27, VarbitID.PRAYER_PIETY),
    AUGURY(InterfaceID.Prayerbook.PRAYER28, VarbitID.PRAYER_AUGURY),
    PRESERVE(InterfaceID.Prayerbook.PRAYER29, VarbitID.PRAYER_PRESERVE);

    private final int componentId;
    private final int varbit;

    Prayer(int componentId, int varbit) {
        this.componentId = componentId;
        this.varbit = varbit;
    }

    /**
     * The prayer's component in the prayer book.
     *
     * @return the packed component id, an {@link InterfaceID.Prayerbook} constant
     */
    public int getComponentId() {
        return componentId;
    }

    /**
     * The varbit that is 1 while the prayer is on.
     *
     * @return the varbit id, a {@link VarbitID} constant
     */
    public int getVarbit() {
        return varbit;
    }
}
