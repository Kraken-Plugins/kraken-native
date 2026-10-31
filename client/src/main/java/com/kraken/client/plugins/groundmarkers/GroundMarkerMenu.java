package com.kraken.client.plugins.groundmarkers;

import com.kraken.api.menu.MenuRow;
import com.kraken.client.util.ColorUtil;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The rows Shift + right-click puts below a tile's Walk here row: Mark or Unmark the tile and, on a marked tile, Label
 * it and change its colour from a Color sub-menu (another colour in use nearby, Pick, or Reset all for the region).
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class GroundMarkerMenu {

    static final String MARK = "Mark";
    static final String UNMARK = "Unmark";
    static final String LABEL = "Label";
    static final String COLOR = "Color";
    static final String PICK = "Pick";
    static final String RESET_ALL = "Reset all";
    static final String TARGET = "Tile";

    /**
     * What the rows do when clicked.
     *
     * @param mark        marks the tile, or unmarks it if it is marked
     * @param label       asks for the mark's label
     * @param color       gives the mark a colour
     * @param pickColor   asks for the mark's colour
     * @param resetColors asks whether to give every mark in the region the tile colour
     */
    record Actions(Runnable mark, Runnable label, Consumer<Color> color, Runnable pickColor, Runnable resetColors) {

        /**
         * Builds the actions.
         *
         * @param mark        marks or unmarks the tile
         * @param label       asks for the mark's label
         * @param color       gives the mark a colour
         * @param pickColor   asks for the mark's colour
         * @param resetColors asks whether to reset the region's colours
         */
        Actions {
            Objects.requireNonNull(mark, "mark");
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(color, "color");
            Objects.requireNonNull(pickColor, "pickColor");
            Objects.requireNonNull(resetColors, "resetColors");
        }
    }

    /**
     * The rows for one tile, top first.
     *
     * @param mark        the tile's mark, or null if it isn't marked
     * @param regionMarks how many marks the tile's region has
     * @param colors      the colours of the marks loaded now, each once
     * @param actions     what the rows do
     * @return Mark for an unmarked tile; Unmark, Label and Color for a marked one
     */
    static List<MenuRow> rows(GroundMarkerPoint mark, int regionMarks, List<Color> colors, Actions actions) {
        if (mark == null) {
            return List.of(MenuRow.of(MARK, TARGET, e -> actions.mark().run()));
        }
        List<MenuRow> children = new ArrayList<>();
        for (Color color : colors) {
            if (children.size() == MenuRow.MAX_CHILDREN - 2) {
                break;
            }
            if (color != null && !color.equals(mark.getColor())) {
                children.add(MenuRow.of(ColorUtil.prependColorTag(COLOR, color), "",
                        e -> actions.color().accept(color)));
            }
        }
        children.add(MenuRow.of(PICK, "", e -> actions.pickColor().run()));
        if (regionMarks > 1) {
            children.add(MenuRow.of(RESET_ALL, "", e -> actions.resetColors().run()));
        }
        return List.of(
                MenuRow.of(UNMARK, TARGET, e -> actions.mark().run()),
                MenuRow.of(LABEL, TARGET, e -> actions.label().run()),
                new MenuRow(COLOR, TARGET, null, children));
    }
}
