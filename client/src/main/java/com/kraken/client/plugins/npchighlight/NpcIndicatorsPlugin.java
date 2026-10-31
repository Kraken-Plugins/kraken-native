package com.kraken.client.plugins.npchighlight;

import com.google.inject.Inject;
import com.google.inject.Provides;
import com.kraken.api.Client;
import com.kraken.api.GameState;
import com.kraken.api.MenuAction;
import com.kraken.api.MenuEntry;
import com.kraken.api.actor.NPC;
import com.kraken.api.actor.Player;
import com.kraken.api.coords.WorldPoint;
import com.kraken.api.definition.NPCComposition;
import com.kraken.api.eventbus.Subscribe;
import com.kraken.api.events.ClientTick;
import com.kraken.api.events.GameStateChanged;
import com.kraken.api.events.GameTick;
import com.kraken.api.events.GraphicsObjectCreated;
import com.kraken.api.events.MenuEntryAdded;
import com.kraken.api.events.MenuEntryRemoved;
import com.kraken.api.events.NpcChanged;
import com.kraken.api.events.NpcDespawned;
import com.kraken.api.events.NpcSpawned;
import com.kraken.api.gameval.SpotanimID;
import com.kraken.api.gameval.VarbitID;
import com.kraken.api.menu.MenuEdits;
import com.kraken.api.menu.MenuMatch;
import com.kraken.api.menu.MenuRow;
import com.kraken.api.menu.MenuRule;
import com.kraken.api.menu.ShiftState;
import com.kraken.api.scene.GraphicsObject;
import com.kraken.client.callback.ClientThread;
import com.kraken.client.events.ConfigChanged;
import com.kraken.client.game.NpcUtil;
import com.kraken.client.input.HotkeyListener;
import com.kraken.client.input.KeyManager;
import com.kraken.client.plugins.Plugin;
import com.kraken.client.plugins.PluginDescriptor;
import com.kraken.client.plugins.config.ConfigManager;
import com.kraken.client.plugins.config.Keybind;
import com.kraken.client.ui.Prompts;
import com.kraken.client.ui.overlay.OverlayManager;
import com.kraken.client.util.ColorUtil;
import com.kraken.client.util.Text;
import com.kraken.client.util.WildcardMatcher;
import lombok.AccessLevel;
import lombok.Getter;

import java.awt.Color;
import java.awt.event.KeyEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Highlights NPCs by name, with wildcards, or by index (tagged NPCs), and shows when and where highlighted NPCs that
 * died will respawn. The game draws the hull and outline styles itself, on NPC highlight channels the plugin holds
 * while it runs ({@link NpcHighlightChannels}); its overlays draw the tile styles, the names and the respawn timers.
 *
 * <p>Shift + right-click an NPC to Tag or Un-tag it, Tag-All or Un-tag-All its name, and set its NPC id's own colour
 * and style ({@link NpcTagMenu}). The rows are rules the native hook applies as the client rebuilds the menu
 * ({@link MenuEdits}): made for an Examine row when it appears while Shift is held, or when Shift goes down over it,
 * shown only while Shift is held, and made again whenever the highlights change so they say what the NPC is now. A
 * picked colour is asked for in a dialog ({@link Prompts}).
 *
 * <p>Threading: the maps and lists below are plain because the game events, {@link ClientThread} tasks and the
 * overlays' {@code render} all run on the client thread. {@code startUp}, {@code shutDown} and
 * {@code onConfigChanged} run on the EDT and hand their work to the client thread.
 *
 * <p>Respawn times are learnt by watching: when a highlighted NPC in view despawns its index is remembered as dead,
 * and when an NPC spawns in its index in view, the ticks since are its respawn time and its tile where it respawns.
 * NPCs that vanish in a puff of smoke (teleporting away or in) don't count.
 */
@PluginDescriptor(
        name = "NPC Indicators",
        description = "Highlight NPCs on-screen and/or on the minimap",
        tags = {"highlight", "minimap", "npcs", "overlay", "respawn", "tags"},
        enabledByDefault = false
)
public class NpcIndicatorsPlugin extends Plugin {

    /**
     * How far, in tiles, the client shows actors: a spawn or despawn further away is an NPC walking in or out of view.
     */
    private static final int MAX_ACTOR_VIEW_RANGE = 15;

    /**
     * The longest respawn time believed, in ticks; a longer one comes from leaving and coming back.
     */
    static final int MAX_RESPAWN_TIME_TICKS = 500;

    private static final String HIGHLIGHT_COLOR_PREFIX = "highlightcolor_";
    private static final String TAG_STYLE_PREFIX = "tagstyle_";

    @Inject
    private Client client;

    @Inject
    private NpcIndicatorsConfig config;

    @Inject
    private ConfigManager configManager;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private NpcIndicatorsOverlay overlay;

    @Inject
    private NpcRespawnOverlay respawnOverlay;

    @Inject
    private ClientThread clientThread;

    @Inject
    private KeyManager keyManager;

    @Inject
    private Prompts prompts;

    /**
     * The NPCs highlighted now, with how. Client thread only.
     */
    @Getter(AccessLevel.PACKAGE)
    private final Map<NPC, HighlightedNpc> highlightedNpcs = new HashMap<>();

    /**
     * Dead NPCs whose respawn is shown, by index. Client thread only.
     */
    @Getter(AccessLevel.PACKAGE)
    private final Map<Integer, MemorizedNpc> deadNpcsToDisplay = new HashMap<>();

    /**
     * When the last game tick was seen, for the respawn countdown between ticks; null before the first.
     */
    @Getter(AccessLevel.PACKAGE)
    private Instant lastTickUpdate;

    /**
     * Highlighted NPCs whose respawns are being learnt, by index. Client thread only.
     */
    private final Map<Integer, MemorizedNpc> memorizedNpcs = new HashMap<>();

    /**
     * The name patterns from the settings. Client thread only.
     */
    private List<String> highlights = List.of();

    /**
     * The indices of NPCs tagged one by one. Client thread only.
     */
    private final Set<Integer> npcTags = new HashSet<>();

    /**
     * Highlighted NPCs that spawned this tick: some walked into view rather than respawned.
     */
    private final List<NPC> spawnedNpcsThisTick = new ArrayList<>();

    /**
     * Remembered NPCs that despawned this tick: some walked out of view rather than died.
     */
    private final List<DespawnedNpc> despawnedNpcsThisTick = new ArrayList<>();

    /**
     * Where puffs of smoke appeared this tick: an NPC there teleported rather than died or respawned.
     */
    private final Set<WorldPoint> teleportGraphicsObjectSpawnedThisTick = new HashSet<>();

    /**
     * The local player's tile at the last tick.
     */
    private WorldPoint lastPlayerLocation;

    /**
     * Whether the next tick's spawns and despawns are skipped: after hopping or logging in, NPCs spawn without
     * respawning.
     */
    private boolean skipNextSpawnCheck;

    /**
     * The game's model highlights, held from {@code startUp} to {@code shutDown}.
     */
    private volatile NpcHighlightChannels channels;

    /**
     * The ids of the rules adding the menu rows next to each Examine row, by the row's identity. Client thread only.
     */
    private final Map<MenuMatch, List<Integer>> menuRows = new LinkedHashMap<>();

    /**
     * Makes the menu rows for the NPCs under the mouse when Shift goes down after the mouse got there.
     */
    private final HotkeyListener shiftListener = new HotkeyListener(() -> Keybind.SHIFT) {
        @Override
        public void hotkeyPressed() {
            offerMenuRows();
        }
    };

    /**
     * Where and which remembered NPC despawned.
     *
     * @param coord its tile
     * @param index its index
     */
    private record DespawnedNpc(WorldPoint coord, int index) {
    }

    @Provides
    NpcIndicatorsConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(NpcIndicatorsConfig.class);
    }

    @Override
    protected void startUp() {
        channels = new NpcHighlightChannels(client.getHighlights());
        overlayManager.add(overlay);
        overlayManager.add(respawnOverlay);
        keyManager.registerHotkeyListener(shiftListener);
        clientThread.invoke(() -> {
            skipNextSpawnCheck = true;
            rebuild();
        });
    }

    @Override
    protected void shutDown() {
        keyManager.unregisterHotkeyListener(shiftListener);
        overlayManager.remove(overlay);
        overlayManager.remove(respawnOverlay);
        NpcHighlightChannels held = channels;
        channels = null;
        if (held != null) {
            held.release();
        }
        clientThread.invoke(() -> {
            deadNpcsToDisplay.clear();
            memorizedNpcs.clear();
            spawnedNpcsThisTick.clear();
            despawnedNpcsThisTick.clear();
            teleportGraphicsObjectSpawnedThisTick.clear();
            npcTags.clear();
            highlightedNpcs.clear();
            removeMenuRows();
        });
    }

    /**
     * Whether the game draws the hull and outline styles now, so the overlay leaves them alone.
     *
     * @return true while the native hook is connected
     */
    boolean isDrawnInGame() {
        return client.getHighlights().isAvailable();
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event) {
        if (event.getGameState() == GameState.LOGIN_SCREEN || event.getGameState() == GameState.HOPPING) {
            highlightedNpcs.clear();
            deadNpcsToDisplay.clear();
            memorizedNpcs.values().forEach(npc -> npc.setDiedOnTick(-1));
            lastPlayerLocation = null;
            skipNextSpawnCheck = true;
            syncChannels();
        }
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event) {
        if (event.getGroup().equals(NpcIndicatorsConfig.GROUP)) {
            clientThread.invoke(this::rebuild);
        }
    }

    @Subscribe
    public void onNpcSpawned(NpcSpawned event) {
        NPC npc = event.getNpc();
        String name = npc.getName();
        if (name == null || name.isEmpty()) {
            return;
        }
        if (npcTags.contains(npc.getIndex())) {
            memorizeNpc(npc);
            highlightedNpcs.put(npc, highlightedNpc(npc));
            spawnedNpcsThisTick.add(npc);
        } else if (highlightMatchesNpcName(name)) {
            highlightedNpcs.put(npc, highlightedNpc(npc));
            memorizeNpc(npc);
            spawnedNpcsThisTick.add(npc);
        }
    }

    @Subscribe
    public void onNpcDespawned(NpcDespawned event) {
        NPC npc = event.getNpc();
        if (memorizedNpcs.containsKey(npc.getIndex())) {
            despawnedNpcsThisTick.add(new DespawnedNpc(npc.getWorldLocation(), npc.getIndex()));
        }
        highlightedNpcs.remove(npc);
    }

    @Subscribe
    public void onNpcChanged(NpcChanged event) {
        NPC npc = event.getNpc();
        String name = npc.getName();
        highlightedNpcs.remove(npc);
        if (name != null && !name.isEmpty() && (npcTags.contains(npc.getIndex()) || highlightMatchesNpcName(name))) {
            highlightedNpcs.put(npc, highlightedNpc(npc));
        }
    }

    @Subscribe
    public void onGraphicsObjectCreated(GraphicsObjectCreated event) {
        GraphicsObject object = event.getGraphicsObject();
        if (object.getId() == SpotanimID.SMOKEPUFF) {
            WorldPoint at = WorldPoint.fromLocal(client, object.getLocation());
            if (at != null) {
                teleportGraphicsObjectSpawnedThisTick.add(at);
            }
        }
    }

    @Subscribe
    public void onGameTick(GameTick event) {
        deadNpcsToDisplay.values().removeIf(npc -> npc.respawnedBy(client.getTickCount()));
        validateSpawnedNpcs();
        lastTickUpdate = Instant.now();
        Player local = client.getLocalPlayer();
        if (local != null) {
            lastPlayerLocation = local.getWorldLocation();
        }
    }

    @Subscribe
    public void onClientTick(ClientTick event) {
        syncChannels();
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
     * Tags or untags one NPC by its index, as the Tag and Un-tag menu entries do. Untagging an NPC whose name is in
     * the list leaves it highlighted. Client thread only.
     *
     * @param npc the NPC
     */
    void tag(NPC npc) {
        int index = npc.getIndex();
        if (highlightedNpcs.containsKey(npc)) {
            npcTags.remove(index);
            if (!highlightMatchesNpcName(npc.getName())) {
                highlightedNpcs.remove(npc);
                memorizedNpcs.remove(index);
            }
        } else {
            memorizeNpc(npc);
            npcTags.add(index);
            highlightedNpcs.put(npc, highlightedNpc(npc));
        }
        syncChannels();
        refreshMenuRows();
    }

    /**
     * Adds an NPC's name to the highlight list, or takes it out if it is there, as the Tag-All and Un-tag-All menu
     * entries do. The settings change rebuilds the highlights. Client thread only.
     *
     * @param npc the NPC
     */
    void tagAll(NPC npc) {
        config.setNpcToHighlight(Text.toCSV(toggleName(highlights, npc.getName())));
    }

    /**
     * A highlight list with a name taken out if it is in it, ignoring case, or added at the end if it isn't.
     *
     * @param highlights the list
     * @param name       the name
     * @return the new list
     */
    static List<String> toggleName(List<String> highlights, String name) {
        List<String> toggled = new ArrayList<>(highlights);
        if (!toggled.removeIf(name::equalsIgnoreCase)) {
            toggled.add(name);
        }
        return toggled;
    }

    /**
     * Colours an NPC menu entry's target: the whole target, the name or the combat level, after taking out its own
     * colours from the part coloured.
     *
     * @param target the target, such as {@code <col=ffff00>Ram<col=ff00>  (level-2)}
     * @param style  which part to colour
     * @param color  the colour
     * @return the target, coloured
     */
    static String recolorMenuTarget(String target, NpcIndicatorsConfig.MenuHighlightStyle style, Color color) {
        if (style == NpcIndicatorsConfig.MenuHighlightStyle.NONE) {
            return target;
        }
        if (style == NpcIndicatorsConfig.MenuHighlightStyle.BOTH) {
            return ColorUtil.prependColorTag(Text.removeTags(target), color);
        }
        String name = target;
        String level = null;
        if (target.contains("  (level-")) {
            int cut = target.lastIndexOf('<');
            name = target.substring(0, cut);
            level = target.substring(cut);
        }
        if (style == NpcIndicatorsConfig.MenuHighlightStyle.NAME) {
            String coloured = ColorUtil.prependColorTag(Text.removeTags(name), color);
            return level == null ? coloured : coloured + level;
        }
        return level == null ? name : name + ColorUtil.prependColorTag(Text.removeTags(level), color);
    }

    /**
     * Whether a name matches one of the patterns in the settings.
     *
     * @param patterns the patterns, each of which may use {@code *}
     * @param name     the NPC's name
     * @return true if any pattern matches, ignoring case
     */
    static boolean matchesAny(List<String> patterns, String name) {
        for (String pattern : patterns) {
            if (WildcardMatcher.matches(pattern, name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reads the settings and highlights every loaded NPC they call for. NPCs the client keeps after logging out
     * aren't highlighted. Client thread only.
     */
    void rebuild() {
        highlights = Text.fromCSV(config.getNpcToHighlight());
        highlightedNpcs.clear();
        GameState state = client.getGameState();
        if (state == GameState.LOGGED_IN || state == GameState.LOADING) {
            for (NPC npc : client.getNpcs()) {
                String name = npc.getName();
                if (name == null || name.isEmpty()) {
                    continue;
                }
                if (npcTags.contains(npc.getIndex())) {
                    highlightedNpcs.put(npc, highlightedNpc(npc));
                } else if (highlightMatchesNpcName(name)) {
                    memorizeNpc(npc);
                    highlightedNpcs.put(npc, highlightedNpc(npc));
                } else {
                    memorizedNpcs.remove(npc.getIndex());
                }
            }
        }
        syncChannels();
        refreshMenuRows();
    }

    /**
     * Makes the menu rows for every Examine row the menu shows now. Client thread only.
     */
    private void offerMenuRows() {
        for (MenuEntry entry : client.getMenuEntries()) {
            offerMenuRows(entry);
        }
    }

    /**
     * Makes the menu rows for an NPC's Examine row while Shift is held, once per row.
     *
     * @param entry a row of the menu
     */
    private void offerMenuRows(MenuEntry entry) {
        if (entry.isCreated() || entry.getType() != MenuAction.EXAMINE_NPC
                || !keyManager.isKeyPressed(KeyEvent.VK_SHIFT)) {
            return;
        }
        MenuMatch anchor = MenuMatch.of(entry);
        if (menuRows.containsKey(anchor)) {
            return;
        }
        NPC npc = entry.getNpc();
        String name = npc == null ? null : npc.getName();
        if (name == null || name.isEmpty()) {
            return;
        }
        int npcId = npc.getId();
        NpcTagMenu.State state = NpcTagMenu.state(highlights, name, npcTags.contains(npc.getIndex()),
                getNpcHighlightColor(npcId) != null, getNpcTagStyle(npcId) != null);
        NpcTagMenu.Actions actions = new NpcTagMenu.Actions(
                () -> withNpc(entry, this::tag),
                () -> withNpc(entry, this::tagAll),
                color -> setNpcHighlightColor(npcId, color),
                () -> pickTagColor(npcId),
                () -> unsetNpcHighlightColor(npcId),
                style -> setNpcTagStyle(npcId, style),
                () -> unsetNpcTagStyle(npcId));
        List<Color> colors = state.highlighted() ? NpcTagMenu.colorChoices(usedColors()) : List.of();
        MenuEdits edits = client.getMenuEdits();
        List<Integer> ids = new ArrayList<>();
        for (MenuRow row : NpcTagMenu.rows(entry.getTarget(), state, colors, actions)) {
            ids.add(edits.add(new MenuRule.Add(anchor, ShiftState.HELD, true, MenuRule.Add.Position.ABOVE, row)));
        }
        menuRows.put(anchor, ids);
    }

    /**
     * Makes the menu rows again, so they say what each NPC is now. Client thread only.
     */
    private void refreshMenuRows() {
        removeMenuRows();
        offerMenuRows();
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
     * Acts on the NPC a menu row is about, as the game holds it now.
     *
     * @param entry  the NPC's row
     * @param action what to do; not called if the NPC is gone or has no name
     */
    private static void withNpc(MenuEntry entry, Consumer<NPC> action) {
        NPC npc = entry.getNpc();
        if (npc != null && npc.getName() != null && !npc.getName().isEmpty()) {
            action.accept(npc);
        }
    }

    /**
     * Asks for an NPC id's colour, starting from the colour it has now, and saves it.
     *
     * @param npcId the NPC id
     */
    private void pickTagColor(int npcId) {
        Color current = getNpcHighlightColor(npcId);
        prompts.pickColor("Tag color", current != null ? current : config.highlightColor(),
                color -> setNpcHighlightColor(npcId, color));
    }

    /**
     * The colours loaded NPCs have of their own, for the Tag color sub-menu.
     *
     * @return the colours, each once, at most {@link NpcTagMenu#MAX_COLORS}
     */
    private List<Color> usedColors() {
        List<Color> used = new ArrayList<>();
        for (NPC npc : client.getNpcs()) {
            Color color = getNpcHighlightColor(npc.getId());
            if (color != null && !used.contains(color)) {
                used.add(color);
                if (used.size() >= NpcTagMenu.MAX_COLORS) {
                    break;
                }
            }
        }
        return used;
    }

    /**
     * Lights the hull and outline styles' NPCs in the game: those shown now, none during a cutscene. Client thread
     * only.
     */
    private void syncChannels() {
        NpcHighlightChannels held = channels;
        if (held == null) {
            return;
        }
        List<HighlightedNpc> lit = new ArrayList<>();
        if (!highlightedNpcs.isEmpty() && client.getVarbitValue(VarbitID.CUTSCENE_STATUS) != 1) {
            for (HighlightedNpc highlighted : highlightedNpcs.values()) {
                if ((highlighted.isHull() || highlighted.isOutline()) && isDrawable(highlighted)) {
                    lit.add(highlighted);
                }
            }
        }
        held.sync(NpcHighlightChannels.group(lit));
    }

    /**
     * Whether an NPC can be drawn now: it shows a definition that can be interacted with, and its highlight's
     * predicate accepts it.
     *
     * @param highlighted the NPC's highlight
     * @return true to draw it
     */
    private static boolean isDrawable(HighlightedNpc highlighted) {
        NPCComposition composition = highlighted.getNpc().getTransformedComposition();
        return composition != null && composition.isInteractible() && highlighted.isShown();
    }

    /**
     * Learns respawns from this tick's spawns and despawns, then forgets them: a remembered NPC that despawned in view
     * died (and its respawn is shown once its tile is known), and one that spawned in view respawned.
     */
    private void validateSpawnedNpcs() {
        if (skipNextSpawnCheck) {
            skipNextSpawnCheck = false;
        } else {
            int tick = client.getTickCount();
            Player local = client.getLocalPlayer();
            WorldPoint here = local == null ? null : local.getWorldLocation();
            for (DespawnedNpc npc : despawnedNpcsThisTick) {
                if (npc.coord() == null || teleportGraphicsObjectSpawnedThisTick.contains(npc.coord())) {
                    continue;
                }
                MemorizedNpc memorized = memorizedNpcs.get(npc.index());
                if (here != null && isInViewRange(here, npc.coord()) && memorized != null) {
                    memorized.setDiedOnTick(tick);
                    if (!memorized.getPossibleRespawnLocations().isEmpty()) {
                        deadNpcsToDisplay.put(memorized.getNpcIndex(), memorized);
                    }
                }
            }
            for (NPC npc : spawnedNpcsThisTick) {
                WorldPoint at = npc.getWorldLocation();
                if (at == null || teleportGraphicsObjectSpawnedThisTick.contains(at)) {
                    continue;
                }
                MemorizedNpc memorized = memorizedNpcs.get(npc.getIndex());
                if (lastPlayerLocation != null && isInViewRange(lastPlayerLocation, at) && memorized != null) {
                    memorized.respawned(tick, at, MAX_RESPAWN_TIME_TICKS);
                }
            }
        }
        spawnedNpcsThisTick.clear();
        despawnedNpcsThisTick.clear();
        teleportGraphicsObjectSpawnedThisTick.clear();
    }

    /**
     * Whether two tiles are close enough for the client to show an actor on one to a player on the other.
     *
     * @param from one tile
     * @param to   the other
     * @return true within {@link #MAX_ACTOR_VIEW_RANGE} tiles on the same plane
     */
    static boolean isInViewRange(WorldPoint from, WorldPoint to) {
        return from.distanceTo(to) < MAX_ACTOR_VIEW_RANGE;
    }

    private void memorizeNpc(NPC npc) {
        memorizedNpcs.putIfAbsent(npc.getIndex(), new MemorizedNpc(npc));
    }

    private boolean highlightMatchesNpcName(String name) {
        return name != null && matchesAny(highlights, name);
    }

    /**
     * How an NPC is highlighted: its own colour and tag style if it has them, else the settings'.
     *
     * @param npc the NPC
     * @return the highlight
     */
    private HighlightedNpc highlightedNpc(NPC npc) {
        int id = npc.getId();
        RenderStyle style = RenderStyle.of(getNpcTagStyle(id), config);
        Color colour = getNpcHighlightColor(id);
        return HighlightedNpc.builder()
                .npc(npc)
                .highlightColor(colour != null ? colour : config.highlightColor())
                .fillColor(config.fillColor())
                .hull(style.hull())
                .tile(style.tile())
                .trueTile(style.trueTile())
                .swTile(style.swTile())
                .swTrueTile(style.swTrueTile())
                .outline(style.outline())
                .name(config.drawNames())
                .nameOnMinimap(config.drawMinimapNames())
                .borderWidth((float) config.borderWidth())
                .outlineFeather(config.outlineFeather())
                .render(this::render)
                .build();
    }

    /**
     * Whether a highlighted NPC is drawn now: not while it is dying, or is someone's pet, if the settings say so.
     *
     * @param npc the NPC
     * @return true to draw it
     */
    private boolean render(NPC npc) {
        if (config.ignoreDeadNpcs() && NpcUtil.isDying(npc)) {
            return false;
        }
        NPCComposition composition = npc.getTransformedComposition();
        return composition == null || !composition.isFollower() || !config.ignorePets();
    }

    /**
     * An NPC id's own highlight colour.
     *
     * @param npcId the NPC id
     * @return the colour, or null if it has none
     */
    private Color getNpcHighlightColor(int npcId) {
        return configManager.getConfiguration(NpcIndicatorsConfig.GROUP, HIGHLIGHT_COLOR_PREFIX + npcId, Color.class);
    }

    /**
     * Gives an NPC id a highlight colour of its own. The settings change rebuilds the highlights.
     *
     * @param npcId the NPC id
     * @param color the colour
     */
    private void setNpcHighlightColor(int npcId, Color color) {
        configManager.setConfiguration(NpcIndicatorsConfig.GROUP, HIGHLIGHT_COLOR_PREFIX + npcId, color);
    }

    /**
     * Takes an NPC id's own highlight colour off. The settings change rebuilds the highlights.
     *
     * @param npcId the NPC id
     */
    private void unsetNpcHighlightColor(int npcId) {
        configManager.unsetConfiguration(NpcIndicatorsConfig.GROUP, HIGHLIGHT_COLOR_PREFIX + npcId);
    }

    /**
     * An NPC id's own tag style.
     *
     * @param npcId the NPC id
     * @return the style, such as {@link RenderStyle#HULL}, or null if it has none
     */
    private String getNpcTagStyle(int npcId) {
        return configManager.getConfiguration(NpcIndicatorsConfig.GROUP, TAG_STYLE_PREFIX + npcId);
    }

    /**
     * Gives an NPC id a tag style of its own. The settings change rebuilds the highlights.
     *
     * @param npcId the NPC id
     * @param style the style, one of {@link RenderStyle}'s names
     */
    private void setNpcTagStyle(int npcId, String style) {
        configManager.setConfiguration(NpcIndicatorsConfig.GROUP, TAG_STYLE_PREFIX + npcId, style);
    }

    /**
     * Takes an NPC id's own tag style off. The settings change rebuilds the highlights.
     *
     * @param npcId the NPC id
     */
    private void unsetNpcTagStyle(int npcId) {
        configManager.unsetConfiguration(NpcIndicatorsConfig.GROUP, TAG_STYLE_PREFIX + npcId);
    }
}
