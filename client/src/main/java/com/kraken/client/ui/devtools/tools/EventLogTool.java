package com.kraken.client.ui.devtools.tools;

import com.kraken.api.Actor;
import com.kraken.api.Item;
import com.kraken.api.ItemContainer;
import com.kraken.api.KrakenApi;
import com.kraken.api.NPC;
import com.kraken.api.ObjectComposition;
import com.kraken.api.Player;
import com.kraken.api.Tile;
import com.kraken.api.TileItem;
import com.kraken.api.TileObject;
import com.kraken.api.coords.LocalPoint;
import com.kraken.api.coords.WorldPoint;
import com.kraken.api.eventbus.EventBus;
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
import com.kraken.api.lua.ClientOp;
import com.kraken.api.lua.ClientState;
import com.kraken.api.lua.InvItem;
import com.kraken.api.lua.Inventory;
import com.kraken.api.lua.events.Events;
import com.kraken.api.lua.events.StatUpdate;
import com.kraken.client.ui.devtools.Category;
import com.kraken.client.ui.devtools.DevTool;
import com.kraken.client.ui.devtools.ToolOption;
import com.kraken.client.ui.devtools.view.LogView;
import com.kraken.client.ui.devtools.view.ToolView;
import org.kordamp.ikonli.materialdesign2.MaterialDesignL;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * A live log of the API's events: the Lua events ({@code osrs.Events}, as Lua plugins get them) and the RuneLite-shaped
 * events on the API's event bus, each layer while its option is on. Lines for RuneLite events start with the event's
 * class name. Subscribes only while its window is open, and only to the layers switched on. The RuneLite object and
 * ground item spawns have options of their own, off by default: while subscribed, the API reads the whole scene's
 * objects every tick and its item piles on ticks.
 */
final class EventLogTool extends DevTool<List<String>> {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final int MAX_PENDING = 1_000;
    private static final int MAX_ITEMS = 8;

    private final ToolOption lua = option("lua", "Lua events", true);
    private final ToolOption runelite = option("runelite", "RuneLite events", false);
    private final ToolOption gameState = option("gameState", "Game state changes", true);
    private final ToolOption npcs = option("npcs", "NPC load / unload", true);
    private final ToolOption players = option("players", "Player spawns (RuneLite only)", true);
    private final ToolOption objectSpawns = option("objectSpawns", "Object spawns (RuneLite only)", false);
    private final ToolOption itemSpawns = option("itemSpawns", "Ground item spawns (RuneLite only)", false);
    private final ToolOption interacting = option("interacting", "Interacting changes (RuneLite only)", false);
    private final ToolOption projectiles = option("projectiles", "Projectiles (RuneLite only)", true);
    private final ToolOption inventories = option("inventories", "Inventory transmits", true);
    private final ToolOption stats = option("stats", "Stat transmits", true);
    private final ToolOption vars = option("vars", "Var transmits", false);
    private final ToolOption varbits = option("varbits", "Varbit updates", false);
    private final ToolOption ticks = option("ticks", "Game ticks", false);
    private final ToolOption map = option("map", "Map rebuilds (Lua only)", true);
    private final ToolOption locs = option("locs", "Loc load / unload (Lua only)", false);
    private final ToolOption resize = option("resize", "Screen resizes", true);
    private final Queue<String> pending = new ConcurrentLinkedQueue<>();
    private final List<Integer> luaSubscriptions = new ArrayList<>();
    private final List<Integer> locSubscriptions = new ArrayList<>();
    private final List<Subscriber> runeliteSubscriptions = new ArrayList<>();
    private final List<Subscriber> objectSubscriptions = new ArrayList<>();
    private final List<Subscriber> itemSubscriptions = new ArrayList<>();
    private KrakenApi api;

    EventLogTool() {
        super("events", "Event log", Category.DIAGNOSTICS, MaterialDesignL.LIGHTNING_BOLT_OUTLINE,
                "A live log of API events (game state, NPCs, players, inventories, stats, vars, varbits, ticks, map "
                        + "rebuilds, locs, resizes; objects, ground items, targets and projectiles in the RuneLite "
                        + "layer) as they're seen, from the Lua events, the RuneLite events, or both.");
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
            unsubscribeLua();
            unsubscribeLocs();
            unsubscribeRuneLite();
            unsubscribe(objectSubscriptions);
            unsubscribe(itemSubscriptions);
        }
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
     * Subscribes to each layer only while its option is on, so a layer that's off costs no reads. Loc events, and the
     * RuneLite object and ground item spawns, also wait for their own options: loc events make the Lua poller compare
     * the whole scene every tick, and the RuneLite spawns make the API do the same for objects and read every pile on
     * ticks. Poll thread.
     */
    private synchronized void syncSubscriptions() {
        if (api == null) {
            return;
        }
        if (lua.isOn() && luaSubscriptions.isEmpty()) {
            subscribeLua();
        } else if (!lua.isOn() && !luaSubscriptions.isEmpty()) {
            unsubscribeLua();
        }
        boolean wantLocs = lua.isOn() && locs.isOn();
        if (wantLocs && locSubscriptions.isEmpty()) {
            subscribeLocs();
        } else if (!wantLocs && !locSubscriptions.isEmpty()) {
            unsubscribeLocs();
        }
        if (runelite.isOn() && runeliteSubscriptions.isEmpty()) {
            subscribeRuneLite();
        } else if (!runelite.isOn() && !runeliteSubscriptions.isEmpty()) {
            unsubscribeRuneLite();
        }
        boolean wantObjects = runelite.isOn() && objectSpawns.isOn();
        if (wantObjects && objectSubscriptions.isEmpty()) {
            subscribeObjects();
        } else if (!wantObjects && !objectSubscriptions.isEmpty()) {
            unsubscribe(objectSubscriptions);
        }
        boolean wantItems = runelite.isOn() && itemSpawns.isOn();
        if (wantItems && itemSubscriptions.isEmpty()) {
            subscribeItems();
        } else if (!wantItems && !itemSubscriptions.isEmpty()) {
            unsubscribe(itemSubscriptions);
        }
    }

    private void subscribeLua() {
        Events events = api.lua().events();
        ClientState state = api.lua().clientState();
        luaSubscriptions.add(events.subscribe(Events.ON_GAME_STATE_CHANGED, trigger -> {
            if (gameState.isOn()) {
                add("Game state  " + trigger + " (" + trigger.value() + ")");
            }
        }));
        luaSubscriptions.add(events.subscribe(Events.ON_NPC_LOAD, (name, index) -> npc("NPC loaded", name, index)));
        luaSubscriptions.add(events.subscribe(Events.ON_NPC_UNLOAD, (name, index) -> npc("NPC unloaded", name, index)));
        luaSubscriptions.add(events.subscribe(Events.ON_INV_TRANSMIT, updated -> {
            if (inventories.isOn()) {
                updated.forEach(inventory -> add(inventory(inventory)));
            }
        }));
        luaSubscriptions.add(events.subscribe(Events.ON_STAT_TRANSMIT, updated -> {
            if (stats.isOn()) {
                add("Stats  " + updated.stream().map(EventLogTool::stat).collect(Collectors.joining(" · ")));
            }
        }));
        luaSubscriptions.add(events.subscribe(Events.ON_VAR_TRANSMIT, ids -> {
            if (vars.isOn()) {
                StringBuilder line = new StringBuilder("Varps  ");
                for (int i = 0; i < ids.length; i++) {
                    line.append(i == 0 ? "" : ", ").append(ids[i]).append(" = ").append(state.getVarp(ids[i]));
                }
                add(line.toString());
            }
        }));
        luaSubscriptions.add(events.subscribe(Events.ON_VARBIT_UPDATE, (id, value) -> {
            if (varbits.isOn()) {
                add("Varbit  " + id + " = " + value);
            }
        }));
        luaSubscriptions.add(events.subscribe(Events.ON_GAME_TICK, () -> {
            if (ticks.isOn()) {
                add("Game tick  " + state.getTickCount());
            }
        }));
        luaSubscriptions.add(events.subscribe(Events.ON_MAP_REBUILD_PRE, () -> {
            if (map.isOn()) {
                add("Map rebuild started");
            }
        }));
        luaSubscriptions.add(events.subscribe(Events.ON_MAP_REBUILD_POST, () -> {
            if (map.isOn()) {
                add("Map rebuilt  base " + api.lua().scene().getBase());
            }
        }));
        luaSubscriptions.add(events.subscribe(Events.ON_SCREEN_RESIZE, () -> {
            if (resize.isOn()) {
                add("Screen resized  " + sizes());
            }
        }));
    }

    private void unsubscribeLua() {
        luaSubscriptions.forEach(api.lua().events()::unsubscribe);
        luaSubscriptions.clear();
    }

    private void subscribeLocs() {
        Events events = api.lua().events();
        ClientOp client = api.lua().clientOp();
        locSubscriptions.add(events.subscribe(Events.ON_LOC_LOAD, (id, name, layer) ->
                add("Loc loaded  " + id + " " + name + " (layer " + layer + ") at " + client.locCoord())));
        locSubscriptions.add(events.subscribe(Events.ON_LOC_UNLOAD, (id, name, layer) ->
                add("Loc unloaded  " + id + " " + name + " (layer " + layer + ") at " + client.locCoord())));
    }

    private void unsubscribeLocs() {
        locSubscriptions.forEach(api.lua().events()::unsubscribe);
        locSubscriptions.clear();
    }

    private void subscribeRuneLite() {
        KrakenApi attached = api;
        on(GameStateChanged.class, e -> {
            if (gameState.isOn()) {
                add("GameStateChanged  " + e.getGameState());
            }
        });
        on(NpcSpawned.class, e -> {
            if (npcs.isOn()) {
                add("NpcSpawned  " + npc(e.getNpc()));
            }
        });
        on(NpcDespawned.class, e -> {
            if (npcs.isOn()) {
                add("NpcDespawned  " + npc(e.getNpc()));
            }
        });
        on(PlayerSpawned.class, e -> {
            if (players.isOn()) {
                add("PlayerSpawned  " + player(e.getPlayer()));
            }
        });
        on(PlayerDespawned.class, e -> {
            if (players.isOn()) {
                add("PlayerDespawned  " + player(e.getPlayer()));
            }
        });
        on(ItemContainerChanged.class, e -> {
            if (inventories.isOn()) {
                add("ItemContainerChanged  " + container(e.getContainerId(), e.getItemContainer()));
            }
        });
        on(StatChanged.class, e -> {
            if (stats.isOn()) {
                add("StatChanged  " + e.getSkill().getName() + " " + e.getBoostedLevel() + "/" + e.getLevel() + " "
                        + e.getXp() + "xp");
            }
        });
        on(VarbitChanged.class, e -> {
            if (e.getVarbitId() == -1 && vars.isOn()) {
                add("VarbitChanged  varp " + e.getVarpId() + " = " + e.getValue());
            } else if (e.getVarbitId() != -1 && varbits.isOn()) {
                add("VarbitChanged  varbit " + e.getVarbitId() + " (varp " + e.getVarpId() + ") = " + e.getValue());
            }
        });
        on(GameTick.class, e -> {
            if (ticks.isOn()) {
                add("GameTick  " + attached.client().getTickCount());
            }
        });
        on(CanvasSizeChanged.class, e -> {
            if (resize.isOn()) {
                add("CanvasSizeChanged  " + sizes());
            }
        });
        on(InteractingChanged.class, e -> {
            if (interacting.isOn()) {
                add("InteractingChanged  " + actor(e.getSource()) + " → " + actor(e.getTarget()));
            }
        });
        on(ProjectileMoved.class, e -> {
            if (projectiles.isOn()) {
                add("ProjectileMoved  " + e.getProjectile().getId() + " to " + local(e.getPosition()) + " z "
                        + e.getZ());
            }
        });
    }

    /**
     * Subscribes to the four object spawn and despawn events, through the two interfaces they share.
     */
    private void subscribeObjects() {
        KrakenApi attached = api;
        on(objectSubscriptions, TileObjectSpawned.class, e ->
                add(e.getClass().getSimpleName() + "  " + object(attached, e.getTileObject())));
        on(objectSubscriptions, TileObjectDespawned.class, e ->
                add(e.getClass().getSimpleName() + "  " + object(attached, e.getTileObject())));
    }

    /**
     * Subscribes to the ground item spawn, despawn and quantity events.
     */
    private void subscribeItems() {
        on(itemSubscriptions, ItemSpawned.class, e -> add("ItemSpawned  " + item(e.getItem(), e.getTile())));
        on(itemSubscriptions, ItemDespawned.class, e -> add("ItemDespawned  " + item(e.getItem(), e.getTile())));
        on(itemSubscriptions, ItemQuantityChanged.class, e -> add("ItemQuantityChanged  " + e.getItem().getId()
                + " " + e.getOldQuantity() + " → " + e.getNewQuantity() + at(e.getTile().getWorldLocation())));
    }

    private <T> void on(Class<T> type, Consumer<T> handler) {
        on(runeliteSubscriptions, type, handler);
    }

    /**
     * Subscribes a handler on the API's event bus.
     *
     * @param into    the list to keep the subscription in, for unsubscribing
     * @param type    the event type
     * @param handler the handler, run on the poll thread
     * @param <T>     the event type
     */
    private <T> void on(List<Subscriber> into, Class<T> type, Consumer<T> handler) {
        into.add(api.eventBus().register(type, handler, 0));
    }

    private void unsubscribeRuneLite() {
        unsubscribe(runeliteSubscriptions);
    }

    /**
     * Drops a list of event bus subscriptions.
     *
     * @param subscriptions the subscriptions; emptied
     */
    private void unsubscribe(List<Subscriber> subscriptions) {
        EventBus bus = api.eventBus();
        subscriptions.forEach(bus::unregister);
        subscriptions.clear();
    }

    private String sizes() {
        return "client " + api.lua().graphics().clientDimensions() + ", window " + api.lua().graphics().screenSize();
    }

    private void npc(String what, String name, int index) {
        if (npcs.isOn()) {
            add(what + "  #" + index + (name == null || name.isEmpty() ? "" : "  " + name));
        }
    }

    private static String npc(NPC npc) {
        return "#" + npc.getIndex() + "  " + npc.getName() + " (" + npc.getId() + ")" + at(npc);
    }

    private static String player(Player player) {
        return "#" + player.getId() + "  " + player.getName() + at(player);
    }

    private static String at(Actor actor) {
        return at(actor.getWorldLocation());
    }

    private static String at(WorldPoint point) {
        return point == null ? "" : " at (" + point.getX() + ", " + point.getY() + ", " + point.getPlane() + ")";
    }

    /**
     * An NPC or player, for a line.
     *
     * @param actor the actor, or null
     * @return "NPC …" or "player …", or "nothing" for null
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

    private static String local(LocalPoint point) {
        return point == null ? "?" : "local (" + point.getX() + ", " + point.getY() + ")";
    }

    /**
     * An object, for a line: its id, its name from its definition (decoded once and cached by the API), and where it
     * is.
     *
     * @param api    the API, for the definition
     * @param object the object
     * @return the description
     */
    private static String object(KrakenApi api, TileObject object) {
        ObjectComposition composition = api.client().getObjectDefinition(object.getId());
        String name = composition == null || composition.getName().isEmpty() ? "" : " " + composition.getName();
        return object.getId() + name + at(object.getWorldLocation());
    }

    private static String item(TileItem item, Tile tile) {
        return item.getId() + " ×" + item.getQuantity() + at(tile.getWorldLocation());
    }

    private static String container(int id, ItemContainer container) {
        Item[] items = container.getItems();
        List<String> held = IntStream.range(0, items.length).filter(slot -> items[slot].getId() != -1)
                .mapToObj(slot -> "slot " + slot + ": " + items[slot].getId() + " ×" + items[slot].getQuantity())
                .toList();
        String slots = held.stream().limit(MAX_ITEMS).collect(Collectors.joining(" · "));
        String more = held.size() > MAX_ITEMS ? " · +" + (held.size() - MAX_ITEMS) + " more" : "";
        return id + "  " + container.count() + "/" + container.size() + (held.isEmpty() ? "" : "  " + slots + more);
    }

    private static String inventory(Inventory inventory) {
        List<InvItem> items = inventory.items();
        String slots = items.stream().limit(MAX_ITEMS)
                .map(i -> "slot " + i.slot() + ": " + (i.isEmpty() ? "empty" : i.id() + " ×" + i.count()))
                .collect(Collectors.joining(" · "));
        String more = items.size() > MAX_ITEMS ? " · +" + (items.size() - MAX_ITEMS) + " more" : "";
        return "Inventory " + inventory.id() + "  " + slots + more;
    }

    private static String stat(StatUpdate update) {
        ClientState.Stats stat = ClientState.Stats.of(update.id());
        String name = stat == null ? "#" + update.id() : stat.displayName();
        return name + " " + update.value() + "/" + update.max() + " " + update.xp() + "xp";
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
