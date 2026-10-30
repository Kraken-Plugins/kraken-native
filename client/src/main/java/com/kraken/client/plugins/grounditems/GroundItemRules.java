package com.kraken.client.plugins.grounditems;

import com.kraken.client.plugins.grounditems.config.HighlightTier;
import com.kraken.client.plugins.grounditems.config.ItemHighlightMode;
import com.kraken.client.plugins.grounditems.config.MenuHighlightMode;
import com.kraken.client.plugins.grounditems.config.OwnershipFilterMode;
import com.kraken.client.util.ColorUtil;
import com.kraken.client.util.QuantityFormatter;
import com.kraken.client.util.Text;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.IntFunction;

/**
 * What the settings make of each ground item: whether it is highlighted or hidden, its colour, its value, and the
 * menu text, notification and lootbeam it would get. Built from the settings each time they change, holding the item
 * lists and value tiers as they were then; the other settings are read as needed. Safe to use from any thread.
 */
final class GroundItemRules {

    /**
     * The item is on neither list.
     */
    static final int NONE = 0;

    /**
     * The item is highlighted by the highlighted list.
     */
    static final int HIGHLIGHTED = 1;

    /**
     * The item is hidden by the hidden list.
     */
    static final int HIDDEN = 2;

    /**
     * A value tier: items worth more than the price get the colour.
     *
     * @param price the tier's start, in coins
     * @param color the tier's colour
     */
    record PriceHighlight(int price, Color color) {
    }

    /**
     * A ground item's menu entry as the settings would show it.
     *
     * @param option        the option, possibly with a colour tag
     * @param target        the target, possibly recoloured and with the quantity
     * @param deprioritized whether the entry should need a right-click
     */
    record MenuText(String option, String target, boolean deprioritized) {
    }

    private final GroundItemsConfig config;
    private final IntFunction<Color> itemColors;
    private final ItemList highlightedItems;
    private final ItemList hiddenItems;
    private final List<PriceHighlight> priceChecks;

    /**
     * Reads the item lists and value tiers from the settings.
     *
     * @param config     the settings
     * @param itemColors each item's own colour by its plain item id, or null for none
     */
    GroundItemRules(GroundItemsConfig config, IntFunction<Color> itemColors) {
        this.config = config;
        this.itemColors = itemColors;
        this.highlightedItems = new ItemList(Text.fromCSV(config.getHighlightItems()));
        this.hiddenItems = new ItemList(Text.fromCSV(config.getHiddenItems()));
        List<PriceHighlight> checks = new ArrayList<>();
        if (config.insaneValuePrice() > 0) {
            checks.add(new PriceHighlight(config.insaneValuePrice(), config.insaneValueColor()));
        }
        if (config.highValuePrice() > 0) {
            checks.add(new PriceHighlight(config.highValuePrice(), config.highValueColor()));
        }
        if (config.mediumValuePrice() > 0) {
            checks.add(new PriceHighlight(config.mediumValuePrice(), config.mediumValueColor()));
        }
        if (config.lowValuePrice() > 0) {
            checks.add(new PriceHighlight(config.lowValuePrice(), config.lowValueColor()));
        }
        this.priceChecks = List.copyOf(checks);
    }

    /**
     * The value tiers, highest first.
     *
     * @return the tiers with a price above 0
     */
    List<PriceHighlight> getPriceChecks() {
        return priceChecks;
    }

    /**
     * Works out whether an item is highlighted or hidden, and its colour, unless already worked out since its last
     * {@link GroundItem#reset()}.
     *
     * @param item the item
     */
    void updateItemColor(GroundItem item) {
        if (item.color != null) {
            return;
        }
        Color highlighted = getHighlighted(item);
        boolean hidden = isHidden(item);
        item.highlighted = highlighted != null;
        item.hidden = hidden;
        item.color = getItemColor(highlighted, hidden);
    }

    /**
     * Which list an item is on. An exact name beats a wildcard, and when both lists match the same way, the
     * highlighted list wins.
     *
     * @param item the item
     * @return {@link #HIGHLIGHTED}, {@link #HIDDEN} or {@link #NONE}
     */
    int isHiddenOrHighlighted(GroundItem item) {
        int highlight = highlightedItems.matches(item);
        if (highlight == ItemList.EXACT) {
            return HIGHLIGHTED;
        }
        int hide = hiddenItems.matches(item);
        if (hide == ItemList.EXACT) {
            return HIDDEN;
        }
        if (highlight == ItemList.WILDCARD) {
            return HIGHLIGHTED;
        }
        if (hide == ItemList.WILDCARD) {
            return HIDDEN;
        }
        return NONE;
    }

    /**
     * The colour an item is highlighted in, if it is: the highlighted list's (or the item's own) colour, else for an
     * item not on the hidden list its own colour, else the colour of the highest value tier it is above.
     *
     * @param item the item
     * @return the colour, or null if it isn't highlighted
     */
    Color getHighlighted(GroundItem item) {
        Color itemColor = itemColors.apply(item.getItemId());
        int list = isHiddenOrHighlighted(item);
        if (list == HIGHLIGHTED) {
            return itemColor != null ? itemColor : config.highlightedColor();
        }
        if (list == HIDDEN) {
            return null;
        }
        if (itemColor != null) {
            return itemColor;
        }
        long price = getValueByMode(item.getGePrice(), item.getHaPrice());
        for (PriceHighlight highlight : priceChecks) {
            if (price > highlight.price()) {
                return highlight.color();
            }
        }
        return null;
    }

    /**
     * Whether an item is hidden: on the hidden list and not the highlighted one, or worth less than the hide-under
     * value both ways while it may be hidden (it has a price, can be traded, or untradeables may be hidden).
     *
     * @param item the item
     * @return true if it is hidden
     */
    boolean isHidden(GroundItem item) {
        int list = isHiddenOrHighlighted(item);
        boolean canBeHidden = item.getGePrice() > 0 || item.isTradeable() || !config.dontHideUntradeables();
        boolean underGe = item.getGePrice() < config.getHideUnderValue();
        boolean underHa = item.getHaPrice() < config.getHideUnderValue();
        return list == HIDDEN || (list != HIGHLIGHTED && canBeHidden && underGe && underHa);
    }

    /**
     * An item's colour from whether it is highlighted and hidden.
     *
     * @param highlighted its highlight colour, or null
     * @param hidden      whether it is hidden
     * @return the highlight colour, else the hidden or default colour
     */
    Color getItemColor(Color highlighted, boolean hidden) {
        if (highlighted != null) {
            return highlighted;
        }
        return hidden ? config.hiddenColor() : config.defaultColor();
    }

    /**
     * The value the settings judge an item by.
     *
     * @param gePrice the Grand Exchange price, 0 if unknown
     * @param haPrice the high alchemy value
     * @return the price, the value or the higher of the two
     */
    long getValueByMode(long gePrice, long haPrice) {
        return switch (config.valueCalculationMode()) {
            case GE -> gePrice;
            case HA -> haPrice;
            case HIGHEST -> Math.max(gePrice, haPrice);
        };
    }

    /**
     * Whether the ownership filter shows an item: everything for {@link OwnershipFilterMode#ALL}; for
     * {@link OwnershipFilterMode#DROPS} the local player's and their group's; for
     * {@link OwnershipFilterMode#TAKEABLE} all but other players' items, unless the local player is an ironman.
     *
     * @param filterMode  the filter
     * @param ownership   the item's {@code GroundItem.OWNERSHIP_*}
     * @param accountType the ironman varbit's value, 0 for a regular account
     * @return true if the item is shown
     */
    static boolean shouldDisplayItem(OwnershipFilterMode filterMode, int ownership, int accountType) {
        return switch (filterMode) {
            case DROPS -> ownership == GroundItem.OWNERSHIP_SELF || ownership == GroundItem.OWNERSHIP_GROUP;
            case TAKEABLE -> ownership != GroundItem.OWNERSHIP_OTHER || accountType == 0;
            case ALL -> true;
        };
    }

    /**
     * The notification a newly dropped item calls for: one for an item on the highlighted list, if those are
     * notified, else one for an item above the notify tier that isn't on the hidden list.
     *
     * @param item the item
     * @return the text, such as {@code You received a valuable drop: Dragon bones (25)}; null for none
     */
    String notification(GroundItem item) {
        int list = isHiddenOrHighlighted(item);
        boolean notifyHighlighted = config.notifyHighlightedDrops() && list == HIGHLIGHTED;
        boolean notifyTier = config.notifyTier() != HighlightTier.OFF
                && getValueByMode(item.getGePrice(), item.getHaPrice()) > config.notifyTier().getValueFromTier(config)
                && list != HIDDEN;
        String dropType;
        if (notifyHighlighted) {
            dropType = "highlighted";
        } else if (notifyTier) {
            dropType = "valuable";
        } else {
            return null;
        }
        StringBuilder text = new StringBuilder("You received a ").append(dropType).append(" drop: ")
                .append(item.getName());
        if (item.getQuantity() > 1) {
            text.append(" (").append(QuantityFormatter.quantityToStackSize(item.getQuantity())).append(')');
        }
        return text.toString();
    }

    /**
     * The lootbeam a tile's items call for: a highlighted item's colour, if highlighted items get one; else, if the
     * most valuable item not hidden is above the lootbeam tier and a value tier, its own colour or that of the
     * highest value tier it is above.
     *
     * @param items       the items on the tile
     * @param accountType the ironman varbit's value, for the ownership filter
     * @return the beam's colour, or null for none
     */
    Color lootbeamColor(Collection<GroundItem> items, int accountType) {
        if (!config.showLootbeamForHighlighted() && config.showLootbeamTier() == HighlightTier.OFF) {
            return null;
        }
        long highestPrice = -1;
        GroundItem highestItem = null;
        OwnershipFilterMode filter = config.ownershipFilterMode();
        for (GroundItem item : items) {
            if (!shouldDisplayItem(filter, item.getOwnership(), accountType)) {
                continue;
            }
            int list = isHiddenOrHighlighted(item);
            if (config.showLootbeamForHighlighted() && list == HIGHLIGHTED) {
                Color own = itemColors.apply(item.getItemId());
                return own != null ? own : config.highlightedColor();
            }
            if (list == HIDDEN) {
                continue;
            }
            long price = getValueByMode(item.getGePrice(), item.getHaPrice());
            if (price > highestPrice) {
                highestPrice = price;
                highestItem = item;
            }
        }
        if (highestItem != null && config.showLootbeamTier() != HighlightTier.OFF) {
            for (PriceHighlight highlight : priceChecks) {
                if (highestPrice > highlight.price()
                        && highestPrice > config.showLootbeamTier().getValueFromTier(config)) {
                    Color own = itemColors.apply(highestItem.getItemId());
                    return own != null ? own : highlight.color();
                }
            }
        }
        return null;
    }

    /**
     * A ground item's menu entry as the settings would show it: the option and the item's name in its colour (for a
     * highlighted item, or a hidden one if those are recoloured, when the menu is highlighted), the stack's quantity
     * after the name, and hidden items deprioritized.
     *
     * @param option the entry's option, such as {@code Take}
     * @param target the entry's target, with the game's colour tags, such as {@code <col=ff9040>Logs}
     * @param item   the item, with its colour worked out ({@link #updateItemColor})
     * @return the entry's text
     */
    MenuText menuText(String option, String target, GroundItem item) {
        String newOption = option;
        String newTarget = target;
        boolean canBeRecolored = item.highlighted || (item.hidden && config.recolorMenuHiddenItems());
        ItemHighlightMode highlightMode = config.itemHighlightMode();
        if ((highlightMode == ItemHighlightMode.MENU || highlightMode == ItemHighlightMode.BOTH) && canBeRecolored
                && !item.color.equals(config.defaultColor())) {
            MenuHighlightMode mode = config.menuHighlightMode();
            if (mode == MenuHighlightMode.BOTH || mode == MenuHighlightMode.OPTION) {
                newOption = ColorUtil.prependColorTag(newOption, item.color);
            }
            int end = newTarget.lastIndexOf('>');
            if ((mode == MenuHighlightMode.BOTH || mode == MenuHighlightMode.NAME) && end >= 11) {
                // The item's name follows the last tag, a 12-character <col=rrggbb>: swap that tag's colour
                newTarget = newTarget.substring(0, end - 11) + ColorUtil.colorTag(item.color)
                        + newTarget.substring(end + 1);
            }
        }
        if (config.showMenuItemQuantities() && item.isStackable() && item.getQuantity() > 1) {
            newTarget = newTarget + " (" + item.getQuantity() + ")";
        }
        boolean deprioritized = item.hidden && !item.highlighted && config.deprioritizeHiddenItems();
        return new MenuText(newOption, newTarget, deprioritized);
    }
}
