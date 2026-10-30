package com.kraken.client.plugins.grounditems;

import com.kraken.client.util.WildcardMatcher;
import lombok.Value;

import java.util.List;
import java.util.Objects;

/**
 * The highlighted or the hidden item list, read from its setting.
 */
@Value
class ItemList {

    /**
     * No entry matches.
     */
    static final int NONE = 0;

    /**
     * Only an entry with a wildcard matches.
     */
    static final int WILDCARD = 1;

    /**
     * An entry matches by its exact name.
     */
    static final int EXACT = 2;

    /**
     * The entries.
     */
    List<ItemThreshold> items;

    /**
     * Reads the entries.
     *
     * @param items the entries as written, such as {@code ["Rune*", "Coins > 1000"]}
     */
    ItemList(List<String> items) {
        this.items = items.stream()
                .map(ItemThreshold::fromName)
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * How an item matches the list: an entry naming it exactly (ignoring case) beats one matching it by wildcard. The
     * stack's quantity must hold for the entry either way.
     *
     * @param item the item
     * @return {@link #EXACT}, {@link #WILDCARD} or {@link #NONE}
     */
    int matches(GroundItem item) {
        for (ItemThreshold it : items) {
            if (!it.isWildcard() && it.getName().equalsIgnoreCase(item.getName())
                    && it.quantityHolds(item.getQuantity())) {
                return EXACT;
            }
        }
        for (ItemThreshold it : items) {
            if (it.isWildcard() && WildcardMatcher.matches(it.getName(), item.getName())
                    && it.quantityHolds(item.getQuantity())) {
                return WILDCARD;
            }
        }
        return NONE;
    }
}
