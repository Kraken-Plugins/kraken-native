package com.kraken.client.worldhopper;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.kraken.api.ChatMessageType;
import com.kraken.api.Client;
import com.kraken.api.GameState;
import com.kraken.api.Skill;
import com.kraken.api.World;
import com.kraken.api.eventbus.EventBus;
import com.kraken.api.events.ChatMessage;
import com.kraken.api.events.GameStateChanged;
import com.kraken.api.events.GameTick;
import com.kraken.api.gameval.InterfaceID;
import com.kraken.api.widgets.WidgetNode;
import com.kraken.client.callback.ClientThread;
import com.kraken.client.input.HotkeyListener;
import com.kraken.client.input.KeyManager;
import lombok.extern.java.Log;

import javax.swing.*;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Hops worlds the way the world switcher does. A hop opens the world switcher if it isn't open (the logout panel's
 * World Switcher button), waits for the server to open it, then clicks the world's row; the server makes the hop.
 * Each step waits for a game tick, and a switcher that hasn't opened after three ticks gives up. The quick-hop keys
 * ({@link WorldHopperConfig#previousKey}, {@link WorldHopperConfig#nextKey}) hop to the previous or next world of the
 * same kind ({@link QuickHop}).
 *
 * <p>Hops work only while logged in and while the client's native hook is connected, since the clicks are menu
 * actions the hook runs. What the hopper is doing is in {@link #getStatus()}, and in the console.
 *
 * <p>A session service: it lives in the session injector. Hops can be asked for from any thread; the work happens on
 * the client thread.
 */
@Log(topic = "kraken")
@Singleton
public class WorldHopper {

    /**
     * How many ticks a hop waits for the world switcher to open before giving up.
     */
    static final int SWITCHER_ATTEMPTS = 3;

    /**
     * How many ticks after clicking a world's row the client may stay on its world before the hop counts as refused.
     */
    static final int SWITCH_TICKS = 10;

    /**
     * The game message the server sends when it won't let the player use the world switcher now.
     */
    static final String BUSY_MESSAGE = "Please finish what you're doing before using the World Switcher.";

    private final Client client;
    private final ClientThread clientThread;
    private final KeyManager keyManager;
    private final EventBus eventBus;
    private final WorldService worldService;
    private final WorldHopperConfig config;
    private final List<Runnable> statusListeners = new CopyOnWriteArrayList<>();

    /**
     * The world a hop is under way to, until its row is clicked. Client thread only.
     */
    private World target;

    /**
     * How many times this hop has asked for the world switcher. Client thread only.
     */
    private int attempts;

    /**
     * The world whose row was last clicked, until the client arrives or the server refuses. Client thread only.
     */
    private World switching;

    /**
     * Ticks since the row of {@link #switching} was clicked. Client thread only.
     */
    private int switchTicks;

    private volatile String status;
    private volatile long statusNanos;
    private volatile boolean hopping;
    private boolean started;

    @Inject
    WorldHopper(Client client, ClientThread clientThread, KeyManager keyManager, EventBus eventBus,
                WorldService worldService, WorldHopperConfig config) {
        this.client = client;
        this.clientThread = clientThread;
        this.keyManager = keyManager;
        this.eventBus = eventBus;
        this.worldService = worldService;
        this.config = config;
    }

    /**
     * Starts listening for the quick-hop keys and the game events a hop follows, and starts fetching the world list.
     * Later calls do nothing.
     */
    public synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        eventBus.register(GameTick.class, event -> onGameTick(), 0);
        eventBus.register(GameStateChanged.class, this::onGameStateChanged, 0);
        eventBus.register(ChatMessage.class, this::onChatMessage, 0);
        keyManager.registerHotkeyListener(new HotkeyListener(config::previousKey) {
            @Override
            public void hotkeyPressed() {
                quickHop(true);
            }
        });
        keyManager.registerHotkeyListener(new HotkeyListener(config::nextKey) {
            @Override
            public void hotkeyPressed() {
                quickHop(false);
            }
        });
        worldService.start();
    }

    /**
     * Hops to a world. Any thread.
     *
     * @param world the world
     */
    public void hopTo(World world) {
        clientThread.invoke(() -> hop(world));
    }

    /**
     * Hops to the next or previous world of the same kind as the current one. Any thread.
     *
     * @param previous true for the previous world, false for the next
     */
    public void quickHop(boolean previous) {
        clientThread.invoke(() -> hopNext(previous));
    }

    /**
     * What the hopper did last, such as "Hopping to world 302…" or why a hop couldn't start.
     *
     * @return the status, or null when there's nothing to say
     */
    public String getStatus() {
        return status;
    }

    /**
     * How long ago {@link #getStatus()} was set.
     *
     * @return the age in milliseconds
     */
    public long getStatusAgeMillis() {
        return (System.nanoTime() - statusNanos) / 1_000_000;
    }

    /**
     * Whether a hop is under way: waiting for the world switcher, or for the server after clicking a world.
     *
     * @return true until the hop arrives, fails or gives up
     */
    public boolean isHopping() {
        return hopping;
    }

    /**
     * Runs a listener on the EDT whenever {@link #getStatus()} changes.
     *
     * @param listener the listener
     */
    public void addStatusListener(Runnable listener) {
        statusListeners.add(listener);
    }

    private void hopNext(boolean previous) {
        List<World> worlds = worldService.getWorlds();
        if (worlds.isEmpty()) {
            setStatus("The world list hasn't loaded yet");
            worldService.refresh();
            return;
        }
        if (!readyToHop()) {
            return;
        }
        int current = client.getWorld();
        int[] levels = client.getRealSkillLevels();
        int freeTotal = 0;
        for (Skill skill : Skill.values()) {
            if (!skill.isMembers() && skill.ordinal() < levels.length) {
                freeTotal += Math.max(0, levels[skill.ordinal()]);
            }
        }
        World next = QuickHop.pick(worlds, current, previous, config.quickHopRegionFilter(),
                config.quickhopOutOfDanger(), client.getTotalLevel(), freeTotal);
        if (next == null) {
            setStatus(worldService.findWorld(current) == null ? "World " + current + " isn't in the world list"
                    : "Couldn't find a world to quick-hop to");
            return;
        }
        hop(next);
    }

    private void hop(World world) {
        if (!readyToHop()) {
            return;
        }
        if (world.getId() == client.getWorld()) {
            setStatus("Already on world " + world.getId());
            return;
        }
        target = world;
        attempts = 0;
        switching = null;
        setStatus("Hopping to world " + world.getId() + "…");
    }

    /**
     * Whether a hop can start now, setting the status to why not when it can't.
     *
     * @return true while logged in with the native hook connected
     */
    private boolean readyToHop() {
        if (client.getGameState() != GameState.LOGGED_IN) {
            setStatus("Log in to hop worlds");
            return false;
        }
        if (!client.getActions().isAvailable()) {
            setStatus("Can't hop: the client's native hook isn't connected");
            return false;
        }
        return true;
    }

    private void onGameTick() {
        if (switching != null && ++switchTicks > SWITCH_TICKS) {
            int id = switching.getId();
            switching = null;
            setStatus("World " + id + " didn't take the hop");
        }
        if (target == null) {
            return;
        }
        if (!switcherOpen()) {
            client.openWorldHopper();
            if (++attempts >= SWITCHER_ATTEMPTS) {
                int id = target.getId();
                target = null;
                setStatus("Couldn't open the world switcher to hop to world " + id);
            }
            return;
        }
        client.hopToWorld(target);
        switching = target;
        switchTicks = 0;
        target = null;
        setStatus("Switching to world " + switching.getId() + "…");
    }

    private void onGameStateChanged(GameStateChanged event) {
        if (event.getGameState() == GameState.LOGIN_SCREEN && (target != null || switching != null)) {
            int id = (switching != null ? switching : target).getId();
            target = null;
            switching = null;
            setStatus("The hop to world " + id + " ended on the login screen");
        } else if (event.getGameState() == GameState.HOPPING) {
            target = null;
            switchTicks = 0;
        } else if (event.getGameState() == GameState.LOGGED_IN && switching != null
                && (client.getWorld() == switching.getId() || client.getWorld() < 0)) {
            switching = null;
            setStatus(null);
        }
    }

    private void onChatMessage(ChatMessage event) {
        if (event.getType() == ChatMessageType.GAMEMESSAGE && BUSY_MESSAGE.equals(event.getMessage())
                && (target != null || switching != null)) {
            target = null;
            switching = null;
            setStatus("Can't hop yet: finish what you're doing first");
        }
    }

    /**
     * Whether the world switcher is open: the component table holds its group, or it is among the open groups.
     *
     * @return true if it is open
     */
    private boolean switcherOpen() {
        for (WidgetNode node : client.getComponentTable().values()) {
            if (node.getId() == InterfaceID.WORLDSWITCHER) {
                return true;
            }
        }
        return client.getOpenWidgetGroups().contains(InterfaceID.WORLDSWITCHER);
    }

    private void setStatus(String status) {
        if (status != null) {
            log.info("World Hopper: " + status);
        }
        this.status = status;
        statusNanos = System.nanoTime();
        hopping = target != null || switching != null;
        SwingUtilities.invokeLater(() -> statusListeners.forEach(Runnable::run));
    }
}
