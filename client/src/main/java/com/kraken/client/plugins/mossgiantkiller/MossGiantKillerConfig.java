package com.kraken.client.plugins.mossgiantkiller;

import com.kraken.api.action.Spell;
import com.kraken.client.plugins.config.Config;
import com.kraken.client.plugins.config.ConfigGroup;
import com.kraken.client.plugins.config.ConfigItem;
import com.kraken.client.plugins.config.ConfigSection;
import com.kraken.client.plugins.config.Range;
import com.kraken.client.plugins.config.ShowIf;

/**
 * The Moss Giant Killer plugin's settings.
 */
@ConfigGroup(MossGiantKillerConfig.GROUP)
public interface MossGiantKillerConfig extends Config {

    /**
     * The config group the settings are kept under.
     */
    String GROUP = "mossgiantkiller";

    /**
     * The section for what to fight and where.
     */
    @ConfigSection(name = "Combat", description = "What to fight, and where", position = 0)
    String combat = "combat";

    /**
     * The section for eating and getting away.
     */
    @ConfigSection(name = "Eating", description = "When to eat, and what to do without food", position = 10)
    String eating = "eating";

    /**
     * The section for picking up and burying.
     */
    @ConfigSection(name = "Loot", description = "What to pick up, and burying bones", position = 20)
    String loot = "loot";

    /**
     * The section for the rest.
     */
    @ConfigSection(name = "Other", description = "Prayer, running, dialogues and the overlay", position = 30)
    String other = "other";

    /**
     * The NPC to fight.
     *
     * @return its name, matched ignoring case
     */
    @ConfigItem(keyName = "npcName", name = "NPC name", description = "The name of the NPC to fight.",
            position = 1, section = combat)
    default String npcName() {
        return "Moss giant";
    }

    /**
     * How far from where the plugin started to look for NPCs to fight.
     *
     * @return the radius in tiles
     */
    @Range(min = 1, max = 30)
    @ConfigItem(keyName = "radius", name = "Fight radius",
            description = "How many tiles from where the plugin started to fight in. It walks back when it has "
                    + "nothing to fight and has wandered further.", position = 2, section = combat)
    default int radius() {
        return 10;
    }

    /**
     * The hitpoints level to eat at.
     *
     * @return the level; at or below it, the plugin eats
     */
    @Range(min = 1, max = 99)
    @ConfigItem(keyName = "eatAt", name = "Eat at",
            description = "Eat when your hitpoints are this low or lower. Anything in the inventory with an Eat "
                    + "option counts as food.", position = 11, section = eating)
    default int eatAt() {
        return 20;
    }

    /**
     * Whether to teleport away when health is low and there's no food left.
     *
     * @return true to cast the escape spell, false to stop where you are
     */
    @ConfigItem(keyName = "teleportWhenOutOfFood", name = "Teleport when out of food",
            description = "When it needs to eat and there's no food left, cast the escape spell and stop. Off: "
                    + "just stop.", position = 12, section = eating)
    default boolean teleportWhenOutOfFood() {
        return true;
    }

    /**
     * The spell that gets you away when the food runs out.
     *
     * @return the spell, cast from the spellbook shown
     */
    @ShowIf(key = "teleportWhenOutOfFood", values = {"true"})
    @ConfigItem(keyName = "escapeSpell", name = "Escape spell",
            description = "The teleport to cast when the food runs out. It must be in the spellbook you have open.",
            position = 13, section = eating)
    default Spell escapeSpell() {
        return Spell.VARROCK_TELEPORT;
    }

    /**
     * The ground items to pick up.
     *
     * @return their names, separated by commas, matched ignoring case; empty to pick up nothing
     */
    @ConfigItem(keyName = "loot", name = "Pick up",
            description = "Names of the ground items to pick up between fights, separated by commas.",
            position = 21, section = loot)
    default String loot() {
        return "Big bones, Nature rune, Law rune, Cosmic rune, Death rune, Chaos rune, Mossy key";
    }

    /**
     * How far away a ground item can be to be picked up.
     *
     * @return the distance in tiles
     */
    @Range(min = 1, max = 20)
    @ConfigItem(keyName = "lootRadius", name = "Pick-up radius",
            description = "How many tiles away a ground item can be to be picked up.", position = 22,
            section = loot)
    default int lootRadius() {
        return 6;
    }

    /**
     * Whether to bury bones between fights.
     *
     * @return true to bury them
     */
    @ConfigItem(keyName = "buryBones", name = "Bury bones",
            description = "Bury anything in the inventory with a Bury option between fights.", position = 23,
            section = loot)
    default boolean buryBones() {
        return true;
    }

    /**
     * Whether to pray against melee while fighting.
     *
     * @return true to switch Protect from Melee on in a fight and off between fights
     */
    @ConfigItem(keyName = "protectFromMelee", name = "Protect from Melee",
            description = "Switch Protect from Melee on while fighting and off between fights, while you have "
                    + "prayer points. Needs 43 Prayer.", position = 31, section = other)
    default boolean protectFromMelee() {
        return false;
    }

    /**
     * Whether to switch running on.
     *
     * @return true to switch it on when it's off
     */
    @ConfigItem(keyName = "keepRunning", name = "Keep running",
            description = "Switch running on when it's off, trying again every minute or so in case you were out "
                    + "of run energy.", position = 32, section = other)
    default boolean keepRunning() {
        return true;
    }

    /**
     * Whether to click through "Click here to continue", such as level-up messages.
     *
     * @return true to continue them
     */
    @ConfigItem(keyName = "continueDialogues", name = "Continue dialogues",
            description = "Click through \"Click here to continue\", such as level-up messages.", position = 33,
            section = other)
    default boolean continueDialogues() {
        return true;
    }

    /**
     * Whether to show the status panel over the game.
     *
     * @return true to show it
     */
    @ConfigItem(keyName = "showOverlay", name = "Show status",
            description = "Show what the plugin is doing, the kills, and the food left.", position = 34,
            section = other)
    default boolean showOverlay() {
        return true;
    }
}
