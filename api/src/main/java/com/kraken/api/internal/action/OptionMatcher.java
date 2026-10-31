package com.kraken.api.internal.action;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Turns an option's text or number into the op the menu runs. Text is matched loosely: the client's markup
 * ({@code <col=ff9040>}) is removed, case is ignored, and spaces, {@code -} and {@code _} count as the same, so
 * "Chop-Down", "chop down" and "Chop down" all match. The first op in the subject's order that matches wins. Empty
 * slots and the "null" name the cache gives unnamed things never match. Internal: the interaction methods use it.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class OptionMatcher {

    /**
     * The client's text markup, such as {@code <col=ffff00>}, but not its escapes for the angle brackets.
     */
    private static final Pattern TAGS = Pattern.compile("<(?!lt>|gt>)[^>]*>");

    /**
     * The characters counted as one: spaces, hyphens and underscores, in runs.
     */
    private static final Pattern SEPARATORS = Pattern.compile("[\\s\\-_]+");

    /**
     * Which op an option's text names.
     *
     * @param options the subject's options by op, 1-based in the array's order; null entries for empty slots
     * @param option  the option's text
     * @param max     the most ops the subject has; options past it aren't matched
     * @return the op, 1-based, or -1 if none matches
     * @throws NullPointerException if the option is null
     */
    public static int match(String[] options, String option, int max) {
        Objects.requireNonNull(option, "option");
        String wanted = normalize(option);
        if (wanted.isEmpty() || options == null) {
            return -1;
        }
        for (int i = 0; i < options.length && i < max; i++) {
            if (isNamed(options[i]) && normalize(options[i]).equals(wanted)) {
                return i + 1;
            }
        }
        return -1;
    }

    /**
     * Whether an op number names one of the subject's options: it is within the subject's limit and, when the
     * options are known, its slot has a name.
     *
     * @param options the subject's options by op, or null if they can't be read
     * @param op      the op, 1-based
     * @param max     the most ops the subject has
     * @return true if the op can be run
     */
    public static boolean isOp(String[] options, int op, int max) {
        if (op < 1 || op > max) {
            return false;
        }
        return options == null || op <= options.length && isNamed(options[op - 1]);
    }

    /**
     * The first op with a name.
     *
     * @param options the subject's options by op, or null
     * @param max     the most ops the subject has
     * @return the op, 1-based, or -1 if none has a name
     */
    public static int first(String[] options, int max) {
        for (int i = 0; options != null && i < options.length && i < max; i++) {
            if (isNamed(options[i])) {
                return i + 1;
            }
        }
        return -1;
    }

    /**
     * Text as options are compared: no markup, lower case, each run of spaces, hyphens and underscores one space,
     * trimmed.
     *
     * @param text the text, or null
     * @return the compared form; "" for null
     */
    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return SEPARATORS.matcher(plain(text)).replaceAll(" ").trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Text as the client shows it: no markup, the escapes {@code <lt>} and {@code <gt>} as the brackets they stand
     * for, spaces collapsed.
     *
     * @param text the text, or null
     * @return the plain text; "" for null
     */
    public static String plain(String text) {
        if (text == null) {
            return "";
        }
        return TAGS.matcher(text).replaceAll("").replace("<lt>", "<").replace("<gt>", ">").replaceAll("\\s+", " ")
                .trim();
    }

    /**
     * Whether an option's slot names something: not empty once its markup is gone, and not "null".
     *
     * @param option the option, or null
     * @return true if it is a name
     */
    public static boolean isNamed(String option) {
        String plain = plain(option);
        return !plain.isEmpty() && !plain.equalsIgnoreCase("null");
    }

    /**
     * The options with names, for a log line.
     *
     * @param options the options by op, or null
     * @return e.g. {@code Talk-to, Attack}; "none" when none has a name, "unknown" for null
     */
    public static String describe(String[] options) {
        if (options == null) {
            return "unknown";
        }
        List<String> names = new ArrayList<>();
        for (String option : options) {
            if (isNamed(option)) {
                names.add(plain(option));
            }
        }
        return names.isEmpty() ? "none" : String.join(", ", names);
    }
}
