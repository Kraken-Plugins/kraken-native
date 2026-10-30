package com.kraken.client.util;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Matches names against patterns with {@code *} wildcards, ignoring case: {@code "goblin*"} matches "Goblin" and
 * "Goblin champion", {@code "*dragon"} matches "Green dragon". Everything other than {@code *} is literal.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class WildcardMatcher {

    private static final Pattern WILDCARD_PATTERN = Pattern.compile("(?i)[^*]+|(\\*+)");

    /**
     * Whether a text matches a pattern as a whole.
     *
     * @param pattern the pattern, with {@code *} standing for any run of characters
     * @param text    the text, such as an NPC's or item's name
     * @return true if it matches, ignoring case
     */
    public static boolean matches(String pattern, String text) {
        Matcher matcher = WILDCARD_PATTERN.matcher(pattern);
        StringBuilder regex = new StringBuilder("(?i)");
        while (matcher.find()) {
            if (matcher.group(1) != null) {
                matcher.appendReplacement(regex, ".*");
            } else {
                matcher.appendReplacement(regex, Matcher.quoteReplacement(Pattern.quote(matcher.group(0))));
            }
        }
        matcher.appendTail(regex);
        return text.matches(regex.toString());
    }
}
