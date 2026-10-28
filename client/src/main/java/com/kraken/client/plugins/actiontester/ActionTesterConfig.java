package com.kraken.client.plugins.actiontester;

import com.kraken.client.plugins.config.Button;
import com.kraken.client.plugins.config.Config;
import com.kraken.client.plugins.config.ConfigGroup;
import com.kraken.client.plugins.config.ConfigItem;
import com.kraken.client.plugins.config.Range;
import com.kraken.client.plugins.config.ShowIf;
import com.kraken.client.plugins.config.Suggestions;
import com.kraken.client.plugins.config.Units;

import java.awt.Point;

/**
 * The Action Tester's settings: the action to run, what it acts on, and the Run button. Each setting shows only for
 * the actions that use it, and the subject settings' drop-downs list what the game has loaded now. Names match
 * whatever their case.
 */
@ConfigGroup(ActionTesterConfig.GROUP)
public interface ActionTesterConfig extends Config {

    /**
     * The settings' group.
     */
    String GROUP = "actiontester";

    /**
     * The key of the Run button.
     */
    String RUN = "run";

    /**
     * The action to run.
     *
     * @return the action
     */
    @ConfigItem(keyName = "test", name = "Action",
            description = "The menu action or packet to send. Only the settings it uses are shown.", position = 0)
    default ActionTest test() {
        return ActionTest.LIST_MENU;
    }

    /**
     * The inventory item the Use actions select first.
     *
     * @return the item's name
     */
    @ShowIf(key = "test", values = {"USE_ITEM_ON_NPC", "USE_ITEM_ON_PLAYER", "USE_ITEM_ON_OBJECT",
            "USE_ITEM_ON_GROUND_ITEM", "USE_ITEM_ON_ITEM"})
    @Suggestions
    @ConfigItem(keyName = "useItem", name = "Item to use",
            description = "The inventory item to use, by name, e.g. Tinderbox.", position = 1)
    default String useItem() {
        return "";
    }

    /**
     * The spell the Cast actions cast.
     *
     * @return the spell's name, or its component as group:child
     */
    @ShowIf(key = "test", values = {"CAST_SPELL_ON_NPC", "CAST_SPELL_ON_PLAYER", "CAST_SPELL_ON_OBJECT",
            "CAST_SPELL_ON_GROUND_ITEM", "CAST_SPELL_ON_ITEM"})
    @Suggestions
    @ConfigItem(keyName = "spell", name = "Spell",
            description = "The spell to cast, by name (e.g. High Alchemy) or as its spellbook component, "
                    + "group:child. The list holds the current spellbook's spells.", position = 2)
    default String spell() {
        return "";
    }

    /**
     * The menu entry to run.
     *
     * @return the entry's text, or its row
     */
    @ShowIf(key = "test", values = {"MENU_ENTRY"})
    @Suggestions
    @ConfigItem(keyName = "menuEntry", name = "Entry",
            description = "The menu entry to run, by its text (e.g. Attack Goblin) or its row, 0 being the top "
                    + "(left-click) row. The list holds the menu as it is now: hover something in the game first.",
            position = 3)
    default String menuEntry() {
        return "";
    }

    /**
     * The NPC the NPC actions act on.
     *
     * @return the NPC's name
     */
    @ShowIf(key = "test", values = {"NPC_OPTION", "USE_ITEM_ON_NPC", "CAST_SPELL_ON_NPC"})
    @Suggestions
    @ConfigItem(keyName = "npc", name = "NPC",
            description = "The NPC to act on, by name; the nearest one with the name is used. The list holds the "
                    + "NPCs loaded now, nearest first.", position = 4)
    default String npc() {
        return "";
    }

    /**
     * The player the player actions act on.
     *
     * @return the player's name
     */
    @ShowIf(key = "test", values = {"PLAYER_OPTION", "USE_ITEM_ON_PLAYER", "CAST_SPELL_ON_PLAYER"})
    @Suggestions
    @ConfigItem(keyName = "player", name = "Player",
            description = "The player to act on, by name. The list holds the other players loaded now, nearest "
                    + "first.", position = 5)
    default String player() {
        return "";
    }

    /**
     * The object the object actions act on.
     *
     * @return the object's name
     */
    @ShowIf(key = "test", values = {"OBJECT_OPTION", "USE_ITEM_ON_OBJECT", "CAST_SPELL_ON_OBJECT"})
    @Suggestions
    @ConfigItem(keyName = "object", name = "Object",
            description = "The object to act on, by name; the nearest one with the name is used. The list holds "
                    + "the objects with options loaded now, nearest first.", position = 6)
    default String object() {
        return "";
    }

    /**
     * The ground item the ground item actions act on.
     *
     * @return the item's name
     */
    @ShowIf(key = "test", values = {"GROUND_ITEM_OPTION", "USE_ITEM_ON_GROUND_ITEM", "CAST_SPELL_ON_GROUND_ITEM"})
    @Suggestions
    @ConfigItem(keyName = "groundItem", name = "Ground item",
            description = "The item on the ground to act on, by name; the nearest one with the name is used. The "
                    + "list holds the ground items loaded now, nearest first.", position = 7)
    default String groundItem() {
        return "";
    }

    /**
     * The inventory item the inventory actions act on.
     *
     * @return the item's name
     */
    @ShowIf(key = "test", values = {"INVENTORY_OPTION", "USE_ITEM_ON_ITEM", "CAST_SPELL_ON_ITEM"})
    @Suggestions
    @ConfigItem(keyName = "item", name = "Inventory item",
            description = "The inventory item to act on, by name; the first one with the name is used. The list "
                    + "holds the inventory's items.", position = 8)
    default String item() {
        return "";
    }

    /**
     * The component the widget actions act on.
     *
     * @return the component as group:child or group:child:slot, optionally followed by a space and anything
     */
    @ShowIf(key = "test", values = {"WIDGET_OPTION", "WIDGET_BUTTON", "PAUSE_BUTTON"})
    @Suggestions
    @ConfigItem(keyName = "component", name = "Component",
            description = "The component, as group:child or group:child:slot (e.g. 149:0:3); anything after a "
                    + "space is ignored. The list holds the shown components the action fits. For click to "
                    + "continue, leave it empty to click the first one showing.", position = 9)
    default String component() {
        return "";
    }

    /**
     * The option to run on the subject.
     *
     * @return the option's text or number, with a sub-option after a dot
     */
    @ShowIf(key = "test", values = {"NPC_OPTION", "PLAYER_OPTION", "OBJECT_OPTION", "GROUND_ITEM_OPTION",
            "INVENTORY_OPTION", "WIDGET_OPTION"})
    @Suggestions
    @ConfigItem(keyName = "option", name = "Option",
            description = "The option to run, by its text (e.g. Attack, Take, Eat) or its number, 1-based. A "
                    + "player's option is its number. A sub-option goes after a dot, by text or number: Drop.All "
                    + "or 3.2. The list holds the subject's options.", position = 10)
    default String option() {
        return "";
    }

    /**
     * The answer to a count dialog.
     *
     * @return the amount
     */
    @ShowIf(key = "test", values = {"RESUME_COUNT"})
    @ConfigItem(keyName = "count", name = "Amount", description = "The amount to answer the dialog with.",
            position = 11)
    default int count() {
        return 1;
    }

    /**
     * The answer to a name or string dialog.
     *
     * @return the text
     */
    @ShowIf(key = "test", values = {"RESUME_NAME", "RESUME_STRING"})
    @ConfigItem(keyName = "text", name = "Text", description = "The text to answer the dialog with.", position = 12)
    default String text() {
        return "";
    }

    /**
     * The answer to an item search dialog.
     *
     * @return the item id
     */
    @ShowIf(key = "test", values = {"RESUME_OBJ"})
    @ConfigItem(keyName = "itemId", name = "Item id", description = "The id of the item to answer the dialog with.",
            position = 13)
    default int itemId() {
        return 0;
    }

    /**
     * How far east of the player to walk.
     *
     * @return tiles east, negative for west
     */
    @ShowIf(key = "test", values = {"WALK"})
    @Range(min = -30, max = 30)
    @ConfigItem(keyName = "walkEast", name = "Tiles east",
            description = "How many tiles east of the player to walk to (negative for west).", position = 14)
    default int walkEast() {
        return 3;
    }

    /**
     * How far north of the player to walk.
     *
     * @return tiles north, negative for south
     */
    @ShowIf(key = "test", values = {"WALK"})
    @Range(min = -30, max = 30)
    @ConfigItem(keyName = "walkNorth", name = "Tiles north",
            description = "How many tiles north of the player to walk to (negative for south).", position = 15)
    default int walkNorth() {
        return 0;
    }

    /**
     * Where the mouse click packet clicks.
     *
     * @return the canvas point
     */
    @ShowIf(key = "test", values = {"MOUSE_CLICK"})
    @ConfigItem(keyName = "clickPoint", name = "Click at", description = "The canvas point to click, x then y.",
            position = 16)
    default Point clickPoint() {
        return new Point(400, 300);
    }

    /**
     * Whether the mouse click packet is a right click.
     *
     * @return true for the right button
     */
    @ShowIf(key = "test", values = {"MOUSE_CLICK"})
    @ConfigItem(keyName = "rightClick", name = "Right button", description = "Send a right click instead of a left.",
            position = 17)
    default boolean rightClick() {
        return false;
    }

    /**
     * Whether to send the mouse click packet before an action, as a real click does, when the subject is on screen.
     *
     * @return true to send a click at the subject's position first
     */
    @ShowIf(key = "test", values = {"NPC_OPTION", "PLAYER_OPTION", "OBJECT_OPTION", "GROUND_ITEM_OPTION",
            "INVENTORY_OPTION", "WIDGET_OPTION", "WIDGET_BUTTON", "WALK"})
    @ConfigItem(keyName = "mouseClick", name = "Send mouse click",
            description = "Send the mouse click packet at the subject's position before the action, as a real click "
                    + "does, when the subject is on screen.", position = 18)
    default boolean mouseClick() {
        return true;
    }

    /**
     * Whether to run the action again every so many ticks.
     *
     * @return true to repeat it
     */
    @ConfigItem(keyName = "repeat", name = "Repeat", description = "Run the action again every so many game ticks.",
            position = 19)
    default boolean repeat() {
        return false;
    }

    /**
     * How often to repeat the action.
     *
     * @return game ticks between runs
     */
    @ShowIf(key = "repeat")
    @Range(min = 1, max = 1000)
    @Units(Units.TICKS)
    @ConfigItem(keyName = "interval", name = "Repeat every", description = "Game ticks between runs of the action.",
            position = 20)
    default int interval() {
        return 5;
    }

    /**
     * The button that runs the action once.
     *
     * @return the button
     */
    @ConfigItem(keyName = RUN, name = "Run", description = "Run the action once now; the console says what was sent.",
            position = 21)
    Button run();
}
