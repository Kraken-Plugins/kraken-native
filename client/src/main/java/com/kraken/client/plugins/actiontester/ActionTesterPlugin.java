package com.kraken.client.plugins.actiontester;

import com.google.inject.Inject;
import com.google.inject.Provides;
import com.kraken.api.Client;
import com.kraken.api.MenuEntry;
import com.kraken.api.action.ActionResult;
import com.kraken.api.action.Actions;
import com.kraken.api.action.Dialogue;
import com.kraken.api.action.Inventory;
import com.kraken.api.action.Movement;
import com.kraken.api.action.Prayers;
import com.kraken.api.actor.NPC;
import com.kraken.api.actor.Player;
import com.kraken.api.coords.WorldPoint;
import com.kraken.api.eventbus.Subscribe;
import com.kraken.api.events.GameTick;
import com.kraken.api.gameval.InterfaceID;
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

import java.awt.Point;
import java.util.List;

/**
 * Exercises the interaction API one action at a time, from the settings page: pick an action, fill in what it acts on
 * and click Run, and the action runs once on the client thread. The console says what was sent, as the API's result
 * (SENT, or why not: GONE, NO_SUCH_OPTION, NOT_FOUND...), or what the native hook couldn't do. An action can also
 * repeat every so many ticks.
 *
 * <p>Each action calls the API the way a plugin would: an NPC's, object's, ground item's or component's option through
 * its {@code interact}, an inventory item used or a spell cast on a target through {@code useOn}, a spell with no
 * target through {@code click}, the run, quick-prayer and dialogue actions through their services ({@link Movement},
 * {@link Prayers}, {@link Dialogue}), and walking through {@link Movement#walk}. The services are injected, as plugins
 * inject them. Only the menu's own entries, the close button and the bare mouse click packet go through
 * {@link Actions} directly, because the API has no method for them.
 *
 * <p>The settings page shows only the settings the chosen action uses, and their drop-downs list what the game has
 * loaded when they open ({@link Subjects}): nearby NPCs, players, objects and ground items, the inventory's items, the
 * subject's options, the components the action fits, the spellbook's spells, the prayer book's prayers, the menu's
 * entries. Subjects are found by name, nearest to the player first. The mouse click before an action follows the
 * Settings page's click policy, not a setting of this plugin.
 *
 * <p>Threading: the settings page posts the Run button and the suggestion requests on the EDT, and both are handed to
 * the client thread; the tick handler and the runs are on the client thread, so the tick count needs no locking.
 */
@Log(topic = "kraken")
@PluginDescriptor(
        name = "Action Tester",
        description = "Runs any action from the settings page through the interaction API, to test them one by one",
        tags = {"action", "packet", "test", "menu", "walk", "dialog", "example"},
        enabledByDefault = false
)
public class ActionTesterPlugin extends Plugin {

    @Inject
    private Client client;

    @Inject
    private ClientThread clientThread;

    @Inject
    private ActionTesterConfig config;

    @Inject
    private Movement movement;

    @Inject
    private Dialogue dialogue;

    @Inject
    private Prayers prayers;

    @Inject
    private Inventory inventory;

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
            case "prayer" -> subjects.prayerNames();
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
            case NPC_OPTION -> npcOption();
            case PLAYER_OPTION -> playerOption();
            case OBJECT_OPTION -> objectOption();
            case GROUND_ITEM_OPTION -> groundItemOption();
            case INVENTORY_OPTION -> inventoryOption();
            case WIDGET_OPTION -> widgetOption();
            case USE_ITEM_ON_NPC, USE_ITEM_ON_PLAYER, USE_ITEM_ON_OBJECT, USE_ITEM_ON_GROUND_ITEM, USE_ITEM_ON_ITEM ->
                    useItem(test);
            case CAST_SPELL_ON_NPC, CAST_SPELL_ON_PLAYER, CAST_SPELL_ON_OBJECT, CAST_SPELL_ON_GROUND_ITEM,
                 CAST_SPELL_ON_ITEM -> castSpellOn(test);
            case CAST_SPELL -> castSpell();
            case PRAYER_TOGGLE -> prayer();
            case QUICK_PRAYERS -> quickPrayers();
            case SPECIAL_ATTACK -> specialAttack();
            case WIDGET_BUTTON -> widgetButton();
            case PAUSE_BUTTON -> pauseButton();
            case CLOSE_INTERFACE -> reportSent(actions.closeInterface(), "closed the open interface",
                    "the close button's body isn't mapped for this build");
            case RESUME_COUNT -> report(dialogue.enterAmount(config.count()),
                    "answer the count dialog with " + config.count());
            case RESUME_NAME -> answerText(true);
            case RESUME_STRING -> answerText(false);
            case RESUME_OBJ -> report(dialogue.selectItem(config.itemId()),
                    "answer the item dialog with item " + config.itemId());
            case WALK -> walk();
            case WALK_TO -> walkToTile();
            case TOGGLE_RUN -> toggleRun();
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
        return reportSent(actions.menuAction(entry), "ran entry " + text + " (" + entry.getType() + " id="
                + entry.getIdentifier() + " p0=" + entry.getParam0() + " p1=" + entry.getParam1() + ")",
                "the entry couldn't be sent");
    }

    private boolean npcOption() {
        NPC npc = npc();
        if (npc == null || blank(config.option(), "an option")) {
            return false;
        }
        return report(interact(npc, config.option()), config.option() + " on " + npc.getName(), npc.getActions());
    }

    private boolean playerOption() {
        Player player = player();
        if (player == null || blank(config.option(), "an option")) {
            return false;
        }
        return report(interact(player, config.option()), config.option() + " on " + player.getName(),
                player.getActions());
    }

    private boolean objectOption() {
        TileObject object = object();
        if (object == null || blank(config.option(), "an option")) {
            return false;
        }
        return report(interact(object, config.option()), config.option() + " on " + config.object().trim(),
                object.getActions());
    }

    private boolean groundItemOption() {
        TileItem item = groundItem();
        if (item == null || blank(config.option(), "an option")) {
            return false;
        }
        return report(interact(item, config.option()), config.option() + " on " + config.groundItem().trim(),
                item.getActions());
    }

    private boolean inventoryOption() {
        Widget slot = slot(config.item(), "an inventory item");
        if (slot == null || blank(config.option(), "an option")) {
            return false;
        }
        return report(interact(slot, config.option()), config.option() + " on " + config.item().trim(),
                options(slot));
    }

    private boolean widgetOption() {
        int[] component = component();
        Widget widget = component == null ? null : loaded(component);
        if (widget == null || blank(config.option(), "an option")) {
            return false;
        }
        return report(interact(widget, config.option()), config.option() + " on component "
                + config.component().trim(), options(widget));
    }

    private boolean castSpell() {
        Widget spell = spell();
        if (spell == null) {
            return false;
        }
        String option = config.option();
        ActionResult result = option.isBlank() ? spell.click() : interact(spell, option);
        return report(result, (option.isBlank() ? "cast " : option + " on ") + config.spell().trim(),
                options(spell));
    }

    private boolean prayer() {
        if (blank(config.prayer(), "a prayer")) {
            return false;
        }
        int id = subjects.prayer(config.prayer());
        Widget widget = id < 0 ? null : client.getWidget(id);
        if (widget == null) {
            say("no prayer called " + config.prayer().trim() + " is in the prayer book (is it loaded?)");
            return false;
        }
        return report(widget.click(), "toggle " + config.prayer().trim(), options(widget));
    }

    private boolean quickPrayers() {
        boolean on = !prayers.isQuickPrayerActive();
        return report(prayers.setQuickPrayer(on), (on ? "turn on" : "turn off") + " the quick-prayers");
    }

    private boolean specialAttack() {
        Widget orb = subjects.orb(InterfaceID.Orbs.SPECBUTTON, InterfaceID.OrbsNomap.SPECBUTTON);
        if (orb == null) {
            say("the special attack orb isn't loaded");
            return false;
        }
        return report(orb.click(), "click the special attack orb", options(orb));
    }

    private boolean toggleRun() {
        boolean on = !movement.isRunning();
        return report(movement.setRunning(on), (on ? "turn on" : "turn off") + " running");
    }

    private boolean widgetButton() {
        int[] component = component();
        Widget widget = component == null ? null : loaded(component);
        if (widget == null) {
            return false;
        }
        return report(widget.click(), "click component " + config.component().trim(), options(widget));
    }

    private boolean pauseButton() {
        if (config.component().isBlank()) {
            return report(dialogue.clickContinue(), "click to continue");
        }
        int[] component = component();
        Widget widget = component == null ? null : loaded(component);
        return widget != null && report(widget.click(), "click " + config.component().trim(), options(widget));
    }

    /**
     * Uses the inventory item the setting names on the target the action names.
     *
     * @param test the action
     * @return true if the use was sent
     */
    private boolean useItem(ActionTest test) {
        Widget held = slot(config.useItem(), "the item to use");
        if (held == null) {
            return false;
        }
        String what = "use " + config.useItem().trim() + " on ";
        return switch (test) {
            case USE_ITEM_ON_NPC -> {
                NPC npc = npc();
                yield npc != null && report(held.useOn(npc), what + npc.getName());
            }
            case USE_ITEM_ON_PLAYER -> {
                Player player = player();
                yield player != null && report(held.useOn(player), what + player.getName());
            }
            case USE_ITEM_ON_OBJECT -> {
                TileObject object = object();
                yield object != null && report(held.useOn(object), what + config.object().trim());
            }
            case USE_ITEM_ON_GROUND_ITEM -> {
                TileItem item = groundItem();
                yield item != null && report(held.useOn(item), what + config.groundItem().trim());
            }
            default -> {
                Widget other = slot(config.item(), "an inventory item");
                yield other != null && report(held.useOn(other), what + config.item().trim());
            }
        };
    }

    /**
     * Casts the spell the setting names on the target the action names.
     *
     * @param test the action
     * @return true if the cast was sent
     */
    private boolean castSpellOn(ActionTest test) {
        Widget spell = spell();
        if (spell == null) {
            return false;
        }
        String what = "cast " + config.spell().trim() + " on ";
        return switch (test) {
            case CAST_SPELL_ON_NPC -> {
                NPC npc = npc();
                yield npc != null && report(spell.useOn(npc), what + npc.getName());
            }
            case CAST_SPELL_ON_PLAYER -> {
                Player player = player();
                yield player != null && report(spell.useOn(player), what + player.getName());
            }
            case CAST_SPELL_ON_OBJECT -> {
                TileObject object = object();
                yield object != null && report(spell.useOn(object), what + config.object().trim());
            }
            case CAST_SPELL_ON_GROUND_ITEM -> {
                TileItem item = groundItem();
                yield item != null && report(spell.useOn(item), what + config.groundItem().trim());
            }
            default -> {
                Widget item = slot(config.item(), "an inventory item");
                yield item != null && report(spell.useOn(item), what + config.item().trim());
            }
        };
    }

    private boolean walk() {
        WorldPoint here = playerTile();
        return here != null && walkTo(here.dx(config.walkEast()).dy(config.walkNorth()), here);
    }

    private boolean walkToTile() {
        WorldPoint here = playerTile();
        Point tile = config.worldTile();
        return here != null && walkTo(new WorldPoint(tile.x, tile.y, here.getPlane()), here);
    }

    /**
     * The local player's world tile, said in the console when it can't be read.
     *
     * @return the tile, or null
     */
    private WorldPoint playerTile() {
        Player local = client.getLocalPlayer();
        WorldPoint here = local == null ? null : local.getWorldLocation();
        if (here == null) {
            say("the player's position can't be read");
        }
        return here;
    }

    private boolean walkTo(WorldPoint to, WorldPoint here) {
        return report(movement.walk(to), "walk to " + to + " from " + here);
    }

    private boolean mouseClick(Actions actions) {
        if (!actions.isMouseClickSupported()) {
            say("the mouse click packet isn't mapped for this build");
            return false;
        }
        Point at = config.clickPoint();
        actions.mouseClick(at.x, at.y, config.rightClick());
        say("sent a " + (config.rightClick() ? "right" : "left") + " click at " + at.x + "," + at.y);
        return true;
    }

    /**
     * Runs an option of an NPC: by its text, or by its number when the setting is one.
     *
     * @param npc    the NPC
     * @param option the setting
     * @return the result
     */
    private static ActionResult interact(NPC npc, String option) {
        int op = Subjects.number(option);
        return op >= 0 ? npc.interact(op) : npc.interact(option);
    }

    /**
     * Runs an option of a player: by its text, or by its number when the setting is one.
     *
     * @param player the player
     * @param option the setting
     * @return the result
     */
    private static ActionResult interact(Player player, String option) {
        int op = Subjects.number(option);
        return op >= 0 ? player.interact(op) : player.interact(option);
    }

    /**
     * Runs an option of an object: by its text, or by its number when the setting is one.
     *
     * @param object the object
     * @param option the setting
     * @return the result
     */
    private static ActionResult interact(TileObject object, String option) {
        int op = Subjects.number(option);
        return op >= 0 ? object.interact(op) : object.interact(option);
    }

    /**
     * Runs an option of a ground item: by its text, or by its number when the setting is one.
     *
     * @param item   the ground item
     * @param option the setting
     * @return the result
     */
    private static ActionResult interact(TileItem item, String option) {
        int op = Subjects.number(option);
        return op >= 0 ? item.interact(op) : item.interact(option);
    }

    /**
     * Runs a component's option. The setting is an op, by text or number, with an optional sub-option after a dot:
     * both parts by text or both by number. A text op with a numbered sub-option is sent as text, which the API
     * doesn't match.
     *
     * @param widget the component
     * @param option the setting
     * @return the result
     */
    private static ActionResult interact(Widget widget, String option) {
        String[] parts = Subjects.splitOption(option);
        int op = Subjects.number(parts[0]);
        if (parts[1] == null) {
            return op >= 0 ? widget.interact(op) : widget.interact(parts[0]);
        }
        int sub = Subjects.number(parts[1]);
        return op >= 0 && sub >= 0 ? widget.interact(op, sub) : widget.interact(parts[0], parts[1]);
    }

    /**
     * Reports an API result in the console: "ran" and the result when it was sent, else "could not run" and why.
     *
     * @param result the result
     * @param what   the action, for the console
     * @return true if it was sent
     */
    /**
     * Answers a name or string dialog with the text setting, saying in the console when the text can't be sent (too
     * long, or characters the dialog packets can't carry).
     *
     * @param name true for the name dialog, false for the string dialog
     * @return true if it was sent
     */
    private boolean answerText(boolean name) {
        String text = config.text();
        String what = "answer the " + (name ? "name" : "string") + " dialog with \"" + text + "\"";
        try {
            return report(name ? dialogue.enterName(text) : dialogue.enterText(text), what);
        } catch (IllegalArgumentException e) {
            say("could not " + what + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * The options a widget offers as the API matches them, for the console: its own ops, or for an item slot with
     * none, Use followed by the item's inventory actions.
     *
     * @param widget the widget
     * @return the options by op, or null if it has none
     */
    private String[] options(Widget widget) {
        return client.getInteractions().actions(widget);
    }

    private boolean report(ActionResult result, String what) {
        return report(result, what, null);
    }

    /**
     * Reports an API result in the console, with the options the subject has when the option wasn't one of them.
     *
     * @param result  the result
     * @param what    the action, for the console
     * @param options the subject's options by op, or null when they aren't listed
     * @return true if it was sent
     */
    private boolean report(ActionResult result, String what, String[] options) {
        String detail = result == ActionResult.NO_SUCH_OPTION && options != null
                ? "; it has " + Subjects.numbered(options) : "";
        say((result.isSent() ? "ran " : "could not run ") + what + ": " + result + detail);
        return result.isSent();
    }

    /**
     * Reports a send through {@link Actions}, which answers with a boolean rather than a result.
     *
     * @param sent   whether it was sent
     * @param done   what to report when it was
     * @param failed what to report when it wasn't
     * @return true if it was sent
     */
    private boolean reportSent(boolean sent, String done, String failed) {
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
     * The inventory slot an item setting names, from the inventory panel shown now; said in the console when there is
     * none.
     *
     * @param name the setting's value
     * @param what what the setting names, e.g. "the item to use"
     * @return the slot's widget, or null
     */
    private Widget slot(String name, String what) {
        if (blank(name, what)) {
            return null;
        }
        Widget slot = inventory.getSlot(name);
        if (slot == null) {
            say("no " + name.trim() + " in the inventory (is it open?)");
        }
        return slot;
    }

    /**
     * The spell the setting names, as its spellbook component; said in the console when it isn't there.
     *
     * @return the spell's widget, or null
     */
    private Widget spell() {
        if (blank(config.spell(), "a spell")) {
            return null;
        }
        int id = subjects.spell(config.spell());
        if (id < 0) {
            say("no spell called " + config.spell().trim() + " is in the spellbook");
            return null;
        }
        Widget widget = client.getWidget(id);
        if (widget == null) {
            say("the spellbook isn't loaded");
        }
        return widget;
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
        int[] component = Subjects.component(config.component());
        if (component == null) {
            say("give the component as group:child, group:child:slot or Group.COMPONENT, not \""
                    + config.component() + "\"");
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
}
