package com.kraken.client;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.kraken.api.eventbus.EventBus;
import com.kraken.client.events.ConfigChanged;

import java.awt.KeyboardFocusManager;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.IntConsumer;
import javax.swing.*;

/**
 * The frame rates Kraken shows, and the cap it asks the game for.
 *
 * <ul>
 *   <li><b>The game's.</b> The native hook counts the frames the game presents and reports them about once a second
 *       ({@link #gameFrames}); unknown while the hook isn't connected.
 *   <li><b>The overlays'.</b> The overlay renderer marks each frame it hands over ({@link #overlayFrameDrawn}).
 *   <li><b>The cap.</b> While no Kraken window is active and {@link KrakenConfig#limitUnfocusedFps} is on, the game
 *       should be held to {@link KrakenConfig#unfocusedFps}; otherwise it runs free. The hook's server listens
 *       ({@link #addFrameLimitListener}) and passes it on.
 * </ul>
 *
 * <p>Thread-safe. Focus changes are heard on the EDT.
 */
@Singleton
public final class FrameRates {

    /**
     * The config keys the cap depends on.
     */
    private static final Set<String> LIMIT_KEYS = Set.of("limitUnfocusedFps", "unfocusedFps");

    /**
     * How long the focus has to settle before the cap follows it. Moving between two Kraken windows briefly leaves
     * none active.
     */
    private static final int FOCUS_SETTLE_MS = 250;

    /**
     * How long a report from the hook stays current. It reports about once a second.
     */
    private static final long GAME_STALE_NANOS = 3_000_000_000L;

    private final KrakenConfig config;
    private final List<IntConsumer> limitListeners = new CopyOnWriteArrayList<>();
    private final RateMeter overlay = new RateMeter();
    private volatile double gameRate = Double.NaN;
    private volatile long gameReportedAt;
    private volatile boolean focused = true;
    private volatile int limit;

    /**
     * Starts following the focus and the cap's settings.
     *
     * @param config   the settings
     * @param eventBus where setting changes are posted
     */
    @Inject
    FrameRates(KrakenConfig config, EventBus eventBus) {
        this.config = config;
        eventBus.register(ConfigChanged.class, event -> {
            if (KrakenConfig.GROUP.equals(event.getGroup()) && LIMIT_KEYS.contains(event.getKey())) {
                updateLimit();
            }
        }, 0);
        Timer settle = new Timer(FOCUS_SETTLE_MS, e -> {
            focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow() != null;
            updateLimit();
        });
        settle.setRepeats(false);
        KeyboardFocusManager.getCurrentKeyboardFocusManager()
                .addPropertyChangeListener("activeWindow", e -> settle.restart());
        SwingUtilities.invokeLater(settle::restart);
    }

    /**
     * Records a report from the native hook. Called on the hook's reader thread.
     *
     * @param frames the frames the game presented since the last report
     * @param micros how long since the last report, in microseconds
     */
    public void gameFrames(long frames, long micros) {
        if (micros > 0) {
            gameRate = frames * 1_000_000.0 / micros;
            gameReportedAt = System.nanoTime();
        }
    }

    /**
     * Forgets the game's frame rate, as when the hook disconnects.
     */
    public void gameDisconnected() {
        gameRate = Double.NaN;
    }

    /**
     * The game's frame rate, as the native hook last reported it.
     *
     * @return frames a second, or NaN if the hook isn't reporting
     */
    public double getGameFrameRate() {
        double rate = gameRate;
        return System.nanoTime() - gameReportedAt > GAME_STALE_NANOS ? Double.NaN : rate;
    }

    /**
     * Marks an overlay frame handed over to be shown. Called on the client thread.
     */
    public void overlayFrameDrawn() {
        overlay.mark(System.nanoTime());
    }

    /**
     * How many overlay frames were shown a second, over about the last second.
     *
     * @return frames a second, 0 while none are drawn
     */
    public double getOverlayFrameRate() {
        return overlay.rate(System.nanoTime());
    }

    /**
     * The frame rate the game should be held to now.
     *
     * @return frames a second, or 0 for no cap
     */
    public int getFrameLimit() {
        return limit;
    }

    /**
     * Runs a listener whenever {@link #getFrameLimit()} changes, on the thread that changed it.
     *
     * @param listener takes the new cap, 0 for none
     */
    public void addFrameLimitListener(IntConsumer listener) {
        limitListeners.add(listener);
    }

    private synchronized void updateLimit() {
        int next = !focused && config.limitUnfocusedFps() ? Math.max(1, config.unfocusedFps()) : 0;
        if (next != limit) {
            limit = next;
            limitListeners.forEach(listener -> listener.accept(next));
        }
    }

    /**
     * Counts events and turns them into a rate once a second. One thread marks; any thread reads.
     */
    static final class RateMeter {

        private static final long WINDOW_NANOS = 1_000_000_000L;

        private long windowStart;
        private int count;
        private volatile double rate;
        private volatile long lastMark;
        private boolean started;

        /**
         * Counts one event.
         *
         * @param now the time, in {@link System#nanoTime()} nanoseconds
         */
        void mark(long now) {
            if (!started || now - lastMark > WINDOW_NANOS) {
                // The first event, or the first after a pause: start a fresh window rather than averaging the pause in
                started = true;
                windowStart = now;
                count = 0;
            }
            count++;
            lastMark = now;
            long elapsed = now - windowStart;
            if (elapsed >= WINDOW_NANOS) {
                rate = (count - 1) * 1e9 / elapsed;
                windowStart = now;
                count = 1;
            }
        }

        /**
         * The rate over the last full window.
         *
         * @param now the time, in {@link System#nanoTime()} nanoseconds
         * @return events a second, 0 if none came in the last second or so
         */
        double rate(long now) {
            return !started || now - lastMark > WINDOW_NANOS ? 0 : rate;
        }
    }
}
