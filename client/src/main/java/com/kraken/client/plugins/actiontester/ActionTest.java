package com.kraken.client.plugins.actiontester;

/**
 * What the Action Tester runs when asked to: one kind of menu action or packet, with its subject and values taken
 * from the plugin's settings.
 */
public enum ActionTest {

    /**
     * Logs the menu's entries as the client holds them now and sends nothing.
     */
    LIST_MENU("List the menu entries (sends nothing)"),

    /**
     * Runs one of the menu's entries as a click on it would: the entry at the chosen index (0 is the left-click
     * row). Covers every subject kind the menu reader knows.
     */
    MENU_ENTRY("Run a menu entry by index"),

    /**
     * Runs an option of the nearest NPC with the target name.
     */
    NPC_OPTION("NPC option (target name, option)"),

    /**
     * Runs an option of the nearest player with the target name; the option is its number, 1-8.
     */
    PLAYER_OPTION("Player option (target name, option number)"),

    /**
     * Runs an option of the nearest loc with the target name.
     */
    OBJECT_OPTION("Object option (target name, option)"),

    /**
     * Runs an option of the nearest ground item with the target name.
     */
    GROUND_ITEM_OPTION("Ground item option (target name, option)"),

    /**
     * Runs an option of the first inventory item with the target name.
     */
    INVENTORY_OPTION("Inventory item option (target name, option)"),

    /**
     * Runs an option of a component given as group:child or group:child:slot.
     */
    WIDGET_OPTION("Widget option (target component, option)"),

    /**
     * Walks to the tile the chosen number of tiles east and north of the player.
     */
    WALK("Walk (east, north from the player)"),

    /**
     * Uses the inventory item on the inventory item with the target name.
     */
    USE_ITEM_ON_ITEM("Use item on an inventory item"),

    /**
     * Uses the inventory item on the nearest NPC with the target name.
     */
    USE_ITEM_ON_NPC("Use item on an NPC"),

    /**
     * Uses the inventory item on the nearest loc with the target name.
     */
    USE_ITEM_ON_OBJECT("Use item on an object"),

    /**
     * Uses the inventory item on the nearest ground item with the target name.
     */
    USE_ITEM_ON_GROUND_ITEM("Use item on a ground item"),

    /**
     * Uses the inventory item on the nearest player with the target name.
     */
    USE_ITEM_ON_PLAYER("Use item on a player"),

    /**
     * Casts the spell given as the target (a spellbook component as group:child, or a spell's name) on the first
     * inventory item with the cast-on name.
     */
    CAST_SPELL_ON_ITEM("Cast a spell on an inventory item (target spell, cast on)"),

    /**
     * Casts the spell given as the target on the nearest NPC with the cast-on name.
     */
    CAST_SPELL_ON_NPC("Cast a spell on an NPC (target spell, cast on)"),

    /**
     * Clicks an old-style button given as group:child: a plain button, a toggle, a set or a close button, by the
     * component's button type.
     */
    WIDGET_BUTTON("Old-style button (target component)"),

    /**
     * Closes the open modal interface as its close button would.
     */
    CLOSE_INTERFACE("Close the open interface"),

    /**
     * Clicks a pause button: the target component, or the first visible one when no target is given.
     */
    PAUSE_BUTTON("Pause button (target component, or the first shown)"),

    /**
     * Answers a count dialog with the number.
     */
    RESUME_COUNT("Answer a count dialog (number)"),

    /**
     * Answers a name dialog with the text.
     */
    RESUME_NAME("Answer a name dialog (text)"),

    /**
     * Answers a string dialog with the text.
     */
    RESUME_STRING("Answer a string dialog (text)"),

    /**
     * Answers an item dialog with the number as the item id.
     */
    RESUME_OBJ("Answer an item dialog (number = item id)"),

    /**
     * Sends only the mouse click packet, at the click point.
     */
    MOUSE_CLICK("Mouse click packet only (click point)");

    private final String label;

    ActionTest(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
