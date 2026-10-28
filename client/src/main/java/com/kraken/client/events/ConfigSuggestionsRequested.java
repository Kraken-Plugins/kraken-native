package com.kraken.client.events;

import lombok.Getter;
import lombok.ToString;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * A settings page wants the suggestions of an item marked {@code @Suggestions}: its drop-down is opening. Posted on
 * the EDT. The plugin answers with {@link #suggest}, from any thread, now or later (after reading the game on the
 * client thread); the drop-down shows the latest answer. Unanswered, it keeps the last list it had.
 */
@Getter
@ToString(exclude = "reply")
public class ConfigSuggestionsRequested {

    /**
     * The item's group, a {@code @ConfigGroup} value.
     */
    private final String group;

    /**
     * The item's key within the group.
     */
    private final String key;

    private final Consumer<List<String>> reply;

    /**
     * Creates the request.
     *
     * @param group the item's group
     * @param key   the item's key
     * @param reply takes the suggestions; called on whichever thread answers
     */
    public ConfigSuggestionsRequested(String group, String key, Consumer<List<String>> reply) {
        this.group = group;
        this.key = key;
        this.reply = reply;
    }

    /**
     * Answers the request.
     *
     * @param suggestions the suggestions, in the order to show them (null entries are skipped); null or empty for
     *                    none
     */
    public void suggest(List<String> suggestions) {
        reply.accept(suggestions == null ? List.of() : suggestions.stream().filter(Objects::nonNull).toList());
    }
}
