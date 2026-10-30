package com.kraken.client.util;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Text helpers for the game's strings and for list settings.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class Text {

    private static final Pattern TAG = Pattern.compile("<[^>]*>");

    /**
     * Splits a comma-separated setting into its entries.
     *
     * @param input the text, such as {@code "Goblin, Guard,,Cow "}
     * @return the entries, trimmed, without empty ones
     */
    public static List<String> fromCSV(String input) {
        return Arrays.stream(input.split(","))
                .map(String::trim)
                .filter(entry -> !entry.isEmpty())
                .toList();
    }

    /**
     * Joins entries into a comma-separated setting.
     *
     * @param input the entries; nulls are left out
     * @return the entries joined by commas
     */
    public static String toCSV(Collection<String> input) {
        return input.stream().filter(Objects::nonNull).collect(Collectors.joining(","));
    }

    /**
     * Removes the game's markup tags, such as {@code <col=ffff00>}, from a string.
     *
     * @param str the string
     * @return the string without its tags
     */
    public static String removeTags(String str) {
        return TAG.matcher(str).replaceAll("");
    }
}
