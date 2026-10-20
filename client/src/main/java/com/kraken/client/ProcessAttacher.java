package com.kraken.client;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import com.kraken.api.KrakenApi;
import com.kraken.api.UnsupportedBuildException;
import com.kraken.api.eventbus.EventBus;
import com.kraken.client.events.ApiStateChanged;
import lombok.Getter;
import lombok.extern.java.Log;

import javax.swing.*;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Attaches a {@link KrakenApi} to the running osclient.exe. {@link #start} attaches in the background (retrying while
 * the client boots), posting the game events on the client's {@link EventBus}, then hands each API poll to every
 * listener. Anything in the client that reads the game (dev tools, plugins) goes through here.
 *
 * <p>Each state change is posted on the bus as an {@link ApiStateChanged}.
 */
@Log(topic = "kraken")
@Singleton
public final class ProcessAttacher {

    /**
     * Where attaching has got to.
     */
    public enum State {

        /**
         * The game hasn't started yet.
         */
        WAITING,

        /**
         * Attaching to the running game.
         */
        ATTACHING,

        /**
         * Attached: the API reads the game.
         */
        ATTACHED,

        /**
         * There's no mapping for the game's build.
         */
        UNSUPPORTED,

        /**
         * Attaching failed; the console says why.
         */
        FAILED
    }

    private static final int ATTEMPTS = 20;
    private static final long RETRY_MS = 500;

    private final File exe;
    private final EventBus eventBus;
    private final List<Runnable> stateListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<KrakenApi>> attachListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<KrakenApi>> pollListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<KrakenApi>> frameListeners = new CopyOnWriteArrayList<>();
    private volatile KrakenApi api;

    /**
     * The attach state.
     */
    @Getter
    private volatile State state = State.WAITING;

    /**
     * One line describing {@link #getState()}, for status bars.
     */
    @Getter
    private volatile String detail = "Waiting for the client to start…";

    @Inject
    ProcessAttacher(@Named("exe") File exe, EventBus eventBus) {
        this.exe = exe;
        this.eventBus = eventBus;
    }

    /**
     * Attaches to the game off the calling thread. Called by the launcher once osclient.exe is running.
     *
     * @param pid the game's process id
     */
    public void start(long pid) {
        Thread attach = new Thread(() -> attach(pid), "kraken-api-attach");
        attach.setDaemon(true);
        attach.start();
    }

    /**
     * The attached API.
     *
     * @return the API, or null until {@link State#ATTACHED}
     */
    public KrakenApi attached() {
        return api;
    }

    /**
     * Whether the API is attached.
     *
     * @return true in {@link State#ATTACHED}
     */
    public boolean isAttached() {
        return state == State.ATTACHED;
    }

    /**
     * Runs a listener on the EDT whenever the state changes.
     *
     * @param listener the listener
     */
    public void addStateListener(Runnable listener) {
        stateListeners.add(listener);
    }

    /**
     * Runs a listener once the API attaches, on the attach thread before the API's first poll. A listener added after
     * that never runs; check {@link #api()} as well.
     *
     * @param listener takes the attached API
     */
    public void addAttachListener(Consumer<KrakenApi> listener) {
        attachListeners.add(listener);
    }

    /**
     * Runs a listener on the API poll thread after every poll (about every 150 ms) once attached. It may read the game
     * through the API, but must hand any UI work to the EDT.
     *
     * @param listener takes the attached API
     */
    public void addPollListener(Consumer<KrakenApi> listener) {
        pollListeners.add(listener);
    }

    /**
     * Runs a listener on the API poll thread at the end of every poll (about every 20 ms) once attached, after the
     * poll's events and tasks and before the poll listeners. The plugin overlay renderer draws its frames from here.
     * The listener should catch its own errors: one that throws is logged on every poll.
     *
     * @param listener takes the attached API
     */
    public void addFrameListener(Consumer<KrakenApi> listener) {
        frameListeners.add(listener);
    }

    private void attach(long pid) {
        // The API's bundled mappings, unless -Dkraken.mappings=<dir> points at another folder of <version>.json files
        String override = System.getProperty("kraken.mappings");
        String source = override != null ? override : "bundled";
        setState(State.ATTACHING, "Attaching to osclient.exe (pid " + pid + ")…");
        Exception last = null;
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            try {
                KrakenApi attached = override != null
                        ? KrakenApi.attach(pid, exe.toPath(), Path.of(override), eventBus)
                        : KrakenApi.attach(pid, exe.toPath(), eventBus);
                attached.setPollListener(() -> poll(attached));
                attached.setFrameListener(() -> notify(frameListeners, attached, "API frame listener failed"));
                api = attached;
                for (Consumer<KrakenApi> listener : attachListeners) {
                    try {
                        listener.accept(attached);
                    } catch (RuntimeException e) {
                        log.log(Level.WARNING, "API attach listener failed", e);
                    }
                }
                log.info("Native API attached (client " + attached.mapping().version() + ", mappings " + source + ")");
                setState(State.ATTACHED, "Attached · client " + attached.mapping().version());
                attached.start();
                return;
            } catch (UnsupportedBuildException e) {
                // Retrying won't help: there is no mapping for this build
                log.warning(e.getMessage());
                setState(State.UNSUPPORTED, "No mapping for client " + e.version() + ". Run the updater for it.");
                return;
            } catch (Exception e) {
                last = e;
                try {
                    Thread.sleep(RETRY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        log.log(Level.WARNING, "Could not attach the native API to osclient.exe", last);
        setState(State.FAILED, "Could not attach to osclient.exe. See the console.");
    }

    private void poll(KrakenApi attached) {
        notify(pollListeners, attached, "API poll listener failed");
    }

    private static void notify(List<Consumer<KrakenApi>> listeners, KrakenApi attached, String failure) {
        for (Consumer<KrakenApi> listener : listeners) {
            try {
                listener.accept(attached);
            } catch (RuntimeException e) {
                log.log(Level.WARNING, failure, e);
            }
        }
    }

    private void setState(State state, String detail) {
        this.state = state;
        this.detail = detail;
        eventBus.post(new ApiStateChanged(state, detail));
        SwingUtilities.invokeLater(() -> stateListeners.forEach(Runnable::run));
    }
}
