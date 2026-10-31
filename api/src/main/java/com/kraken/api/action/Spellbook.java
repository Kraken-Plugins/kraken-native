package com.kraken.api.action;

/**
 * The spellbooks, by the value varbit {@code SPELLBOOK} holds while each is the one shown.
 */
public enum Spellbook {
    STANDARD(0),
    ANCIENT(1),
    LUNAR(2),
    ARCEUUS(3);

    private final int id;

    Spellbook(int id) {
        this.id = id;
    }

    /**
     * The value varbit {@code SPELLBOOK} holds while this spellbook is shown.
     *
     * @return the value
     */
    public int getId() {
        return id;
    }

    /**
     * The spellbook a value of varbit {@code SPELLBOOK} names.
     *
     * @param id the varbit's value
     * @return the spellbook, or null for a value no spellbook has
     */
    public static Spellbook of(int id) {
        for (Spellbook book : values()) {
            if (book.id == id) {
                return book;
            }
        }
        return null;
    }
}
