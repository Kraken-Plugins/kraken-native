package com.kraken.api.menu;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * One rule the native hook applies to the right-click menu each time the client rebuilds it (every client cycle
 * while the menu is closed), before the cycle's click handling and the drawing see the rows. Rules are kept by
 * {@link MenuEdits}; the hook applies them in the order they were added, each on the rows as the rules before it
 * left them.
 *
 * <p>Three kinds: an {@link Edit} changes the rows it matches, a {@link Swap} moves a row that another row's presence
 * asks for, an {@link Add} puts a row of the plugin's own next to the rows it matches.
 */
public sealed interface MenuRule permits MenuRule.Edit, MenuRule.Swap, MenuRule.Add {

    /**
     * When the rule applies, by the Shift key.
     *
     * @return the shift state
     */
    ShiftState shift();

    /**
     * Whether the rule goes away by itself once nothing has matched it for about half a second of rebuilds (25 of
     * them), as the per-entry edits do.
     *
     * @return true for a scoped rule
     */
    boolean scoped();

    /**
     * Changes the rows a match names.
     *
     * @param match    the rows
     * @param shift    when the rule applies
     * @param scoped   whether the rule expires once nothing matches it
     * @param finalRow true to keep the rows from being swapped by later rules
     * @param actions  what to do to each row
     * @param option   the option text to write, for {@link Action#SET_OPTION}; "" otherwise
     * @param target   the target text to write, for {@link Action#SET_TARGET}; "" otherwise
     */
    record Edit(MenuMatch match, ShiftState shift, boolean scoped, boolean finalRow, Set<Action> actions,
                String option, String target) implements MenuRule {

        /**
         * What an edit does to a row.
         */
        public enum Action {

            /**
             * Takes the row out of the menu.
             */
            HIDE,

            /**
             * Moves the row to the bottom, above Cancel, so a left click runs the row above it instead.
             */
            DEPRIORITIZE,

            /**
             * Swaps the row with the top row. A sub-menu row is copied to the top instead.
             */
            PROMOTE,

            /**
             * Swaps the row with the highest row on the same subject, making it that subject's left click.
             */
            PROMOTE_SUBJECT,

            /**
             * Writes the option text.
             */
            SET_OPTION,

            /**
             * Writes the target text.
             */
            SET_TARGET,

            /**
             * Reports a click on the row to the plugin ({@link com.kraken.api.MenuEntry#onClick}); the client still
             * runs the row.
             */
            REPORT
        }

        /**
         * Builds an edit.
         *
         * @param match    the rows
         * @param shift    when the rule applies
         * @param scoped   whether the rule expires once nothing matches it
         * @param finalRow true to keep the rows from being swapped by later rules
         * @param actions  what to do to each row
         * @param option   the option text to write, or ""
         * @param target   the target text to write, or ""
         */
        public Edit {
            Objects.requireNonNull(match, "match");
            Objects.requireNonNull(shift, "shift");
            actions = actions.isEmpty() ? EnumSet.noneOf(Action.class) : EnumSet.copyOf(actions);
            option = option == null ? "" : option;
            target = target == null ? "" : target;
        }

        /**
         * An edit that applies whenever its rows show, for good.
         *
         * @param match   the rows
         * @param actions what to do to each row
         * @return the rule
         */
        public static Edit of(MenuMatch match, Action... actions) {
            return new Edit(match, ShiftState.ANY, false, false, Set.of(actions), "", "");
        }

        /**
         * An edit that applies in one shift state.
         *
         * @param match   the rows
         * @param shift   when it applies
         * @param actions what to do to each row
         * @return the rule
         */
        public static Edit of(MenuMatch match, ShiftState shift, Action... actions) {
            return new Edit(match, shift, false, false, Set.of(actions), "", "");
        }
    }

    /**
     * Swaps a row with another when both show: each row the trigger match names changes places with the first row
     * the swapped match names (below it, with the same target or on the same subject when asked), or with the top
     * row when {@code toTop} is set. A row takes part in one swap per rebuild.
     *
     * @param trigger     the rows that ask for the swap
     * @param swapped     the rows to swap them with
     * @param shift       when the rule applies
     * @param sameTarget  true to only swap with a row whose target text equals the trigger's
     * @param sameSubject true to only swap with a row on the same subject as the trigger
     * @param below       true to only swap with a row below the trigger
     * @param toTop       true to swap the found row with the top row instead of with the trigger
     */
    record Swap(MenuMatch trigger, MenuMatch swapped, ShiftState shift, boolean sameTarget, boolean sameSubject,
                boolean below, boolean toTop) implements MenuRule {

        /**
         * Builds a swap.
         *
         * @param trigger     the rows that ask for the swap
         * @param swapped     the rows to swap them with
         * @param shift       when the rule applies
         * @param sameTarget  true to only swap with a row whose target text equals the trigger's
         * @param sameSubject true to only swap with a row on the same subject as the trigger
         * @param below       true to only swap with a row below the trigger
         * @param toTop       true to swap the found row with the top row instead of with the trigger
         */
        public Swap {
            Objects.requireNonNull(trigger, "trigger");
            Objects.requireNonNull(swapped, "swapped");
            Objects.requireNonNull(shift, "shift");
        }

        /**
         * The usual left-click swap: a row whose option is one text changes places with the row below it whose
         * option is another and whose target is the same, so the other option becomes the left click.
         *
         * @param option  the option of the row that asks for the swap, such as "Talk-to"
         * @param swapped the option to bring up, such as "Bank"
         * @param shift   when the rule applies
         * @return the rule
         */
        public static Swap options(String option, String swapped, ShiftState shift) {
            return new Swap(MenuMatch.any().option(TextMatch.exact(option)),
                    MenuMatch.any().option(TextMatch.exact(swapped)), shift, true, false, true, false);
        }

        @Override
        public boolean scoped() {
            return false;
        }
    }

    /**
     * Puts a row of the plugin's own next to the highest row a match names, once per rebuild. Clicking the row (or
     * a row of its sub-menu) runs the row's callback and posts a {@code MenuOptionClicked} for it, and nothing in the
     * client.
     *
     * @param anchor   the rows to put it next to
     * @param shift    when the rule applies
     * @param scoped   whether the rule expires once nothing matches it
     * @param position where the row goes
     * @param row      the row
     */
    record Add(MenuMatch anchor, ShiftState shift, boolean scoped, Position position, MenuRow row)
            implements MenuRule {

        /**
         * Where an added row goes.
         */
        public enum Position {

            /**
             * Right above the matched row.
             */
            ABOVE,

            /**
             * Right below the matched row.
             */
            BELOW,

            /**
             * At the top of the menu, as its left click.
             */
            TOP
        }

        /**
         * Builds an add.
         *
         * @param anchor   the rows to put it next to
         * @param shift    when the rule applies
         * @param scoped   whether the rule expires once nothing matches it
         * @param position where the row goes
         * @param row      the row
         */
        public Add {
            Objects.requireNonNull(anchor, "anchor");
            Objects.requireNonNull(shift, "shift");
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(row, "row");
        }
    }
}
