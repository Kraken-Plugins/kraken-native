package com.kraken.client.plugins.tickcounter;

import com.google.inject.Inject;
import com.google.inject.Provides;
import com.kraken.api.Client;
import com.kraken.api.GameState;
import com.kraken.api.eventbus.Subscribe;
import com.kraken.api.events.GameStateChanged;
import com.kraken.api.events.GameTick;
import com.kraken.api.gameval.InterfaceID;
import com.kraken.api.widgets.Widget;
import com.kraken.client.callback.ClientThread;
import com.kraken.client.plugins.Plugin;
import com.kraken.client.plugins.PluginDescriptor;
import com.kraken.client.plugins.config.ConfigManager;
import lombok.extern.java.Log;

/**
 * The example plugin: counts game ticks while logged in, shows the count on the chatbox's Report button in place of
 * the word "Report", and logs it to the console now and then. It shows the shape of a Kraken plugin: an injected
 * {@link Client} and {@link ClientThread}, a {@code @Provides} config, {@code startUp}/{@code shutDown},
 * {@link Subscribe} methods for game events, and a widget write.
 *
 * <p>The count is only touched on the client thread (in the event handlers and the {@code clientThread} tasks), so it
 * needs no locking.
 */
@Log(topic = "kraken")
@PluginDescriptor(
        name = "Tick Counter",
        description = "Counts game ticks while you're logged in and shows the count on the Report button",
        tags = {"tick", "timer", "report", "example"},
        enabledByDefault = false
)
public class TickCounterPlugin extends Plugin {

    /**
     * Game ticks a minute: 100 ticks of 0.6 s.
     */
    private static final int TICKS_PER_MINUTE = 100;

    /**
     * The Report button's own text, put back when the plugin stops.
     */
    static final String REPORT = "Report";

    @Inject
    private Client client;

    @Inject
    private ClientThread clientThread;

    @Inject
    private TickCounterConfig config;

    /**
     * The ticks counted this session. Client thread only.
     */
    private int ticks;

    @Provides
    TickCounterConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(TickCounterConfig.class);
    }

    @Override
    protected void startUp() {
        clientThread.invoke(() -> {
            ticks = 0;
            log.info("Tick counter started: " + client.getGameState() + ", client tick " + client.getTickCount());
        });
    }

    @Override
    protected void shutDown() {
        clientThread.invoke(() -> {
            setReportText(REPORT);
            log.info("Tick counter stopped after " + ticks + " ticks");
        });
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
        setReportText(String.valueOf(ticks));
        int every = config.logInterval() * TICKS_PER_MINUTE;
        if (every > 0 && ticks % every == 0) {
            log.info("Tick counter: " + ticks + " ticks (" + duration(ticks) + ")");
        }
    }

    /**
     * Sets the Report button's text, if the chatbox is loaded. The game draws the new text from the next frame, and
     * keeps it until the chatbox is reloaded, so the count is set again every tick.
     *
     * @param text the text
     */
    private void setReportText(String text) {
        Widget report = client.getWidget(InterfaceID.Chatbox.REPORTABUSE_TEXT1);
        if (report != null) {
            report.setText(text);
        }
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
