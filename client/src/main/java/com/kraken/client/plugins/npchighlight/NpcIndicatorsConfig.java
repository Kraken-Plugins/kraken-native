package com.kraken.client.plugins.npchighlight;

import com.kraken.client.plugins.config.Alpha;
import com.kraken.client.plugins.config.Config;
import com.kraken.client.plugins.config.ConfigGroup;
import com.kraken.client.plugins.config.ConfigItem;
import com.kraken.client.plugins.config.ConfigSection;
import com.kraken.client.plugins.config.Range;
import com.kraken.client.plugins.config.Unsupported;

import java.awt.Color;

/**
 * The NPC Indicators plugin's settings: which NPCs to highlight, and how. Per-NPC colours and styles are kept beside
 * these under {@code highlightcolor_<id>} and {@code tagstyle_<id>}.
 */
@ConfigGroup(NpcIndicatorsConfig.GROUP)
public interface NpcIndicatorsConfig extends Config {

    /**
     * The settings' group.
     */
    String GROUP = "npcindicators";

    /**
     * The section for how highlighted NPCs are drawn.
     */
    @ConfigSection(name = "Render style", description = "The render style of NPC highlighting.", position = 0)
    String renderStyleSection = "renderStyleSection";

    /**
     * Whether to highlight the NPC's model. The game fills and outlines the model itself.
     *
     * @return true to highlight the model
     */
    @ConfigItem(position = 0, keyName = "highlightHull", name = "Highlight hull",
            description = "Configures whether or not NPC should be highlighted by hull.",
            section = renderStyleSection)
    default boolean highlightHull() {
        return true;
    }

    /**
     * Whether to outline the tiles the NPC is drawn on, following it as it moves.
     *
     * @return true to outline them
     */
    @ConfigItem(position = 1, keyName = "highlightTile", name = "Highlight tile",
            description = "Configures whether or not NPC should be highlighted by tile.",
            section = renderStyleSection)
    default boolean highlightTile() {
        return false;
    }

    /**
     * Whether to outline the tiles the NPC is on as the server sees it.
     *
     * @return true to outline them
     */
    @ConfigItem(position = 2, keyName = "highlightTrueTile", name = "Highlight true tile",
            description = "Configures whether or not NPC should be highlighted by true tile.",
            section = renderStyleSection)
    default boolean highlightTrueTile() {
        return false;
    }

    /**
     * Whether to outline the south-west tile the NPC is drawn on.
     *
     * @return true to outline it
     */
    @ConfigItem(position = 3, keyName = "highlightSouthWestTile", name = "Highlight south west tile",
            description = "Configures whether or not NPC should be highlighted by south western tile.",
            section = renderStyleSection)
    default boolean highlightSouthWestTile() {
        return false;
    }

    /**
     * Whether to outline the south-west tile the NPC is on as the server sees it.
     *
     * @return true to outline it
     */
    @ConfigItem(position = 4, keyName = "highlightSouthWestTrueTile", name = "Highlight south west true tile",
            description = "Configures whether or not NPC should be highlighted by south western true tile.",
            section = renderStyleSection)
    default boolean highlightSouthWestTrueTile() {
        return false;
    }

    /**
     * Whether to outline the NPC's model. The game draws the outline.
     *
     * @return true to outline the model
     */
    @ConfigItem(position = 5, keyName = "highlightOutline", name = "Highlight outline",
            description = "Configures whether or not the model of the NPC should be highlighted by outline.",
            section = renderStyleSection)
    default boolean highlightOutline() {
        return false;
    }

    /**
     * The highlight's colour, for outlines and names.
     *
     * @return the colour
     */
    @Alpha
    @ConfigItem(position = 10, keyName = "npcColor", name = "Highlight color",
            description = "Color of the NPC highlight border, menu, and text.", section = renderStyleSection)
    default Color highlightColor() {
        return Color.CYAN;
    }

    /**
     * The colour the highlight fills with.
     *
     * @return the colour; its alpha is the fill's opacity
     */
    @Alpha
    @ConfigItem(position = 11, keyName = "fillColor", name = "Fill color", description = "Color of the NPC highlight fill.",
            section = renderStyleSection)
    default Color fillColor() {
        return new Color(0, 255, 255, 20);
    }

    /**
     * The width of the highlight's outlines.
     *
     * @return the width in pixels
     */
    @ConfigItem(position = 12, keyName = "borderWidth", name = "Border width",
            description = "Width of the highlighted NPC border.", section = renderStyleSection)
    default double borderWidth() {
        return 2;
    }

    /**
     * How much of the model outline fades out.
     *
     * @return 0 for a sharp outline, up to 4
     */
    @Unsupported("The game's model outline has no feather")
    @Range(min = 0, max = 4)
    @ConfigItem(position = 13, keyName = "outlineFeather", name = "Outline feather",
            description = "Specify between 0-4 how much of the model outline should be faded.",
            section = renderStyleSection)
    default int outlineFeather() {
        return 0;
    }

    /**
     * The NPCs to highlight.
     *
     * @return comma-separated names, each of which may use {@code *} as a wildcard
     */
    @ConfigItem(position = 7, keyName = "npcToHighlight", name = "NPCs to highlight",
            description = "List of NPC names to highlight. Format: npc1, npc2, npc3")
    default String getNpcToHighlight() {
        return "";
    }

    /**
     * Sets the NPCs to highlight.
     *
     * @param npcsToHighlight comma-separated names
     */
    @ConfigItem(keyName = "npcToHighlight", name = "", description = "")
    void setNpcToHighlight(String npcsToHighlight);

    /**
     * Whether to write highlighted NPCs' names above them.
     *
     * @return true to write names
     */
    @ConfigItem(position = 8, keyName = "drawNames", name = "Draw names above NPC",
            description = "Configures whether or not NPC names should be drawn above the NPC.")
    default boolean drawNames() {
        return false;
    }

    /**
     * Whether to write highlighted NPCs' names on the minimap.
     *
     * @return true to write them
     */
    @Unsupported("Needs minimap positions")
    @ConfigItem(position = 9, keyName = "drawMinimapNames", name = "Draw names on minimap",
            description = "Configures whether or not NPC names should be drawn on the minimap.")
    default boolean drawMinimapNames() {
        return false;
    }

    /**
     * Which part of a highlighted NPC's right-click menu entries is coloured.
     */
    enum MenuHighlightStyle {
        /**
         * Nothing is coloured.
         */
        NONE,
        /**
         * The combat level.
         */
        LEVEL,
        /**
         * The name.
         */
        NAME,
        /**
         * The whole target.
         */
        BOTH
    }

    /**
     * Which part of highlighted NPCs' menu entries to colour.
     *
     * @return the style
     */
    @Unsupported("Needs right-click menu edits")
    @ConfigItem(position = 10, keyName = "highlightMenuStyle", name = "Highlight menu style",
            description = "Highlight style in the right-click menu.")
    default MenuHighlightStyle highlightMenuStyle() {
        return MenuHighlightStyle.NONE;
    }

    /**
     * Whether to stop highlighting NPCs once they are dying.
     *
     * @return true to stop
     */
    @ConfigItem(position = 11, keyName = "ignoreDeadNpcs", name = "Ignore dead NPCs",
            description = "Prevents highlighting NPCs after they are dead.")
    default boolean ignoreDeadNpcs() {
        return true;
    }

    /**
     * The colour of dying NPCs' menu entries.
     *
     * @return the colour, or null to leave them alone
     */
    @Unsupported("Needs right-click menu edits")
    @ConfigItem(position = 12, keyName = "deadNpcMenuColor", name = "Dead NPC menu color",
            description = "Color of the NPC menus for dead NPCs.")
    Color deadNpcMenuColor();

    /**
     * Whether to show where and when highlighted NPCs that died will respawn.
     *
     * @return true to show it
     */
    @ConfigItem(position = 13, keyName = "showRespawnTimer", name = "Show respawn timer",
            description = "Show respawn timer of tagged NPCs.")
    default boolean showRespawnTimer() {
        return false;
    }

    /**
     * Whether to leave pets and other followers unhighlighted.
     *
     * @return true to leave them out
     */
    @ConfigItem(position = 14, keyName = "ignorePets", name = "Ignore pets",
            description = "Excludes pets from being highlighted.")
    default boolean ignorePets() {
        return true;
    }
}
