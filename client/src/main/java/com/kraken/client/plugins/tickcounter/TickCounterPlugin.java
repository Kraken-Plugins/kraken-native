package com.kraken.client.plugins.tickcounter;

import com.google.inject.Inject;
import com.google.inject.Provides;
import com.kraken.api.Client;
import com.kraken.api.GameState;
import com.kraken.api.eventbus.Subscribe;
import com.kraken.api.events.GameStateChanged;
import com.kraken.api.events.GameTick;
import com.kraken.client.callback.ClientThread;
import com.kraken.client.plugins.config.ConfigManager;
import com.kraken.client.plugins.Plugin;
import com.kraken.client.plugins.PluginDescriptor;
import com.kraken.client.ui.overlay.OverlayManager;
import lombok.extern.java.Log;

/**
 * The example plugin: counts game ticks while logged in, shows the count in a panel over the game and logs it to the
 * console now and then. It shows the shape of every Kraken plugin, which is a RuneLite plugin's: an injected
 * {@link Client} and {@link ClientThread}, a {@code @Provides} config, an overlay, {@code startUp}/{@code shutDown}, and
 * {@link Subscribe} methods for game events.
 *
 * <p>The count is only touched on the client thread (in the event handlers, the {@code clientThread} task and the
 * overlay's {@code render}), so it needs no locking.
 */
@Log(topic = "kraken")
@PluginDescriptor(
        name = "Tick Counter",
        description = "Counts game ticks while you're logged in, shows the count over the game and logs it to the "
                + "console",
        tags = {"tick", "timer", "example"},
        enabledByDefault = false
)
public class TickCounterPlugin extends Plugin {

    /**
     * Game ticks a minute: 100 ticks of 0.6 s.
     */
    private static final int TICKS_PER_MINUTE = 100;

    @Inject
    private Client client;

    @Inject
    private ClientThread clientThread;

    @Inject
    private TickCounterConfig config;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private TickCounterOverlay overlay;

    private int ticks;

    @Provides
    TickCounterConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(TickCounterConfig.class);
    }

    @Override
    protected void startUp() {
        overlayManager.add(overlay);
        clientThread.invoke(() -> {
            ticks = 0;
            log.info("Tick counter started: " + client.getGameState() + ", client tick " + client.getTickCount());
        });
    }

    @Override
    protected void shutDown() {
        overlayManager.remove(overlay);
        clientThread.invoke(() -> log.info("Tick counter stopped after " + ticks + " ticks"));
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event) {
        GameState state = event.getGameState();
        if (state == GameState.LOGIN_SCREEN || state == GameState.HOPPING) {
            if (ticks > 0) {
                log.info("Tick counter: " + ticks + " ticks (" + duration(ticks) + ") that session");
            }
            ticks = 0;
        }
    }

    @Subscribe
    public void onGameTick(GameTick event) {
        ticks++;
        int every = config.logInterval() * TICKS_PER_MINUTE;
        if (every > 0 && ticks % every == 0) {
            log.info("Tick counter: " + ticks + " ticks (" + duration(ticks) + ")");
        }
    }

    /**
     * The ticks counted this session. Client thread only.
     *
     * @return the count
     */
    int getTicks() {
        return ticks;
    }

    /**
     * How long a number of ticks lasts.
     *
     * @param ticks the tick count
     * @return minutes and seconds, e.g. "1:00"
     */
    static String duration(int ticks) {
        long seconds = ticks * 600L / 1000;
        return seconds / 60 + ":" + String.format("%02d", seconds % 60);
    }
}
