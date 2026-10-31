package com.kraken.api.internal.action;

import com.kraken.api.Client;
import com.kraken.api.MenuAction;
import com.kraken.api.Point;
import com.kraken.api.action.ActionResult;
import com.kraken.api.action.ClickPolicy;
import com.kraken.api.actor.NPC;
import com.kraken.api.actor.Player;
import com.kraken.api.coords.WorldPoint;
import com.kraken.api.definition.ItemComposition;
import com.kraken.api.definition.NPCComposition;
import com.kraken.api.definition.ObjectComposition;
import com.kraken.api.scene.DecorativeObject;
import com.kraken.api.scene.GameObject;
import com.kraken.api.scene.GroundObject;
import com.kraken.api.scene.Tile;
import com.kraken.api.scene.TileItem;
import com.kraken.api.scene.TileObject;
import com.kraken.api.scene.WallObject;
import com.kraken.api.widgets.Widget;
import com.kraken.api.widgets.WidgetConfig;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The rules behind the interaction methods ({@code npc.interact("Attack")}, {@code widget.useOn(npc)} and the rest):
 * which options each kind of subject offers, which menu row an op runs and with what arguments, when a subject counts
 * as gone, and whether a mouse click goes first. The rows are built the way the client's menu builder builds them: an
 * NPC's options are its shown (transformed) type's, a loc's its impostor's, with conditional ops resolved; a loc row
 * names the loc's base id and the tile it was placed on, on its own plane; a component row its slot and item. Each
 * call sends one batch through {@code Actions.run} or {@code Actions.useOn}, or nothing, and every failure is logged
 * at {@code FINE} on {@code kraken.api}.
 *
 * <p>Internal: one per {@link Client}, from {@code client.getInteractions()}; the entity methods are one-line calls
 * into it. Plugins set the click policy here.
 *
 * <p>Thread-safe. Reads happen on the calling thread; the client thread is the best place to call from, since the
 * reads and the send then see the same poll.
 */
public final class Interactions {

    private static final Logger log = Logger.getLogger("kraken.api");

    /**
     * The most ops each kind of subject has.
     */
    public static final int NPC_OPS = 5;
    public static final int PLAYER_OPS = 8;
    public static final int OBJECT_OPS = 5;
    public static final int GROUND_ITEM_OPS = 5;
    public static final int COMPONENT_OPS = 10;

    /**
     * The op an inventory slot with no ops of its own runs for Use; the item's inventory actions follow it.
     */
    public static final String USE = "Use";

    /**
     * The old-style button type of a pause ("Click here to continue") button.
     */
    private static final int PAUSE_BUTTON = 6;

    private static final MenuAction[] NPC_ACTIONS = {MenuAction.NPC_FIRST_OPTION, MenuAction.NPC_SECOND_OPTION,
            MenuAction.NPC_THIRD_OPTION, MenuAction.NPC_FOURTH_OPTION, MenuAction.NPC_FIFTH_OPTION};
    private static final MenuAction[] PLAYER_ACTIONS = {MenuAction.PLAYER_FIRST_OPTION,
            MenuAction.PLAYER_SECOND_OPTION, MenuAction.PLAYER_THIRD_OPTION, MenuAction.PLAYER_FOURTH_OPTION,
            MenuAction.PLAYER_FIFTH_OPTION, MenuAction.PLAYER_SIXTH_OPTION, MenuAction.PLAYER_SEVENTH_OPTION,
            MenuAction.PLAYER_EIGHTH_OPTION};
    private static final MenuAction[] OBJECT_ACTIONS = {MenuAction.GAME_OBJECT_FIRST_OPTION,
            MenuAction.GAME_OBJECT_SECOND_OPTION, MenuAction.GAME_OBJECT_THIRD_OPTION,
            MenuAction.GAME_OBJECT_FOURTH_OPTION, MenuAction.GAME_OBJECT_FIFTH_OPTION};
    private static final MenuAction[] GROUND_ITEM_ACTIONS = {MenuAction.GROUND_ITEM_FIRST_OPTION,
            MenuAction.GROUND_ITEM_SECOND_OPTION, MenuAction.GROUND_ITEM_THIRD_OPTION,
            MenuAction.GROUND_ITEM_FOURTH_OPTION, MenuAction.GROUND_ITEM_FIFTH_OPTION};

    /**
     * The rows of the old-style buttons {@link #click(Widget)} presses, by button type: a plain button, a close
     * button, a toggle and a set. Type 2 selects a target and type 6 is the pause button.
     */
    private static final Map<Integer, MenuAction> BUTTONS = Map.of(1, MenuAction.WIDGET_TYPE_1,
            3, MenuAction.WIDGET_CLOSE, 4, MenuAction.WIDGET_TYPE_4, 5, MenuAction.WIDGET_TYPE_5);

    private final Client client;
    private volatile ClickPolicy clickPolicy = ClickPolicy.ON_SCREEN;
    private volatile Point staticClickPoint = new Point(0, 0);

    /**
     * Creates the rules for a client. {@code Client} makes its own.
     *
     * @param client the client the subjects belong to, and whose {@code Actions} sends the rows
     */
    public Interactions(Client client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    /**
     * Whether a mouse click goes before each action, and where.
     *
     * @return the policy; {@link ClickPolicy#ON_SCREEN} unless set
     */
    public ClickPolicy getClickPolicy() {
        return clickPolicy;
    }

    /**
     * Sets whether a mouse click goes before each action, and where.
     *
     * @param policy the policy
     */
    public void setClickPolicy(ClickPolicy policy) {
        this.clickPolicy = Objects.requireNonNull(policy, "policy");
    }

    /**
     * Where {@link ClickPolicy#STATIC} clicks, and where {@link ClickPolicy#ON_SCREEN} clicks for a subject off the
     * canvas.
     *
     * @return the point in canvas pixels; (0, 0) unless set
     */
    public Point getStaticClickPoint() {
        return staticClickPoint;
    }

    /**
     * Sets where {@link ClickPolicy#STATIC} clicks, and where {@link ClickPolicy#ON_SCREEN} clicks for a subject off
     * the canvas.
     *
     * @param point the point in canvas pixels
     */
    public void setStaticClickPoint(Point point) {
        this.staticClickPoint = Objects.requireNonNull(point, "point");
    }

    /**
     * The options an NPC's menu offers: its shown (transformed) type's actions, else its base type's, with
     * conditional actions resolved.
     *
     * @param npc the NPC
     * @return the options by op, null entries for empty slots; null if its type can't be read or it shows as nothing
     */
    public String[] actions(NPC npc) {
        NPCComposition shown = npc.getTransformedComposition();
        if (shown != null) {
            return shown.getActions();
        }
        NPCComposition base = npc.getComposition();
        return base == null || base.getConfigs().length > 0 ? null : base.getActions();
    }

    /**
     * Runs an NPC's option by its text.
     *
     * @param npc    the NPC
     * @param option the option's text, matched as {@link OptionMatcher} matches
     * @return the result
     */
    public ActionResult interact(NPC npc, String option) {
        return byText(npc(npc), option);
    }

    /**
     * Runs an NPC's option by its number.
     *
     * @param npc the NPC
     * @param op  the op, 1-5 as the menu numbers them
     * @return the result
     */
    public ActionResult interact(NPC npc, int op) {
        return byNumber(npc(npc), op);
    }

    /**
     * The options a player's menu offers: the ones the server set, the same for every player.
     *
     * @param player the player; unused, since every player has the same options
     * @return the options by op, null for an option the server hasn't set; null if they can't be read
     */
    public String[] actions(Player player) {
        String[] options = client.getPlayerOptions();
        return options.length == 0 ? null : options;
    }

    /**
     * Runs a player's option by its text.
     *
     * @param player the player
     * @param option the option's text, matched as {@link OptionMatcher} matches
     * @return the result
     */
    public ActionResult interact(Player player, String option) {
        return byText(player(player), option);
    }

    /**
     * Runs a player's option by its number.
     *
     * @param player the player
     * @param op     the op, 1-8 as the menu numbers them
     * @return the result
     */
    public ActionResult interact(Player player, int op) {
        return byNumber(player(player), op);
    }

    /**
     * The definition a loc shows as: its impostor, picked by its varbit or varp.
     *
     * @param object the loc
     * @return the shown definition; the loc's own if it doesn't transform; null if it shows as nothing or can't be
     * read
     */
    public ObjectComposition shown(TileObject object) {
        ObjectComposition base = client.getObjectDefinition(object.getId());
        return base == null ? null : base.getImpostor();
    }

    /**
     * A loc's name as the menu shows it: its impostor's.
     *
     * @param object the loc
     * @return the name; null if it shows as nothing or can't be read
     */
    public String name(TileObject object) {
        ObjectComposition shown = shown(object);
        return shown == null ? null : shown.getName();
    }

    /**
     * The options a loc's menu offers: its impostor's actions, with conditional actions resolved.
     *
     * @param object the loc
     * @return the options by op, null entries for empty slots; null if it shows as nothing or can't be read
     */
    public String[] actions(TileObject object) {
        ObjectComposition shown = shown(object);
        return shown == null ? null : shown.getActions();
    }

    /**
     * Runs a loc's option by its text.
     *
     * @param object the loc
     * @param option the option's text, matched as {@link OptionMatcher} matches
     * @return the result
     */
    public ActionResult interact(TileObject object, String option) {
        return byText(object(object), option);
    }

    /**
     * Runs a loc's option by its number.
     *
     * @param object the loc
     * @param op     the op, 1-5 as the menu numbers them
     * @return the result
     */
    public ActionResult interact(TileObject object, int op) {
        return byNumber(object(object), op);
    }

    /**
     * A ground item's name.
     *
     * @param item the item
     * @return the name, or null if the item's type can't be read
     */
    public String name(TileItem item) {
        ItemComposition type = client.getItemDefinition(item.getId());
        return type == null ? null : type.getName();
    }

    /**
     * The options a ground item's menu offers: the item's ground actions, with conditional actions resolved. Take is
     * op 3.
     *
     * @param item the item
     * @return the options by op, null entries for empty slots; null if the item's type can't be read
     */
    public String[] actions(TileItem item) {
        ItemComposition type = client.getItemDefinition(item.getId());
        return type == null ? null : type.getGroundActions();
    }

    /**
     * Runs a ground item's option by its text.
     *
     * @param item   the item
     * @param option the option's text, matched as {@link OptionMatcher} matches
     * @return the result
     */
    public ActionResult interact(TileItem item, String option) {
        return byText(groundItem(item), option);
    }

    /**
     * Runs a ground item's option by its number.
     *
     * @param item the item
     * @param op   the op, 1-5 as the menu numbers them
     * @return the result
     */
    public ActionResult interact(TileItem item, int op) {
        return byNumber(groundItem(item), op);
    }

    /**
     * The options a component's menu offers: its own ops, or for an item slot with none of its own, Use followed by
     * the item's inventory actions.
     *
     * @param widget the component
     * @return the options by op, null entries for empty slots; null if it has none
     */
    public String[] actions(Widget widget) {
        String[] own = widget.getActions();
        if (own != null || widget.getItemId() < 0) {
            return own;
        }
        ItemComposition type = client.getItemDefinition(widget.getItemId());
        String[] inventory = type == null ? null : type.getInventoryActions();
        if (inventory == null) {
            return null;
        }
        String[] options = new String[inventory.length + 1];
        options[0] = USE;
        System.arraycopy(inventory, 0, options, 1, inventory.length);
        return options;
    }

    /**
     * Runs a component's option by its text, with one of the op's sub-options when given.
     *
     * @param widget    the component
     * @param option    the option's text, matched as {@link OptionMatcher} matches
     * @param subOption the sub-option's text, matched the same way, or null for the op itself
     * @return the result
     */
    public ActionResult interact(Widget widget, String option, String subOption) {
        Objects.requireNonNull(option, "option");
        Subject subject = component(widget);
        if (subject.gone() != null) {
            return fail(ActionResult.GONE, subject.gone());
        }
        int op = OptionMatcher.match(subject.options(), option, subject.max());
        if (op < 0) {
            return noOption(subject, "\"" + option + "\"");
        }
        int subOp = 0;
        if (subOption != null) {
            String[] subOptions = widget.getSubOptions(op);
            subOp = OptionMatcher.match(subOptions, subOption, subOptions == null ? 0 : subOptions.length);
            if (subOp < 0) {
                return fail(ActionResult.NO_SUCH_OPTION, subject.what() + "'s " + option + " has no \"" + subOption
                        + "\" sub-option; it has " + OptionMatcher.describe(subOptions));
            }
        }
        return send(componentOp(widget, op, subOp), subject.what());
    }

    /**
     * Runs a component's option by its number, with one of the op's sub-options when given.
     *
     * @param widget the component
     * @param op     the op, 1-10 as the menu numbers them
     * @param subOp  the sub-option, 1-based, or 0 for the op itself
     * @return the result
     */
    public ActionResult interact(Widget widget, int op, int subOp) {
        Subject subject = component(widget);
        if (subject.gone() != null) {
            return fail(ActionResult.GONE, subject.gone());
        }
        if (!OptionMatcher.isOp(subject.options(), op, subject.max())) {
            return noOption(subject, String.valueOf(op));
        }
        if (subOp != 0) {
            String[] subOptions = widget.getSubOptions(op);
            if (subOp < 0 || subOptions == null || !OptionMatcher.isOp(subOptions, subOp, subOptions.length)) {
                return fail(ActionResult.NO_SUCH_OPTION, subject.what() + "'s op " + op + " has no sub-option "
                        + subOp + "; it has " + OptionMatcher.describe(subOptions));
            }
        }
        return send(componentOp(widget, op, subOp), subject.what());
    }

    /**
     * Clicks a component: runs the first of its first named op, its pause button ("Click here to continue") and its
     * old-style button's action that it has.
     *
     * @param widget the component
     * @return the result; {@link ActionResult#NO_SUCH_OPTION} if it has none of them
     */
    public ActionResult click(Widget widget) {
        Subject subject = component(widget);
        if (subject.gone() != null) {
            return fail(ActionResult.GONE, subject.gone());
        }
        int op = OptionMatcher.first(subject.options(), subject.max());
        if (op > 0) {
            return send(componentOp(widget, op, 0), subject.what());
        }
        Supplier<Point> at = () -> ClickPoints.widget(client, widget);
        if (isPauseButton(widget)) {
            return send(new ActionRequest(MenuAction.WIDGET_CONTINUE, widget.getIndex(), widget.getId(), 0, -1, 0,
                    null, clickPoint(at)), subject.what());
        }
        MenuAction button = BUTTONS.get(widget.getEvent());
        if (button != null) {
            return send(new ActionRequest(button, widget.getIndex(), widget.getId(), 0, -1, 0, null, clickPoint(at)),
                    subject.what());
        }
        return fail(ActionResult.NO_SUCH_OPTION, subject.what() + " has no option, pause button or button action");
    }

    /**
     * Uses a component's item or spell on an NPC: selects the component and runs the NPC's targeted row.
     *
     * @param source the component holding the item or spell
     * @param target the NPC
     * @return the result; {@link ActionResult#NO_SUCH_OPTION} if the source's target mask leaves out NPCs
     */
    public ActionResult useOn(Widget source, NPC target) {
        Subject subject = npc(target);
        return useOn(source, WidgetConfig.USE_NPC, "an NPC", subject, null, () -> subject.row().apply(1));
    }

    /**
     * Uses a component's item or spell on a player.
     *
     * @param source the component holding the item or spell
     * @param target the player
     * @return the result; {@link ActionResult#NO_SUCH_OPTION} if the source's target mask leaves out players
     */
    public ActionResult useOn(Widget source, Player target) {
        Subject subject = player(target);
        return useOn(source, WidgetConfig.USE_PLAYER, "a player", subject, null, () -> subject.row().apply(1));
    }

    /**
     * Uses a component's item or spell on a loc, at the tile it was placed on.
     *
     * @param source the component holding the item or spell
     * @param target the loc
     * @return the result; {@link ActionResult#NO_SUCH_OPTION} if the source's target mask leaves out objects
     */
    public ActionResult useOn(Widget source, TileObject target) {
        Subject subject = object(target);
        return useOn(source, WidgetConfig.USE_OBJECT, "an object", subject, null, () -> subject.row().apply(1));
    }

    /**
     * Uses a component's item or spell on a ground item.
     *
     * @param source the component holding the item or spell
     * @param target the ground item
     * @return the result; {@link ActionResult#NO_SUCH_OPTION} if the source's target mask leaves out ground items
     */
    public ActionResult useOn(Widget source, TileItem target) {
        Subject subject = groundItem(target);
        return useOn(source, WidgetConfig.USE_GROUND_ITEM, "a ground item", subject, null,
                () -> subject.row().apply(1));
    }

    /**
     * Uses a component's item or spell on another component, such as an inventory item.
     *
     * @param source the component holding the item or spell
     * @param target the component to use it on, which must take selections ({@link WidgetConfig#WIDGET_USE_TARGET})
     * @return the result; {@link ActionResult#NO_SUCH_OPTION} if the source's target mask leaves out components or the
     * target doesn't take selections
     */
    public ActionResult useOn(Widget source, Widget target) {
        Subject subject = component(target);
        String refusal = hasBits(target.getClickMask(), WidgetConfig.WIDGET_USE_TARGET) ? null
                : subject.what() + " doesn't take a selected item or spell";
        return useOn(source, WidgetConfig.USE_WIDGET, "a component", subject, refusal,
                () -> new ActionRequest(MenuAction.WIDGET_TARGET_ON_WIDGET, target.getIndex(), target.getId(), 0,
                        target.getItemId(), 0, null, clickPoint(() -> ClickPoints.widget(client, target))));
    }

    /**
     * Walks to a tile of the loaded scene, as Walk here on it does.
     *
     * @param tile the world tile
     * @return the result; {@link ActionResult#OUT_OF_SCENE} if the tile is outside the loaded scene
     */
    public ActionResult walk(WorldPoint tile) {
        Objects.requireNonNull(tile, "tile");
        if (!client.getTopLevelWorldView().contains(tile)) {
            return fail(ActionResult.OUT_OF_SCENE, tile + " is outside the loaded scene");
        }
        int[] scene = scene(tile);
        return send(new ActionRequest(MenuAction.WALK, scene[0], scene[1], 0, -1, 0, tile,
                clickPoint(() -> ClickPoints.tile(client, tile))), "the walk to " + tile);
    }

    /**
     * Sends one row with the mouse click the policy asks for, and logs a failure.
     *
     * @param request the row; its click point is ignored
     * @param subject where the subject is on the canvas, or a supplier of null when it isn't
     * @param what    the subject, for the log
     * @return the result
     */
    public ActionResult run(ActionRequest request, Supplier<Point> subject, String what) {
        return send(request.withClick(clickPoint(subject)), what);
    }

    /**
     * Where the mouse click goes for a subject under the click policy.
     *
     * @param subject where the subject is on the canvas, null while it is off it; only asked for under
     *                {@link ClickPolicy#ON_SCREEN}
     * @return the click point, or null for no click
     */
    public Point clickPoint(Supplier<Point> subject) {
        return switch (clickPolicy) {
            case NONE -> null;
            case STATIC -> staticClickPoint;
            case ON_SCREEN -> {
                Point at = subject.get();
                yield at != null ? at : staticClickPoint;
            }
        };
    }

    private Subject npc(NPC npc) {
        String what = named(npc.getName(), "NPC") + " (NPC " + npc.getIndex() + ")";
        IntFunction<ActionRequest> row = op -> new ActionRequest(NPC_ACTIONS[Math.min(op, NPC_OPS) - 1], 0, 0,
                npc.getIndex(), -1, 0, null, clickPoint(() -> ClickPoints.actor(client, npc)));
        if (npc.getAddress() == 0) {
            return Subject.gone(what, "is gone", row);
        }
        NPCComposition shown = npc.getTransformedComposition();
        NPCComposition base = shown == null ? npc.getComposition() : null;
        if (shown == null && base != null && base.getConfigs().length > 0) {
            return Subject.gone(what, "shows as nothing", row);
        }
        String[] options = shown != null ? shown.getActions() : base == null ? null : base.getActions();
        return new Subject(what, null, options, NPC_OPS, row);
    }

    private Subject player(Player player) {
        String what = named(player.getName(), "player") + " (player " + player.getId() + ")";
        IntFunction<ActionRequest> row = op -> new ActionRequest(PLAYER_ACTIONS[Math.min(op, PLAYER_OPS) - 1], 0, 0,
                player.getId(), -1, 0, null, clickPoint(() -> ClickPoints.actor(client, player)));
        if (player.getAddress() == 0) {
            return Subject.gone(what, "is gone", row);
        }
        return new Subject(what, null, actions(player), PLAYER_OPS, row);
    }

    private Subject object(TileObject object) {
        Tile tile = object.getTile();
        WorldPoint placed = tile.getWorldLocation();
        Point scene = tile.getSceneLocation();
        ObjectComposition base = client.getObjectDefinition(object.getId());
        ObjectComposition shown = base == null ? null : base.getImpostor();
        String name = shown != null ? shown.getName() : base != null ? base.getName() : null;
        String what = named(name, "object") + " (object " + object.getId() + " at " + placed + ")";
        IntFunction<ActionRequest> row = op -> new ActionRequest(OBJECT_ACTIONS[Math.min(op, OBJECT_OPS) - 1],
                scene.getX(), scene.getY(), object.getId(), -1, 0, placed,
                clickPoint(() -> ClickPoints.object(client, object)));
        if (!isPlaced(object, tile)) {
            return Subject.gone(what, "is no longer there", row);
        }
        if (base != null && shown == null && base.getImpostorIds().length > 0) {
            return Subject.gone(what, "shows as nothing", row);
        }
        return new Subject(what, null, shown == null ? null : shown.getActions(), OBJECT_OPS, row);
    }

    private Subject groundItem(TileItem item) {
        Tile tile = item.getTile();
        WorldPoint where = tile.getWorldLocation();
        Point scene = tile.getSceneLocation();
        String what = named(name(item), "item") + " (item " + item.getId() + " at " + where + ")";
        IntFunction<ActionRequest> row = op -> new ActionRequest(
                GROUND_ITEM_ACTIONS[Math.min(op, GROUND_ITEM_OPS) - 1], scene.getX(), scene.getY(), item.getId(), -1,
                0, where, clickPoint(() -> ClickPoints.groundItem(client, item)));
        if (tile.getGroundItems().stream().noneMatch(lying -> lying.getId() == item.getId())) {
            return Subject.gone(what, "is no longer there", row);
        }
        return new Subject(what, null, actions(item), GROUND_ITEM_OPS, row);
    }

    private Subject component(Widget widget) {
        String name = OptionMatcher.plain(widget.getName());
        String what = name.isEmpty() ? widget.toString() : name + " (" + widget + ")";
        IntFunction<ActionRequest> row = op -> componentOp(widget, op, 0);
        Widget now = client.getWidgetReader().lookup(widget.getId(), widget.getIndex());
        if (now == null) {
            return Subject.gone(what, "isn't loaded", row);
        }
        if (now.getItemId() != widget.getItemId()) {
            return Subject.gone(what, "now holds item " + now.getItemId(), row);
        }
        return new Subject(what, null, actions(widget), COMPONENT_OPS, row);
    }

    private ActionRequest componentOp(Widget widget, int op, int subOp) {
        return new ActionRequest(MenuAction.CC_OP, widget.getIndex(), widget.getId(), op, widget.getItemId(), subOp,
                null, clickPoint(() -> ClickPoints.widget(client, widget)));
    }

    private ActionResult useOn(Widget source, int bit, String kind, Subject target, String refusal,
                               Supplier<ActionRequest> row) {
        Subject from = component(source);
        if (from.gone() != null) {
            return fail(ActionResult.GONE, from.gone());
        }
        if (!hasBits(source.getClickMask(), bit)) {
            return fail(ActionResult.NO_SUCH_OPTION, from.what() + " can't be used on " + kind);
        }
        if (target.gone() != null) {
            return fail(ActionResult.GONE, target.gone());
        }
        if (refusal != null) {
            return fail(ActionResult.NO_SUCH_OPTION, refusal);
        }
        ActionRequest select = new ActionRequest(MenuAction.WIDGET_TARGET, source.getIndex(), source.getId(), 0,
                source.getItemId(), 0, null, clickPoint(() -> ClickPoints.widget(client, source)));
        ActionResult result = client.getActions().useOn(select, row.get());
        if (!result.isSent()) {
            log.fine(() -> from.what() + " on " + target.what() + ": " + result);
        }
        return result;
    }

    private ActionResult byText(Subject subject, String option) {
        Objects.requireNonNull(option, "option");
        if (subject.gone() != null) {
            return fail(ActionResult.GONE, subject.gone());
        }
        int op = OptionMatcher.match(subject.options(), option, subject.max());
        return op < 0 ? noOption(subject, "\"" + option + "\"") : send(subject.row().apply(op), subject.what());
    }

    private ActionResult byNumber(Subject subject, int op) {
        if (subject.gone() != null) {
            return fail(ActionResult.GONE, subject.gone());
        }
        return OptionMatcher.isOp(subject.options(), op, subject.max())
                ? send(subject.row().apply(op), subject.what()) : noOption(subject, String.valueOf(op));
    }

    private ActionResult noOption(Subject subject, String option) {
        return fail(ActionResult.NO_SUCH_OPTION, subject.what() + " has no " + option + " option; it has "
                + OptionMatcher.describe(subject.options()));
    }

    private ActionResult send(ActionRequest request, String what) {
        ActionResult result = client.getActions().run(request);
        if (!result.isSent()) {
            log.fine(() -> request.action() + " on " + what + ": " + result);
        }
        return result;
    }

    private static ActionResult fail(ActionResult result, String detail) {
        if (log.isLoggable(Level.FINE)) {
            log.fine(detail);
        }
        return result;
    }

    /**
     * Whether a loc is still on the tile it was read on: the tile's object in the same layer, or one of its game
     * objects, is the same loc.
     *
     * @param object the loc
     * @param tile   its tile
     * @return true if it is still there
     */
    private static boolean isPlaced(TileObject object, Tile tile) {
        return switch (object) {
            case GameObject game -> Arrays.asList(tile.getGameObjects()).contains(game);
            case WallObject wall -> wall.equals(tile.getWallObject());
            case DecorativeObject decor -> decor.equals(tile.getDecorativeObject());
            case GroundObject ground -> ground.equals(tile.getGroundObject());
        };
    }

    /**
     * Whether a component is a pause button ("Click here to continue"): an old-style button of type 6, or one whose
     * click mask has the pause bit ({@link WidgetConfig#PAUSE_BUTTON}), as the client's menu tells them apart. The
     * button type is in the snapshot, so it is tested first; the click mask takes a read.
     *
     * @param widget the component
     * @return true for a pause button
     */
    public static boolean isPauseButton(Widget widget) {
        if (widget.getEvent() == PAUSE_BUTTON) {
            return true;
        }
        int mask = widget.getClickMask();
        return mask != -1 && (mask & WidgetConfig.PAUSE_BUTTON) != 0;
    }

    /**
     * The first of several components that is loaded and showing, else the first that is loaded, such as a minimap
     * orb, which has one component beside the minimap and one in the interface without it.
     *
     * @param componentIds the components' packed ids, the preferred first
     * @return the widget, or null if none is loaded
     */
    public Widget firstShown(int... componentIds) {
        Widget loaded = null;
        for (int id : componentIds) {
            Widget widget = client.getWidget(id);
            if (widget == null) {
                continue;
            }
            if (!widget.isHidden()) {
                return widget;
            }
            if (loaded == null) {
                loaded = widget;
            }
        }
        return loaded;
    }

    /**
     * Whether a click mask has a bit. A mask that can't be read (-1) has them all.
     *
     * @param mask the click mask
     * @param bits the bits wanted
     * @return true if all are set
     */
    private static boolean hasBits(int mask, int bits) {
        return (mask & bits) == bits;
    }

    private int[] scene(WorldPoint tile) {
        return new int[]{tile.getX() - client.getTopLevelWorldView().getBaseX(),
                tile.getY() - client.getTopLevelWorldView().getBaseY()};
    }

    private static String named(String name, String kind) {
        String plain = OptionMatcher.plain(name);
        return plain.isEmpty() || plain.equalsIgnoreCase("null") ? "an unnamed " + kind : plain;
    }

    /**
     * A subject as the interaction methods see it.
     *
     * @param what    the subject, for the log
     * @param gone    why it counts as gone, or null while it is there
     * @param options its options by op, or null if they can't be read
     * @param max     the most ops it has
     * @param row     the row an op runs, with the click point the policy asks for
     */
    private record Subject(String what, String gone, String[] options, int max, IntFunction<ActionRequest> row) {

        static Subject gone(String what, String why, IntFunction<ActionRequest> row) {
            return new Subject(what, what + " " + why, null, 0, row);
        }
    }
}
