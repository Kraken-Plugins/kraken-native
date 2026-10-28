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
import com.kraken.api.eventbus.Subscribe;
import com.kraken.api.events.GameTick;
import com.kraken.api.scene.TileItem;
import com.kraken.api.scene.TileObject;
import com.kraken.api.widgets.Widget;
import com.kraken.client.callback.ClientThread;
import com.kraken.client.events.ConfigButtonClicked;
import com.kraken.client.events.ConfigSuggestionsRequested;
import com.kraken.client.plugins.Plugin;
import com.kraken.client.plugins.PluginDescriptor;
import com.kraken.client.plugins.config.ConfigManager;
import lombok.extern.java.Log;

import java.awt.Rectangle;
import java.util.List;

import static com.kraken.client.plugins.actiontester.Subjects.INVENTORY;

/**
 * Exercises every action the client can run without the menu, one at a time, from the settings page: pick an action,
 * fill in what it acts on and click Run, and the action runs once on the client thread, saying in the console what
 * it sent (the subject it found, the op it worked out, the calls' success) or why it couldn't. An action can also
 * repeat every so many ticks. The actions cover the row bodies (NPC, player, object and ground item options, Walk
 * here, inventory and widget options with their sub-options, using an item or casting a spell on an NPC, player,
 * object, ground item or inventory item, the old-style buttons, closing an interface, a pause button), the dialog
 * answers (count, name, string, item), the bare mouse click packet, and replaying one of the menu's own entries,
 * which goes through the same path a menu event would.
 *
 * <p>The settings page shows only the settings the chosen action uses, and their drop-downs list what the game has
 * loaded when they open ({@link Subjects}): nearby NPCs, players, objects and ground items, the inventory's items,
 * the subject's options, the components the action fits, the spellbook's spells, the menu's entries. Subjects are
 * found by name, nearest to the player first. Options are matched against the subject's own action list (an NPC's or
 * object's composition, an item's inventory actions, a component's ops), or taken as a number; a sub-option after a
 * dot is matched against the op's sub-menu the same way. With the mouse click setting on, the click packet goes first
 * at the subject's canvas position when it is on screen.
 *
 * <p>Threading: the settings page posts the Run button and the suggestion requests on the EDT, and both are handed to
 * the client thread; the tick handler and the runs are on the client thread, so the tick count needs no locking.
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
    private ActionTesterConfig config;

    private Subjects subjects;

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
        subjects = new Subjects(client);
        clientThread.invoke(() -> sinceRun = 0);
        log.info("Action Tester: pick an action, fill in what it acts on and click Run");
    }

    @Override
    protected void shutDown() {
    }

    @Subscribe
    public void onConfigButtonClicked(ConfigButtonClicked event) {
        if (ActionTesterConfig.GROUP.equals(event.getGroup()) && ActionTesterConfig.RUN.equals(event.getKey())) {
            clientThread.invoke(() -> {
                sinceRun = 0;
                run();
            });
        }
    }

    @Subscribe
    public void onConfigSuggestionsRequested(ConfigSuggestionsRequested event) {
        if (ActionTesterConfig.GROUP.equals(event.getGroup())) {
            clientThread.invoke(() -> event.suggest(suggestions(event.getKey())));
        }
    }

    @Subscribe
    public void onGameTick(GameTick event) {
        sinceRun++;
        if (config.repeat() && sinceRun >= Math.max(1, config.interval())) {
            sinceRun = 0;
            run();
        }
    }

    /**
     * What a setting's drop-down suggests now.
     *
     * @param key the setting's key
     * @return the suggestions; empty for a setting without any
     */
    List<String> suggestions(String key) {
        return switch (key) {
            case "useItem", "item" -> subjects.inventoryNames();
            case "spell" -> subjects.spellNames();
            case "menuEntry" -> subjects.menuEntryTexts();
            case "npc" -> subjects.npcNames();
            case "player" -> subjects.playerNames();
            case "object" -> subjects.objectNames();
            case "groundItem" -> subjects.groundItemNames();
            case "component" -> subjects.components(config.test());
            case "option" -> subjects.options(config.test(), config);
            default -> List.of();
        };
    }

    /**
     * Runs the chosen action once, if the game can take actions now.
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
        return switch (test) {
            case LIST_MENU -> listMenu();
            case MENU_ENTRY -> menuEntry(actions);
            case NPC_OPTION -> npcOption(actions);
            case PLAYER_OPTION -> playerOption(actions);
            case OBJECT_OPTION -> objectOption(actions);
            case GROUND_ITEM_OPTION -> groundItemOption(actions);
            case INVENTORY_OPTION -> inventoryOption(actions);
            case WIDGET_OPTION -> widgetOption(actions);
            case USE_ITEM_ON_NPC, USE_ITEM_ON_PLAYER, USE_ITEM_ON_OBJECT, USE_ITEM_ON_GROUND_ITEM, USE_ITEM_ON_ITEM ->
                    useItem(actions, test);
            case CAST_SPELL_ON_NPC, CAST_SPELL_ON_PLAYER, CAST_SPELL_ON_OBJECT, CAST_SPELL_ON_GROUND_ITEM,
                 CAST_SPELL_ON_ITEM -> castSpell(actions, test);
            case WIDGET_BUTTON -> widgetButton(actions);
            case PAUSE_BUTTON -> pauseButton(actions);
            case CLOSE_INTERFACE -> report(actions.closeInterface(), "closed the open interface",
                    "the close button's body isn't mapped for this build");
            case RESUME_COUNT -> report(actions.resumeCountDialog(config.count()), "answered the count dialog with "
                    + config.count(), "the dialog packets aren't mapped for this build");
            case RESUME_NAME -> report(actions.resumeNameDialog(config.text()), "answered the name dialog with \""
                    + config.text() + "\"", "the dialog packets aren't mapped for this build, or the text is too long");
            case RESUME_STRING -> report(actions.resumeStringDialog(config.text()), "answered the string dialog with \""
                    + config.text() + "\"", "the dialog packets aren't mapped for this build, or the text is too long");
            case RESUME_OBJ -> report(actions.resumeObjDialog(config.itemId()), "answered the item dialog with item "
                    + config.itemId(), "the dialog packets aren't mapped for this build");
            case WALK -> walk(actions);
            case MOUSE_CLICK -> mouseClick(actions);
        };
    }

    private boolean listMenu() {
        MenuEntry[] rows = subjects.menuRows();
        if (rows.length == 0) {
            say("the menu is empty (hover something in the game first)");
            return false;
        }
        say(rows.length + " menu entries, top row first" + (client.isMenuOpen() ? " (menu open)" : "") + ":");
        for (int i = 0; i < rows.length; i++) {
            MenuEntry e = rows[i];
            say("  [" + i + "] " + Subjects.menuText(e) + "  type=" + e.getType() + " id=" + e.getIdentifier()
                    + " p0=" + e.getParam0() + " p1=" + e.getParam1() + " item=" + e.getItemId()
                    + (e.getWorldLocation() == null ? "" : " at " + e.getWorldLocation())
                    + (client.getActions().isSupported(e.getType()) ? "" : "  (not runnable)"));
        }
        return true;
    }

    private boolean menuEntry(Actions actions) {
        if (blank(config.menuEntry(), "a menu entry")) {
            return false;
        }
        MenuEntry[] rows = subjects.menuRows();
        MenuEntry entry = Subjects.menuEntry(rows, config.menuEntry());
        if (entry == null) {
            say("the menu has no entry " + config.menuEntry().trim() + " (it has " + rows.length
                    + " entries; hover something in the game first)");
            return false;
        }
        String text = Subjects.menuText(entry);
        if (!actions.isSupported(entry.getType())) {
            say("entry " + text + " is a " + entry.getType() + ", which has no row body");
            return false;
        }
        return report(actions.menuAction(entry), "ran entry " + text + " (" + entry.getType() + " id="
                + entry.getIdentifier() + " p0=" + entry.getParam0() + " p1=" + entry.getParam1() + ")",
                "the entry couldn't be sent");
    }

    private boolean npcOption(Actions actions) {
        NPC npc = npc();
        if (npc == null || blank(config.option(), "an option")) {
            return false;
        }
        String[] names = subjects.npcActions(npc);
        int op = Subjects.opOf(names, config.option(), NPC_OPS.length);
        if (op < 0) {
            say(npc.getName() + " has no " + config.option() + " option; it has " + Subjects.numbered(names));
            return false;
        }
        return send(actions, NPC_OPS[op - 1], 0, 0, npc.getIndex(), -1, 0, actorPoint(npc),
                config.option() + " on " + npc.getName() + " (index " + npc.getIndex() + ", op " + op + ")");
    }

    private boolean playerOption(Actions actions) {
        Player player = player();
        if (player == null || blank(config.option(), "an option")) {
            return false;
        }
        int op = Subjects.number(config.option());
        if (op < 1 || op > PLAYER_OPS.length) {
            say("a player option is its number, 1-8, not \"" + config.option() + "\"");
            return false;
        }
        return send(actions, PLAYER_OPS[op - 1], 0, 0, player.getId(), -1, 0, actorPoint(player),
                "option " + op + " on " + player.getName() + " (index " + player.getId() + ")");
    }

    private boolean objectOption(Actions actions) {
        TileObject object = object();
        if (object == null || blank(config.option(), "an option")) {
            return false;
        }
        String[] names = subjects.objectActions(object);
        int op = Subjects.opOf(names, config.option(), OBJECT_OPS.length);
        if (op < 0) {
            say(config.object() + " has no " + config.option() + " option; it has " + Subjects.numbered(names));
            return false;
        }
        WorldPoint where = object.getWorldLocation();
        return send(actions, OBJECT_OPS[op - 1], sceneX(where), sceneY(where), object.getId(), -1, 0,
                object.getCanvasLocation(), config.option() + " on " + config.object() + " (id " + object.getId()
                        + ") at " + where);
    }

    private boolean groundItemOption(Actions actions) {
        TileItem item = groundItem();
        if (item == null || blank(config.option(), "an option")) {
            return false;
        }
        String[] names = subjects.groundActions(item);
        int op = Subjects.opOf(names, config.option(), GROUND_ITEM_OPS.length);
        if (op < 0) {
            say(config.groundItem() + " has no " + config.option() + " option; it has " + Subjects.numbered(names));
            return false;
        }
        WorldPoint where = item.getTile().getWorldLocation();
        return send(actions, GROUND_ITEM_OPS[op - 1], sceneX(where), sceneY(where), item.getId(), -1, 0,
                tilePoint(item.getTile().getLocalLocation(), where), config.option() + " on " + config.groundItem()
                        + " (id " + item.getId() + ") at " + where);
    }

    private boolean inventoryOption(Actions actions) {
        Widget slot = slot(config.item(), "an inventory item");
        if (slot == null || blank(config.option(), "an option")) {
            return false;
        }
        String[] option = Subjects.splitOption(config.option());
        String[] names = subjects.itemActions(slot);
        int op = Subjects.opOf(names, option[0], 10);
        if (op < 0) {
            say(config.item() + " has no " + option[0] + " option; it has " + Subjects.numbered(names));
            return false;
        }
        int subOp = subOpOf(slot, op, option[1]);
        if (subOp < 0) {
            return false;
        }
        return send(actions, MenuAction.CC_OP, slot.getIndex(), INVENTORY, op, slot.getItemId(), subOp,
                widgetPoint(slot), config.option() + " on " + config.item() + " (item " + slot.getItemId() + ", slot "
                        + slot.getIndex() + ", op " + op + subOpText(subOp) + ")");
    }

    private boolean widgetOption(Actions actions) {
        int[] component = component();
        Widget widget = component == null ? null : loaded(component);
        if (widget == null || blank(config.option(), "an option")) {
            return false;
        }
        String[] option = Subjects.splitOption(config.option());
        int op = Subjects.opOf(widget.getActions(), option[0], 10);
        if (op < 0) {
            say("component " + config.component() + " has no " + option[0] + " option; it has "
                    + Subjects.numbered(widget.getActions()));
            return false;
        }
        int subOp = subOpOf(widget, op, option[1]);
        if (subOp < 0) {
            return false;
        }
        return send(actions, MenuAction.CC_OP, component[1], component[0], op, widget.getItemId(), subOp,
                widgetPoint(widget), config.option() + " on component " + config.component() + " (op " + op
                        + subOpText(subOp) + ")");
    }

    /**
     * The sub-option of an op the option setting names, checked against the component's sub-menu.
     *
     * @param widget the component
     * @param op     the op, 1-based
     * @param sub    the sub-option's text or number, or null for the op itself
     * @return the sub-option, 1-based; 0 for the op itself; -1, said in the console, when the op has no such
     * sub-option
     */
    private int subOpOf(Widget widget, int op, String sub) {
        if (sub == null) {
            return 0;
        }
        String[] subOptions = widget.getSubOptions(op);
        int subOp = Subjects.opOf(subOptions, sub, subOptions == null ? 0 : subOptions.length);
        if (subOp < 0) {
            say("op " + op + " has no " + sub + " sub-option; it has " + Subjects.numbered(subOptions));
        }
        return subOp;
    }

    private static String subOpText(int subOp) {
        return subOp > 0 ? ", sub-option " + subOp : "";
    }

    private boolean useItem(Actions actions, ActionTest test) {
        Widget held = slot(config.useItem(), "the item to use");
        Target target = held == null ? null : target(test);
        if (target == null) {
            return false;
        }
        String using = "used " + config.useItem() + " (item " + held.getItemId() + ", slot " + held.getIndex()
                + ") on " + target.what();
        boolean sent = test == ActionTest.USE_ITEM_ON_ITEM
                ? actions.useItemOnItem(INVENTORY, held.getIndex(), held.getItemId(), target.param0(),
                target.itemId())
                : actions.useItemOn(INVENTORY, held.getIndex(), held.getItemId(), target.param0(), target.param1(),
                target.action(), target.identifier(), target.itemId());
        return report(sent, using, "the use couldn't be sent");
    }

    private boolean castSpell(Actions actions, ActionTest test) {
        if (blank(config.spell(), "a spell")) {
            return false;
        }
        int spell = Subjects.spellComponent(config.spell());
        if (spell < 0) {
            say("give the spell by name (e.g. High Alchemy) or as group:child, not \"" + config.spell() + "\"");
            return false;
        }
        Target target = target(test);
        if (target == null) {
            return false;
        }
        return report(actions.castSpellOn(spell, target.param0(), target.param1(), target.action(),
                        target.identifier(), target.itemId()), "cast " + config.spell() + " (" + (spell >>> 16) + ":"
                        + (spell & 0xffff) + ") on " + target.what(), "the cast couldn't be sent");
    }

    /**
     * What a Use or Cast action acts on, said in the console when it isn't found.
     *
     * @param test the action
     * @return the subject as its targeted action's parameters, or null if there is none
     */
    private Target target(ActionTest test) {
        switch (test) {
            case USE_ITEM_ON_NPC, CAST_SPELL_ON_NPC -> {
                NPC npc = npc();
                return npc == null ? null : new Target(0, 0, MenuAction.WIDGET_TARGET_ON_NPC, npc.getIndex(), -1,
                        npc.getName() + " (index " + npc.getIndex() + ")");
            }
            case USE_ITEM_ON_PLAYER, CAST_SPELL_ON_PLAYER -> {
                Player player = player();
                return player == null ? null : new Target(0, 0, MenuAction.WIDGET_TARGET_ON_PLAYER, player.getId(),
                        -1, player.getName() + " (index " + player.getId() + ")");
            }
            case USE_ITEM_ON_OBJECT, CAST_SPELL_ON_OBJECT -> {
                TileObject object = object();
                if (object == null) {
                    return null;
                }
                WorldPoint where = object.getWorldLocation();
                return new Target(sceneX(where), sceneY(where), MenuAction.WIDGET_TARGET_ON_GAME_OBJECT,
                        object.getId(), -1, config.object() + " (id " + object.getId() + ") at " + where);
            }
            case USE_ITEM_ON_GROUND_ITEM, CAST_SPELL_ON_GROUND_ITEM -> {
                TileItem item = groundItem();
                if (item == null) {
                    return null;
                }
                WorldPoint where = item.getTile().getWorldLocation();
                return new Target(sceneX(where), sceneY(where), MenuAction.WIDGET_TARGET_ON_GROUND_ITEM, item.getId(),
                        -1, config.groundItem() + " (id " + item.getId() + ") at " + where);
            }
            case USE_ITEM_ON_ITEM, CAST_SPELL_ON_ITEM -> {
                Widget slot = slot(config.item(), "an inventory item");
                return slot == null ? null : new Target(slot.getIndex(), INVENTORY,
                        MenuAction.WIDGET_TARGET_ON_WIDGET, 0, slot.getItemId(), config.item() + " (item "
                        + slot.getItemId() + ", slot " + slot.getIndex() + ")");
            }
            default -> {
                return null;
            }
        }
    }

    private boolean widgetButton(Actions actions) {
        int[] component = component();
        Widget widget = component == null ? null : loaded(component);
        if (widget == null) {
            return false;
        }
        int type = widget.getEvent();
        MenuAction action = Subjects.BUTTONS.get(type);
        if (action == null) {
            say("component " + config.component() + " has button type " + type + ", not a plain, toggle, set or "
                    + "close button (type 2 selects a target, 6 is a pause button)");
            return false;
        }
        return send(actions, action, component[1], component[0], 0, -1, 0, widgetPoint(widget),
                "button type " + type + " on component " + config.component());
    }

    private boolean pauseButton(Actions actions) {
        int[] component;
        if (config.component().isBlank()) {
            Widget button = subjects.firstPauseButton();
            if (button == null) {
                say("no Click here to continue is showing (or give one as group:child)");
                return false;
            }
            component = new int[]{button.getId(), button.getIndex()};
        } else {
            component = component();
            if (component == null) {
                return false;
            }
        }
        return report(actions.resumePause(component[0], component[1]), "clicked the pause button "
                + (component[0] >>> 16) + ":" + (component[0] & 0xffff) + (component[1] < 0 ? "" : ":"
                + component[1]), "the pause button couldn't be clicked");
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

    private boolean mouseClick(Actions actions) {
        if (!actions.isMouseClickSupported()) {
            say("the mouse click packet isn't mapped for this build");
            return false;
        }
        java.awt.Point at = config.clickPoint();
        actions.mouseClick(at.x, at.y, config.rightClick());
        say("sent a " + (config.rightClick() ? "right" : "left") + " click at " + at.x + "," + at.y);
        return true;
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
     * @param subOp      the sub-option, 1-based, or 0 for the op itself
     * @param canvas     where the subject is on the canvas, or null if it isn't
     * @param what       what to report
     * @return true if it was sent
     */
    private boolean send(Actions actions, MenuAction action, int param0, int param1, int identifier, int itemId,
                         int subOp, Point canvas, String what) {
        if (!actions.isSupported(action)) {
            say(action + " isn't mapped for this build");
            return false;
        }
        boolean sent;
        if (config.mouseClick() && canvas != null) {
            sent = actions.click(canvas.getX(), canvas.getY(), param0, param1, action, identifier, itemId, subOp);
            what += " with a click at " + canvas.getX() + "," + canvas.getY();
        } else {
            sent = actions.menuAction(param0, param1, action, identifier, itemId, subOp);
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

    /**
     * Whether a setting the action needs is empty, saying so in the console.
     *
     * @param setting the setting's value
     * @param what    what it names, e.g. "an NPC"
     * @return true if it is empty
     */
    private boolean blank(String setting, String what) {
        if (setting.isBlank()) {
            say("choose " + what + " first");
            return true;
        }
        return false;
    }

    private NPC npc() {
        if (blank(config.npc(), "an NPC")) {
            return null;
        }
        NPC npc = subjects.npc(config.npc()).orElse(null);
        if (npc == null) {
            say("no NPC called " + config.npc().trim() + " is loaded");
        }
        return npc;
    }

    private Player player() {
        if (blank(config.player(), "a player")) {
            return null;
        }
        Player player = subjects.player(config.player()).orElse(null);
        if (player == null) {
            say("no player called " + config.player().trim() + " is loaded");
        }
        return player;
    }

    private TileObject object() {
        if (blank(config.object(), "an object")) {
            return null;
        }
        TileObject object = subjects.object(config.object()).orElse(null);
        if (object == null) {
            say("no object called " + config.object().trim() + " is loaded");
        }
        return object;
    }

    private TileItem groundItem() {
        if (blank(config.groundItem(), "a ground item")) {
            return null;
        }
        TileItem item = subjects.groundItem(config.groundItem()).orElse(null);
        if (item == null) {
            say("no ground item called " + config.groundItem().trim() + " is loaded");
        }
        return item;
    }

    /**
     * The inventory slot an item setting names, said in the console when there is none.
     *
     * @param name the setting's value
     * @param what what the setting names, e.g. "the item to use"
     * @return the slot's widget, or null
     */
    private Widget slot(String name, String what) {
        if (blank(name, what)) {
            return null;
        }
        Widget slot = subjects.inventorySlot(name).orElse(null);
        if (slot == null) {
            say("no " + name.trim() + " in the inventory (is it open?)");
        }
        return slot;
    }

    /**
     * The component setting, read, said in the console when it isn't one.
     *
     * @return {packed id, slot}, or null
     */
    private int[] component() {
        if (blank(config.component(), "a component")) {
            return null;
        }
        int[] component = Subjects.parseComponent(config.component());
        if (component == null) {
            say("give the component as group:child or group:child:slot, not \"" + config.component() + "\"");
        }
        return component;
    }

    /**
     * A component's widget, said in the console when it isn't loaded.
     *
     * @param component the component
     * @return the widget, or null
     */
    private Widget loaded(int[] component) {
        Widget widget = subjects.widget(component);
        if (widget == null) {
            say("component " + config.component() + " isn't loaded");
        }
        return widget;
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

    /**
     * The subject of a Use or Cast action, as its targeted action's parameters (those of
     * {@link Actions#useItemOn(int, int, int, int, int, MenuAction, int, int)}).
     *
     * @param param0     the subject's first parameter: a scene x, or an inventory slot
     * @param param1     its second: a scene y, or the inventory's packed id
     * @param action     the targeted action
     * @param identifier an NPC's or player's index, or an object's or item's id
     * @param itemId     an inventory item's id, or -1
     * @param what       the subject, for the console
     */
    private record Target(int param0, int param1, MenuAction action, int identifier, int itemId, String what) {
    }
}
