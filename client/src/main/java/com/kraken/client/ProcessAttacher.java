package com.kraken.client;

import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import com.kraken.api.ClientBuild;
import com.kraken.api.GameSession;
import com.kraken.api.UnsupportedBuildException;
import com.kraken.api.eventbus.EventBus;
import com.kraken.api.mapping.Mapping;
import com.kraken.client.events.ApiStateChanged;
import lombok.Getter;
import lombok.extern.java.Log;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Level;
import javax.swing.*;

/**
 * Attaches a {@link GameSession} to the running osclient.exe. {@link #start} reads the exe's build and its mapping,
 * then attaches in the background (retrying while the client boots), posting the game events on the client's
 * {@link EventBus}. Once attached it creates the session injector, a child of the client's injector with the
 * {@link SessionModule} bindings (the {@link GameSession}, its {@code Client} and {@code Mapping}), hands it to the
 * attach listeners, which load the plugins under it, and then hands each API poll to every poll listener.
 *
 * <p>Components that exist before the game does (the frame, the dev tools, the overlay renderer) follow the state and
 * polls through the listeners here. Each state change is also posted on the bus as an {@link ApiStateChanged}.
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
    private final Injector injector;
    private final List<Runnable> stateListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<Injector>> attachListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<GameSession>> pollListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<GameSession>> frameListeners = new CopyOnWriteArrayList<>();
    private volatile GameSession session;

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

    /**
     * Creates the attacher.
     *
     * @param exe      the game binary, whose build picks the mapping
     * @param eventBus the client's bus, where the game events and state changes are posted
     * @param injector the client's injector, parent of the session injector
     */
    @Inject
    ProcessAttacher(@Named("exe") File exe, EventBus eventBus, Injector injector) {
        this.exe = exe;
        this.eventBus = eventBus;
        this.injector = injector;
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
    public GameSession attached() {
        return session;
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
     * that never runs; check {@link #attached()} as well.
     *
     * @param listener takes the session injector, which binds the attached {@link GameSession}, its {@code Client}
     *                 and {@code Mapping} ({@link SessionModule})
     */
    public void addAttachListener(Consumer<Injector> listener) {
        attachListeners.add(listener);
    }

    /**
     * Runs a listener on the API poll thread after every poll (about every 150 ms) once attached. It may read the game
     * through the API, but must hand any UI work to the EDT.
     *
     * @param listener takes the attached API
     */
    public void addPollListener(Consumer<GameSession> listener) {
        pollListeners.add(listener);
    }

    /**
     * Runs a listener on the API poll thread at the end of every poll (about every 20 ms) once attached, after the
     * poll's events and tasks and before the poll listeners. The plugin overlay renderer draws its frames from here.
     * The listener should catch its own errors: one that throws is logged on every poll.
     *
     * @param listener takes the attached API
     */
    public void addFrameListener(Consumer<GameSession> listener) {
        frameListeners.add(listener);
    }

    /**
     * Attaches to the game, builds the session injector and starts the API's poll loop. Runs on the attach thread.
     *
     * @param pid the game's process id
     */
    private void attach(long pid) {
        // The API's bundled mappings, unless -Dkraken.mappings=<dir> points at another folder of <version>.json files
        String override = System.getProperty("kraken.mappings");
        String source = override != null ? override : "bundled";
        setState(State.ATTACHING, "Attaching to osclient.exe (pid " + pid + ")…");
        ClientBuild build;
        Mapping mapping;
        try {
            // The exe and its mapping don't change while the game runs, so they're read once rather than on every try
            build = ClientBuild.of(exe.toPath());
            mapping = override != null ? GameSession.mappingFor(build, Path.of(override)) : GameSession.mappingFor(build);
        } catch (UnsupportedBuildException e) {
            unsupported(e);
            return;
        } catch (IOException | RuntimeException e) {
            log.log(Level.WARNING, "Could not read the build of " + exe + " or its mapping", e);
            setState(State.FAILED, "Could not read the client's build or mapping. See the console.");
            return;
        }

        GameSession attached = open(pid, build, mapping);
        if (attached == null) {
            return;
        }
        Injector sessionInjector;
        try {
            sessionInjector = injector.createChildInjector(new SessionModule(attached, pid));
        } catch (RuntimeException e) {
            log.log(Level.SEVERE, "Could not create the session injector", e);
            attached.close();
            setState(State.FAILED, "Could not attach to osclient.exe. See the console.");
            return;
        }
        attached.setPollListener(() -> poll(attached));
        attached.setFrameListener(() -> notify(frameListeners, attached, "API frame listener failed"));
        session = attached;
        for (Consumer<Injector> listener : attachListeners) {
            try {
                listener.accept(sessionInjector);
            } catch (RuntimeException e) {
                log.log(Level.WARNING, "API attach listener failed", e);
            }
        }
        log.info("Native API attached (client " + mapping.version() + ", mappings " + source + ")");
        setState(State.ATTACHED, "Attached · client " + mapping.version());
        attached.start();
    }

    /**
     * Opens the game process, retrying while the client boots (its module may not be loaded yet).
     *
     * @param pid     the game's process id
     * @param build   the game binary's build
     * @param mapping the mapping for that build
     * @return the attached API, or null if attaching failed or was interrupted (the state says why)
     */
    private GameSession open(long pid, ClientBuild build, Mapping mapping) {
        RuntimeException last = null;
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            try {
                return GameSession.attach(pid, build, mapping, eventBus);
            } catch (UnsupportedBuildException e) {
                // Retrying won't help: the mapping was made from another binary
                unsupported(e);
                return null;
            } catch (RuntimeException e) {
                last = e;
                try {
                    Thread.sleep(RETRY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        log.log(Level.WARNING, "Could not attach the native API to osclient.exe", last);
        setState(State.FAILED, "Could not attach to osclient.exe. See the console.");
        return null;
    }

    /**
     * Reports that there's no usable mapping for the game's build.
     *
     * @param e why the build is unsupported
     */
    private void unsupported(UnsupportedBuildException e) {
        log.warning(e.getMessage());
        setState(State.UNSUPPORTED, "No mapping for client " + e.version() + ". Run the updater for it.");
    }

    private void poll(GameSession attached) {
        notify(pollListeners, attached, "API poll listener failed");
    }

    private static void notify(List<Consumer<GameSession>> listeners, GameSession attached, String failure) {
        for (Consumer<GameSession> listener : listeners) {
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
