package com.kraken.api;

import com.kraken.api.eventbus.EventBus;
import com.kraken.api.impl.Adapters;
import com.kraken.api.impl.EventSource;
import com.kraken.api.lua.LuaApi;
import com.kraken.api.lua.events.EventPoller;
import com.kraken.api.lua.events.Events;
import com.kraken.api.mapping.Mapping;
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
 * its client version ({@code mappings/<version>.json}). Then {@link #client()} is the RuneLite-shaped API
 * ({@link Client}, {@code NPC}, {@code WorldPoint}…), and {@link #lua()} the Lua mirror it is built on, one object per
 * namespace of the client's Lua {@code osrs} table.
 *
 * <p>{@link #start()} runs the poll loop on the {@code kraken-api-poll} thread, which plays the part of RuneLite's
 * client thread. About every 20 ms it:
 * <ol>
 *   <li>runs the tasks handed to {@link #invoke(Runnable)} and friends;
 *   <li>synthesises the Lua events ({@code lua().events()}), then the RuneLite-shaped events on {@link #eventBus()},
 *       with {@code GameTick} after the tick's other events, then {@code ClientTick};
 *   <li>runs the {@link #invokeAtTickEnd} tasks if a game tick happened;
 *   <li>calls the {@link #setFrameListener frame listener}, where the client renders plugin overlays;
 *   <li>calls the {@link #setPollListener poll listener}, at most every 150 ms.
 * </ol>
 * Both kinds of events are only synthesised while something subscribes to them.
 *
 * <p>Read-only throughout: it never writes to or calls into the game.
 */
public final class KrakenApi implements AutoCloseable {

    private static final Logger log = Logger.getLogger("kraken.api");
    private static final String MODULE = "osclient.exe";
    private static final long POLL_INTERVAL_MS = 20;
    private static final long RENDER_INTERVAL_MS = 150;

    private final ProcessMemory memory;
    private final Mapping mapping;
    private final LuaApi lua;
    private final Client client;
    private final EventBus eventBus;
    private final EventPoller poller;
    private final EventSource source;
    private final TaskQueue tasks = new TaskQueue();

    private volatile boolean running;
    /**
     * A callback run on the poll thread after events are dispatched, at most every 150 ms. The dev tools use it to
     * refresh their views. It may read the game; it must hand any UI work to its own thread.
     */
    @Setter
    private volatile Runnable pollListener;
    /**
     * A callback run on the poll thread at the end of every poll (about every 20 ms), after the tick-end tasks and
     * before the poll listener. The client renders plugin overlays from it, deciding itself whether a frame is due.
     */
    @Setter
    private volatile Runnable frameListener;
    private Thread thread;

    private KrakenApi(ProcessMemory memory, Mapping mapping, EventBus eventBus) {
        this.memory = memory;
        this.mapping = mapping;
        this.eventBus = eventBus;
        this.lua = new LuaApi(memory, mapping);
        this.client = Adapters.client(lua);
        this.poller = new EventPoller(lua.events(), lua.clientOp(), lua.clientState(), lua.graphics(), lua.scene());
        this.source = Adapters.eventSource(client, lua, eventBus);
        for (String problem : Events.verify(mapping)) {
            log.warning("Event enum mismatch: " + problem);
        }
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
    public static KrakenApi attach(long pid, Path exe) throws IOException {
        return attach(pid, exe, new EventBus());
    }

    /**
     * Like {@link #attach(long, Path)}, posting the game events on a bus the caller already has, so subscribers that
     * registered before the game came up get them, the current state included (see {@link #eventBus()}).
     *
     * @param pid      the {@code osclient.exe} process id
     * @param exe      path to that binary on disk (its version picks the mapping; its hash must match it)
     * @param eventBus the bus to post the RuneLite-shaped game events on
     * @return the attached API; {@link #start} its poll loop to get events
     * @throws IOException               if the exe or the mapping can't be read
     * @throws UnsupportedBuildException if there is no mapping for the version, or it was made from another binary
     * @throws MemoryException           if the process cannot be opened
     */
    public static KrakenApi attach(long pid, Path exe, EventBus eventBus) throws IOException {
        ClientBuild build = ClientBuild.of(exe);
        String name = build.version() + ".json";
        Mapping mapping = Mapping.bundled(build.version());
        if (mapping == null) {
            throw new UnsupportedBuildException(build, "no bundled " + Mapping.RESOURCE_DIR + name);
        }
        return attach(pid, build, mapping, name, eventBus);
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
     * @throws UnsupportedBuildException if the folder has no mapping for the version, or it was made from another binary
     * @throws MemoryException           if the process cannot be opened
     */
    public static KrakenApi attach(long pid, Path exe, Path mappingsDir) throws IOException {
        return attach(pid, exe, mappingsDir, new EventBus());
    }

    /**
     * Like {@link #attach(long, Path, Path)}, posting the game events on a bus the caller already has.
     *
     * @param pid         the {@code osclient.exe} process id
     * @param exe         path to that binary on disk (its version picks the mapping; its hash must match it)
     * @param mappingsDir folder holding {@code <version>.json} mappings
     * @param eventBus    the bus to post the RuneLite-shaped game events on
     * @return the attached API; {@link #start} its poll loop to get events
     * @throws IOException               if the exe or the mapping can't be read
     * @throws UnsupportedBuildException if the folder has no mapping for the version, or it was made from another binary
     * @throws MemoryException           if the process cannot be opened
     */
    public static KrakenApi attach(long pid, Path exe, Path mappingsDir, EventBus eventBus) throws IOException {
        ClientBuild build = ClientBuild.of(exe);
        Path file = mappingsDir.resolve(build.version() + ".json");
        if (!Files.isRegularFile(file)) {
            throw new UnsupportedBuildException(build, "no " + file.getFileName() + " in " + mappingsDir);
        }
        return attach(pid, build, Mapping.load(file), file.getFileName().toString(), eventBus);
    }

    private static KrakenApi attach(long pid, ClientBuild build, Mapping mapping, String name, EventBus eventBus) {
        if (!mapping.sha256().equalsIgnoreCase(build.sha256())) {
            throw new UnsupportedBuildException(build, name + " was made from a different binary (sha256 "
                    + mapping.sha256().substring(0, 16) + "); re-run the updater for this one");
        }
        ProcessMemory memory = ProcessMemory.attach((int) pid, MODULE);
        log.info("Attached to " + MODULE + " pid " + pid + " (client " + build.version() + ", base 0x"
                + Long.toHexString(memory.moduleBase()) + ")");
        return new KrakenApi(memory, mapping, eventBus);
    }

    /**
     * The RuneLite-shaped API: the game client as RuneLite's {@code Client} presents it.
     *
     * @return the client, read through {@link #lua()}
     */
    public Client client() {
        return client;
    }

    /**
     * The Lua mirror: {@code osrs.ClientOp}, {@code osrs.ClientState}, {@code osrs.Events} and the other namespaces,
     * read as the client's native Lua handlers read them.
     *
     * @return the Lua mirror for this process
     */
    public LuaApi lua() {
        return lua;
    }

    /**
     * Where the RuneLite-shaped game events ({@code com.kraken.api.events}) are posted, on the poll thread. A kind of
     * event is only tracked while the bus has subscribers for it. Tracking that starts at the first poll replays the
     * current state (the game state, every loaded NPC and player as spawned, stats and inventories), as if the client
     * had just logged in; tracking that starts later takes a silent baseline.
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
     * returns true, as RuneLite's {@code ClientThread.invoke(BooleanSupplier)}.
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
        while (running) {
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (!memory.isValid()) {
                log.info("osclient.exe exited; stopping poll loop");
                break;
            }
            tasks.runPending();
            if (!step("Lua event poll", poller::poll)) {
                break;
            }
            boolean[] ticked = {false};
            if (!step("RuneLite event poll", () -> ticked[0] = source.poll())) {
                break;
            }
            if (ticked[0]) {
                tasks.runTickEnd();
            }
            Runnable frame = frameListener;
            if (frame != null && !step("Frame listener", frame)) {
                break;
            }

            long now = System.currentTimeMillis();
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
        memory.close();
    }
}
