package com.kraken.api;

import com.kraken.api.eventbus.EventBus;
import com.kraken.api.internal.event.EventSource;
import com.kraken.api.internal.event.NativeEvent;
import com.kraken.api.internal.invoke.NativeInvoker;
import com.kraken.api.mapping.Mapping;
import com.kraken.api.memory.Memory;
import com.kraken.api.memory.MemoryException;
import com.kraken.api.memory.ProcessMemory;
import lombok.Setter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Entry point for the native API. {@link #attach} opens the running {@code osclient.exe} and selects the mapping for
 * its client version ({@code mappings/<version>.json}). Then {@link #client()} is the API ({@link Client},
 * {@code NPC}, {@code WorldPoint}…): each getter follows the mapped offset chains in the live process, replaying what
 * the client's own Lua handlers read.
 *
 * <p>{@link #start()} runs the poll loop on the {@code kraken-api-poll} thread, which acts as the client thread. Every
 * 20 ms, or as soon as the native hook pushes a raise ({@link #onNative}), it:
 * <ol>
 *   <li>runs the tasks handed to {@link #invoke(Runnable)} and friends;
 *   <li>synthesises the game events on {@link #eventBus()}, with {@code GameTick} after the tick's other events, then
 *       a {@code ClientTick} per client cycle: every iteration without a native hook; with one, on each iteration a
 *       raise woke, and at least every 250 ms as a safety net for ticks and dropped raises;
 *   <li>runs the {@link #invokeAtTickEnd} tasks if a game tick happened;
 *   <li>calls the {@link #setFrameListener frame listener}, where the client renders plugin overlays;
 *   <li>calls the {@link #setPollListener poll listener}, at most every 150 ms.
 * </ol>
 * Events are only synthesised while something subscribes to them.
 *
 * <p>It reads the game and never writes to it. The client's highlights ({@link Client#getHighlights()}) and the menu
 * actions it runs ({@link Client#getActions()}) are the two things it changes, by having the client's native hook
 * call the game's own functions ({@link #setNativeInvoker}).
 */
public final class GameSession implements AutoCloseable {

    private static final Logger log = Logger.getLogger("kraken.api");
    private static final String MODULE = "osclient.exe";
    private static final long POLL_INTERVAL_MS = 20;
    private static final long RENDER_INTERVAL_MS = 150;

    /**
     * The longest gap between event reads while a native hook is connected. Each raise the hook pushes through
     * {@link #onNative} wakes the loop and reads at once; this timer is the safety net for ticks ({@code GameTick} and
     * {@code ClientTick}, which no tracker owns) and for raises the hook dropped. Without a hook the events are read on
     * every iteration, every {@link #POLL_INTERVAL_MS}.
     */
    private static final long HOOKED_EVENT_POLL_MS = 250;

    /**
     * How long the loop waits between iterations when no raise wakes it: {@link #POLL_INTERVAL_MS}, or longer in tests
     * so that only a raise moves it.
     */
    private final long pollIntervalMs;

    /**
     * Guards {@link #wakeRequested}; the loop waits on it between iterations and {@link #wake} notifies it.
     */
    private final Object wakeLock = new Object();

    /**
     * Whether a raise arrived since the loop last waited, so its next wait returns at once. Guarded by
     * {@link #wakeLock}.
     */
    private boolean wakeRequested;

    private final Memory memory;
    private final Mapping mapping;
    private final Client client;
    private final EventBus eventBus;
    private final EventSource source;
    private final TaskQueue tasks = new TaskQueue();

    private volatile boolean running;

    @Setter
    private volatile boolean hookConnected;

    /**
     * Whether a native raise arrived since the last event read, so the next loop iteration reads instead of waiting
     * for the safety timer. Only the poll thread touches it: the {@link #onNative} task sets it and the poll loop
     * clears it, so it needs no volatile or locking.
     */
    private boolean eventPollRequested;

    /**
     * A callback run on the poll thread after events are dispatched, at most every 150 ms. The dev tools use it to
     * refresh their views. It may read the game; it must hand any UI work to its own thread.
     */
    @Setter
    private volatile Runnable pollListener;

    /**
     * A callback run on the poll thread at the end of every loop iteration (every 20 ms, and after each native raise),
     * after the tick-end tasks and before the poll listener. The client renders plugin overlays from it, deciding
     * itself whether a frame is due.
     */
    @Setter
    private volatile Runnable frameListener;
    private Thread thread;

    /**
     * Builds a session over a process image, with its own {@link Client} and event source reading that image.
     * {@link #attach} makes the one for the attached process; making another is for tests and tools that read a
     * memory image of their own.
     *
     * @param memory   the client process's memory; closed with the session if it is a {@link ProcessMemory}
     * @param mapping  the client build's mapping
     * @param eventBus the bus to post the game events on
     * @throws com.kraken.api.mapping.Mapping.MissingMappingException if the mapping lacks a key every build has
     */
    public GameSession(Memory memory, Mapping mapping, EventBus eventBus) {
        this(memory, mapping, eventBus, POLL_INTERVAL_MS);
    }

    /**
     * Like {@link #GameSession(Memory, Mapping, EventBus)}, with the loop's wait between iterations set. Tests set it
     * long, so the loop only moves when a raise wakes it.
     *
     * @param memory         the client process's memory; closed with the session if it is a {@link ProcessMemory}
     * @param mapping        the client build's mapping
     * @param eventBus       the bus to post the game events on
     * @param pollIntervalMs how long the loop waits between iterations when no raise wakes it
     */
    GameSession(Memory memory, Mapping mapping, EventBus eventBus, long pollIntervalMs) {
        this.memory = memory;
        this.mapping = mapping;
        this.eventBus = eventBus;
        this.pollIntervalMs = pollIntervalMs;
        this.client = new Client(memory, mapping);
        this.source = new EventSource(client, eventBus);
    }

    /**
     * Attaches to the given process using the mapping bundled with the API for its client version (the classpath
     * resource {@code mappings/<version>.json}).
     *
     * @param pid the {@code osclient.exe} process id
     * @param exe path to that binary on disk (its version picks the mapping; its hash must match it)
     * @return the attached API, posting on a bus of its own; {@link #start} its poll loop to get events
     * @throws IOException               if the exe or the mapping can't be read
     * @throws UnsupportedBuildException if there is no mapping for the version, or it was made from another binary
     * @throws MemoryException           if the process cannot be opened
     */
    public static GameSession attach(long pid, Path exe) throws IOException {
        return attach(pid, exe, new EventBus());
    }

    /**
     * Like {@link #attach(long, Path)}, posting the game events on a bus the caller already has, so subscribers that
     * registered before the game came up get them, the current state included (see {@link #eventBus()}).
     *
     * @param pid      the {@code osclient.exe} process id
     * @param exe      path to that binary on disk (its version picks the mapping; its hash must match it)
     * @param eventBus the bus to post the game events on
     * @return the attached API; {@link #start} its poll loop to get events
     * @throws IOException               if the exe or the mapping can't be read
     * @throws UnsupportedBuildException if there is no mapping for the version, or it was made from another binary
     * @throws MemoryException           if the process cannot be opened
     */
    public static GameSession attach(long pid, Path exe, EventBus eventBus) throws IOException {
        ClientBuild build = ClientBuild.of(exe);
        return attach(pid, build, mappingFor(build), eventBus);
    }

    /**
     * Like {@link #attach(long, Path)}, but reads {@code <mappingsDir>/<version>.json} from disk instead of the
     * bundled mapping, e.g. to try a mapping the updater wrote somewhere else.
     *
     * @param pid         the {@code osclient.exe} process id
     * @param exe         path to that binary on disk (its version picks the mapping; its hash must match it)
     * @param mappingsDir folder holding {@code <version>.json} mappings
     * @return the attached API, posting on a bus of its own; {@link #start} its poll loop to get events
     * @throws IOException               if the exe or the mapping can't be read
     * @throws UnsupportedBuildException if the folder has no mapping for the version or it was made from another binary
     * @throws MemoryException           if the process cannot be opened
     */
    public static GameSession attach(long pid, Path exe, Path mappingsDir) throws IOException {
        return attach(pid, exe, mappingsDir, new EventBus());
    }

    /**
     * Like {@link #attach(long, Path, Path)}, posting the game events on a bus the caller already has.
     *
     * @param pid         the {@code osclient.exe} process id
     * @param exe         path to that binary on disk (its version picks the mapping; its hash must match it)
     * @param mappingsDir folder holding {@code <version>.json} mappings
     * @param eventBus    the bus to post the game events on
     * @return the attached API; {@link #start} its poll loop to get events
     * @throws IOException               if the exe or the mapping can't be read
     * @throws UnsupportedBuildException if the folder has no mapping for the version or it was made from another binary
     * @throws MemoryException           if the process cannot be opened
     */
    public static GameSession attach(long pid, Path exe, Path mappingsDir, EventBus eventBus) throws IOException {
        ClientBuild build = ClientBuild.of(exe);
        return attach(pid, build, mappingFor(build, mappingsDir), eventBus);
    }

    /**
     * Like {@link #attach(long, Path, EventBus)}, with the build and its mapping already read. A caller that retries
     * while the client boots reads and hashes the exe and parses the mapping once ({@link ClientBuild#of},
     * {@link #mappingFor}), then retries only this, which opens the process.
     *
     * @param pid      the {@code osclient.exe} process id
     * @param build    the running binary's build
     * @param mapping  the mapping for that build; its hash must match the build's
     * @param eventBus the bus to post the game events on
     * @return the attached API; {@link #start} its poll loop to get events
     * @throws UnsupportedBuildException if the mapping was made from another binary
     * @throws MemoryException           if the process cannot be opened
     */
    public static GameSession attach(long pid, ClientBuild build, Mapping mapping, EventBus eventBus) {
        if (!mapping.sha256().equalsIgnoreCase(build.sha256())) {
            throw new UnsupportedBuildException(build, mapping.version() + ".json was made from a different binary "
                    + "(sha256 " + mapping.sha256().substring(0, 16) + "); re-run the updater for this one");
        }
        ProcessMemory memory = ProcessMemory.attach((int) pid, MODULE);
        log.info("Attached to " + MODULE + " pid " + pid + " (client " + build.version() + ", base 0x"
                + Long.toHexString(memory.moduleBase()) + ")");
        return new GameSession(memory, mapping, eventBus);
    }

    /**
     * The mapping bundled with the API for a build: the classpath resource {@code mappings/<version>.json}.
     *
     * @param build the running binary's build
     * @return the mapping for its version
     * @throws IOException               if the mapping can't be read
     * @throws UnsupportedBuildException if there is no bundled mapping for the version
     */
    public static Mapping mappingFor(ClientBuild build) throws IOException {
        Mapping mapping = Mapping.bundled(build.version());
        if (mapping == null) {
            throw new UnsupportedBuildException(build, "no bundled " + Mapping.RESOURCE_DIR + build.version() + ".json");
        }
        return mapping;
    }

    /**
     * The mapping for a build from a folder on disk, {@code <mappingsDir>/<version>.json}, e.g. one the updater wrote.
     *
     * @param build       the running binary's build
     * @param mappingsDir folder holding {@code <version>.json} mappings
     * @return the mapping for its version
     * @throws IOException               if the mapping can't be read
     * @throws UnsupportedBuildException if the folder has no mapping for the version
     */
    public static Mapping mappingFor(ClientBuild build, Path mappingsDir) throws IOException {
        Path file = mappingsDir.resolve(build.version() + ".json");
        if (!Files.isRegularFile(file)) {
            throw new UnsupportedBuildException(build, "no " + file.getFileName() + " in " + mappingsDir);
        }
        return Mapping.load(file);
    }

    /**
     * The game client.
     *
     * @return the client for this process
     */
    public Client client() {
        return client;
    }

    /**
     * Where the game events ({@code com.kraken.api.events}) are posted, on the poll thread. A kind of
     * event is only tracked while the bus has subscribers for it. Tracking that starts at the first poll replays the
     * current state (the game state, the scene's objects and ground items, every loaded NPC and player as spawned, the
     * actors' targets, stats and inventories), as if the client had just logged in; tracking that starts later takes a
     * silent baseline.
     *
     * @return the bus passed to {@link #attach(long, Path, EventBus)}, or the API's own
     */
    public EventBus eventBus() {
        return eventBus;
    }

    /**
     * Runs a task on the client (poll) thread: now if called on it, else at the start of the next poll.
     *
     * @param task the task
     */
    public void invoke(Runnable task) {
        tasks.invoke(once(task));
    }

    /**
     * Runs a task on the client thread, now if called on it, else at the next poll, and again at each poll until it
     * returns true.
     *
     * @param task the task; false asks to run it again at the next poll
     */
    public void invoke(BooleanSupplier task) {
        tasks.invoke(task);
    }

    /**
     * Runs a task on the client thread at the start of the next poll, even when called on it.
     *
     * @param task the task
     */
    public void invokeLater(Runnable task) {
        tasks.invokeLater(once(task));
    }

    /**
     * Runs a task on the client thread at the next poll, and again at each poll until it returns true.
     *
     * @param task the task; false asks to run it again at the next poll
     */
    public void invokeLater(BooleanSupplier task) {
        tasks.invokeLater(task);
    }

    /**
     * Runs a task on the client thread after the next game tick's events, {@code GameTick}'s subscribers included.
     *
     * @param task the task
     */
    public void invokeAtTickEnd(Runnable task) {
        tasks.invokeAtTickEnd(task);
    }

    /**
     * Delivers a native event raise, pushed by the hook in the game process, to the event source on the client thread.
     * It wakes the poll loop, which runs it at once and then reads the events, so the trackers read and post for it in
     * their usual order without waiting out the loop's sleep or the safety timer. Posts nothing itself; the trackers
     * do, as they do from the timer poll, and the tick events still come from the game's counters. Safe to call from
     * any thread.
     *
     * <p>The hook is optional: without it this is never called and the events are read every 20 ms. While it is
     * connected ({@link #setHookConnected}), each raise wakes a read, and the timer reads at least every 250 ms as a
     * safety net for ticks ({@code GameTick} and {@code ClientTick}, which no tracker owns) and for dropped raises.
     *
     * @param event the parsed raise
     */
    public void onNative(NativeEvent event) {
        invoke(() -> {
            source.onNative(event);
            eventPollRequested = true;
        });
        wake();
    }

    /**
     * Hands the API a connection to the native hook that runs calls on the game thread, or takes it away. While it is
     * set, {@link Client#getHighlights()} draws in the game, every highlight set so far is applied through it, and
     * {@link Client#getActions()} runs menu actions. Internal: the client's native hook calls this as its connection
     * comes up and drops.
     *
     * @param invoker the connection, or null while there is none
     */
    public void setNativeInvoker(NativeInvoker invoker) {
        client.getHighlights().setInvoker(invoker);
        client.getActions().setInvoker(invoker);
    }

    /**
     * Whether the calling thread is the client (poll) thread, where game events are posted and tasks run.
     *
     * @return true on the poll thread
     */
    public boolean isClientThread() {
        return tasks.isClientThread();
    }

    /**
     * The mapping this API reads through.
     *
     * @return the mapping for the attached client's build
     */
    public Mapping mapping() {
        return mapping;
    }

    /**
     * Whether the client process is still running.
     *
     * @return true while {@code osclient.exe} can be read
     */
    public boolean isAttached() {
        return memory.isValid();
    }

    /**
     * Starts the background poll loop. Idempotent.
     */
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        thread = new Thread(this::pollLoop, "kraken-api-poll");
        thread.setDaemon(true);
        thread.start();
    }

    private void pollLoop() {
        tasks.bind(Thread.currentThread());
        long lastRender = 0;
        long lastEventPoll = 0;
        while (running) {
            try {
                awaitNextIteration();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (!memory.isValid()) {
                log.info("osclient.exe exited; stopping poll loop");
                break;
            }
            tasks.runPending();
            long now = System.currentTimeMillis();
            // The loop, its tasks and the frame listener run every POLL_INTERVAL_MS and on every raise. While the hook
            // is connected the events are read when a raise asked for it or the safety interval lapsed; without it
            // (interval 0), every iteration.
            long safetyInterval = hookConnected ? HOOKED_EVENT_POLL_MS : 0;
            if (eventPollRequested || now - lastEventPoll >= safetyInterval) {
                eventPollRequested = false;
                lastEventPoll = now;
                boolean[] ticked = {false};
                if (!step("Event poll", () -> ticked[0] = source.poll())) {
                    break;
                }
                if (ticked[0]) {
                    tasks.runTickEnd();
                }
            }
            Runnable frame = frameListener;
            if (frame != null && !step("Frame listener", frame)) {
                break;
            }

            Runnable listener = pollListener;
            if (listener != null && now - lastRender >= RENDER_INTERVAL_MS) {
                lastRender = now;
                if (!step("Poll listener", listener)) {
                    break;
                }
            }
        }
        running = false;
    }

    /**
     * Waits out the poll interval between two loop iterations, or returns as soon as {@link #onNative} pushes a raise
     * (at once if one came during the last iteration), so a raise is read when it arrives rather than after the sleep.
     * Without raises it waits like a plain sleep.
     *
     * @throws InterruptedException if the session is closing
     */
    private void awaitNextIteration() throws InterruptedException {
        synchronized (wakeLock) {
            if (!wakeRequested) {
                wakeLock.wait(pollIntervalMs);
            }
            wakeRequested = false;
        }
    }

    /**
     * Ends the poll loop's current or next wait, so its next iteration starts now. Safe to call from any thread.
     */
    private void wake() {
        synchronized (wakeLock) {
            wakeRequested = true;
            wakeLock.notifyAll();
        }
    }

    /**
     * Runs one stage of a poll, so a stage that fails doesn't hold up the others.
     *
     * @param name  the stage, for the log
     * @param stage the stage
     * @return false if the process is gone and the loop should stop
     */
    private boolean step(String name, Runnable stage) {
        try {
            stage.run();
            return true;
        } catch (MemoryException e) {
            // Transient bad read (e.g. mid-transition). Keep going unless the process is gone.
            return memory.isValid();
        } catch (RuntimeException e) {
            log.log(Level.WARNING, name + " failed", e);
            return true;
        }
    }

    private static BooleanSupplier once(Runnable task) {
        return () -> {
            task.run();
            return true;
        };
    }

    @Override
    public synchronized void close() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
        if (memory instanceof ProcessMemory process) {
            process.close();
        }
    }
}
