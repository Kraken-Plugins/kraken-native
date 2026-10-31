package com.kraken.client.plugins.npchighlight;

import com.kraken.api.menu.MenuRow;
import com.kraken.client.util.ColorUtil;
import com.kraken.client.util.WildcardMatcher;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The rows Shift + right-click puts above an NPC's Examine row: Tag or Un-tag the NPC, Tag-All or Un-tag-All its
 * name, and, for an NPC that is highlighted, Tag color and Tag style sub-menus that set its NPC id's own colour and
 * style.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class NpcTagMenu {

    static final String TAG = "Tag";
    static final String UNTAG = "Un-tag";
    static final String TAG_ALL = "Tag-All";
    static final String UNTAG_ALL = "Un-tag-All";
    static final String TAG_COLOR = "Tag color";
    static final String TAG_STYLE = "Tag style";
    static final String SET_COLOR = "Set color";
    static final String PICK_COLOR = "Pick color";
    static final String RESET = "Reset";

    /**
     * How many colours the Tag color sub-menu offers, counting the defaults that fill it up.
     */
    static final int MAX_COLORS = 5;

    private static final List<Color> DEFAULT_COLORS = List.of(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW,
            Color.MAGENTA);

    private static final List<String> STYLE_NAMES = List.of("Hull", "Tile", "True tile", "South-west tile",
            "South-west true tile", "Outline");

    private static final List<String> STYLES = List.of(RenderStyle.HULL, RenderStyle.TILE, RenderStyle.TRUE_TILE,
            RenderStyle.SW_TILE, RenderStyle.SW_TRUE_TILE, RenderStyle.OUTLINE);

    /**
     * What the plugin holds about an NPC, which picks the rows.
     *
     * @param tagged   whether the NPC is tagged by its index
     * @param listed   whether its name is in the highlight list as it is, ignoring case
     * @param wildcard whether another pattern in the list matches its name
     * @param ownColor whether its NPC id has a colour of its own
     * @param ownStyle whether its NPC id has a tag style of its own
     */
    record State(boolean tagged, boolean listed, boolean wildcard, boolean ownColor, boolean ownStyle) {

        /**
         * Whether the plugin highlights the NPC, so its colour and style can be set.
         *
         * @return true if it is tagged or its name is matched
         */
        boolean highlighted() {
            return tagged || listed || wildcard;
        }
    }

    /**
     * What the rows do when clicked.
     *
     * @param tag        tags the NPC, or un-tags it
     * @param tagAll     adds the NPC's name to the highlight list, or takes it out
     * @param color      gives the NPC id a colour
     * @param pickColor  asks for the NPC id's colour
     * @param resetColor takes the NPC id's colour off
     * @param style      gives the NPC id a tag style, one of {@link RenderStyle}'s names
     * @param resetStyle takes the NPC id's tag style off
     */
    record Actions(Runnable tag, Runnable tagAll, Consumer<Color> color, Runnable pickColor, Runnable resetColor,
                   Consumer<String> style, Runnable resetStyle) {

        /**
         * Builds the actions.
         *
         * @param tag        tags or un-tags the NPC
         * @param tagAll     adds or takes out the NPC's name
         * @param color      gives the NPC id a colour
         * @param pickColor  asks for the NPC id's colour
         * @param resetColor takes the NPC id's colour off
         * @param style      gives the NPC id a tag style
         * @param resetStyle takes the NPC id's tag style off
         */
        Actions {
            Objects.requireNonNull(tag, "tag");
            Objects.requireNonNull(tagAll, "tagAll");
            Objects.requireNonNull(color, "color");
            Objects.requireNonNull(pickColor, "pickColor");
            Objects.requireNonNull(resetColor, "resetColor");
            Objects.requireNonNull(style, "style");
            Objects.requireNonNull(resetStyle, "resetStyle");
        }
    }

    /**
     * What the plugin holds about an NPC.
     *
     * @param highlights the highlight list's patterns
     * @param name       the NPC's name
     * @param tagged     whether the NPC is tagged by its index
     * @param ownColor   whether its NPC id has a colour of its own
     * @param ownStyle   whether its NPC id has a tag style of its own
     * @return the state
     */
    static State state(List<String> highlights, String name, boolean tagged, boolean ownColor, boolean ownStyle) {
        boolean listed = highlights.stream().anyMatch(name::equalsIgnoreCase);
        boolean wildcard = highlights.stream().filter(pattern -> !pattern.equalsIgnoreCase(name))
                .anyMatch(pattern -> WildcardMatcher.matches(pattern, name));
        return new State(tagged, listed, wildcard, ownColor, ownStyle);
    }

    /**
     * The rows for one NPC, top first. Tag-All is left out for a name a wildcard pattern matches, as Un-tag-All
     * can't take a wildcard out.
     *
     * @param target  the Examine row's target, with its colour tags
     * @param state   what the plugin holds about the NPC
     * @param colors  the colours to offer, from {@link #colorChoices}
     * @param actions what the rows do
     * @return the rows
     */
    static List<MenuRow> rows(String target, State state, List<Color> colors, Actions actions) {
        List<MenuRow> rows = new ArrayList<>();
        rows.add(MenuRow.of(state.tagged() ? UNTAG : TAG, target, e -> actions.tag().run()));
        if (!state.wildcard()) {
            rows.add(MenuRow.of(state.listed() ? UNTAG_ALL : TAG_ALL, target, e -> actions.tagAll().run()));
        }
        if (!state.highlighted()) {
            return rows;
        }
        List<MenuRow> colorRows = new ArrayList<>();
        for (Color color : colors) {
            colorRows.add(MenuRow.of(ColorUtil.prependColorTag(SET_COLOR, color), "",
                    e -> actions.color().accept(color)));
        }
        colorRows.add(MenuRow.of(PICK_COLOR, "", e -> actions.pickColor().run()));
        if (state.ownColor()) {
            colorRows.add(MenuRow.of(RESET, "", e -> actions.resetColor().run()));
        }
        rows.add(new MenuRow(TAG_COLOR, target, null, colorRows));
        List<MenuRow> styleRows = new ArrayList<>();
        for (int i = 0; i < STYLES.size(); i++) {
            String style = STYLES.get(i);
            styleRows.add(MenuRow.of(STYLE_NAMES.get(i), "", e -> actions.style().accept(style)));
        }
        if (state.ownStyle()) {
            styleRows.add(MenuRow.of(RESET, "", e -> actions.resetStyle().run()));
        }
        rows.add(new MenuRow(TAG_STYLE, target, null, styleRows));
        return rows;
    }

    /**
     * The colours the Tag color sub-menu offers: those NPCs nearby have of their own, then red, green, blue, yellow
     * and magenta, up to {@link #MAX_COLORS}.
     *
     * @param used the colours of NPCs nearby, each once, at most {@link #MAX_COLORS}
     * @return the colours
     */
    static List<Color> colorChoices(List<Color> used) {
        List<Color> colors = new ArrayList<>(used.subList(0, Math.min(used.size(), MAX_COLORS)));
        for (Color color : DEFAULT_COLORS) {
            if (colors.size() < MAX_COLORS && !colors.contains(color)) {
                colors.add(color);
            }
        }
        return colors;
    }
}
