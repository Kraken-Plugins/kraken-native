package com.kraken.api.internal.menu;

import com.kraken.api.menu.MenuRule;
import com.kraken.api.menu.ShiftState;
import com.kraken.api.menu.TextMatch;

import java.util.List;
import java.util.Set;

/**
 * One menu rule as the native hook takes it: the mapping's numbers in place of the API's enums, so the client's
 * protocol only has to lay the fields out. Internal: {@code MenuEdits} compiles its rules into these and hands
 * them to the {@link MenuHook}.
 *
 * @param id      the rule's id, which the hook reports expiries and clicks by
 * @param type    the kind of rule
 * @param shift   when the rule applies
 * @param scoped  whether the hook drops the rule once nothing has matched it for a while
 * @param finalRow true to keep the rows an edit matches from later swaps
 * @param match   the rows (an edit's rows, a swap's trigger, an add's anchor)
 * @param actions an edit's actions
 * @param option  the option text an edit writes
 * @param target  the target text an edit writes
 * @param swapped a swap's other rows, or null
 * @param sameTarget a swap's same-target condition
 * @param sameSubject a swap's same-subject condition
 * @param below   a swap's below condition
 * @param toTop   a swap's swap-with-top
 * @param position an add's position, or null
 * @param row     an add's row, or null
 */
public record WireRule(int id, Type type, ShiftState shift, boolean scoped, boolean finalRow, Match match,
                       Set<MenuRule.Edit.Action> actions, String option, String target, Match swapped,
                       boolean sameTarget, boolean sameSubject, boolean below, boolean toTop,
                       MenuRule.Add.Position position, Row row) {

    /**
     * The kinds of rule.
     */
    public enum Type {
        EDIT, SWAP, ADD
    }

    /**
     * A match with the mapping's numbers: a field counts when its flag in {@code mask} is set.
     *
     * @param mask    which fields count, as {@link Field}s
     * @param kind    the subject kind ({@code enums.minimenuSubject})
     * @param typeId  the type id
     * @param index   the subject index
     * @param op      the op index as the entry holds it
     * @param group   a component's interface id
     * @param level   the tile's level
     * @param x       the tile's x
     * @param z       the tile's z
     * @param option  the option pattern, or null
     * @param target  the target pattern, or null
     * @param subRows whether sub-menu rows are matched instead of top rows
     */
    public record Match(Set<Field> mask, int kind, int typeId, int index, int op, int group, int level, int x, int z,
                        TextMatch option, TextMatch target, boolean subRows) {

        /**
         * The parts of a match.
         */
        public enum Field {
            KIND, TYPE_ID, INDEX, OP, TILE, OPTION, TARGET, GROUP
        }
    }

    /**
     * A row of Kraken's own and its sub rows, by id.
     *
     * @param id       the row id
     * @param option   the option text
     * @param target   the target text
     * @param children the sub rows, top first
     */
    public record Row(int id, String option, String target, List<Row> children) {
    }
}
