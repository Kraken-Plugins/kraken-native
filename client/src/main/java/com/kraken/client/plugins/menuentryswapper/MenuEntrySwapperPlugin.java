package com.kraken.client.plugins.menuentryswapper;

import com.google.inject.Inject;
import com.google.inject.Provides;
import com.kraken.api.Client;
import com.kraken.api.GameState;
import com.kraken.api.MenuAction;
import com.kraken.api.MenuEntry;
import com.kraken.api.actor.NPC;
import com.kraken.api.definition.ItemComposition;
import com.kraken.api.definition.NPCComposition;
import com.kraken.api.definition.ObjectComposition;
import com.kraken.api.eventbus.Subscribe;
import com.kraken.api.events.ClientTick;
import com.kraken.api.events.GameStateChanged;
import com.kraken.api.events.MenuEntryAdded;
import com.kraken.api.events.MenuEntryRemoved;
import com.kraken.api.gameval.InterfaceID;
import com.kraken.api.menu.MenuEdits;
import com.kraken.api.menu.MenuMatch;
import com.kraken.api.menu.MenuRow;
import com.kraken.api.menu.MenuRule;
import com.kraken.api.menu.ShiftState;
import com.kraken.api.widgets.Widget;
import com.kraken.client.callback.ClientThread;
import com.kraken.client.events.ConfigChanged;
import com.kraken.client.game.NpcUtil;
import com.kraken.client.input.HotkeyListener;
import com.kraken.client.input.KeyManager;
import com.kraken.client.plugins.Plugin;
import com.kraken.client.plugins.PluginDescriptor;
import com.kraken.client.plugins.config.ConfigManager;
import com.kraken.client.plugins.config.Keybind;
import com.kraken.client.util.Text;
import lombok.extern.java.Log;

import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Changes which option a left click (or a Shift-click) runs on items, NPCs, objects, interfaces and ground items.
 * The settings become menu rules the native hook applies every time the client rebuilds the menu
 * ({@link MenuEdits}), so the swapped option is the left click from the moment a row shows. Shift + right-click an
 * item, NPC, object or worn item to pick its own left-click and Shift-click option from the Swap sub-menus the plugin
 * adds; the choices are stored as RuneLite stores them, so they carry over.
 *
 * <p>Differences from the original: a Shift-click Walk here setting applies to every object or NPC, including one
 * with a Shift-click swap of its own; swaps to a sub-option (an item's or object's sub-menu) aren't applied, nor
 * offered; worn item options come from the item's op params as before, but an item type without them offers only
 * the options the menu shows.
 */
@Log(topic = "kraken")
@PluginDescriptor(
        name = "Menu Entry Swapper",
        description = "Change the default option that is displayed when hovering over objects",
        tags = {"npcs", "inventory", "items", "objects"},
        enabledByDefault = false
)
public class MenuEntrySwapperPlugin extends Plugin {

    private static final String ITEM_KEY_PREFIX = "item_";
    private static final String OBJECT_KEY_PREFIX = "object_";
    private static final String OBJECT_SHIFT_KEY_PREFIX = "object_shift_";
    private static final String NPC_KEY_PREFIX = "npc_";
    private static final String NPC_SHIFT_KEY_PREFIX = "npc_shift_";
    private static final String WORN_ITEM_KEY_PREFIX = "wornitem_";
    private static final String WORN_ITEM_SHIFT_KEY_PREFIX = "wornitem_shift_";
    private static final String UI_KEY_PREFIX = "ui_";
    private static final String UI_SHIFT_KEY_PREFIX = "ui_shift_";

    /**
     * The op the Examine row of an inventory or worn item runs as.
     */
    private static final int EXAMINE_OP = 10;

    /**
     * The item type's string params holding a worn item's option names, in op order (op params 1 to 8).
     */
    private static final int[] WORN_ITEM_OP_PARAMS = {451, 452, 453, 454, 455, 456, 457, 458};

    private static final List<String> STORED_PREFIXES = List.of(
            MenuEntrySwapperConfig.GROUP + "." + ITEM_KEY_PREFIX, MenuEntrySwapperRules.SHIFTCLICK_GROUP + "." + ITEM_KEY_PREFIX,
            MenuEntrySwapperConfig.GROUP + "." + NPC_KEY_PREFIX, MenuEntrySwapperConfig.GROUP + "." + OBJECT_KEY_PREFIX,
            MenuEntrySwapperConfig.GROUP + "." + WORN_ITEM_KEY_PREFIX, MenuEntrySwapperConfig.GROUP + "." + UI_KEY_PREFIX);

    @Inject
    private Client client;

    @Inject
    private ClientThread clientThread;

    @Inject
    private MenuEntrySwapperConfig config;

    @Inject
    private ConfigManager configManager;

    @Inject
    private KeyManager keyManager;

    private MenuEntrySwapperRules rules;

    /**
     * The ids of the rules built from the settings, replaced whole when they change. Client thread only.
     */
    private final List<Integer> ruleIds = new ArrayList<>();

    /**
     * The Swap rows added next to each Examine row shown while Shift is held, by the row's identity. Client thread
     * only.
     */
    private final Map<MenuMatch, List<Integer>> swapRows = new LinkedHashMap<>();

    /**
     * Builds the Swap rows for what is under the mouse when Shift goes down after the mouse got there.
     */
    private final HotkeyListener shiftListener = new HotkeyListener(() -> Keybind.SHIFT) {
        @Override
        public void hotkeyPressed() {
            for (MenuEntry entry : client.getMenuEntries()) {
                offerSwapRows(entry);
            }
        }
    };

    @Provides
    MenuEntrySwapperConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(MenuEntrySwapperConfig.class);
    }

    @Override
    protected void startUp() {
        rules = new MenuEntrySwapperRules(config, this::itemName);
        keyManager.registerHotkeyListener(shiftListener);
        clientThread.invoke(this::rebuild);
    }

    @Override
    protected void shutDown() {
        keyManager.unregisterHotkeyListener(shiftListener);
        clientThread.invoke(() -> {
            MenuEdits edits = client.getMenuEdits();
            edits.removeAll(ruleIds);
            ruleIds.clear();
            for (List<Integer> ids : swapRows.values()) {
                edits.removeAll(ids);
            }
            swapRows.clear();
        });
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event) {
        if (MenuEntrySwapperConfig.GROUP.equals(event.getGroup())
                || MenuEntrySwapperRules.SHIFTCLICK_GROUP.equals(event.getGroup())) {
            clientThread.invoke(this::rebuild);
        }
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event) {
        if (event.getGameState() == GameState.LOGGED_IN) {
            rebuild();
        }
    }

    @Subscribe
    public void onMenuEntryAdded(MenuEntryAdded event) {
        offerSwapRows(event.getMenuEntry());
    }

    @Subscribe
    public void onMenuEntryRemoved(MenuEntryRemoved event) {
        MenuEntry entry = event.getMenuEntry();
        if (entry.isCreated()) {
            return;
        }
        List<Integer> ids = swapRows.remove(MenuMatch.of(entry));
        if (ids != null) {
            client.getMenuEdits().removeAll(ids);
        }
    }

    @Subscribe
    public void onClientTick(ClientTick event) {
        if (config.removeDeadNpcMenus() && !client.isMenuOpen()) {
            removeDeadNpcs();
        }
    }

    /**
     * Replaces the rules with ones for the settings and stored swaps as they are now.
     */
    void rebuild() {
        MenuEdits edits = client.getMenuEdits();
        edits.removeAll(ruleIds);
        ruleIds.clear();
        Map<String, String> stored = new LinkedHashMap<>();
        for (String prefix : STORED_PREFIXES) {
            for (String key : configManager.getConfigurationKeys(prefix)) {
                int dot = key.indexOf('.');
                String value = configManager.getConfiguration(key.substring(0, dot), key.substring(dot + 1));
                if (value != null && !value.isEmpty()) {
                    stored.put(key, value);
                }
            }
        }
        for (MenuRule rule : rules.rules(stored)) {
            ruleIds.add(edits.add(rule));
        }
    }

    /**
     * Hides the rows of NPCs that are dying, as the original does by filtering the menu.
     */
    private void removeDeadNpcs() {
        MenuEntry[] entries = client.getMenuEntries();
        MenuEntry[] kept = Arrays.stream(entries).filter(e -> {
            NPC npc = e.getNpc();
            return npc == null || !NpcUtil.isDying(npc);
        }).toArray(MenuEntry[]::new);
        if (kept.length != entries.length) {
            client.setMenuEntries(kept);
        }
    }

    /**
     * Adds the Swap left-click and Swap shift-click rows under an Examine row (or the lowest op row of an interface
     * component) while Shift is held and the menu is closed, once per row shown.
     *
     * @param entry the row that appeared
     */
    private void offerSwapRows(MenuEntry entry) {
        if (entry.isCreated() || client.isMenuOpen() || !keyManager.isKeyPressed(KeyEvent.VK_SHIFT)) {
            return;
        }
        MenuMatch anchor = MenuMatch.of(entry);
        if (swapRows.containsKey(anchor)) {
            return;
        }
        List<MenuRule> adds = switch (entry.getType()) {
            case EXAMINE_NPC -> config.npcCustomization() ? npcSwapRows(entry) : List.of();
            case EXAMINE_OBJECT -> config.objectCustomization() ? objectSwapRows(entry) : List.of();
            case CC_OP_LOW_PRIORITY -> entry.getIdentifier() == EXAMINE_OP ? itemSwapRows(entry) : List.of();
            case CC_OP, WIDGET_TARGET -> uiSwapRows(entry);
            default -> List.of();
        };
        if (adds.isEmpty()) {
            return;
        }
        MenuEdits edits = client.getMenuEdits();
        List<Integer> ids = new ArrayList<>();
        for (MenuRule add : adds) {
            ids.add(edits.add(add));
        }
        swapRows.put(anchor, ids);
    }

    /**
     * The two Swap rows for an NPC: each of its options but Attack (which the game's own setting swaps), Walk here,
     * and Reset when a swap is stored.
     *
     * @param examine the NPC's Examine row
     * @return the rules adding the rows, the Shift-click one first so it ends up below the left-click one
     */
    private List<MenuRule> npcSwapRows(MenuEntry examine) {
        NPC npc = examine.getNpc();
        NPCComposition composition = npc == null ? null : npc.getTransformedComposition();
        if (composition == null) {
            return List.of();
        }
        List<MenuRule> adds = new ArrayList<>();
        for (boolean shift : new boolean[]{true, false}) {
            String key = (shift ? NPC_SHIFT_KEY_PREFIX : NPC_KEY_PREFIX) + composition.getId();
            Integer current = stored(MenuEntrySwapperConfig.GROUP, key);
            List<MenuRow> children = new ArrayList<>();
            String[] actions = composition.getActions();
            for (int i = 0; i < actions.length; i++) {
                if (actions[i] == null || actions[i].isEmpty() || "Attack".equalsIgnoreCase(actions[i])
                        || (current != null && current == i)) {
                    continue;
                }
                children.add(MenuRow.of(actions[i], "", setter(MenuEntrySwapperConfig.GROUP, key, i,
                        composition.getName(), actions[i], shift)));
            }
            children.add(MenuRow.of("Walk here", "", setter(MenuEntrySwapperConfig.GROUP, key, -1,
                    composition.getName(), "Walk here", shift)));
            if (current != null) {
                children.add(MenuRow.of("Reset", "", resetter(MenuEntrySwapperConfig.GROUP, key,
                        composition.getName(), shift)));
            }
            adds.add(swapRow(examine, shift, children));
        }
        return adds;
    }

    /**
     * The two Swap rows for an object: each of its options, Walk here, and Reset when a swap is stored.
     *
     * @param examine the object's Examine row
     * @return the rules adding the rows
     */
    private List<MenuRule> objectSwapRows(MenuEntry examine) {
        ObjectComposition composition = client.getObjectDefinition(examine.getIdentifier());
        if (composition == null) {
            return List.of();
        }
        List<MenuRule> adds = new ArrayList<>();
        for (boolean shift : new boolean[]{true, false}) {
            String key = (shift ? OBJECT_SHIFT_KEY_PREFIX : OBJECT_KEY_PREFIX) + composition.getId();
            Integer current = stored(MenuEntrySwapperConfig.GROUP, key);
            List<MenuRow> children = new ArrayList<>();
            String[] actions = composition.getActions();
            for (int i = 0; i < actions.length; i++) {
                if (actions[i] == null || actions[i].isEmpty() || (current != null && current == i)) {
                    continue;
                }
                children.add(MenuRow.of(actions[i], "", setter(MenuEntrySwapperConfig.GROUP, key, i,
                        composition.getName(), actions[i], shift)));
            }
            children.add(MenuRow.of("Walk here", "", setter(MenuEntrySwapperConfig.GROUP, key, -1,
                    composition.getName(), "Walk here", shift)));
            if (current != null) {
                children.add(MenuRow.of("Reset", "", resetter(MenuEntrySwapperConfig.GROUP, key,
                        composition.getName(), shift)));
            }
            adds.add(swapRow(examine, shift, children));
        }
        return adds;
    }

    /**
     * The Swap rows for an inventory or worn item, by the component the Examine row is on.
     *
     * @param examine the item's Examine row
     * @return the rules adding the rows; none for another component
     */
    private List<MenuRule> itemSwapRows(MenuEntry examine) {
        int group = examine.getParam1() >>> 16;
        if (group == InterfaceID.INVENTORY) {
            return heldItemSwapRows(examine);
        }
        if (group == InterfaceID.WORNITEMS) {
            return wornItemSwapRows(examine);
        }
        return List.of();
    }

    /**
     * The two Swap rows for an inventory item: each of its actions but the default, Use when the default isn't Use,
     * and Reset when a swap is stored. Each row only shows while its customization setting is on.
     *
     * @param examine the item's Examine row
     * @return the rules adding the rows
     */
    private List<MenuRule> heldItemSwapRows(MenuEntry examine) {
        ItemComposition item = client.getItemDefinition(examine.getItemId());
        if (item == null) {
            return List.of();
        }
        String[] actions = item.getInventoryActions();
        int defaultOp = defaultOp(actions);
        List<MenuRule> adds = new ArrayList<>();
        for (boolean shift : new boolean[]{true, false}) {
            if (shift ? !config.shiftClickCustomization() : !config.leftClickCustomization()) {
                continue;
            }
            String group = shift ? MenuEntrySwapperRules.SHIFTCLICK_GROUP : MenuEntrySwapperConfig.GROUP;
            String key = ITEM_KEY_PREFIX + item.getId();
            Integer current = stored(group, key);
            List<MenuRow> children = new ArrayList<>();
            for (int i = 0; i < actions.length && i < MenuEntrySwapperRules.ITEM_OPS.length; i++) {
                if (actions[i] == null || actions[i].isEmpty() || defaultOp == i || (current != null && current == i)) {
                    continue;
                }
                children.add(MenuRow.of(actions[i], "", setter(group, key, i, item.getName(), actions[i], shift)));
            }
            if (defaultOp != -1 && (current == null || current != -1)) {
                children.add(MenuRow.of("Use", "", setter(group, key, -1, item.getName(), "Use", shift)));
            }
            if (current != null) {
                children.add(MenuRow.of("Reset", "", resetter(group, key, item.getName(), shift)));
            }
            adds.add(swapRow(examine, shift, children));
        }
        return adds;
    }

    /**
     * The two Swap rows for a worn item: each of its options, from the item's op params, and Reset when a swap is
     * stored.
     *
     * @param examine the item's Examine row
     * @return the rules adding the rows
     */
    private List<MenuRule> wornItemSwapRows(MenuEntry examine) {
        int itemId = examine.getItemId();
        if (itemId < 0) {
            Widget slot = examine.getWidget();
            Widget child = slot == null ? null : slot.getChild(1);
            itemId = child == null ? -1 : child.getItemId();
        }
        ItemComposition item = itemId < 0 ? null : client.getItemDefinition(itemId);
        if (item == null) {
            return List.of();
        }
        List<MenuRule> adds = new ArrayList<>();
        for (boolean shift : new boolean[]{true, false}) {
            String key = (shift ? WORN_ITEM_SHIFT_KEY_PREFIX : WORN_ITEM_KEY_PREFIX) + item.getId();
            Integer current = stored(MenuEntrySwapperConfig.GROUP, key);
            List<MenuRow> children = new ArrayList<>();
            for (int i = 0; i < WORN_ITEM_OP_PARAMS.length; i++) {
                String name = item.getStringValue(WORN_ITEM_OP_PARAMS[i]);
                int op = MenuEntrySwapperRules.WORN_FIRST_OP + i;
                if (name == null || name.isEmpty() || (current != null && current == op)) {
                    continue;
                }
                children.add(MenuRow.of(name, "", setter(MenuEntrySwapperConfig.GROUP, key, op, item.getName(), name,
                        shift)));
            }
            if (current != null) {
                children.add(MenuRow.of("Reset", "", resetter(MenuEntrySwapperConfig.GROUP, key, item.getName(),
                        shift)));
            }
            adds.add(swapRow(examine, shift, children));
        }
        return adds;
    }

    /**
     * The two Swap rows for an interface component outside the inventory and worn items (but for the quiver's ammo
     * slot): each op the menu shows for it, and Reset when a swap is stored. Only a component with more than one op
     * row gets them, anchored to its lowest row.
     *
     * @param entry one of the component's op rows
     * @return the rules adding the rows; none when the row isn't the component's lowest or the component has one op
     */
    private List<MenuRule> uiSwapRows(MenuEntry entry) {
        Widget w = entry.getWidget();
        if (w == null || w.getActions() == null) {
            return List.of();
        }
        int interfaceId = w.getId() >>> 16;
        if (interfaceId == InterfaceID.INVENTORY
                || (interfaceId == InterfaceID.WORNITEMS && w.getId() != InterfaceID.Wornitems.EXTRA_QUIVER_AMMO)) {
            return List.of();
        }
        if (w.getIndex() != -1 && w.getItemId() == -1) {
            return List.of();
        }
        List<MenuEntry> ops = new ArrayList<>();
        for (MenuEntry row : client.getMenuEntries()) {
            if ((row.getType() == MenuAction.CC_OP || row.getType() == MenuAction.CC_OP_LOW_PRIORITY)
                    && row.getParam0() == entry.getParam0() && row.getParam1() == entry.getParam1()) {
                ops.add(row);
            }
        }
        if (ops.size() < 2 || !ops.get(0).equals(entry)) {
            return List.of();
        }
        int componentId = w.getId();
        int itemId = w.getIndex() == -1 ? -1 : w.getItemId();
        String suffix = componentId + (itemId != -1 ? "_" + itemId : "");
        List<MenuRule> adds = new ArrayList<>();
        for (boolean shift : new boolean[]{true, false}) {
            String key = (shift ? UI_SHIFT_KEY_PREFIX : UI_KEY_PREFIX) + suffix;
            Integer current = stored(MenuEntrySwapperConfig.GROUP, key);
            List<MenuRow> children = new ArrayList<>();
            for (int i = ops.size() - 1; i >= 0; i--) {
                MenuEntry op = ops.get(i);
                if (current != null && current == op.getIdentifier()) {
                    continue;
                }
                children.add(MenuRow.of(op.getOption(), "", setter(MenuEntrySwapperConfig.GROUP, key,
                        op.getIdentifier(), Text.removeTags(op.getTarget()), Text.removeTags(op.getOption()), shift)));
            }
            if (current != null) {
                children.add(MenuRow.of("Reset", "", resetter(MenuEntrySwapperConfig.GROUP, key,
                        Text.removeTags(entry.getTarget()), shift)));
            }
            adds.add(swapRow(entry, shift, children));
        }
        return adds;
    }

    /**
     * A Swap row under an anchor row, shown while Shift is held and the anchor shows.
     *
     * @param anchor   the row to sit under
     * @param shift    whether it is the Swap shift-click row
     * @param children its sub-menu
     * @return the rule adding it
     */
    private static MenuRule swapRow(MenuEntry anchor, boolean shift, List<MenuRow> children) {
        MenuRow row = new MenuRow(shift ? "Swap shift-click" : "Swap left-click", anchor.getTarget(), null, children);
        return new MenuRule.Add(MenuMatch.of(anchor), ShiftState.HELD, true, MenuRule.Add.Position.BELOW, row);
    }

    /**
     * The default left-click action of an item: the first of its first three actions, or Use.
     *
     * @param actions the item's inventory actions
     * @return the action's index, or -1 for Use
     */
    static int defaultOp(String[] actions) {
        for (int i = 0; i < 3 && i < actions.length; i++) {
            if (actions[i] != null && !actions[i].isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    private Integer stored(String group, String key) {
        String value = configManager.getConfiguration(group, key);
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Consumer<MenuEntry> setter(String group, String key, int value, String name, String option,
                                       boolean shift) {
        return e -> {
            configManager.setConfiguration(group, key, value);
            log.info("The default " + (shift ? "shift" : "left") + " click option for '" + Text.removeTags(name)
                    + "' has been set to '" + option + "'.");
        };
    }

    private Consumer<MenuEntry> resetter(String group, String key, String name, boolean shift) {
        return e -> {
            configManager.unsetConfiguration(group, key);
            log.info("The default " + (shift ? "shift" : "left") + " click option for '" + Text.removeTags(name)
                    + "' has been reset.");
        };
    }

    /**
     * An item's name, for the rules that match item rows by it.
     *
     * @param itemId the item
     * @return the name, or null if the definition can't be read
     */
    private String itemName(int itemId) {
        try {
            ItemComposition item = client.getItemDefinition(itemId);
            return item == null || item.getName() == null || item.getName().isEmpty() ? null : item.getName();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * The stored swap keys the plugin reads, for tests.
     *
     * @return the full key prefixes
     */
    static List<String> storedPrefixes() {
        return STORED_PREFIXES;
    }

    /**
     * The stored swaps, for tests: each full key to its value.
     *
     * @param configManager the settings
     * @return the swaps
     */
    static Map<String, String> storedSwaps(ConfigManager configManager) {
        Map<String, String> stored = new HashMap<>();
        for (String prefix : STORED_PREFIXES) {
            for (String key : configManager.getConfigurationKeys(prefix)) {
                int dot = key.indexOf('.');
                stored.put(key, configManager.getConfiguration(key.substring(0, dot), key.substring(dot + 1)));
            }
        }
        return stored;
    }
}
