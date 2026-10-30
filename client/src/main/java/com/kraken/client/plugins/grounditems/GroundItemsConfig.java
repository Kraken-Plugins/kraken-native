package com.kraken.client.plugins.grounditems;

import com.kraken.client.plugins.config.Alpha;
import com.kraken.client.plugins.config.Config;
import com.kraken.client.plugins.config.ConfigGroup;
import com.kraken.client.plugins.config.ConfigItem;
import com.kraken.client.plugins.config.ConfigSection;
import com.kraken.client.plugins.config.Keybind;
import com.kraken.client.plugins.config.Units;
import com.kraken.client.plugins.config.Unsupported;
import com.kraken.client.plugins.grounditems.config.DespawnTimerMode;
import com.kraken.client.plugins.grounditems.config.HighlightTier;
import com.kraken.client.plugins.grounditems.config.ItemHighlightMode;
import com.kraken.client.plugins.grounditems.config.LootbeamStyle;
import com.kraken.client.plugins.grounditems.config.MenuHighlightMode;
import com.kraken.client.plugins.grounditems.config.OwnershipFilterMode;
import com.kraken.client.plugins.grounditems.config.PriceDisplayMode;
import com.kraken.client.plugins.grounditems.config.ValueCalculationMode;

import java.awt.Color;

/**
 * The Ground Items plugin's settings: which items to highlight or hide, how values colour them, and how they are
 * drawn. Prices are high alchemy values; the only Grand Exchange prices known are those of coins and platinum tokens.
 */
@ConfigGroup(GroundItemsConfig.GROUP)
public interface GroundItemsConfig extends Config {

    /**
     * The settings' group.
     */
    String GROUP = "grounditems";

    /**
     * The key of the ownership filter.
     */
    String OWNERSHIP_FILTER_MODE = "ownershipFilterMode";

    /**
     * The section holding the highlighted and hidden item lists.
     */
    @ConfigSection(name = "Item lists", description = "The highlighted and hidden item lists.", position = 0)
    String itemLists = "itemLists";

    /**
     * The items to highlight.
     *
     * @return comma-separated names, each optionally with {@code *} wildcards and a {@code >} or {@code <} quantity
     */
    @ConfigItem(keyName = "highlightedItems", name = "Highlighted items",
            description = "Configures specifically highlighted ground items. Format: item1, item2, item3",
            position = 0, section = itemLists)
    default String getHighlightItems() {
        return "";
    }

    /**
     * Sets the items to highlight.
     *
     * @param key comma-separated names
     */
    @ConfigItem(keyName = "highlightedItems", name = "", description = "")
    void setHighlightedItem(String key);

    /**
     * The items to hide.
     *
     * @return comma-separated names, each optionally with {@code *} wildcards and a {@code >} or {@code <} quantity
     */
    @ConfigItem(keyName = "hiddenItems", name = "Hidden items",
            description = "Configures hidden ground items. Format: item1, item2, item3", position = 1,
            section = itemLists)
    default String getHiddenItems() {
        return "Vial, Ashes, Coins, Bones, Bucket, Jug, Seaweed";
    }

    /**
     * Sets the items to hide.
     *
     * @param key comma-separated names
     */
    @ConfigItem(keyName = "hiddenItems", name = "", description = "")
    void setHiddenItems(String key);

    /**
     * Whether to draw only highlighted items.
     *
     * @return true to leave out the others
     */
    @ConfigItem(keyName = "showHighlightedOnly", name = "Show highlighted items only",
            description = "Configures whether or not to draw items only on your highlighted list.", position = 2)
    default boolean showHighlightedOnly() {
        return false;
    }

    /**
     * Whether items that can't be traded are kept even when worth less than {@link #getHideUnderValue()}.
     *
     * @return true to keep them
     */
    @ConfigItem(keyName = "dontHideUntradeables", name = "Do not hide untradeables",
            description = "Configures whether or not untradeable items ignore hiding under settings.", position = 3)
    default boolean dontHideUntradeables() {
        return true;
    }

    /**
     * Whether a stack's menu entries show its quantity.
     *
     * @return true to show it
     */
    @Unsupported("Needs right-click menu edits")
    @ConfigItem(keyName = "showMenuItemQuantities", name = "Show menu item quantities",
            description = "Configures whether or not to show the item quantities in the menu.", position = 4)
    default boolean showMenuItemQuantities() {
        return true;
    }

    /**
     * Whether hidden items' menu entries are coloured with {@link #hiddenColor()}.
     *
     * @return true to colour them
     */
    @Unsupported("Needs right-click menu edits")
    @ConfigItem(keyName = "recolorMenuHiddenItems", name = "Recolor menu hidden items",
            description = "Configures whether or not hidden items in right-click menu will be recolored.",
            position = 5)
    default boolean recolorMenuHiddenItems() {
        return false;
    }

    /**
     * Whether hidden items' menu entries need a right-click.
     *
     * @return true to deprioritize them
     */
    @Unsupported("Needs right-click menu edits")
    @ConfigItem(keyName = "deprioritizeHiddenItems", name = "Deprioritize menu hidden items",
            description = "Depriotizies the menu options for items which are hidden, requiring a right-click to pick "
                    + "up.", position = 5)
    default boolean deprioritizeHiddenItems() {
        return false;
    }

    /**
     * Whether to outline the tiles items lie on, in the items' colours.
     *
     * @return true to outline them
     */
    @ConfigItem(keyName = "highlightTiles", name = "Highlight tiles",
            description = "Configures whether or not to highlight tiles containing ground items.", position = 6)
    default boolean highlightTiles() {
        return false;
    }

    /**
     * Whether a highlighted item dropping sends a notification.
     *
     * @return true to notify
     */
    @Unsupported("Needs notifications")
    @ConfigItem(keyName = "notifyHighlightedDrops", name = "Notify for highlighted drops",
            description = "Configures whether or not to notify for drops on your highlighted list.", position = 7)
    default boolean notifyHighlightedDrops() {
        return false;
    }

    /**
     * The value tier from which a drop sends a notification.
     *
     * @return the tier, or {@link HighlightTier#OFF} for none
     */
    @Unsupported("Needs notifications")
    @ConfigItem(keyName = "notifyTier", name = "Notify tier",
            description = "Configures which price tiers will trigger a notification on drop.", position = 8)
    default HighlightTier notifyTier() {
        return HighlightTier.OFF;
    }

    /**
     * Which prices are written after an item's name.
     *
     * @return the prices shown
     */
    @ConfigItem(keyName = "priceDisplayMode", name = "Price display mode",
            description = "Configures which price types are shown alongside ground item name.", position = 9)
    default PriceDisplayMode priceDisplayMode() {
        return PriceDisplayMode.BOTH;
    }

    /**
     * Where items are highlighted.
     *
     * @return the overlay, the menu, both or neither
     */
    @ConfigItem(keyName = "itemHighlightMode", name = "Item highlight mode",
            description = "Configures how ground items will be highlighted.", position = 10)
    default ItemHighlightMode itemHighlightMode() {
        return ItemHighlightMode.BOTH;
    }

    /**
     * What a highlighted item's menu entries colour.
     *
     * @return the option, the name or both
     */
    @Unsupported("Needs right-click menu edits")
    @ConfigItem(keyName = "menuHighlightMode", name = "Menu highlight mode",
            description = "Configures what to highlight in right-click menu.", position = 11)
    default MenuHighlightMode menuHighlightMode() {
        return MenuHighlightMode.NAME;
    }

    /**
     * Which value decides an item's value tier.
     *
     * @return the value used
     */
    @ConfigItem(keyName = "highlightValueCalculation", name = "Highlight value calculation",
            description = "Configures which coin value is used to determine highlight color.", position = 12)
    default ValueCalculationMode valueCalculationMode() {
        return ValueCalculationMode.HIGHEST;
    }

    /**
     * The value under which items are hidden.
     *
     * @return the value in coins; 0 hides nothing for its value
     */
    @ConfigItem(keyName = "hideUnderValue", name = "Hide under value",
            description = "Configures hidden ground items under both GE and HA value.", position = 13)
    default int getHideUnderValue() {
        return 0;
    }

    /**
     * The colour of items neither highlighted nor hidden nor in a value tier.
     *
     * @return the colour
     */
    @Alpha
    @ConfigItem(keyName = "defaultColor", name = "Default items",
            description = "Configures the color for default, non-highlighted items.", position = 14)
    default Color defaultColor() {
        return Color.WHITE;
    }

    /**
     * The colour of highlighted items.
     *
     * @return the colour
     */
    @Alpha
    @ConfigItem(keyName = "highlightedColor", name = "Highlighted items",
            description = "Configures the color for highlighted items.", position = 15)
    default Color highlightedColor() {
        return Color.decode("#AA00FF");
    }

    /**
     * The colour of hidden items, shown while the hotkey is held.
     *
     * @return the colour
     */
    @Alpha
    @ConfigItem(keyName = "hiddenColor", name = "Hidden items",
            description = "Configures the color for hidden items in right-click menu and when holding ALT.",
            position = 16)
    default Color hiddenColor() {
        return Color.GRAY;
    }

    /**
     * The colour of low value items.
     *
     * @return the colour
     */
    @Alpha
    @ConfigItem(keyName = "lowValueColor", name = "Low value items",
            description = "Configures the color for low value items.", position = 17)
    default Color lowValueColor() {
        return Color.decode("#66B2FF");
    }

    /**
     * The value low value items start above.
     *
     * @return the value in coins; 0 leaves the tier out
     */
    @ConfigItem(keyName = "lowValuePrice", name = "Low value price",
            description = "Configures the start price for low value items.", position = 18)
    default int lowValuePrice() {
        return 20000;
    }

    /**
     * The colour of medium value items.
     *
     * @return the colour
     */
    @Alpha
    @ConfigItem(keyName = "mediumValueColor", name = "Medium value items",
            description = "Configures the color for medium value items.", position = 19)
    default Color mediumValueColor() {
        return Color.decode("#99FF99");
    }

    /**
     * The value medium value items start above.
     *
     * @return the value in coins; 0 leaves the tier out
     */
    @ConfigItem(keyName = "mediumValuePrice", name = "Medium value price",
            description = "Configures the start price for medium value items.", position = 20)
    default int mediumValuePrice() {
        return 100000;
    }

    /**
     * The colour of high value items.
     *
     * @return the colour
     */
    @Alpha
    @ConfigItem(keyName = "highValueColor", name = "High value items",
            description = "Configures the color for high value items.", position = 21)
    default Color highValueColor() {
        return Color.decode("#FF9600");
    }

    /**
     * The value high value items start above.
     *
     * @return the value in coins; 0 leaves the tier out
     */
    @ConfigItem(keyName = "highValuePrice", name = "High value price",
            description = "Configures the start price for high value items.", position = 22)
    default int highValuePrice() {
        return 1000000;
    }

    /**
     * The colour of insane value items.
     *
     * @return the colour
     */
    @Alpha
    @ConfigItem(keyName = "insaneValueColor", name = "Insane value items",
            description = "Configures the color for insane value items.", position = 23)
    default Color insaneValueColor() {
        return Color.decode("#FF66B2");
    }

    /**
     * The value insane value items start above.
     *
     * @return the value in coins; 0 leaves the tier out
     */
    @ConfigItem(keyName = "insaneValuePrice", name = "Insane value price",
            description = "Configures the start price for insane value items.", position = 24)
    default int insaneValuePrice() {
        return 10000000;
    }

    /**
     * Which items are shown by who they belong to.
     *
     * @return the filter
     */
    @Unsupported("Needs ground item ownership")
    @ConfigItem(keyName = OWNERSHIP_FILTER_MODE, name = "Ownership filter",
            description = "Show all items, takeable items, or only your or your group's drops.", position = 25)
    default OwnershipFilterMode ownershipFilterMode() {
        return OwnershipFilterMode.ALL;
    }

    /**
     * How quickly the hotkey must be pressed twice to hide every item, until it is pressed again.
     *
     * @return the delay in milliseconds; 0 turns double-tapping off
     */
    @Units(Units.MILLISECONDS)
    @ConfigItem(keyName = "doubleTapDelay", name = "Double-tap delay",
            description = "Delay for the double-tap ALT to hide ground items. 0 to disable.", position = 26)
    default int doubleTapDelay() {
        return 250;
    }

    /**
     * Whether menu entries for several stacks of one item are merged into one, with a count.
     *
     * @return true to merge them
     */
    @Unsupported("Needs right-click menu edits")
    @ConfigItem(keyName = "collapseEntries", name = "Collapse ground item menu",
            description = "Collapses ground item menu entries together and appends count.", position = 27)
    default boolean collapseEntries() {
        return false;
    }

    /**
     * How the time until an item despawns is shown.
     *
     * @return the timer's style, or {@link DespawnTimerMode#OFF}
     */
    @Unsupported("Needs ground item despawn times")
    @ConfigItem(keyName = "groundItemTimers", name = "Despawn timer",
            description = "Shows despawn timers for items you've dropped and received as loot.", position = 28)
    default DespawnTimerMode groundItemTimers() {
        return DespawnTimerMode.OFF;
    }

    /**
     * Whether text is outlined rather than shadowed.
     *
     * @return true to outline it
     */
    @ConfigItem(keyName = "textOutline", name = "Text outline",
            description = "Use an outline around text instead of a text shadow.", position = 29)
    default boolean textOutline() {
        return false;
    }

    /**
     * Whether every highlighted item gets a lootbeam.
     *
     * @return true to show them
     */
    @Unsupported("Needs drawing models in the game")
    @ConfigItem(keyName = "showLootbeamForHighlighted", name = "Highlighted item lootbeams",
            description = "Configures lootbeams to show for all highlighted items.", position = 30)
    default boolean showLootbeamForHighlighted() {
        return false;
    }

    /**
     * The value tier from which items get a lootbeam.
     *
     * @return the tier, or {@link HighlightTier#OFF} for none
     */
    @Unsupported("Needs drawing models in the game")
    @ConfigItem(keyName = "showLootbeamTier", name = "Lootbeam tier",
            description = "Configures which price tiers will trigger a lootbeam.", position = 31)
    default HighlightTier showLootbeamTier() {
        return HighlightTier.HIGH;
    }

    /**
     * The lootbeam's model.
     *
     * @return the style
     */
    @Unsupported("Needs drawing models in the game")
    @ConfigItem(keyName = "lootbeamStyle", name = "Lootbeam style", description = "Style of lootbeam to use.",
            position = 32)
    default LootbeamStyle lootbeamStyle() {
        return LootbeamStyle.MODERN;
    }

    /**
     * The key that, held, shows every item, hidden ones included.
     *
     * @return the keybind
     */
    @ConfigItem(keyName = "hotkey", name = "Hotkey", description = "Configures the hotkey used by the Ground Items "
            + "plugin.", position = 33)
    default Keybind hotkey() {
        return Keybind.ALT;
    }
}
