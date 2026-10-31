package com.kraken.client.plugins.groundmarkers;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import com.google.inject.Inject;
import com.google.inject.Provides;
import com.kraken.api.Client;
import com.kraken.api.GameState;
import com.kraken.api.MenuAction;
import com.kraken.api.MenuEntry;
import com.kraken.api.coords.WorldPoint;
import com.kraken.api.eventbus.Subscribe;
import com.kraken.api.events.GameStateChanged;
import com.kraken.api.events.MenuEntryAdded;
import com.kraken.api.events.MenuEntryRemoved;
import com.kraken.api.menu.MenuEdits;
import com.kraken.api.menu.MenuMatch;
import com.kraken.api.menu.MenuRow;
import com.kraken.api.menu.MenuRule;
import com.kraken.api.menu.ShiftState;
import com.kraken.api.scene.Tile;
import com.kraken.api.scene.WorldView;
import com.kraken.client.callback.ClientThread;
import com.kraken.client.events.ConfigButtonClicked;
import com.kraken.client.events.ConfigChanged;
import com.kraken.client.input.HotkeyListener;
import com.kraken.client.input.KeyManager;
import com.kraken.client.plugins.Plugin;
import com.kraken.client.plugins.PluginDescriptor;
import com.kraken.client.plugins.config.ConfigManager;
import com.kraken.client.plugins.config.Keybind;
import com.kraken.client.ui.Prompts;
import com.kraken.client.ui.overlay.OverlayManager;
import lombok.extern.java.Log;

import java.awt.Color;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.swing.SwingUtilities;

/**
 * Marks tiles. Shift + right-click a tile to Mark or Unmark it, and to Label a marked tile or change its colour; the
 * mark hotkey marks or unmarks the tile under the mouse. Marked tiles are saved per region in the plugin's settings
 * group, so they come back whenever their region is loaded, and move between clients as JSON through the clipboard
 * (Import, Export and Clear on the settings page).
 *
 * <p>{@link #points}, the marks of the loaded area, is rebuilt on the client thread when the plugin starts, when a
 * map finishes loading and when the saved marks change, and swapped whole so the overlay reads it without locking.
 * Instanced areas aren't translated to their template regions, so marks saved for a region don't show in an
 * instance built from it.
 *
 * <p>The menu rows ({@link GroundMarkerMenu}) are rules the native hook applies as the client rebuilds the menu
 * ({@link MenuEdits}): made for a Walk here row when it appears while Shift is held, or when Shift goes down over it,
 * shown only while Shift is held, and made again whenever the marks change so they say what the tile is now. The
 * label and the picked colour are asked for in dialogs ({@link Prompts}), as the chatbox isn't written to.
 */
@Log(topic = "kraken")
@PluginDescriptor(
        name = "Ground Markers",
        description = "Enable marking of tiles using the Shift key",
        tags = {"overlay", "tiles"},
        enabledByDefault = false
)
public class GroundMarkerPlugin extends Plugin {

    private static final String REGION_PREFIX = "region_";
    private static final Type POINT_LIST = new TypeToken<List<GroundMarkerPoint>>() {
    }.getType();

    /**
     * Reads and writes marked tile JSON, with colours as {@code #AARRGGBB}.
     */
    static final Gson GSON = new GsonBuilder().registerTypeAdapter(Color.class, new ColorTypeAdapter()).create();

    @Inject
    private Client client;

    @Inject
    private ClientThread clientThread;

    @Inject
    private GroundMarkerConfig config;

    @Inject
    private ConfigManager configManager;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private GroundMarkerOverlay overlay;

    @Inject
    private KeyManager keyManager;

    @Inject
    private Prompts prompts;

    /**
     * The marks of the loaded area, swapped whole on the client thread.
     */
    private volatile List<ColorTileMarker> points = List.of();

    /**
     * The ids of the rules adding the menu rows next to each Walk here row, by the row's identity. Client thread only.
     */
    private final Map<MenuMatch, List<Integer>> menuRows = new LinkedHashMap<>();

    /**
     * Marks or unmarks the tile under the mouse when the mark hotkey goes down.
     */
    private final HotkeyListener markListener = new HotkeyListener(() -> config.markHotkey()) {
        @Override
        public void hotkeyPressed() {
            markHoveredTile();
        }
    };

    /**
     * Makes the menu rows for the tile under the mouse when Shift goes down after the mouse got there.
     */
    private final HotkeyListener shiftListener = new HotkeyListener(() -> Keybind.SHIFT) {
        @Override
        public void hotkeyPressed() {
            offerMenuRows();
        }
    };

    @Provides
    GroundMarkerConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(GroundMarkerConfig.class);
    }

    @Override
    protected void startUp() {
        overlayManager.add(overlay);
        keyManager.registerHotkeyListener(markListener);
        keyManager.registerHotkeyListener(shiftListener);
        clientThread.invoke(this::loadPoints);
    }

    @Override
    protected void shutDown() {
        keyManager.unregisterHotkeyListener(shiftListener);
        keyManager.unregisterHotkeyListener(markListener);
        overlayManager.remove(overlay);
        points = List.of();
        clientThread.invoke(this::removeMenuRows);
    }

    /**
     * The marks of the loaded area.
     *
     * @return the marks; empty while logged out
     */
    List<ColorTileMarker> getPoints() {
        return points;
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event) {
        if (event.getGameState() == GameState.LOGGED_IN) {
            loadPoints();
        }
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event) {
        if (GroundMarkerConfig.GROUND_MARKER_CONFIG_GROUP.equals(event.getGroup())
                && event.getKey().startsWith(REGION_PREFIX)) {
            clientThread.invoke(this::loadPoints);
        }
    }

    @Subscribe
    public void onConfigButtonClicked(ConfigButtonClicked event) {
        if (!GroundMarkerConfig.GROUND_MARKER_CONFIG_GROUP.equals(event.getGroup())) {
            return;
        }
        switch (event.getKey()) {
            case "importMarkers" -> importFromClipboard();
            case "exportMarkers" -> clientThread.invoke(this::exportToClipboard);
            case "clearMarkers" -> clientThread.invoke(this::clearLoaded);
            default -> {
            }
        }
    }

    @Subscribe
    public void onMenuEntryAdded(MenuEntryAdded event) {
        offerMenuRows(event.getMenuEntry());
    }

    @Subscribe
    public void onMenuEntryRemoved(MenuEntryRemoved event) {
        MenuEntry entry = event.getMenuEntry();
        if (entry.isCreated()) {
            return;
        }
        List<Integer> ids = menuRows.remove(MenuMatch.of(entry));
        if (ids != null) {
            client.getMenuEdits().removeAll(ids);
        }
    }

    /**
     * The saved marks of one region.
     *
     * @param regionId the region
     * @return its marks; empty if it has none or they can't be read
     */
    Collection<GroundMarkerPoint> getPoints(int regionId) {
        String json = configManager.getConfiguration(GroundMarkerConfig.GROUND_MARKER_CONFIG_GROUP,
                REGION_PREFIX + regionId);
        if (json == null || json.isEmpty()) {
            return List.of();
        }
        try {
            List<GroundMarkerPoint> parsed = GSON.fromJson(json, POINT_LIST);
            return parsed == null ? List.of() : parsed;
        } catch (JsonParseException e) {
            log.warning("Ground Markers: can't read the marks of region " + regionId + ": " + e.getMessage());
            return List.of();
        }
    }

    /**
     * Saves one region's marks, replacing what it had.
     *
     * @param regionId the region
     * @param regionPoints its marks; null or empty removes the region's entry
     */
    void savePoints(int regionId, Collection<GroundMarkerPoint> regionPoints) {
        if (regionPoints == null || regionPoints.isEmpty()) {
            configManager.unsetConfiguration(GroundMarkerConfig.GROUND_MARKER_CONFIG_GROUP, REGION_PREFIX + regionId);
            return;
        }
        configManager.setConfiguration(GroundMarkerConfig.GROUND_MARKER_CONFIG_GROUP, REGION_PREFIX + regionId,
                GSON.toJson(regionPoints));
    }

    /**
     * Rebuilds the marks of the loaded area from the saved ones, then the menu rows shown now. Client thread only.
     */
    private void loadPoints() {
        if (client.getGameState() != GameState.LOGGED_IN) {
            points = List.of();
        } else {
            List<ColorTileMarker> loaded = new ArrayList<>();
            for (int regionId : mapRegions(client.getTopLevelWorldView())) {
                for (GroundMarkerPoint point : getPoints(regionId)) {
                    loaded.add(new ColorTileMarker(WorldPoint.fromRegion(point.getRegionId(), point.getRegionX(),
                            point.getRegionY(), point.getZ()), point.getColor(), point.getLabel()));
                }
            }
            points = List.copyOf(loaded);
        }
        removeMenuRows();
        offerMenuRows();
    }

    /**
     * Marks the tile under the mouse, or unmarks it if it is marked. Client thread only.
     */
    private void markHoveredTile() {
        Tile tile = client.getTopLevelWorldView().getSelectedSceneTile();
        if (tile != null) {
            markTile(tile.getWorldLocation());
        }
    }

    /**
     * Marks a tile in the tile colour, or unmarks it if it is marked. Client thread only.
     *
     * @param worldPoint the tile
     */
    private void markTile(WorldPoint worldPoint) {
        int regionId = worldPoint.getRegionID();
        GroundMarkerPoint point = new GroundMarkerPoint(regionId, worldPoint.getRegionX(), worldPoint.getRegionY(),
                worldPoint.getPlane(), config.markerColor(), null);
        savePoints(regionId, toggle(getPoints(regionId), point));
        loadPoints();
    }

    /**
     * Asks for a mark's label and writes it; an empty label takes it off.
     *
     * @param mark the mark
     */
    private void labelTile(GroundMarkerPoint mark) {
        prompts.textInput("Tile label", mark.getLabel(), input -> clientThread.invoke(() -> {
            String label = input.isEmpty() ? null : input;
            updateMark(new GroundMarkerPoint(mark.getRegionId(), mark.getRegionX(), mark.getRegionY(), mark.getZ(),
                    currentColor(mark), label));
        }));
    }

    /**
     * Asks for a mark's colour and writes it.
     *
     * @param mark the mark
     */
    private void pickTileColor(GroundMarkerPoint mark) {
        Color initial = mark.getColor() != null ? mark.getColor() : config.markerColor();
        prompts.pickColor("Tile marker color", initial, color -> clientThread.invoke(() -> colorTile(mark, color)));
    }

    /**
     * Gives a mark a colour, keeping its label as it is now. Client thread only.
     *
     * @param mark  the mark
     * @param color the colour
     */
    private void colorTile(GroundMarkerPoint mark, Color color) {
        updateMark(new GroundMarkerPoint(mark.getRegionId(), mark.getRegionX(), mark.getRegionY(), mark.getZ(),
                color, currentLabel(mark)));
    }

    /**
     * Asks whether to give every mark of a region the tile colour, and does.
     *
     * @param regionId the region
     * @param count    how many marks it has, for the question
     */
    private void resetTileColors(int regionId, int count) {
        prompts.confirm("Are you sure you want to reset the color of " + count + " tiles?",
                () -> clientThread.invoke(() -> {
                    savePoints(regionId, recolorAll(getPoints(regionId), config.markerColor()));
                    loadPoints();
                }));
    }

    /**
     * Saves a mark over the one on its tile, if the tile is still marked. Client thread only.
     *
     * @param mark the new mark
     */
    private void updateMark(GroundMarkerPoint mark) {
        Collection<GroundMarkerPoint> regionPoints = getPoints(mark.getRegionId());
        if (regionPoints.contains(mark)) {
            savePoints(mark.getRegionId(), replace(regionPoints, mark));
            loadPoints();
        }
    }

    /**
     * The colour a mark's tile is saved with now, which a dialog left open may have changed.
     *
     * @param mark the mark as it was when its rows were made
     * @return the saved colour, or the mark's if the tile isn't marked any more
     */
    private Color currentColor(GroundMarkerPoint mark) {
        GroundMarkerPoint saved = find(getPoints(mark.getRegionId()), mark);
        return saved != null ? saved.getColor() : mark.getColor();
    }

    /**
     * The label a mark's tile is saved with now, which a dialog left open may have changed.
     *
     * @param mark the mark as it was when its rows were made
     * @return the saved label, or the mark's if the tile isn't marked any more
     */
    private String currentLabel(GroundMarkerPoint mark) {
        GroundMarkerPoint saved = find(getPoints(mark.getRegionId()), mark);
        return saved != null ? saved.getLabel() : mark.getLabel();
    }

    /**
     * Makes the menu rows for every Walk here row the menu shows now. Client thread only.
     */
    private void offerMenuRows() {
        for (MenuEntry entry : client.getMenuEntries()) {
            offerMenuRows(entry);
        }
    }

    /**
     * Makes the menu rows for a Walk here row while Shift is held, once per row and tile. They go right below it, so
     * Walk here stays the left click.
     *
     * @param entry a row of the menu
     */
    private void offerMenuRows(MenuEntry entry) {
        WorldPoint tile = entry.getWorldLocation();
        if (entry.isCreated() || entry.getType() != MenuAction.WALK || tile == null
                || !keyManager.isKeyPressed(KeyEvent.VK_SHIFT)) {
            return;
        }
        MenuMatch anchor = MenuMatch.of(entry);
        if (menuRows.containsKey(anchor)) {
            return;
        }
        int regionId = tile.getRegionID();
        Collection<GroundMarkerPoint> regionPoints = getPoints(regionId);
        GroundMarkerPoint mark = find(regionPoints, new GroundMarkerPoint(regionId, tile.getRegionX(),
                tile.getRegionY(), tile.getPlane(), null, null));
        List<Color> colors = points.stream().map(ColorTileMarker::getColor).filter(Objects::nonNull).distinct()
                .toList();
        GroundMarkerMenu.Actions actions = new GroundMarkerMenu.Actions(
                () -> markTile(tile),
                () -> labelTile(mark),
                color -> colorTile(mark, color),
                () -> pickTileColor(mark),
                () -> resetTileColors(regionId, regionPoints.size()));
        // Each row goes right below Walk here, so the bottom one is added first
        List<MenuRow> rows = GroundMarkerMenu.rows(mark, regionPoints.size(), colors, actions).reversed();
        MenuEdits edits = client.getMenuEdits();
        List<Integer> ids = new ArrayList<>();
        for (MenuRow row : rows) {
            ids.add(edits.add(new MenuRule.Add(anchor, ShiftState.HELD, true, MenuRule.Add.Position.BELOW, row)));
        }
        menuRows.put(anchor, ids);
    }

    /**
     * Takes every menu row out. Client thread only.
     */
    private void removeMenuRows() {
        MenuEdits edits = client.getMenuEdits();
        for (List<Integer> ids : menuRows.values()) {
            edits.removeAll(ids);
        }
        menuRows.clear();
    }

    /**
     * Copies the marks of the loaded area to the clipboard as JSON. Client thread only.
     */
    private void exportToClipboard() {
        List<GroundMarkerPoint> active = new ArrayList<>();
        for (int regionId : mapRegions(client.getTopLevelWorldView())) {
            active.addAll(getPoints(regionId));
        }
        if (active.isEmpty()) {
            log.info("Ground Markers: you have no ground markers to export");
            return;
        }
        String json = GSON.toJson(active);
        SwingUtilities.invokeLater(() -> Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(json), null));
        log.info("Ground Markers: " + active.size() + " ground markers were copied to your clipboard");
    }

    /**
     * Adds the marks on the clipboard to the saved ones, region by region, leaving out tiles already marked. Runs on
     * the Swing thread, where the clipboard is read.
     */
    private void importFromClipboard() {
        String text;
        try {
            text = (String) Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
        } catch (IOException | UnsupportedFlavorException | IllegalStateException e) {
            log.warning("Ground Markers: unable to read the clipboard: " + e.getMessage());
            return;
        }
        List<GroundMarkerPoint> imported = parse(text);
        if (imported.isEmpty()) {
            log.info("Ground Markers: you do not have any ground markers copied in your clipboard");
            return;
        }
        Map<Integer, List<GroundMarkerPoint>> byRegion = new LinkedHashMap<>();
        for (GroundMarkerPoint point : imported) {
            byRegion.computeIfAbsent(point.getRegionId(), id -> new ArrayList<>()).add(point);
        }
        byRegion.forEach((regionId, regionPoints) -> savePoints(regionId, merge(getPoints(regionId), regionPoints)));
        clientThread.invoke(this::loadPoints);
        log.info("Ground Markers: " + imported.size() + " ground markers were imported from the clipboard");
    }

    /**
     * Unmarks every tile of the loaded area. Client thread only.
     */
    private void clearLoaded() {
        int[] regions = mapRegions(client.getTopLevelWorldView());
        long cleared = 0;
        for (int regionId : regions) {
            cleared += getPoints(regionId).size();
        }
        if (cleared == 0) {
            log.info("Ground Markers: you have no ground markers to clear");
            return;
        }
        for (int regionId : regions) {
            savePoints(regionId, null);
        }
        loadPoints();
        log.info("Ground Markers: " + cleared + " ground marker" + (cleared == 1 ? " was" : "s were") + " cleared");
    }

    /**
     * The marks in exported JSON.
     *
     * @param text the JSON, or anything else
     * @return the marks; empty if the text isn't a list of marks
     */
    static List<GroundMarkerPoint> parse(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        try {
            List<GroundMarkerPoint> parsed = GSON.fromJson(text, POINT_LIST);
            return parsed == null ? List.of() : parsed.stream().filter(point -> point != null).toList();
        } catch (JsonParseException e) {
            return List.of();
        }
    }

    /**
     * A region's marks with one tile marked if it wasn't, or unmarked if it was.
     *
     * @param existing the region's marks
     * @param point    the tile, with the colour a new mark gets
     * @return the new marks
     */
    static List<GroundMarkerPoint> toggle(Collection<GroundMarkerPoint> existing, GroundMarkerPoint point) {
        List<GroundMarkerPoint> out = new ArrayList<>(existing);
        if (!out.remove(point)) {
            out.add(point);
        }
        return out;
    }

    /**
     * The mark on a tile.
     *
     * @param existing the region's marks
     * @param tile     a mark on the tile; its colour and label don't matter
     * @return the tile's mark, or null if it isn't marked
     */
    static GroundMarkerPoint find(Collection<GroundMarkerPoint> existing, GroundMarkerPoint tile) {
        for (GroundMarkerPoint point : existing) {
            if (point.equals(tile)) {
                return point;
            }
        }
        return null;
    }

    /**
     * A region's marks with the mark on one tile replaced, in its place.
     *
     * @param existing the region's marks
     * @param point    the new mark for its tile
     * @return the new marks; the same marks if the tile isn't marked
     */
    static List<GroundMarkerPoint> replace(Collection<GroundMarkerPoint> existing, GroundMarkerPoint point) {
        return existing.stream().map(p -> p.equals(point) ? point : p).toList();
    }

    /**
     * A region's marks all in one colour, keeping their labels.
     *
     * @param existing the region's marks
     * @param color    the colour
     * @return the new marks
     */
    static List<GroundMarkerPoint> recolorAll(Collection<GroundMarkerPoint> existing, Color color) {
        return existing.stream().map(p -> new GroundMarkerPoint(p.getRegionId(), p.getRegionX(), p.getRegionY(),
                p.getZ(), color, p.getLabel())).toList();
    }

    /**
     * A region's marks with imported ones added, leaving out imported tiles that are already marked.
     *
     * @param existing the region's marks
     * @param imported the imported marks
     * @return the merged marks, existing first
     */
    static List<GroundMarkerPoint> merge(Collection<GroundMarkerPoint> existing,
                                         Collection<GroundMarkerPoint> imported) {
        List<GroundMarkerPoint> out = new ArrayList<>(existing);
        for (GroundMarkerPoint point : imported) {
            if (!out.contains(point)) {
                out.add(point);
            }
        }
        return out;
    }

    /**
     * The regions the loaded area covers.
     *
     * @param view the world view
     * @return the region ids; empty if the scene can't be read
     */
    static int[] mapRegions(WorldView view) {
        return regions(view.getBaseX(), view.getBaseY(), view.getSizeX(), view.getSizeY());
    }

    /**
     * The regions an area of tiles covers.
     *
     * @param baseX the area's south-west world x
     * @param baseY the area's south-west world y
     * @param sizeX the area's width in tiles
     * @param sizeY the area's height in tiles
     * @return the region ids, west to east and south to north; empty for an area that can't be read
     */
    static int[] regions(int baseX, int baseY, int sizeX, int sizeY) {
        if (baseX < 0 || baseY < 0 || sizeX <= 0 || sizeY <= 0) {
            return new int[0];
        }
        Set<Integer> out = new LinkedHashSet<>();
        for (int rx = baseX >> 6; rx <= (baseX + sizeX - 1) >> 6; rx++) {
            for (int ry = baseY >> 6; ry <= (baseY + sizeY - 1) >> 6; ry++) {
                out.add(rx << 8 | ry);
            }
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }
}
