package com.kraken.client.plugins.grounditems;

import lombok.Value;

/**
 * One entry of an item list: a name, possibly with {@code *} wildcards, and a quantity the stack must be over or
 * under, written after it: {@code Coins > 1000}, {@code Shark<5}. An entry without a quantity matches any stack of one
 * or more.
 */
@Value
class ItemThreshold {

    /**
     * Which side of the quantity a stack must be on.
     */
    enum Inequality {

        /**
         * Fewer than the quantity.
         */
        LESS_THAN,

        /**
         * More than the quantity.
         */
        MORE_THAN
    }

    /**
     * The name, possibly with wildcards.
     */
    String name;

    /**
     * The quantity compared with.
     */
    int quantity;

    /**
     * Which side of the quantity a stack must be on.
     */
    Inequality inequality;

    /**
     * Whether the name has a wildcard.
     */
    boolean wildcard;

    /**
     * Reads an entry. A quantity that isn't a number reads as none.
     *
     * @param entry the entry, such as {@code "Rune*"} or {@code "Coins > 1000"}
     * @return the threshold, or null for an empty entry
     */
    static ItemThreshold fromName(String entry) {
        if (entry == null || entry.isEmpty()) {
            return null;
        }
        Inequality operator = Inequality.MORE_THAN;
        int qty = 0;
        boolean wildcard = entry.contains("*");
        String name = entry;
        for (int i = entry.length() - 1; i >= 0; i--) {
            char c = entry.charAt(i);
            if (c >= '0' && c <= '9' || Character.isWhitespace(c)) {
                continue;
            }
            if ((c == '<' || c == '>') && i + 1 < entry.length()) {
                operator = c == '<' ? Inequality.LESS_THAN : Inequality.MORE_THAN;
                try {
                    qty = Integer.parseInt(entry.substring(i + 1).trim());
                } catch (NumberFormatException e) {
                    qty = 0;
                    operator = Inequality.MORE_THAN;
                }
                name = entry.substring(0, i);
            }
            break;
        }
        return new ItemThreshold(name.trim(), qty, operator, wildcard);
    }

    /**
     * Whether a stack's size is on the threshold's side of its quantity.
     *
     * @param itemCount the stack's size
     * @return true if it is
     */
    boolean quantityHolds(int itemCount) {
        if (inequality == Inequality.LESS_THAN) {
            return itemCount < quantity;
        }
        return itemCount > quantity;
    }
}
