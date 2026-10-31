package com.kraken.api;

import lombok.Getter;

/**
 * A skill. The ordinal is the client's stat id, the index the skill arrays on {@link Client} use (Lua's
 * {@code ClientState.Stats}, with Lua's {@code SKILL_RUNECRAFTING} named {@link #RUNECRAFT}).
 */
public enum Skill {
    ATTACK("Attack", false),
    DEFENCE("Defence", false),
    STRENGTH("Strength", false),
    HITPOINTS("Hitpoints", false),
    RANGED("Ranged", false),
    PRAYER("Prayer", false),
    MAGIC("Magic", false),
    COOKING("Cooking", false),
    WOODCUTTING("Woodcutting", false),
    FLETCHING("Fletching", true),
    FISHING("Fishing", false),
    FIREMAKING("Firemaking", false),
    CRAFTING("Crafting", false),
    SMITHING("Smithing", false),
    MINING("Mining", false),
    HERBLORE("Herblore", true),
    AGILITY("Agility", true),
    THIEVING("Thieving", true),
    SLAYER("Slayer", true),
    FARMING("Farming", true),
    RUNECRAFT("Runecraft", false),
    HUNTER("Hunter", true),
    CONSTRUCTION("Construction", true),
    SAILING("Sailing", true);

    /**
     * The skill's name as the game shows it, e.g. "Runecraft".
     */
    @Getter
    private final String name;

    /**
     * Whether only members can train the skill. Free-to-play worlds with a total level requirement count only the
     * other skills.
     */
    @Getter
    private final boolean members;

    Skill(String name, boolean members) {
        this.name = name;
        this.members = members;
    }
}
