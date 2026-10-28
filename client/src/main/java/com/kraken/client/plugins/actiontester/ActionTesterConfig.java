package com.kraken.client.plugins.actiontester;

import com.kraken.client.plugins.config.Config;
import com.kraken.client.plugins.config.ConfigGroup;
import com.kraken.client.plugins.config.ConfigItem;
import com.kraken.client.plugins.config.ConfigSection;
import com.kraken.client.plugins.config.Range;
import com.kraken.client.plugins.config.Units;

import java.awt.Point;

/**
 * The Action Tester's settings: which test to run, the switch that runs it, and the subject and values the tests
 * read. Each test says in its label which of the values it uses.
 */
@ConfigGroup(ActionTesterConfig.GROUP)
public interface ActionTesterConfig extends Config {

    /**
     * The settings' group.
     */
    String GROUP = "actiontester";

    /**
     * The test and how it runs.
     */
    @ConfigSection(name = "Run", description = "Which test to run and when", position = 0)
    String run = "run";

    /**
     * What the tests act on.
     */
    @ConfigSection(name = "Subject", description = "The names, options and values the tests use", position = 10)
    String subject = "subject";

    /**
     * The test to run.
     *
     * @return the test
     */
    @ConfigItem(keyName = "test", name = "Test", description = "Which action or packet to send.", position = 0,
            section = run)
    default ActionTest test() {
        return ActionTest.LIST_MENU;
    }

    /**
     * The switch that runs the test: turning it on runs the test once and turns it back off.
     *
     * @return true while a run is asked for
     */
    @ConfigItem(keyName = "runNow", name = "Run now", description = "Tick to run the test once; it unticks itself.",
            position = 1, section = run)
    default boolean runNow() {
        return false;
    }

    /**
     * How often to repeat the test.
     *
     * @return game ticks between runs; 0 runs only when asked
     */
    @Range(max = 1000)
    @Units(Units.TICKS)
    @ConfigItem(keyName = "interval", name = "Repeat every",
            description = "Game ticks between runs of the test. 0 runs it only when Run now is ticked.", position = 2,
            section = run)
    default int interval() {
        return 0;
    }

    /**
     * Whether to send the mouse click packet before an action, as a real click does, when the subject is on screen.
     *
     * @return true to send a click at the subject's position first
     */
    @ConfigItem(keyName = "mouseClick", name = "Send mouse click",
            description = "Send the mouse click packet at the subject's position before the action, as a real click "
                    + "does, when the subject is on screen.", position = 3, section = run)
    default boolean mouseClick() {
        return true;
    }

    /**
     * The subject's name, or a component.
     *
     * @return an NPC's, player's, object's or item's name (case doesn't matter), or a component as group:child or
     * group:child:slot for the widget tests, or a spell as group:child or by name for the spell tests
     */
    @ConfigItem(keyName = "target", name = "Target",
            description = "The NPC, player, object or item the test acts on, by name (case doesn't matter). For the "
                    + "widget, button and pause tests, a component as group:child or group:child:slot, e.g. 149:0:3. "
                    + "For the spell tests, the spell as group:child or by its name, e.g. High Alchemy.",
            position = 0, section = subject)
    default String target() {
        return "Moss giant";
    }

    /**
     * The option to run on the subject.
     *
     * @return the option's text as the menu shows it (case doesn't matter), or its number; with a sub-option after a
     * dot for an op that opens a sub-menu
     */
    @ConfigItem(keyName = "option", name = "Option",
            description = "The menu option to run, e.g. Attack, Take, Eat or Open (case doesn't matter), or its "
                    + "number (1-based). Players take a number only. A sub-option of an item or widget option goes "
                    + "after a dot, by text or number: Drop.All or 3.2.", position = 1, section = subject)
    default String option() {
        return "Attack";
    }

    /**
     * What the spell tests cast the spell on.
     *
     * @return an inventory item's or NPC's name, case doesn't matter
     */
    @ConfigItem(keyName = "castOn", name = "Cast on",
            description = "The inventory item or NPC the spell tests cast the target spell on, by name.", position = 2,
            section = subject)
    default String castOn() {
        return "Rune platebody";
    }

    /**
     * The inventory item to use on the target.
     *
     * @return the item's name, case doesn't matter
     */
    @ConfigItem(keyName = "item", name = "Item to use",
            description = "The inventory item the Use tests select first, by name, e.g. Tinderbox.", position = 3,
            section = subject)
    default String item() {
        return "Tinderbox";
    }

    /**
     * A number for the tests that take one.
     *
     * @return the count of a count dialog, the item id of an item dialog, or the index of a menu entry
     */
    @ConfigItem(keyName = "number", name = "Number",
            description = "The count for a count dialog, the item id for an item dialog, or the menu entry's index "
                    + "(0 is the left-click row).", position = 4, section = subject)
    default int number() {
        return 0;
    }

    /**
     * The text for the name and string dialogs.
     *
     * @return the text
     */
    @ConfigItem(keyName = "text", name = "Text", description = "The answer to a name or string dialog.", position = 5,
            section = subject)
    default String text() {
        return "hello";
    }

    /**
     * How far east of the player to walk.
     *
     * @return tiles east, negative for west
     */
    @Range(min = -30, max = 30)
    @ConfigItem(keyName = "walkEast", name = "Walk east",
            description = "Tiles east of the player to walk to (negative for west).", position = 6, section = subject)
    default int walkEast() {
        return 3;
    }

    /**
     * How far north of the player to walk.
     *
     * @return tiles north, negative for south
     */
    @Range(min = -30, max = 30)
    @ConfigItem(keyName = "walkNorth", name = "Walk north",
            description = "Tiles north of the player to walk to (negative for south).", position = 7, section = subject)
    default int walkNorth() {
        return 0;
    }

    /**
     * Where the mouse click test clicks.
     *
     * @return the canvas point
     */
    @ConfigItem(keyName = "clickPoint", name = "Click point",
            description = "The canvas point the mouse click test clicks, as X:Y.", position = 8, section = subject)
    default Point clickPoint() {
        return new Point(400, 300);
    }
}
