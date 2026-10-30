package com.kraken.client.plugins.groundmarkers;

import com.kraken.client.plugins.config.Alpha;
import com.kraken.client.plugins.config.Button;
import com.kraken.client.plugins.config.Config;
import com.kraken.client.plugins.config.ConfigGroup;
import com.kraken.client.plugins.config.ConfigItem;
import com.kraken.client.plugins.config.ConfigSection;
import com.kraken.client.plugins.config.Keybind;
import com.kraken.client.plugins.config.Range;
import com.kraken.client.plugins.config.Unsupported;

import java.awt.Color;

/**
 * The Ground Markers plugin's settings: how marked tiles look, the key that marks the tile under the mouse, and the
 * buttons that import, export and clear marked tiles. The marked tiles themselves are kept in the same group under a
 * {@code region_<id>} key per region, each a JSON list of {@link GroundMarkerPoint}s.
 */
@ConfigGroup(GroundMarkerConfig.GROUND_MARKER_CONFIG_GROUP)
public interface GroundMarkerConfig extends Config {

    /**
     * The settings' group, which also holds the marked tiles.
     */
    String GROUND_MARKER_CONFIG_GROUP = "groundMarker";

    /**
     * The key of the import, export and clear options setting.
     */
    String SHOW_IMPORT_EXPORT_KEY_NAME = "showImportExport";

    /**
     * The section for marking tiles and moving them between clients.
     */
    @ConfigSection(name = "Marking and sharing", description = "Marking tiles in the game, and importing, exporting "
            + "and clearing marked tiles", position = 10)
    String sharing = "sharing";

    /**
     * The colour new marks get.
     *
     * @return the colour
     */
    @Alpha
    @ConfigItem(keyName = "markerColor", name = "Tile color", description = "The default color for marked tiles.",
            position = 0)
    default Color markerColor() {
        return Color.YELLOW;
    }

    /**
     * Whether to mark the tiles on the minimap too.
     *
     * @return true to draw them on the minimap
     */
    @Unsupported("Needs minimap positions")
    @ConfigItem(keyName = "drawOnMinimap", name = "Draw tiles on minimap",
            description = "Configures whether marked tiles should be drawn on minimap.", position = 1)
    default boolean drawTileOnMinimmap() {
        return false;
    }

    /**
     * Whether the world map orb's right-click menu has Import, Export and Clear options.
     *
     * @return true to add the options
     */
    @Unsupported("Needs right-click menu edits; use the buttons below")
    @ConfigItem(keyName = SHOW_IMPORT_EXPORT_KEY_NAME, name = "Show import/export/clear options",
            description = "Show the Import, Export, and Clear options on the world map orb right-click menu.",
            position = 2)
    default boolean showImportExport() {
        return true;
    }

    /**
     * The marks' border width.
     *
     * @return the width in pixels
     */
    @ConfigItem(keyName = "borderWidth", name = "Border width", description = "Width of the marked tile border.",
            position = 3)
    default double borderWidth() {
        return 2;
    }

    /**
     * How opaque the marks' fill is.
     *
     * @return the alpha, 0 to 255
     */
    @Range(max = 255)
    @ConfigItem(keyName = "fillOpacity", name = "Fill opacity", description = "Opacity of the tile fill color.",
            position = 4)
    default int fillOpacity() {
        return 50;
    }

    /**
     * The key that marks or unmarks the tile under the mouse.
     *
     * @return the key; not set by default
     */
    @ConfigItem(keyName = "markHotkey", name = "Mark hovered tile",
            description = "Press to mark the tile under the mouse in the tile color, or unmark it. The game sees the "
                    + "key too, so pick one it doesn't use.", position = 0, section = sharing)
    default Keybind markHotkey() {
        return Keybind.NOT_SET;
    }

    /**
     * Imports marked tiles from the clipboard.
     *
     * @return the button
     */
    @ConfigItem(keyName = "importMarkers", name = "Import from clipboard",
            description = "Adds the marked tiles copied to the clipboard, as an export from this plugin writes them.",
            warning = "Import the ground markers on the clipboard?", position = 1, section = sharing)
    Button importMarkers();

    /**
     * Exports the marked tiles of the loaded area to the clipboard.
     *
     * @return the button
     */
    @ConfigItem(keyName = "exportMarkers", name = "Export to clipboard",
            description = "Copies the marked tiles of the area loaded around you to the clipboard.", position = 2,
            section = sharing)
    Button exportMarkers();

    /**
     * Clears the marked tiles of the loaded area.
     *
     * @return the button
     */
    @ConfigItem(keyName = "clearMarkers", name = "Clear loaded markers",
            description = "Unmarks every marked tile in the area loaded around you.",
            warning = "Clear every ground marker in the area loaded around you?", position = 3, section = sharing)
    Button clearMarkers();
}
