package com.kraken.client.plugins.tickcounter;

import com.google.inject.Inject;
import com.kraken.api.Client;
import com.kraken.api.GameState;
import com.kraken.api.eventbus.Subscribe;
import com.kraken.api.events.GameStateChanged;
import com.kraken.api.events.GameTick;
import com.kraken.client.callback.ClientThread;
import com.kraken.client.plugins.Plugin;
import com.kraken.client.plugins.PluginDescriptor;
import lombok.extern.java.Log;

/**
 * The example plugin: counts game ticks while logged in and logs the count to the console once a minute. It shows the
 * shape of every Kraken plugin, which is a RuneLite plugin's: an injected {@link Client} and {@link ClientThread},
 * {@code startUp}/{@code shutDown}, and {@link Subscribe} methods for game events.
 *
 * <p>The count is only touched on the client thread (in the event handlers and the {@code clientThread} task), so it
 * needs no locking.
 */
@Log(topic = "kraken")
@PluginDescriptor(
        name = "Tick Counter",
        description = "Counts game ticks while you're logged in and logs the count to the console once a minute",
        tags = {"tick", "timer", "example"},
        enabledByDefault = false
)
public class TickCounterPlugin extends Plugin {

    /**
     * Ticks between log lines: 100 ticks of 0.6 s is a minute.
     */
    private static final int LOG_EVERY = 100;

    @Inject
    private Client client;

    @Inject
    private ClientThread clientThread;

    private int ticks;

    @Override
    protected void startUp() {
        clientThread.invoke(() -> {
            ticks = 0;
            log.info("Tick counter started: " + client.getGameState() + ", client tick " + client.getTickCount());
        });
    }

    @Override
    protected void shutDown() {
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
        if (ticks % LOG_EVERY == 0) {
            log.info("Tick counter: " + ticks + " ticks (" + duration(ticks) + ")");
        }
    }

    /**
     * How long a number of ticks lasts.
     *
     * @param ticks the tick count
     * @return minutes and seconds, e.g. "1:00"
     */
    private static String duration(int ticks) {
        long seconds = ticks * 600L / 1000;
        return seconds / 60 + ":" + String.format("%02d", seconds % 60);
    }
}
