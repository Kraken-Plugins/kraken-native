package com.kraken.api.menu;

import java.util.Locale;
import java.util.Objects;

/**
 * How a {@link MenuMatch} compares a row's option or target text. Texts are compared with the client's colour tags
 * removed and lower-cased, so {@code exact("talk-to")} matches {@code <col=ffff00>Talk-to}; the pattern is
 * normalised the same way when built.
 *
 * @param mode how to compare
 * @param text the pattern, lower-cased and tag-free
 */
public record TextMatch(Mode mode, String text) {

    /**
     * The comparison a pattern makes with a row's text.
     */
    public enum Mode {

        /**
         * The whole text equals the pattern.
         */
        EXACT,

        /**
         * The text contains the pattern.
         */
        CONTAINS,

        /**
         * The text starts with the pattern.
         */
        PREFIX,

        /**
         * The text ends with the pattern.
         */
        SUFFIX,

        /**
         * The pattern matches the whole text, with {@code *} standing for any run of characters and {@code ?} for
         * one.
         */
        WILDCARD
    }

    /**
     * The longest pattern the hook keeps.
     */
    public static final int MAX_LENGTH = 64;

    /**
     * Builds a pattern, normalising the text.
     *
     * @param mode how to compare
     * @param text the pattern, with or without colour tags and in any case
     */
    public TextMatch(Mode mode, String text) {
        this.mode = Objects.requireNonNull(mode, "mode");
        String normalised = normalise(Objects.requireNonNull(text, "text"));
        this.text = normalised.length() > MAX_LENGTH ? normalised.substring(0, MAX_LENGTH) : normalised;
    }

    /**
     * A pattern the whole text must equal.
     *
     * @param text the text
     * @return the pattern
     */
    public static TextMatch exact(String text) {
        return new TextMatch(Mode.EXACT, text);
    }

    /**
     * A pattern the text must contain.
     *
     * @param text the text
     * @return the pattern
     */
    public static TextMatch contains(String text) {
        return new TextMatch(Mode.CONTAINS, text);
    }

    /**
     * A pattern the text must start with.
     *
     * @param text the text
     * @return the pattern
     */
    public static TextMatch prefix(String text) {
        return new TextMatch(Mode.PREFIX, text);
    }

    /**
     * A pattern the text must end with.
     *
     * @param text the text
     * @return the pattern
     */
    public static TextMatch suffix(String text) {
        return new TextMatch(Mode.SUFFIX, text);
    }

    /**
     * A glob pattern the whole text must match.
     *
     * @param pattern the pattern, with {@code *} and {@code ?}
     * @return the pattern
     */
    public static TextMatch wildcard(String pattern) {
        return new TextMatch(Mode.WILDCARD, pattern);
    }

    /**
     * Normalises a text the way the hook normalises a row's: colour tags ({@code <...>}) removed, ASCII lower-cased.
     *
     * @param text the text
     * @return the normalised text
     */
    public static String normalise(String text) {
        StringBuilder out = new StringBuilder(text.length());
        boolean tag = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '<') {
                tag = true;
            } else if (c == '>' && tag) {
                tag = false;
            } else if (!tag) {
                out.append(c);
            }
        }
        return out.toString().toLowerCase(Locale.ROOT);
    }
}
