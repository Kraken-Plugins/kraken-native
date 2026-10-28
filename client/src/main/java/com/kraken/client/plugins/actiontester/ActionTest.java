package com.kraken.client.plugins.actiontester;

/**
 * What the Action Tester runs when asked to: one kind of menu action or packet, with its subject and values taken
 * from the plugin's settings. Labels name the subject first, so the drop-down reads as groups.
 */
public enum ActionTest {

    /**
     * Logs the menu's entries as the client holds them now, top row first, and sends nothing.
     */
    LIST_MENU("Menu: list the entries"),

    /**
     * Runs one of the menu's entries as a click on it would: by its text, or its row (0 is the top, left-click row).
     * Covers every subject kind the menu reader knows.
     */
    MENU_ENTRY("Menu: run an entry"),

    /**
     * Runs an option of the nearest NPC with the name.
     */
    NPC_OPTION("NPC: option"),

    /**
     * Uses an inventory item on the nearest NPC with the name.
     */
    USE_ITEM_ON_NPC("NPC: use an item on"),

    /**
     * Casts a spell on the nearest NPC with the name.
     */
    CAST_SPELL_ON_NPC("NPC: cast a spell on"),

    /**
     * Runs an option of the nearest player with the name; the option is its number, 1-8.
     */
    PLAYER_OPTION("Player: option"),

    /**
     * Uses an inventory item on the nearest player with the name.
     */
    USE_ITEM_ON_PLAYER("Player: use an item on"),

    /**
     * Casts a spell on the nearest player with the name.
     */
    CAST_SPELL_ON_PLAYER("Player: cast a spell on"),

    /**
     * Runs an option of the nearest object (loc) with the name.
     */
    OBJECT_OPTION("Object: option"),

    /**
     * Uses an inventory item on the nearest object with the name.
     */
    USE_ITEM_ON_OBJECT("Object: use an item on"),

    /**
     * Casts a spell on the nearest object with the name.
     */
    CAST_SPELL_ON_OBJECT("Object: cast a spell on"),

    /**
     * Runs an option of the nearest ground item with the name.
     */
    GROUND_ITEM_OPTION("Ground item: option"),

    /**
     * Uses an inventory item on the nearest ground item with the name.
     */
    USE_ITEM_ON_GROUND_ITEM("Ground item: use an item on"),

    /**
     * Casts a spell on the nearest ground item with the name, as Telekinetic Grab is.
     */
    CAST_SPELL_ON_GROUND_ITEM("Ground item: cast a spell on"),

    /**
     * Runs an option of the first inventory item with the name.
     */
    INVENTORY_OPTION("Inventory: option"),

    /**
     * Uses an inventory item on the first other inventory item with the name.
     */
    USE_ITEM_ON_ITEM("Inventory: use an item on"),

    /**
     * Casts a spell on the first inventory item with the name, as High Alchemy is.
     */
    CAST_SPELL_ON_ITEM("Inventory: cast a spell on"),

    /**
     * Runs an option of a component given as group:child or group:child:slot.
     */
    WIDGET_OPTION("Widget: option"),

    /**
     * Clicks an old-style button given as group:child: a plain button, a toggle, a set or a close button, by the
     * component's button type.
     */
    WIDGET_BUTTON("Widget: old-style button"),

    /**
     * Clicks a pause ("Click here to continue") button: the component, or the first one showing when none is given.
     */
    PAUSE_BUTTON("Widget: click to continue"),

    /**
     * Closes the open modal interface as its close button would.
     */
    CLOSE_INTERFACE("Widget: close the interface"),

    /**
     * Answers a count dialog ("Enter amount") with the amount.
     */
    RESUME_COUNT("Dialog: enter an amount"),

    /**
     * Answers a name dialog with the text.
     */
    RESUME_NAME("Dialog: enter a name"),

    /**
     * Answers a string dialog with the text.
     */
    RESUME_STRING("Dialog: enter text"),

    /**
     * Answers an item search dialog with the item id.
     */
    RESUME_OBJ("Dialog: choose an item"),

    /**
     * Walks to the tile so many tiles east and north of the player.
     */
    WALK("Walk: to a tile near the player"),

    /**
     * Sends only the mouse click packet, at the click point.
     */
    MOUSE_CLICK("Mouse: click packet only");

    private final String label;

    ActionTest(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
