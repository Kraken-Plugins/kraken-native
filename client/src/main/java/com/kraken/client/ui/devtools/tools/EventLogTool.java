package com.kraken.client.ui.devtools.tools;

import com.kraken.api.Client;
import com.kraken.api.KrakenApi;
import com.kraken.api.actor.Actor;
import com.kraken.api.actor.NPC;
import com.kraken.api.actor.Player;
import com.kraken.api.coords.LocalPoint;
import com.kraken.api.coords.WorldPoint;
import com.kraken.api.eventbus.Subscriber;
import com.kraken.api.events.CanvasSizeChanged;
import com.kraken.api.events.GameStateChanged;
import com.kraken.api.events.GameTick;
import com.kraken.api.events.InteractingChanged;
import com.kraken.api.events.ItemContainerChanged;
import com.kraken.api.events.ItemDespawned;
import com.kraken.api.events.ItemQuantityChanged;
import com.kraken.api.events.ItemSpawned;
import com.kraken.api.events.NpcDespawned;
import com.kraken.api.events.NpcSpawned;
import com.kraken.api.events.PlayerDespawned;
import com.kraken.api.events.PlayerSpawned;
import com.kraken.api.events.ProjectileMoved;
import com.kraken.api.events.StatChanged;
import com.kraken.api.events.TileObjectDespawned;
import com.kraken.api.events.TileObjectSpawned;
import com.kraken.api.events.VarbitChanged;
import com.kraken.api.item.Item;
import com.kraken.api.item.ItemContainer;
import com.kraken.api.scene.Tile;
import com.kraken.api.scene.TileItem;
import com.kraken.api.scene.TileObject;
import com.kraken.client.ui.devtools.Category;
import com.kraken.client.ui.devtools.DevTool;
import com.kraken.client.ui.devtools.ToolOption;
import com.kraken.client.ui.devtools.view.LogView;
import com.kraken.client.ui.devtools.view.ToolView;
import org.kordamp.ikonli.materialdesign2.MaterialDesignL;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * A live log of the API's events, as they're seen on its event bus, each kind while its option is on. Subscribes only
 * to the kinds switched on, and only while its window is open: the scene's objects are read once per game tick while
 * anything listens for object spawns, and the ground items on ticks, so those two are off by default.
 */
final class EventLogTool extends DevTool<List<String>> {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final int MAX_PENDING = 1_000;
    private static final int MAX_ITEMS = 8;

    private static final String GAME_STATE = "gameState";
    private static final String NPCS = "npcs";
    private static final String PLAYERS = "players";
    private static final String OBJECTS = "objects";
    private static final String ITEMS = "items";
    private static final String INTERACTING = "interacting";
    private static final String PROJECTILES = "projectiles";
    private static final String INVENTORIES = "inventories";
    private static final String STATS = "stats";
    private static final String VARS = "vars";
    private static final String TICKS = "ticks";
    private static final String RESIZE = "resize";

    private final ToolOption gameState = option("gameState", "Game state changes", true);
    private final ToolOption npcs = option("npcs", "NPC spawns", true);
    private final ToolOption players = option("players", "Player spawns", true);
    private final ToolOption objectSpawns = option("objectSpawns", "Object spawns", false);
    private final ToolOption itemSpawns = option("itemSpawns", "Ground item spawns", false);
    private final ToolOption interacting = option("interacting", "Interacting changes", false);
    private final ToolOption projectiles = option("projectiles", "Projectiles", true);
    private final ToolOption inventories = option("inventories", "Inventory transmits", true);
    private final ToolOption stats = option("stats", "Stat transmits", true);
    private final ToolOption vars = option("vars", "Var transmits", false);
    private final ToolOption varbits = option("varbits", "Varbit updates", false);
    private final ToolOption ticks = option("ticks", "Game ticks", false);
    private final ToolOption resize = option("resize", "Screen resizes", true);

    private final Queue<String> pending = new ConcurrentLinkedQueue<>();
    /**
     * Each kind's subscriptions, by kind name. Written on the poll thread (and on stop, under the lock).
     */
    private final Map<String, List<Subscriber>> subscriptions = new HashMap<>();
    private KrakenApi api;

    EventLogTool() {
        super("events", "Event log", Category.DIAGNOSTICS, MaterialDesignL.LIGHTNING_BOLT_OUTLINE,
                "A live log of the API's events (game state, NPC and player spawns, objects, ground items, targets, "
                        + "projectiles, inventories, stats, vars, varbits, ticks and resizes) as they're seen.");
    }

    @Override
    public Support windowSupport() {
        return Support.READY;
    }

    @Override
    protected synchronized void onStart(KrakenApi api) {
        this.api = api;
    }

    @Override
    protected synchronized void onStop() {
        if (api != null) {
            for (List<Subscriber> held : subscriptions.values()) {
                drop(held);
            }
        }
        subscriptions.clear();
        api = null;
        pending.clear();
    }

    @Override
    protected List<String> read(KrakenApi api) {
        syncSubscriptions();
        if (pending.isEmpty()) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        for (String line; (line = pending.poll()) != null; ) {
            lines.add(line);
        }
        return lines;
    }

    @Override
    protected ToolView<List<String>> createView() {
        return new LogView();
    }

    /**
     * Subscribes each kind of event while one of its options is on, and drops it once they're all off. Poll thread.
     */
    private synchronized void syncSubscriptions() {
        if (api == null) {
            return;
        }
        keep(GAME_STATE, gameState.isOn(), this::subscribeGameState);
        keep(NPCS, npcs.isOn(), this::subscribeNpcs);
        keep(PLAYERS, players.isOn(), this::subscribePlayers);
        keep(OBJECTS, objectSpawns.isOn(), this::subscribeObjects);
        keep(ITEMS, itemSpawns.isOn(), this::subscribeItems);
        keep(INTERACTING, interacting.isOn(), this::subscribeInteracting);
        keep(PROJECTILES, projectiles.isOn(), this::subscribeProjectiles);
        keep(INVENTORIES, inventories.isOn(), this::subscribeInventories);
        keep(STATS, stats.isOn(), this::subscribeStats);
        keep(VARS, vars.isOn() || varbits.isOn(), this::subscribeVars);
        keep(TICKS, ticks.isOn(), this::subscribeTicks);
        keep(RESIZE, resize.isOn(), this::subscribeResize);
    }

    /**
     * Subscribes a kind if it's wanted and not yet subscribed, or drops it if it isn't wanted any more.
     *
     * @param kind      the kind's name
     * @param wanted    whether its option is on
     * @param subscribe subscribes its event types
     */
    private void keep(String kind, boolean wanted, Runnable subscribe) {
        List<Subscriber> held = subscriptions.computeIfAbsent(kind, k -> new ArrayList<>());
        if (wanted && held.isEmpty()) {
            subscribe.run();
        } else if (!wanted && !held.isEmpty()) {
            drop(held);
        }
    }

    private void subscribeGameState() {
        on(GAME_STATE, GameStateChanged.class, e -> {
            if (gameState.isOn()) {
                add("GameStateChanged  " + e.getGameState() + " (" + e.getGameState().getState() + ")");
            }
        });
    }

    private void subscribeNpcs() {
        on(NPCS, NpcSpawned.class, e -> {
            if (npcs.isOn()) {
                add("NpcSpawned  " + npc(e.getNpc()));
            }
        });
        on(NPCS, NpcDespawned.class, e -> {
            if (npcs.isOn()) {
                add("NpcDespawned  " + npc(e.getNpc()));
            }
        });
    }

    private void subscribePlayers() {
        on(PLAYERS, PlayerSpawned.class, e -> {
            if (players.isOn()) {
                add("PlayerSpawned  " + player(e.getPlayer()));
            }
        });
        on(PLAYERS, PlayerDespawned.class, e -> {
            if (players.isOn()) {
                add("PlayerDespawned  " + player(e.getPlayer()));
            }
        });
    }

    /**
     * Subscribes to the four object spawn and despawn events, through the two interfaces they share.
     */
    private void subscribeObjects() {
        Client client = api.client();
        on(OBJECTS, TileObjectSpawned.class, e ->
                add(e.getClass().getSimpleName() + "  " + object(client, e.getTileObject())));
        on(OBJECTS, TileObjectDespawned.class, e ->
                add(e.getClass().getSimpleName() + "  " + object(client, e.getTileObject())));
    }

    private void subscribeItems() {
        on(ITEMS, ItemSpawned.class, e -> add("ItemSpawned  " + item(e.getItem(), e.getTile())));
        on(ITEMS, ItemDespawned.class, e -> add("ItemDespawned  " + item(e.getItem(), e.getTile())));
        on(ITEMS, ItemQuantityChanged.class, e -> add("ItemQuantityChanged  " + e.getItem().getId() + " "
                + e.getOldQuantity() + " → " + e.getNewQuantity() + at(e.getTile().getWorldLocation())));
    }

    private void subscribeInteracting() {
        on(INTERACTING, InteractingChanged.class, e -> {
            if (interacting.isOn()) {
                add("InteractingChanged  " + actor(e.getSource()) + " → " + actor(e.getTarget()));
            }
        });
    }

    private void subscribeProjectiles() {
        on(PROJECTILES, ProjectileMoved.class, e -> {
            if (projectiles.isOn()) {
                add("ProjectileMoved  " + e.getProjectile().getId() + " to " + local(e.getPosition()) + " z "
                        + e.getZ());
            }
        });
    }

    private void subscribeInventories() {
        on(INVENTORIES, ItemContainerChanged.class, e -> {
            if (inventories.isOn()) {
                add("ItemContainerChanged  " + container(e.getContainerId(), e.getItemContainer()));
            }
        });
    }

    private void subscribeStats() {
        on(STATS, StatChanged.class, e -> {
            if (stats.isOn()) {
                add("StatChanged  " + e.getSkill().getName() + " " + e.getBoostedLevel() + "/" + e.getLevel() + " "
                        + e.getXp() + "xp");
            }
        });
    }

    private void subscribeVars() {
        on(VARS, VarbitChanged.class, e -> {
            if (e.getVarbitId() == -1 && vars.isOn()) {
                add("VarbitChanged  varp " + e.getVarpId() + " = " + e.getValue());
            } else if (e.getVarbitId() != -1 && varbits.isOn()) {
                add("VarbitChanged  varbit " + e.getVarbitId() + " (varp " + e.getVarpId() + ") = " + e.getValue());
            }
        });
    }

    private void subscribeTicks() {
        Client client = api.client();
        on(TICKS, GameTick.class, e -> {
            if (ticks.isOn()) {
                add("GameTick  " + client.getTickCount());
            }
        });
    }

    private void subscribeResize() {
        Client client = api.client();
        on(RESIZE, CanvasSizeChanged.class, e -> {
            if (resize.isOn()) {
                add("CanvasSizeChanged  client " + client.getCanvasWidth() + " x " + client.getCanvasHeight()
                        + ", window " + client.getScreenWidth() + " x " + client.getScreenHeight());
            }
        });
    }

    /**
     * Subscribes a handler on the API's event bus, keeping the subscription under a kind.
     *
     * @param kind    the kind's name
     * @param type    the event type
     * @param handler the handler, run on the poll thread
     * @param <T>     the event type
     */
    private <T> void on(String kind, Class<T> type, Consumer<T> handler) {
        subscriptions.get(kind).add(api.eventBus().register(type, handler, 0f));
    }

    /**
     * Drops a list of event bus subscriptions.
     *
     * @param held the subscriptions; emptied
     */
    private void drop(List<Subscriber> held) {
        held.forEach(api.eventBus()::unregister);
        held.clear();
    }

    /**
     * An NPC, for a line.
     *
     * @param npc the NPC
     * @return its index, name, id and tile
     */
    private static String npc(NPC npc) {
        return "#" + npc.getIndex() + "  " + npc.getName() + " (" + npc.getId() + ")" + at(npc);
    }

    /**
     * A player, for a line.
     *
     * @param player the player
     * @return its id, name and tile
     */
    private static String player(Player player) {
        return "#" + player.getId() + "  " + player.getName() + at(player);
    }

    /**
     * Where an actor stands, for a line.
     *
     * @param actor the actor
     * @return " at (x, y, plane)", or "" if unknown
     */
    private static String at(Actor actor) {
        return at(actor.getWorldLocation());
    }

    /**
     * A world tile, for a line.
     *
     * @param point the tile, or null
     * @return " at (x, y, plane)", or "" if null
     */
    private static String at(WorldPoint point) {
        return point == null ? "" : " at (" + point.getX() + ", " + point.getY() + ", " + point.getPlane() + ")";
    }

    /**
     * An NPC or player, for a line.
     *
     * @param actor the actor, or null
     * @return "NPC ..." or "player ...", or "nothing" for null
     */
    private static String actor(Actor actor) {
        if (actor instanceof NPC npc) {
            return "NPC " + npc(npc);
        }
        if (actor instanceof Player player) {
            return "player " + player(player);
        }
        return actor == null ? "nothing" : actor.getName();
    }

    /**
     * A local point, for a line.
     *
     * @param point the point, or null
     * @return "local (x, y)", or "?"
     */
    private static String local(LocalPoint point) {
        return point == null ? "?" : "local (" + point.getX() + ", " + point.getY() + ")";
    }

    /**
     * An object, for a line: its id, its name from its definition, and where it is.
     *
     * @param client the client, for the definition
     * @param object the object
     * @return the description
     */
    private static String object(Client client, TileObject object) {
        String name = Scenery.name(client, object.getId());
        return object.getId() + (name.isEmpty() ? "" : " " + name) + at(object.getWorldLocation());
    }

    /**
     * A ground item, for a line.
     *
     * @param item the item stack
     * @param tile the tile it lies on
     * @return its id, quantity and tile
     */
    private static String item(TileItem item, Tile tile) {
        return item.getId() + " ×" + item.getQuantity() + at(tile.getWorldLocation());
    }

    /**
     * An item container, for a line: its occupancy and the first few items.
     *
     * @param id        the container's id
     * @param container the container
     * @return the description
     */
    private static String container(int id, ItemContainer container) {
        Item[] items = container.getItems();
        List<String> held = IntStream.range(0, items.length).filter(slot -> items[slot].getId() != -1)
                .mapToObj(slot -> "slot " + slot + ": " + items[slot].getId() + " ×" + items[slot].getQuantity())
                .toList();
        String slots = held.stream().limit(MAX_ITEMS).collect(Collectors.joining(" · "));
        String more = held.size() > MAX_ITEMS ? " · +" + (held.size() - MAX_ITEMS) + " more" : "";
        return id + "  " + container.count() + "/" + container.size() + (held.isEmpty() ? "" : "  " + slots + more);
    }

    /**
     * Queues a log line. Event callbacks run on the poll thread; {@link #read} drains these on the same thread.
     *
     * @param line the log line, without the timestamp
     */
    private void add(String line) {
        pending.add(TIME.format(LocalTime.now()) + "  " + line);
        while (pending.size() > MAX_PENDING) {
            pending.poll();
        }
    }
}
