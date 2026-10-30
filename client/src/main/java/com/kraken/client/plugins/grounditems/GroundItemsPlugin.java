package com.kraken.client.plugins.grounditems;

import com.google.inject.Inject;
import com.google.inject.Provides;
import com.kraken.api.Client;
import com.kraken.api.GameState;
import com.kraken.api.coords.WorldPoint;
import com.kraken.api.definition.ItemComposition;
import com.kraken.api.eventbus.Subscribe;
import com.kraken.api.events.FocusChanged;
import com.kraken.api.events.GameStateChanged;
import com.kraken.api.events.ItemDespawned;
import com.kraken.api.events.ItemQuantityChanged;
import com.kraken.api.events.ItemSpawned;
import com.kraken.api.gameval.ItemID;
import com.kraken.api.gameval.VarbitID;
import com.kraken.api.scene.TileItem;
import com.kraken.client.callback.ClientThread;
import com.kraken.client.events.ConfigChanged;
import com.kraken.client.input.KeyManager;
import com.kraken.client.plugins.Plugin;
import com.kraken.client.plugins.PluginDescriptor;
import com.kraken.client.plugins.config.ConfigManager;
import com.kraken.client.ui.overlay.OverlayManager;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.java.Log;

import java.awt.Color;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes the names, quantities and values of the items on the ground over them, coloured by the highlighted and
 * hidden lists and the value tiers, and optionally outlines their tiles. Holding the hotkey shows hidden items too;
 * double-tapping it hides every item until it is pressed again.
 *
 * <p>Values are high alchemy values. The only Grand Exchange prices known, needing no lookup, are those of coins (1)
 * and platinum tokens (1,000); every other item's reads as 0, so the Grand Exchange modes see no value for it.
 *
 * <p>State lives on the client thread: the item spawn events, the hotkey and the overlay all run there.
 * {@code startUp}, {@code shutDown} and {@code onConfigChanged} run on the EDT, so they swap {@link #rules} whole and
 * hand item work to the client thread. Started mid-session, the plugin reads the items already on the ground.
 *
 * <p>Items are kept by tile and item id, every stack of one item on a tile added up, as the despawn events don't say
 * which stack went. The lootbeams each tile calls for are worked out ({@link #getLootbeams()}) but not drawn.
 */
@Log(topic = "kraken")
@PluginDescriptor(
        name = "Ground Items",
        description = "Highlight ground items and/or show price information",
        tags = {"grand", "exchange", "high", "alchemy", "prices", "highlight", "overlay", "lootbeam"},
        enabledByDefault = false
)
public class GroundItemsPlugin extends Plugin {

    /**
     * The prefix of the settings keys holding an item's own colour, followed by its plain item id.
     */
    static final String HIGHLIGHT_COLOR_PREFIX = "highlight_";

    /**
     * What one platinum token is worth in coins.
     */
    private static final long PLATINUM_TOKEN_VALUE = 1000;

    @Inject
    private Client client;

    @Inject
    private ClientThread clientThread;

    @Inject
    private GroundItemsConfig config;

    @Inject
    private ConfigManager configManager;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private GroundItemsOverlay overlay;

    @Inject
    private KeyManager keyManager;

    @Inject
    private GroundItemHotkeyListener hotkeyListener;

    /**
     * Whether the hotkey is held. Client thread only.
     */
    @Getter(AccessLevel.PACKAGE)
    @Setter(AccessLevel.PACKAGE)
    private boolean hotKeyPressed;

    /**
     * Whether a double tap of the hotkey has hidden every item. Client thread only.
     */
    @Getter(AccessLevel.PACKAGE)
    @Setter(AccessLevel.PACKAGE)
    private boolean hideAll;

    /**
     * What the settings make of items, rebuilt when they change.
     */
    @Getter(AccessLevel.PACKAGE)
    private volatile GroundItemRules rules;

    /**
     * The items on the ground, by tile then item id, in the order they were first seen. Client thread only.
     */
    private final Map<WorldPoint, Map<Integer, GroundItem>> collectedGroundItems = new LinkedHashMap<>();

    /**
     * The lootbeam colour each tile calls for, for the tiles that call for one. Client thread only.
     */
    @Getter(AccessLevel.PACKAGE)
    private final Map<WorldPoint, Color> lootbeams = new HashMap<>();

    @Provides
    GroundItemsConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(GroundItemsConfig.class);
    }

    @Override
    protected void startUp() {
        rules = new GroundItemRules(config, this::getItemColor);
        overlayManager.add(overlay);
        keyManager.registerHotkeyListener(hotkeyListener);
        clientThread.invoke(this::rebuild);
    }

    @Override
    protected void shutDown() {
        overlayManager.remove(overlay);
        keyManager.unregisterHotkeyListener(hotkeyListener);
        clientThread.invoke(() -> {
            collectedGroundItems.clear();
            lootbeams.clear();
            hotKeyPressed = false;
            hideAll = false;
        });
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event) {
        if (GroundItemsConfig.GROUP.equals(event.getGroup())) {
            rules = new GroundItemRules(config, this::getItemColor);
            clientThread.invoke(() -> {
                groundItems().forEach(GroundItem::reset);
                handleLootbeams();
            });
        }
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event) {
        GameState state = event.getGameState();
        if (state == GameState.LOADING || state == GameState.LOGIN_SCREEN || state == GameState.HOPPING) {
            collectedGroundItems.clear();
            lootbeams.clear();
        }
    }

    @Subscribe
    public void onFocusChanged(FocusChanged event) {
        if (!event.isFocused()) {
            hotKeyPressed = false;
        }
    }

    @Subscribe
    public void onItemSpawned(ItemSpawned event) {
        WorldPoint location = event.getTile().getWorldLocation();
        GroundItem groundItem = add(location, event.getItem());
        if (groundItem == null) {
            return;
        }
        if (GroundItemRules.shouldDisplayItem(config.ownershipFilterMode(), groundItem.getOwnership(),
                client.getVarbitValue(VarbitID.IRONMAN))) {
            String notification = rules.notification(groundItem);
            if (notification != null) {
                log.info("Ground Items: " + notification);
            }
        }
        handleLootbeam(location);
    }

    @Subscribe
    public void onItemDespawned(ItemDespawned event) {
        TileItem item = event.getItem();
        WorldPoint location = event.getTile().getWorldLocation();
        Map<Integer, GroundItem> tile = collectedGroundItems.get(location);
        GroundItem groundItem = tile == null ? null : tile.get(item.getId());
        if (groundItem == null) {
            return;
        }
        if (groundItem.getQuantity() <= item.getQuantity()) {
            tile.remove(item.getId());
            if (tile.isEmpty()) {
                collectedGroundItems.remove(location);
            }
        } else {
            groundItem.setQuantity(groundItem.getQuantity() - item.getQuantity());
            // Which stack went isn't known, so neither is the oldest one left
            groundItem.setSpawnTime(null);
            groundItem.reset();
        }
        handleLootbeam(location);
    }

    @Subscribe
    public void onItemQuantityChanged(ItemQuantityChanged event) {
        WorldPoint location = event.getTile().getWorldLocation();
        Map<Integer, GroundItem> tile = collectedGroundItems.get(location);
        GroundItem groundItem = tile == null ? null : tile.get(event.getItem().getId());
        if (groundItem != null) {
            groundItem.setQuantity(groundItem.getQuantity() + event.getNewQuantity() - event.getOldQuantity());
            groundItem.reset();
        }
        handleLootbeam(location);
    }

    /**
     * Every item on the ground, as a new list. Client thread only.
     *
     * @return the items, tile by tile in the order the tiles were first seen
     */
    List<GroundItem> groundItems() {
        List<GroundItem> items = new ArrayList<>();
        for (Map<Integer, GroundItem> tile : collectedGroundItems.values()) {
            items.addAll(tile.values());
        }
        return items;
    }

    /**
     * Adds the items on the ground now, replacing what was kept. Client thread only.
     */
    private void rebuild() {
        collectedGroundItems.clear();
        lootbeams.clear();
        if (!client.isLoggedIn()) {
            return;
        }
        for (TileItem item : client.getScene().getGroundItems()) {
            add(item.getTile().getWorldLocation(), item);
        }
        handleLootbeams();
    }

    /**
     * Records a stack: a new item on its tile, or more of one already there.
     *
     * @param location the tile
     * @param item     the stack
     * @return the item it was added to; null if its definition can't be read
     */
    private GroundItem add(WorldPoint location, TileItem item) {
        GroundItem groundItem = buildGroundItem(location, item);
        if (groundItem == null) {
            return null;
        }
        Map<Integer, GroundItem> tile = collectedGroundItems.computeIfAbsent(location, k -> new LinkedHashMap<>());
        GroundItem existing = tile.get(item.getId());
        if (existing != null) {
            existing.setQuantity(existing.getQuantity() + groundItem.getQuantity());
            existing.reset();
            return existing;
        }
        tile.put(item.getId(), groundItem);
        return groundItem;
    }

    /**
     * A record of one stack, from its item's definition. The client's item piles hold no ownership, privacy or
     * despawn times, so the item has no owner and an unknown spawn time.
     *
     * @param location the tile
     * @param item     the stack
     * @return the record; null if the definition can't be read
     */
    private GroundItem buildGroundItem(WorldPoint location, TileItem item) {
        int itemId = item.getId();
        ItemComposition definition = client.getItemDefinition(itemId);
        if (definition == null) {
            return null;
        }
        int realItemId = definition.getNote() != -1 ? definition.getLinkedNoteId() : itemId;
        ItemComposition real = realItemId == itemId ? definition : client.getItemDefinition(realItemId);
        if (real == null) {
            real = definition;
        }
        GroundItem groundItem = GroundItem.builder()
                .id(itemId)
                .itemId(realItemId)
                .quantity(item.getQuantity())
                .location(location)
                .name(definition.getName())
                .haPrice(real.getHaPrice())
                .gePrice(gePrice(realItemId))
                .tradeable(real.isTradeable())
                .ownership(GroundItem.OWNERSHIP_NONE)
                .isPrivate(false)
                .spawnTime(null)
                .stackable(definition.isStackable())
                .despawnTime(Duration.ZERO)
                .visibleTime(Duration.ZERO)
                .build();
        if (realItemId == ItemID.COINS) {
            groundItem.setHaPrice(1);
        }
        return groundItem;
    }

    /**
     * The Grand Exchange price of an item, for the items whose price is fixed.
     *
     * @param itemId the plain item id
     * @return 1 for coins, 1,000 for platinum tokens, else 0 (unknown)
     */
    static long gePrice(int itemId) {
        if (itemId == ItemID.COINS) {
            return 1;
        }
        if (itemId == ItemID.PLATINUM) {
            return PLATINUM_TOKEN_VALUE;
        }
        return 0;
    }

    /**
     * Works out the lootbeam a tile calls for. Client thread only.
     *
     * @param location the tile
     */
    private void handleLootbeam(WorldPoint location) {
        Map<Integer, GroundItem> tile = collectedGroundItems.get(location);
        Collection<GroundItem> items = tile == null ? List.of() : tile.values();
        Color color = items.isEmpty() ? null
                : rules.lootbeamColor(items, client.getVarbitValue(VarbitID.IRONMAN));
        if (color == null) {
            lootbeams.remove(location);
        } else {
            lootbeams.put(location, color);
        }
    }

    /**
     * Works out the lootbeams of every tile with items. Client thread only.
     */
    private void handleLootbeams() {
        lootbeams.clear();
        for (WorldPoint location : new ArrayList<>(collectedGroundItems.keySet())) {
            handleLootbeam(location);
        }
    }

    /**
     * An item's own colour, set from the right-click menu.
     *
     * @param itemId the plain item id
     * @return the colour, or null if it has none
     */
    Color getItemColor(int itemId) {
        return configManager.getConfiguration(GroundItemsConfig.GROUP, HIGHLIGHT_COLOR_PREFIX + itemId, Color.class);
    }
}
