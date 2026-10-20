package com.kraken.api;

import lombok.Getter;

/**
 * A skill, with RuneLite's names and order. The ordinal is the client's stat id, the index the skill arrays on
 * {@link Client} use (Lua's {@code ClientState.Stats}, with Lua's {@code SKILL_RUNECRAFTING} named {@link #RUNECRAFT}
 * as in RuneLite).
 */
public enum Skill {
    ATTACK("Attack"),
    DEFENCE("Defence"),
    STRENGTH("Strength"),
    HITPOINTS("Hitpoints"),
    RANGED("Ranged"),
    PRAYER("Prayer"),
    MAGIC("Magic"),
    COOKING("Cooking"),
    WOODCUTTING("Woodcutting"),
    FLETCHING("Fletching"),
    FISHING("Fishing"),
    FIREMAKING("Firemaking"),
    CRAFTING("Crafting"),
    SMITHING("Smithing"),
    MINING("Mining"),
    HERBLORE("Herblore"),
    AGILITY("Agility"),
    THIEVING("Thieving"),
    SLAYER("Slayer"),
    FARMING("Farming"),
    RUNECRAFT("Runecraft"),
    HUNTER("Hunter"),
    CONSTRUCTION("Construction"),
    SAILING("Sailing");

    /**
     * The skill's name as the game shows it, e.g. "Runecraft".
     */
    @Getter
    private final String name;

    Skill(String name) {
        this.name = name;
    }
}
