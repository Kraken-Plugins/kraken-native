package com.kraken.client.plugins.tilemarkers;

import com.google.inject.Inject;
import com.google.inject.Provides;
import com.kraken.api.Client;
import com.kraken.api.coords.WorldPoint;
import com.kraken.api.eventbus.Subscribe;
import com.kraken.api.highlight.HighlightCategory;
import com.kraken.api.highlight.HighlightChannel;
import com.kraken.api.highlight.HighlightConfig;
import com.kraken.api.highlight.HighlightTarget;
import com.kraken.client.events.ConfigChanged;
import com.kraken.client.plugins.Plugin;
import com.kraken.client.plugins.PluginDescriptor;
import com.kraken.client.plugins.config.ConfigManager;
import com.kraken.client.ui.overlay.OverlayManager;
import lombok.extern.java.Log;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The tile example plugin: marks the world tiles listed in its settings. The game draws the marks on a tile
 * {@link HighlightChannel} the plugin holds while it runs, inside the scene, under actors and objects; while the
 * native hook isn't connected, its overlay outlines the tiles instead.
 *
 * <p>Nothing here waits on the game: the channel keeps the tiles, and the highlights apply them whenever the hook
 * connects. {@link #tiles} is swapped whole, so the overlay reads it on the client thread without locking.
 */
@Log(topic = "kraken")
@PluginDescriptor(
        name = "Tile Markers",
        description = "Marks world tiles you list",
        tags = {"tile", "marker", "ground", "highlight", "example"},
        enabledByDefault = false
)
public class TileMarkersPlugin extends Plugin {

    @Inject
    private Client client;

    @Inject
    private TileMarkersConfig config;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private TileMarkersOverlay overlay;

    private volatile List<WorldPoint> tiles = List.of();
    private volatile HighlightChannel channel;

    @Provides
    TileMarkersConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(TileMarkersConfig.class);
    }

    @Override
    protected void startUp() {
        HighlightChannel taken = client.getHighlights().channel(HighlightCategory.TILE);
        channel = taken;
        apply();
        overlayManager.add(overlay);
    }

    @Override
    protected void shutDown() {
        overlayManager.remove(overlay);
        HighlightChannel held = channel;
        channel = null;
        if (held != null) {
            held.release();
        }
        tiles = List.of();
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event) {
        if (event.getGroup().equals(TileMarkersConfig.GROUP)) {
            apply();
        }
    }

    /**
     * The tiles being marked.
     *
     * @return the world tiles
     */
    List<WorldPoint> getTiles() {
        return tiles;
    }

    /**
     * Whether the game draws the marks now, so the overlay stays out of it.
     *
     * @return true while the native hook is connected
     */
    boolean isDrawnInGame() {
        return client.getHighlights().isAvailable();
    }

    /**
     * Reads the settings into the channel: the style, then exactly the listed tiles.
     */
    private void apply() {
        List<WorldPoint> parsed = parse(config.tiles());
        tiles = parsed;
        HighlightChannel held = channel;
        if (held != null) {
            held.configure(HighlightConfig.tileOutline(config.colour(), Math.max(1, Math.min(config.width(), 16)),
                    config.fill()));
            held.set(parsed.stream().map(HighlightTarget::tile).toList());
        }
    }

    /**
     * The tiles in the setting. An entry that isn't three whole numbers is logged and skipped.
     *
     * @param list {@code x, y, plane} entries separated by semicolons or new lines
     * @return the tiles, without repeats, in the order listed
     */
    static List<WorldPoint> parse(String list) {
        Set<WorldPoint> out = new LinkedHashSet<>();
        for (String entry : list.split("[;\\n]")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] parts = trimmed.split("\\s*,\\s*|\\s+");
            try {
                if (parts.length != 3) {
                    throw new NumberFormatException("expected x, y, plane");
                }
                int plane = Integer.parseInt(parts[2]);
                if (plane < 0 || plane > 3) {
                    throw new NumberFormatException("plane must be 0-3");
                }
                out.add(new WorldPoint(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), plane));
            } catch (NumberFormatException e) {
                log.warning("Tile Markers: skipping \"" + trimmed + "\" (" + e.getMessage() + ")");
            }
        }
        return List.copyOf(out);
    }
}
