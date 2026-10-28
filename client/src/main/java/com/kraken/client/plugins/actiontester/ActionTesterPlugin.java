package com.kraken.client.plugins.actiontester;

import com.google.inject.Inject;
import com.google.inject.Provides;
import com.kraken.api.Client;
import com.kraken.api.MenuAction;
import com.kraken.api.MenuEntry;
import com.kraken.api.Perspective;
import com.kraken.api.Point;
import com.kraken.api.action.Actions;
import com.kraken.api.actor.Actor;
import com.kraken.api.actor.NPC;
import com.kraken.api.actor.Player;
import com.kraken.api.coords.LocalPoint;
import com.kraken.api.coords.WorldPoint;
import com.kraken.api.definition.ItemComposition;
import com.kraken.api.definition.NPCComposition;
import com.kraken.api.definition.ObjectComposition;
import com.kraken.api.eventbus.Subscribe;
import com.kraken.api.events.GameTick;
import com.kraken.api.gameval.InterfaceID;
import com.kraken.api.scene.Scene;
import com.kraken.api.scene.TileItem;
import com.kraken.api.scene.TileObject;
import com.kraken.api.widgets.Widget;
import com.kraken.client.callback.ClientThread;
import com.kraken.client.events.ConfigChanged;
import com.kraken.client.plugins.Plugin;
import com.kraken.client.plugins.PluginDescriptor;
import com.kraken.client.plugins.config.ConfigManager;
import lombok.extern.java.Log;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

/**
 * Exercises every action the client can run without the menu, one at a time, from the settings page: tick Run now
 * and the chosen test runs once on the client thread, saying in the console what it sent (the subject it found, the
 * op it worked out, the calls' success) or why it couldn't. A test can also repeat every so many ticks. The tests
 * cover the row bodies (NPC, player, object and ground item options, Walk here, inventory and widget options, using
 * an item on an item, NPC, object, ground item or player, a pause button), the dialog answers (count, name, string,
 * item), the bare mouse click packet, and replaying one of the menu's own entries, which goes through the same path a
 * menu event would.
 *
 * <p>Subjects are found by name among what is loaded, nearest to the player first. Options are matched against the
 * subject's own action list (an NPC's or object's composition, an item's inventory actions, a component's ops), or
 * taken as a number. With the mouse click setting on, the click packet goes first at the subject's canvas position
 * when it is on screen.
 *
 * <p>Threading: the settings page runs {@code onConfigChanged} on the EDT, which hands the run to the client thread
 * and unticks the switch; the tick handler and the runs are on the client thread, so the tick count needs no locking.
 */
@Log(topic = "kraken")
@PluginDescriptor(
        name = "Action Tester",
        description = "Runs any menu action or packet from the settings page, to test them one by one",
        tags = {"action", "packet", "test", "menu", "walk", "dialog", "example"},
        enabledByDefault = false
)
public class ActionTesterPlugin extends Plugin {

    /**
     * The settings key of the Run now switch.
     */
    static final String RUN_KEY = "runNow";

    /**
     * The inventory component the item tests look in.
     */
    static final int INVENTORY = InterfaceID.Inventory.ITEMS;

    /**
     * The button type of a pause ("Click here to continue") component.
     */
    private static final int PAUSE_BUTTON = 6;

    /**
     * How far above an actor's tile the mouse click lands, in local units: about a model's middle.
     */
    private static final int CLICK_HEIGHT = 100;

    private static final MenuAction[] NPC_OPS = {MenuAction.NPC_FIRST_OPTION, MenuAction.NPC_SECOND_OPTION,
            MenuAction.NPC_THIRD_OPTION, MenuAction.NPC_FOURTH_OPTION, MenuAction.NPC_FIFTH_OPTION};
    private static final MenuAction[] PLAYER_OPS = {MenuAction.PLAYER_FIRST_OPTION, MenuAction.PLAYER_SECOND_OPTION,
            MenuAction.PLAYER_THIRD_OPTION, MenuAction.PLAYER_FOURTH_OPTION, MenuAction.PLAYER_FIFTH_OPTION,
            MenuAction.PLAYER_SIXTH_OPTION, MenuAction.PLAYER_SEVENTH_OPTION, MenuAction.PLAYER_EIGHTH_OPTION};
    private static final MenuAction[] OBJECT_OPS = {MenuAction.GAME_OBJECT_FIRST_OPTION,
            MenuAction.GAME_OBJECT_SECOND_OPTION, MenuAction.GAME_OBJECT_THIRD_OPTION,
            MenuAction.GAME_OBJECT_FOURTH_OPTION, MenuAction.GAME_OBJECT_FIFTH_OPTION};
    private static final MenuAction[] GROUND_ITEM_OPS = {MenuAction.GROUND_ITEM_FIRST_OPTION,
            MenuAction.GROUND_ITEM_SECOND_OPTION, MenuAction.GROUND_ITEM_THIRD_OPTION,
            MenuAction.GROUND_ITEM_FOURTH_OPTION, MenuAction.GROUND_ITEM_FIFTH_OPTION};

    @Inject
    private Client client;

    @Inject
    private ClientThread clientThread;

    @Inject
    private ConfigManager configManager;

    @Inject
    private ActionTesterConfig config;

    /**
     * Game ticks since the last run. Client thread only.
     */
    private int sinceRun;

    @Provides
    ActionTesterConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(ActionTesterConfig.class);
    }

    @Override
    protected void startUp() {
        clientThread.invoke(() -> sinceRun = 0);
        if (config.runNow()) {
            configManager.setConfiguration(ActionTesterConfig.GROUP, RUN_KEY, false);
        }
        log.info("Action Tester: pick a test, fill in the subject and tick Run now");
    }

    @Override
    protected void shutDown() {
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event) {
        if (!ActionTesterConfig.GROUP.equals(event.getGroup()) || !RUN_KEY.equals(event.getKey())
                || !Boolean.parseBoolean(event.getNewValue())) {
            return;
        }
        configManager.setConfiguration(ActionTesterConfig.GROUP, RUN_KEY, false);
        clientThread.invoke(() -> {
            sinceRun = 0;
            run();
        });
    }

    @Subscribe
    public void onGameTick(GameTick event) {
        sinceRun++;
        int interval = config.interval();
        if (interval > 0 && sinceRun >= interval) {
            sinceRun = 0;
            run();
        }
    }

    /**
     * Runs the configured test once, if the game can take actions now.
     *
     * @return true if something was sent (or listed)
     */
    boolean run() {
        Actions actions = client.getActions();
        ActionTest test = config.test();
        if (test != ActionTest.LIST_MENU && !actions.isAvailable()) {
            say("the native hook isn't connected, so nothing can be sent");
            return false;
        }
        switch (test) {
            case LIST_MENU -> {
                return listMenu();
            }
            case MENU_ENTRY -> {
                return menuEntry(actions);
            }
            case NPC_OPTION -> {
                return npcOption(actions);
            }
            case PLAYER_OPTION -> {
                return playerOption(actions);
            }
            case OBJECT_OPTION -> {
                return objectOption(actions);
            }
            case GROUND_ITEM_OPTION -> {
                return groundItemOption(actions);
            }
            case INVENTORY_OPTION -> {
                return inventoryOption(actions);
            }
            case WIDGET_OPTION -> {
                return widgetOption(actions);
            }
            case WALK -> {
                return walk(actions);
            }
            case USE_ITEM_ON_ITEM, USE_ITEM_ON_NPC, USE_ITEM_ON_OBJECT, USE_ITEM_ON_GROUND_ITEM, USE_ITEM_ON_PLAYER -> {
                return useItem(actions, test);
            }
            case PAUSE_BUTTON -> {
                return pauseButton(actions);
            }
            case RESUME_COUNT -> {
                return report(actions.resumeCountDialog(config.number()), "answered the count dialog with "
                        + config.number(), "the dialog packets aren't mapped for this build");
            }
            case RESUME_NAME -> {
                return report(actions.resumeNameDialog(config.text()), "answered the name dialog with \""
                        + config.text() + "\"", "the dialog packets aren't mapped for this build, or the text is too long");
            }
            case RESUME_STRING -> {
                return report(actions.resumeStringDialog(config.text()), "answered the string dialog with \""
                        + config.text() + "\"", "the dialog packets aren't mapped for this build, or the text is too long");
            }
            case RESUME_OBJ -> {
                return report(actions.resumeObjDialog(config.number()), "answered the item dialog with item "
                        + config.number(), "the dialog packets aren't mapped for this build");
            }
            case MOUSE_CLICK -> {
                java.awt.Point at = config.clickPoint();
                if (!actions.isMouseClickSupported()) {
                    say("the mouse click packet isn't mapped for this build");
                    return false;
                }
                actions.mouseClick(at.x, at.y, false);
                say("sent a left click at " + at.x + "," + at.y);
                return true;
            }
        }
        return false;
    }

    private boolean listMenu() {
        MenuEntry[] entries = client.getMenuEntries();
        if (entries.length == 0) {
            say("the menu is empty (hover something in the game first)");
            return false;
        }
        say(entries.length + " menu entries" + (client.isMenuOpen() ? " (menu open)" : "") + ":");
        for (int i = 0; i < entries.length; i++) {
            MenuEntry e = entries[i];
            say("  [" + i + "] " + e.getOption() + " " + e.getTarget() + "  type=" + e.getType() + " id="
                    + e.getIdentifier() + " p0=" + e.getParam0() + " p1=" + e.getParam1() + " item=" + e.getItemId()
                    + (e.getWorldLocation() == null ? "" : " at " + e.getWorldLocation())
                    + (client.getActions().isSupported(e.getType()) ? "" : "  (not runnable)"));
        }
        return true;
    }

    private boolean menuEntry(Actions actions) {
        MenuEntry[] entries = client.getMenuEntries();
        int index = config.number();
        if (index < 0 || index >= entries.length) {
            say("the menu has " + entries.length + " entries, none at index " + index
                    + " (hover something in the game first)");
            return false;
        }
        MenuEntry entry = entries[index];
        if (!actions.isSupported(entry.getType())) {
            say("entry [" + index + "] " + entry.getOption() + " " + entry.getTarget() + " is a " + entry.getType()
                    + ", which has no row body");
            return false;
        }
        return report(actions.menuAction(entry), "ran entry [" + index + "] " + entry.getOption() + " "
                + entry.getTarget() + " (" + entry.getType() + " id=" + entry.getIdentifier() + " p0="
                + entry.getParam0() + " p1=" + entry.getParam1() + ")", "the entry couldn't be sent");
    }

    private boolean npcOption(Actions actions) {
        Optional<NPC> found = nearestNpc(config.target());
        if (found.isEmpty()) {
            say("no NPC called " + config.target() + " is loaded");
            return false;
        }
        NPC npc = found.get();
        int op = opOf(npcActions(npc), config.option(), NPC_OPS.length);
        if (op < 0) {
            say(npc.getName() + " has no " + config.option() + " option; it has " + list(npcActions(npc)));
            return false;
        }
        return send(actions, NPC_OPS[op - 1], 0, 0, npc.getIndex(), -1, actorPoint(npc),
                config.option() + " on " + npc.getName() + " (index " + npc.getIndex() + ", op " + op + ")");
    }

    private boolean playerOption(Actions actions) {
        Optional<Player> found = nearestPlayer(config.target());
        if (found.isEmpty()) {
            say("no player called " + config.target() + " is loaded");
            return false;
        }
        Player player = found.get();
        int op = number(config.option());
        if (op < 1 || op > PLAYER_OPS.length) {
            say("a player option is its number, 1-8, not \"" + config.option() + "\"");
            return false;
        }
        return send(actions, PLAYER_OPS[op - 1], 0, 0, player.getId(), -1, actorPoint(player),
                "option " + op + " on " + player.getName() + " (index " + player.getId() + ")");
    }

    private boolean objectOption(Actions actions) {
        Optional<TileObject> found = nearestObject(config.target());
        if (found.isEmpty()) {
            say("no object called " + config.target() + " is loaded");
            return false;
        }
        TileObject object = found.get();
        String[] names = objectActions(object);
        int op = opOf(names, config.option(), OBJECT_OPS.length);
        if (op < 0) {
            say(config.target() + " has no " + config.option() + " option; it has " + list(names));
            return false;
        }
        WorldPoint where = object.getWorldLocation();
        return send(actions, OBJECT_OPS[op - 1], sceneX(where), sceneY(where), object.getId(), -1,
                object.getCanvasLocation(), config.option() + " on " + config.target() + " (id " + object.getId()
                        + ") at " + where);
    }

    private boolean groundItemOption(Actions actions) {
        Optional<TileItem> found = nearestGroundItem(config.target());
        if (found.isEmpty()) {
            say("no ground item called " + config.target() + " is loaded");
            return false;
        }
        TileItem item = found.get();
        int op = opOf(groundActions(item), config.option(), GROUND_ITEM_OPS.length);
        if (op < 0) {
            say(config.target() + " has no " + config.option() + " option; it has " + list(groundActions(item)));
            return false;
        }
        WorldPoint where = item.getTile().getWorldLocation();
        return send(actions, GROUND_ITEM_OPS[op - 1], sceneX(where), sceneY(where), item.getId(), -1,
                tilePoint(item.getTile().getLocalLocation(), where), config.option() + " on " + config.target()
                        + " (id " + item.getId() + ") at " + where);
    }

    private boolean inventoryOption(Actions actions) {
        Optional<Widget> found = inventorySlot(config.target());
        if (found.isEmpty()) {
            say("no " + config.target() + " in the inventory (is it open?)");
            return false;
        }
        Widget slot = found.get();
        String[] names = itemActions(slot);
        int op = opOf(names, config.option(), 10);
        if (op < 0) {
            say(config.target() + " has no " + config.option() + " option; it has " + list(names));
            return false;
        }
        return send(actions, MenuAction.CC_OP, slot.getIndex(), INVENTORY, op, slot.getItemId(), widgetPoint(slot),
                config.option() + " on " + config.target() + " (item " + slot.getItemId() + ", slot " + slot.getIndex()
                        + ", op " + op + ")");
    }

    private boolean widgetOption(Actions actions) {
        int[] component = parseComponent(config.target());
        if (component == null) {
            say("give the widget as group:child or group:child:slot, not \"" + config.target() + "\"");
            return false;
        }
        Widget widget = widget(component);
        if (widget == null) {
            say("component " + config.target() + " isn't loaded");
            return false;
        }
        int op = opOf(widget.getActions(), config.option(), 10);
        if (op < 0) {
            say("component " + config.target() + " has no " + config.option() + " option; it has "
                    + list(widget.getActions()));
            return false;
        }
        return send(actions, MenuAction.CC_OP, component[1], component[0], op, widget.getItemId(), widgetPoint(widget),
                config.option() + " on component " + config.target() + " (op " + op + ")");
    }

    private boolean walk(Actions actions) {
        Player local = client.getLocalPlayer();
        WorldPoint here = local == null ? null : local.getWorldLocation();
        if (here == null) {
            say("the player's position can't be read");
            return false;
        }
        WorldPoint to = here.dx(config.walkEast()).dy(config.walkNorth());
        if (!actions.isSupported(MenuAction.WALK)) {
            say("Walk here isn't mapped for this build");
            return false;
        }
        Point canvas = config.mouseClick() ? tilePoint(LocalPoint.fromWorld(client, to), to) : null;
        boolean sent = canvas != null
                ? actions.click(canvas.getX(), canvas.getY(), sceneX(to), sceneY(to), MenuAction.WALK, 0)
                : actions.walk(to);
        return report(sent, "walked to " + to + " from " + here + (canvas == null ? "" : " with a click at "
                + canvas.getX() + "," + canvas.getY()), "the walk couldn't be sent");
    }

    private boolean useItem(Actions actions, ActionTest test) {
        Optional<Widget> held = inventorySlot(config.item());
        if (held.isEmpty()) {
            say("no " + config.item() + " in the inventory to use (is it open?)");
            return false;
        }
        Widget slot = held.get();
        int slotIndex = slot.getIndex();
        int itemId = slot.getItemId();
        String using = config.item() + " (item " + itemId + ", slot " + slotIndex + ")";
        switch (test) {
            case USE_ITEM_ON_ITEM -> {
                Optional<Widget> target = inventorySlot(config.target());
                if (target.isEmpty()) {
                    say("no " + config.target() + " in the inventory to use " + config.item() + " on");
                    return false;
                }
                Widget onto = target.get();
                return report(actions.useItemOnItem(INVENTORY, slotIndex, itemId, onto.getIndex(), onto.getItemId()),
                        "used " + using + " on " + config.target() + " (item " + onto.getItemId() + ", slot "
                                + onto.getIndex() + ")", "the use couldn't be sent");
            }
            case USE_ITEM_ON_NPC -> {
                Optional<NPC> npc = nearestNpc(config.target());
                if (npc.isEmpty()) {
                    say("no NPC called " + config.target() + " is loaded");
                    return false;
                }
                return report(actions.useItemOn(INVENTORY, slotIndex, itemId, 0, 0, MenuAction.WIDGET_TARGET_ON_NPC,
                        npc.get().getIndex(), -1), "used " + using + " on " + npc.get().getName() + " (index "
                        + npc.get().getIndex() + ")", "the use couldn't be sent");
            }
            case USE_ITEM_ON_PLAYER -> {
                Optional<Player> player = nearestPlayer(config.target());
                if (player.isEmpty()) {
                    say("no player called " + config.target() + " is loaded");
                    return false;
                }
                return report(actions.useItemOn(INVENTORY, slotIndex, itemId, 0, 0, MenuAction.WIDGET_TARGET_ON_PLAYER,
                        player.get().getId(), -1), "used " + using + " on " + player.get().getName() + " (index "
                        + player.get().getId() + ")", "the use couldn't be sent");
            }
            case USE_ITEM_ON_OBJECT -> {
                Optional<TileObject> object = nearestObject(config.target());
                if (object.isEmpty()) {
                    say("no object called " + config.target() + " is loaded");
                    return false;
                }
                WorldPoint where = object.get().getWorldLocation();
                return report(actions.useItemOn(INVENTORY, slotIndex, itemId, sceneX(where), sceneY(where),
                        MenuAction.WIDGET_TARGET_ON_GAME_OBJECT, object.get().getId(), -1), "used " + using + " on "
                        + config.target() + " (id " + object.get().getId() + ") at " + where, "the use couldn't be sent");
            }
            case USE_ITEM_ON_GROUND_ITEM -> {
                Optional<TileItem> ground = nearestGroundItem(config.target());
                if (ground.isEmpty()) {
                    say("no ground item called " + config.target() + " is loaded");
                    return false;
                }
                WorldPoint where = ground.get().getTile().getWorldLocation();
                return report(actions.useItemOn(INVENTORY, slotIndex, itemId, sceneX(where), sceneY(where),
                        MenuAction.WIDGET_TARGET_ON_GROUND_ITEM, ground.get().getId(), -1), "used " + using + " on "
                        + config.target() + " (id " + ground.get().getId() + ") at " + where, "the use couldn't be sent");
            }
            default -> {
                return false;
            }
        }
    }

    private boolean pauseButton(Actions actions) {
        int[] component = parseComponent(config.target());
        if (component == null) {
            Widget button = firstPauseButton();
            if (button == null) {
                say("no pause button is showing (or give one as group:child)");
                return false;
            }
            component = new int[]{button.getId(), button.getIndex()};
        }
        return report(actions.resumePause(component[0], component[1]), "clicked the pause button " + component[0]
                + ":" + component[1] + " (" + (component[0] >> 16) + ":" + (component[0] & 0xffff) + ")",
                "the pause button couldn't be clicked");
    }

    /**
     * Sends an action, with the mouse click first when wanted and the subject is on screen, and reports it.
     *
     * @param actions    the actions
     * @param action     the action
     * @param param0     its first parameter
     * @param param1     its second parameter
     * @param identifier its identifier
     * @param itemId     a component's item, or -1
     * @param canvas     where the subject is on the canvas, or null if it isn't
     * @param what       what to report
     * @return true if it was sent
     */
    private boolean send(Actions actions, MenuAction action, int param0, int param1, int identifier, int itemId,
                         Point canvas, String what) {
        if (!actions.isSupported(action)) {
            say(action + " isn't mapped for this build");
            return false;
        }
        boolean sent;
        if (config.mouseClick() && canvas != null) {
            sent = actions.click(canvas.getX(), canvas.getY(), param0, param1, action, identifier, itemId);
            what += " with a click at " + canvas.getX() + "," + canvas.getY();
        } else {
            sent = actions.menuAction(param0, param1, action, identifier, itemId);
            if (config.mouseClick()) {
                what += " (off screen, so no click)";
            }
        }
        return report(sent, "ran " + what + " as " + action, what + " couldn't be sent");
    }

    private boolean report(boolean sent, String done, String failed) {
        say(sent ? done : failed);
        return sent;
    }

    private void say(String message) {
        log.info("Action Tester: " + message);
    }

    private Optional<NPC> nearestNpc(String name) {
        return nearest(client.getNpcs(), name, Actor::getName, Actor::getWorldLocation);
    }

    private Optional<Player> nearestPlayer(String name) {
        return nearest(client.getPlayers(), name, Actor::getName, Actor::getWorldLocation);
    }

    private Optional<TileObject> nearestObject(String name) {
        Scene scene = client.getScene();
        return scene == null ? Optional.empty() : nearest(scene.getTileObjects(), name, object -> {
            ObjectComposition composition = client.getObjectDefinition(object.getId());
            return composition == null ? null : composition.getName();
        }, TileObject::getWorldLocation);
    }

    private Optional<TileItem> nearestGroundItem(String name) {
        Scene scene = client.getScene();
        return scene == null ? Optional.empty() : nearest(scene.getGroundItems(), name, item -> {
            ItemComposition composition = client.getItemDefinition(item.getId());
            return composition == null ? null : composition.getName();
        }, item -> item.getTile().getWorldLocation());
    }

    /**
     * The loaded thing with a name, nearest to the local player.
     *
     * @param things   what is loaded
     * @param name     the name wanted, case ignored
     * @param nameOf   a thing's name
     * @param placeOf  a thing's world tile
     * @param <T>      the kind of thing
     * @return the nearest match, if any
     */
    private <T> Optional<T> nearest(List<T> things, String name, Function<T, String> nameOf,
                                    Function<T, WorldPoint> placeOf) {
        Player local = client.getLocalPlayer();
        WorldPoint here = local == null ? null : local.getWorldLocation();
        String wanted = name.trim();
        return things.stream()
                .filter(t -> wanted.equalsIgnoreCase(nameOf.apply(t)))
                .min(Comparator.comparingInt(t -> distance(here, placeOf.apply(t))));
    }

    private static int distance(WorldPoint from, WorldPoint to) {
        return from == null || to == null ? Integer.MAX_VALUE : from.distanceTo(to);
    }

    /**
     * The inventory slot holding an item with a name.
     *
     * @param name the item's name, case ignored
     * @return the slot's widget, if the item is there
     */
    private Optional<Widget> inventorySlot(String name) {
        Widget inventory = client.getWidget(INVENTORY);
        Widget[] slots = inventory == null ? null : inventory.getDynamicChildren();
        if (slots == null) {
            return Optional.empty();
        }
        String wanted = name.trim();
        for (Widget slot : slots) {
            if (slot == null || slot.getItemId() <= 0) {
                continue;
            }
            ItemComposition composition = client.getItemDefinition(slot.getItemId());
            if (composition != null && wanted.equalsIgnoreCase(composition.getName())) {
                return Optional.of(slot);
            }
        }
        return Optional.empty();
    }

    /**
     * The widget of a component given as {group, child, slot}.
     *
     * @param component the parsed component
     * @return the widget, or its child at the slot, or null if it isn't loaded
     */
    private Widget widget(int[] component) {
        Widget widget = client.getWidget(component[0]);
        if (widget != null && component[1] >= 0) {
            widget = widget.getChild(component[1]);
        }
        return widget;
    }

    /**
     * The first visible pause button among the open interfaces.
     *
     * @return the button's widget, or null if none shows
     */
    private Widget firstPauseButton() {
        for (int group : client.getOpenWidgetGroups()) {
            Widget[] widgets = client.getWidgetGroup(group);
            if (widgets == null) {
                continue;
            }
            for (Widget widget : widgets) {
                Widget button = pauseButtonIn(widget);
                if (button != null) {
                    return button;
                }
            }
        }
        return null;
    }

    private static Widget pauseButtonIn(Widget widget) {
        if (widget == null || widget.isHidden()) {
            return null;
        }
        if (widget.getEvent() == PAUSE_BUTTON) {
            return widget;
        }
        Widget[] children = widget.getDynamicChildren();
        if (children != null) {
            for (Widget child : children) {
                if (child != null && !child.isHidden() && child.getEvent() == PAUSE_BUTTON) {
                    return child;
                }
            }
        }
        return null;
    }

    private String[] npcActions(NPC npc) {
        NPCComposition composition = npc.getTransformedComposition();
        if (composition == null) {
            composition = npc.getComposition();
        }
        return composition == null ? null : composition.getActions();
    }

    private String[] objectActions(TileObject object) {
        ObjectComposition composition = client.getObjectDefinition(object.getId());
        return composition == null ? null : composition.getActions();
    }

    /**
     * A ground item's options: the item's ground actions (Take is the third).
     *
     * @param item the ground item
     * @return the options by op, 1-based in the array's order, or null if the item isn't known
     */
    private String[] groundActions(TileItem item) {
        ItemComposition composition = client.getItemDefinition(item.getId());
        return composition == null ? null : composition.getGroundActions();
    }

    /**
     * An inventory slot's options: the slot's own ops when it has them, else Use followed by the item's inventory
     * actions, which is how this plugin numbers them until a live List the menu run settles the client's numbering.
     *
     * @param slot the slot's widget
     * @return the options by op, 1-based in the array's order
     */
    private String[] itemActions(Widget slot) {
        String[] own = slot.getActions();
        if (own != null) {
            return own;
        }
        ItemComposition composition = client.getItemDefinition(slot.getItemId());
        String[] inventory = composition == null ? null : composition.getInventoryActions();
        if (inventory == null) {
            return null;
        }
        String[] ops = new String[inventory.length + 1];
        ops[0] = "Use";
        System.arraycopy(inventory, 0, ops, 1, inventory.length);
        return ops;
    }

    /**
     * Which op an option is: its number if it is one, else its 1-based place among the actions, matched by text.
     *
     * @param actions the subject's actions by op (1-based in the array's order), null entries for none
     * @param option  the option's text or number
     * @param max     the most ops the subject has
     * @return the op, 1-based, or -1 for no such option
     */
    static int opOf(String[] actions, String option, int max) {
        String wanted = option.trim();
        int number = number(wanted);
        if (number >= 1 && number <= max) {
            return number;
        }
        if (actions == null) {
            return -1;
        }
        for (int i = 0; i < actions.length && i < max; i++) {
            if (actions[i] != null && actions[i].toLowerCase(Locale.ROOT).equals(wanted.toLowerCase(Locale.ROOT))) {
                return i + 1;
            }
        }
        return -1;
    }

    /**
     * A component given as group:child, group:child:slot or a packed id, with an optional :slot.
     *
     * @param text the setting's text
     * @return {packed id, slot (-1 for none)}, or null if the text isn't a component
     */
    static int[] parseComponent(String text) {
        String[] parts = text.trim().split(":");
        try {
            if (parts.length == 2 || parts.length == 3) {
                int group = Integer.parseInt(parts[0].trim());
                int child = Integer.parseInt(parts[1].trim());
                int slot = parts.length == 3 ? Integer.parseInt(parts[2].trim()) : -1;
                if (group < 0 || child < 0 || group > 0xffff || child > 0xffff) {
                    return null;
                }
                return new int[]{(group << 16) | child, slot};
            }
            if (parts.length == 1 && !parts[0].isBlank()) {
                int packed = Integer.parseInt(parts[0].trim());
                return packed > 0xffff ? new int[]{packed, -1} : null;
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return null;
    }

    private static int number(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String list(String[] actions) {
        List<String> names = new ArrayList<>();
        if (actions != null) {
            for (int i = 0; i < actions.length; i++) {
                if (actions[i] != null) {
                    names.add((i + 1) + ": " + actions[i]);
                }
            }
        }
        return names.isEmpty() ? "no options" : String.join(", ", names);
    }

    private int sceneX(WorldPoint world) {
        return world.getX() - client.getScene().getBaseX();
    }

    private int sceneY(WorldPoint world) {
        return world.getY() - client.getScene().getBaseY();
    }

    /**
     * Where to click an actor: a point above the middle of its tile, on the canvas.
     *
     * @param actor the actor
     * @return the canvas point, or null while the actor isn't on screen
     */
    private Point actorPoint(Actor actor) {
        LocalPoint local = actor.getLocalLocation();
        WorldPoint world = actor.getWorldLocation();
        if (local == null || world == null) {
            return null;
        }
        return onCanvas(Perspective.localToCanvas(client, local, world.getPlane(), CLICK_HEIGHT));
    }

    private Point tilePoint(LocalPoint local, WorldPoint world) {
        if (local == null || world == null) {
            return null;
        }
        return onCanvas(Perspective.localToCanvas(client, local, world.getPlane()));
    }

    private Point widgetPoint(Widget widget) {
        Rectangle bounds = widget == null ? null : widget.getBounds();
        return bounds == null ? null : onCanvas(new Point((int) bounds.getCenterX(), (int) bounds.getCenterY()));
    }

    private Point onCanvas(Point point) {
        if (point == null || point.getX() < 0 || point.getY() < 0 || point.getX() >= client.getCanvasWidth()
                || point.getY() >= client.getCanvasHeight()) {
            return null;
        }
        return point;
    }
}
