package com.kraken.client;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import com.kraken.api.KrakenApi;
import com.kraken.api.UnsupportedBuildException;
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
 * The client's link to the native API. {@link #start} attaches a {@link KrakenApi} to the running osclient.exe in the
 * background (retrying while the client boots), then hands each API poll to every listener. Anything in the client
 * that reads the game (the dev tools today, plugins later) goes through here.
 */
@Log(topic = "kraken")
@Singleton
public final class ApiConnection {
    public enum State {WAITING, ATTACHING, ATTACHED, UNSUPPORTED, FAILED}

    private static final int ATTEMPTS = 20;
    private static final long RETRY_MS = 500;

    private final File exe;
    private final List<Runnable> stateListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<KrakenApi>> pollListeners = new CopyOnWriteArrayList<>();
    private volatile KrakenApi api;
    @Getter
    private volatile State state = State.WAITING;
    /** One line describing {@link #getState()}, for status bars. */
    @Getter
    private volatile String detail = "Waiting for the client to start…";

    @Inject
    ApiConnection(@Named("exe") File exe) {
        this.exe = exe;
    }

    /** Called by the launcher once osclient.exe is running. Attaches off the calling thread. */
    public void start(long pid) {
        Thread attach = new Thread(() -> attach(pid), "kraken-api-attach");
        attach.setDaemon(true);
        attach.start();
    }

    /** The attached API, or null until {@link State#ATTACHED}. */
    public KrakenApi api() {
        return api;
    }

    public boolean isAttached() {
        return state == State.ATTACHED;
    }

    /** Runs {@code listener} on the EDT whenever the state changes. */
    public void addStateListener(Runnable listener) {
        stateListeners.add(listener);
    }

    /**
     * Runs {@code listener} on the API poll thread after every poll (about every 150 ms) once attached. It may read
     * the game through the API, but must hand any UI work to the EDT.
     */
    public void addPollListener(Consumer<KrakenApi> listener) {
        pollListeners.add(listener);
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
                        ? KrakenApi.attach(pid, exe.toPath(), Path.of(override))
                        : KrakenApi.attach(pid, exe.toPath());
                attached.setPollListener(() -> poll(attached));
                api = attached;
                attached.start();
                log.info("Native API attached (client " + attached.mapping().version() + ", mappings " + source + ")");
                setState(State.ATTACHED, "Attached · client " + attached.mapping().version());
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
        for (Consumer<KrakenApi> listener : pollListeners) {
            try {
                listener.accept(attached);
            } catch (RuntimeException e) {
                log.log(Level.WARNING, "API poll listener failed", e);
            }
        }
    }

    private void setState(State state, String detail) {
        this.state = state;
        this.detail = detail;
        SwingUtilities.invokeLater(() -> stateListeners.forEach(Runnable::run));
    }
}
