package com.kraken.client.worldhopper;

import com.kraken.client.plugins.config.Config;
import com.kraken.client.plugins.config.ConfigGroup;
import com.kraken.client.plugins.config.ConfigItem;
import com.kraken.client.plugins.config.ConfigSection;
import com.kraken.client.plugins.config.Keybind;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.Set;

/**
 * The World Hopper's settings: the World Hopper page's cog opens them. Group {@value #GROUP}, which also keeps, in
 * hidden items, how the world list was last filtered and sorted.
 */
@ConfigGroup(WorldHopperConfig.GROUP)
public interface WorldHopperConfig extends Config {

    /**
     * The group the World Hopper's settings are kept in.
     */
    String GROUP = "worldhopper";

    /**
     * The section for quick-hopping.
     */
    @ConfigSection(name = "Quick-hop", description = "Hopping to the next or previous world with a key", position = 0)
    String quickHop = "quickHop";

    /**
     * The key that hops to the previous world.
     *
     * @return the keybind
     */
    @ConfigItem(keyName = "previousKey", name = "Quick-hop previous",
            description = "Hop to the previous world of the same kind. The game sees the keys too: the arrow keys "
                    + "also turn the camera.",
            position = 0, section = quickHop)
    default Keybind previousKey() {
        return new Keybind(KeyEvent.VK_LEFT, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK);
    }

    /**
     * The key that hops to the next world.
     *
     * @return the keybind
     */
    @ConfigItem(keyName = "nextKey", name = "Quick-hop next",
            description = "Hop to the next world of the same kind. The game sees the keys too: the arrow keys also "
                    + "turn the camera.",
            position = 1, section = quickHop)
    default Keybind nextKey() {
        return new Keybind(KeyEvent.VK_RIGHT, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK);
    }

    /**
     * Whether a quick-hop from a PvP or high-risk world goes to a safe one.
     *
     * @return true to leave dangerous worlds
     */
    @ConfigItem(keyName = "quickhopOutOfDanger", name = "Quick-hop out of dangerous worlds",
            description = "From a PvP or high-risk world, quick-hop to a safe world rather than another dangerous one.",
            position = 2, section = quickHop)
    default boolean quickhopOutOfDanger() {
        return true;
    }

    /**
     * The regions quick-hops stay in.
     *
     * @return the regions; empty for any
     */
    @ConfigItem(keyName = "quickHopRegionFilter", name = "Quick-hop regions",
            description = "Only quick-hop to worlds in these regions. None ticked means any region.",
            position = 3, section = quickHop)
    default Set<WorldRegion> quickHopRegionFilter() {
        return Set.of();
    }

    /**
     * The region the world list was last narrowed to.
     *
     * @return the region, or null for every region
     */
    @ConfigItem(keyName = "regionFilter", name = "Region filter", description = "The world list's region filter.",
            hidden = true)
    default WorldRegion regionFilter() {
        return null;
    }

    /**
     * Saves the world list's region filter.
     *
     * @param region the region, or null for every region
     */
    @ConfigItem(keyName = "regionFilter", name = "Region filter", description = "The world list's region filter.",
            hidden = true)
    void regionFilter(WorldRegion region);

    /**
     * The kind of world the world list was last narrowed to.
     *
     * @return the filter
     */
    @ConfigItem(keyName = "typeFilter", name = "Type filter", description = "The world list's type filter.",
            hidden = true)
    default WorldTypeFilter typeFilter() {
        return WorldTypeFilter.ALL;
    }

    /**
     * Saves the world list's type filter.
     *
     * @param filter the filter
     */
    @ConfigItem(keyName = "typeFilter", name = "Type filter", description = "The world list's type filter.",
            hidden = true)
    void typeFilter(WorldTypeFilter filter);

    /**
     * The column the world list was last sorted by.
     *
     * @return the column's index: 0 world, 1 players, 2 region, 3 activity
     */
    @ConfigItem(keyName = "sortColumn", name = "Sort column", description = "The world list's sort column.",
            hidden = true)
    default int sortColumn() {
        return 0;
    }

    /**
     * Saves the world list's sort column.
     *
     * @param column the column's index
     */
    @ConfigItem(keyName = "sortColumn", name = "Sort column", description = "The world list's sort column.",
            hidden = true)
    void sortColumn(int column);

    /**
     * Whether the world list was last sorted in descending order.
     *
     * @return true for descending
     */
    @ConfigItem(keyName = "sortDescending", name = "Sort descending",
            description = "Whether the world list sorts in descending order.", hidden = true)
    default boolean sortDescending() {
        return false;
    }

    /**
     * Saves the world list's sort order.
     *
     * @param descending true for descending
     */
    @ConfigItem(keyName = "sortDescending", name = "Sort descending",
            description = "Whether the world list sorts in descending order.", hidden = true)
    void sortDescending(boolean descending);
}
