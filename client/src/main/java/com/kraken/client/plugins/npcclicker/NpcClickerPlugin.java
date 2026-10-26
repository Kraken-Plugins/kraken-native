package com.kraken.client.plugins.npcclicker;

import com.google.inject.Inject;
import com.google.inject.Provides;
import com.kraken.api.Client;
import com.kraken.api.MenuAction;
import com.kraken.api.Perspective;
import com.kraken.api.Point;
import com.kraken.api.action.Actions;
import com.kraken.api.actor.NPC;
import com.kraken.api.actor.Player;
import com.kraken.api.coords.LocalPoint;
import com.kraken.api.coords.WorldPoint;
import com.kraken.api.definition.NPCComposition;
import com.kraken.api.eventbus.Subscribe;
import com.kraken.api.events.GameTick;
import com.kraken.client.callback.ClientThread;
import com.kraken.client.plugins.Plugin;
import com.kraken.client.plugins.PluginDescriptor;
import com.kraken.client.plugins.config.ConfigManager;
import lombok.extern.java.Log;

import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;

/**
 * The menu action example plugin: clicks an NPC by name, running one of its menu options the way a click on the menu
 * would, without moving the mouse. It finds the nearest NPC with the configured name, works out which of its ops the
 * configured option is, and asks the client to run it ({@link Actions}); with the mouse click setting on, the mouse
 * click packet goes first, at the NPC's position on the canvas, as a real click sends it. It clicks once when turned
 * on and then every so many ticks if the interval is set, and says in the console what it clicked, or why it
 * couldn't.
 *
 * <p>Everything runs on the client thread: the {@link ClientThread} task from {@code startUp} and the game tick
 * handler, so the tick count needs no locking. Actions reach the game only while its native hook is connected.
 */
@Log(topic = "kraken")
@PluginDescriptor(
        name = "NPC Clicker",
        description = "Clicks an NPC by name: runs one of its menu options as a click would, with the mouse click first",
        tags = {"npc", "click", "action", "example"},
        enabledByDefault = false
)
public class NpcClickerPlugin extends Plugin {

    /**
     * The NPC ops, in the order of a composition's actions; op n is action n - 1.
     */
    private static final MenuAction[] NPC_OPS = {MenuAction.NPC_FIRST_OPTION, MenuAction.NPC_SECOND_OPTION,
            MenuAction.NPC_THIRD_OPTION, MenuAction.NPC_FOURTH_OPTION, MenuAction.NPC_FIFTH_OPTION};

    /**
     * How far above the NPC's tile the mouse click lands, in local units: about a model's middle.
     */
    private static final int CLICK_HEIGHT = 100;

    @Inject
    private Client client;

    @Inject
    private ClientThread clientThread;

    @Inject
    private NpcClickerConfig config;

    /**
     * Game ticks since the last click. Client thread only.
     */
    private int sinceClick;

    @Provides
    NpcClickerConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(NpcClickerConfig.class);
    }

    @Override
    protected void startUp() {
        clientThread.invoke(() -> {
            sinceClick = 0;
            click();
        });
    }

    @Override
    protected void shutDown() {
    }

    @Subscribe
    public void onGameTick(GameTick event) {
        sinceClick++;
        int interval = config.interval();
        if (interval > 0 && sinceClick >= interval) {
            sinceClick = 0;
            click();
        }
    }

    /**
     * Clicks the nearest NPC with the configured name, if there is one and the game can take actions now.
     *
     * @return true if an action was sent
     */
    boolean click() {
        Actions actions = client.getActions();
        if (!actions.isAvailable()) {
            log.info("NPC Clicker: the native hook isn't connected, so no action can be sent");
            return false;
        }
        String wanted = config.npcName().trim();
        Optional<NPC> nearest = nearest(wanted);
        if (nearest.isEmpty()) {
            log.info("NPC Clicker: no " + wanted + " is loaded");
            return false;
        }
        NPC npc = nearest.get();
        int op = opOf(npc, config.option().trim());
        if (op < 0) {
            log.info("NPC Clicker: " + npc.getName() + " has no " + config.option() + " option");
            return false;
        }
        MenuAction action = NPC_OPS[op - 1];
        if (!actions.isSupported(action)) {
            log.info("NPC Clicker: this build's mapping can't run NPC actions");
            return false;
        }
        boolean sent;
        if (config.mouseClick()) {
            Point canvas = canvasPoint(npc);
            if (canvas == null) {
                log.info("NPC Clicker: " + npc.getName() + " isn't on screen, so there is nowhere to click");
                return false;
            }
            sent = actions.click(canvas.getX(), canvas.getY(), 0, 0, action, npc.getIndex());
            if (sent) {
                log.info("NPC Clicker: clicked " + config.option() + " on " + npc.getName() + " (index " + npc.getIndex()
                        + ") at " + canvas.getX() + "," + canvas.getY());
            }
        } else {
            sent = actions.menuAction(0, 0, action, npc.getIndex());
            if (sent) {
                log.info("NPC Clicker: ran " + config.option() + " on " + npc.getName() + " (index " + npc.getIndex()
                        + ")");
            }
        }
        return sent;
    }

    /**
     * The loaded NPC with a name, nearest to the local player (or the first loaded one while the player can't be
     * read).
     *
     * @param name the name, case ignored
     * @return the NPC, if any is loaded
     */
    private Optional<NPC> nearest(String name) {
        Player local = client.getLocalPlayer();
        WorldPoint here = local == null ? null : local.getWorldLocation();
        return client.getNpcs().stream()
                .filter(npc -> npc.getName().equalsIgnoreCase(name))
                .min(Comparator.comparingInt(npc -> distance(here, npc.getWorldLocation())));
    }

    private static int distance(WorldPoint from, WorldPoint to) {
        return from == null || to == null ? Integer.MAX_VALUE : from.distanceTo(to);
    }

    /**
     * Which of an NPC's ops an option is, from its composition's actions.
     *
     * @param npc    the NPC
     * @param option the option's text, case ignored
     * @return the op, 1-5, or -1 if the NPC has no such option
     */
    static int opOf(NPC npc, String option) {
        NPCComposition composition = npc.getTransformedComposition();
        if (composition == null) {
            composition = npc.getComposition();
        }
        String[] actions = composition == null ? null : composition.getActions();
        if (actions == null) {
            return -1;
        }
        for (int i = 0; i < actions.length && i < NPC_OPS.length; i++) {
            if (actions[i] != null && actions[i].toLowerCase(Locale.ROOT).equals(option.toLowerCase(Locale.ROOT))) {
                return i + 1;
            }
        }
        return -1;
    }

    /**
     * Where to click an NPC: a point above the middle of its tile, on the canvas.
     *
     * @param npc the NPC
     * @return the canvas point, or null while the NPC isn't on screen
     */
    private Point canvasPoint(NPC npc) {
        LocalPoint local = npc.getLocalLocation();
        WorldPoint world = npc.getWorldLocation();
        if (local == null || world == null) {
            return null;
        }
        Point point = Perspective.localToCanvas(client, local, world.getPlane(), CLICK_HEIGHT);
        if (point == null || point.getX() < 0 || point.getY() < 0 || point.getX() >= client.getCanvasWidth()
                || point.getY() >= client.getCanvasHeight()) {
            return null;
        }
        return point;
    }
}
