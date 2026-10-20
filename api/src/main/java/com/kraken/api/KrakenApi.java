package com.kraken.api;

import com.kraken.api.impl.Adapters;
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
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Entry point for the native API. {@link #attach} opens the running {@code osclient.exe} and selects the mapping for
 * its client version ({@code mappings/<version>.json}). Then {@link #client()} is the RuneLite-shaped API
 * ({@link Client}, {@code NPC}, {@code WorldPoint}…), and {@link #lua()} the Lua mirror it is built on, one object per
 * namespace of the client's Lua {@code osrs} table. {@link #start()} runs a background poll loop that synthesises
 * events by diffing successive snapshots.
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
    private final EventPoller poller;

    private volatile boolean running;
    /**
     * A callback run on the poll thread after events are dispatched, at most every 150 ms. The dev tools use it to
     * refresh their views. It may read the game; it must hand any UI work to its own thread.
     */
    @Setter
    private volatile Runnable pollListener;
    private Thread thread;

    private KrakenApi(ProcessMemory memory, Mapping mapping) {
        this.memory = memory;
        this.mapping = mapping;
        this.lua = new LuaApi(memory, mapping);
        this.client = Adapters.client(lua);
        this.poller = new EventPoller(lua.events(), lua.clientOp(), lua.clientState(), lua.graphics(), lua.scene());
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
     * @return the attached API; {@link #start} its poll loop to get events
     * @throws IOException               if the exe or the mapping can't be read
     * @throws UnsupportedBuildException if there is no mapping for the version, or it was made from another binary
     * @throws MemoryException           if the process cannot be opened
     */
    public static KrakenApi attach(long pid, Path exe) throws IOException {
        ClientBuild build = ClientBuild.of(exe);
        String name = build.version() + ".json";
        Mapping mapping = Mapping.bundled(build.version());
        if (mapping == null) {
            throw new UnsupportedBuildException(build, "no bundled " + Mapping.RESOURCE_DIR + name);
        }
        return attach(pid, build, mapping, name);
    }

    /**
     * Like {@link #attach(long, Path)}, but reads {@code <mappingsDir>/<version>.json} from disk instead of the
     * bundled mapping, e.g. to try a mapping the updater wrote somewhere else.
     *
     * @param pid         the {@code osclient.exe} process id
     * @param exe         path to that binary on disk (its version picks the mapping; its hash must match it)
     * @param mappingsDir folder holding {@code <version>.json} mappings
     * @return the attached API; {@link #start} its poll loop to get events
     * @throws IOException               if the exe or the mapping can't be read
     * @throws UnsupportedBuildException if the folder has no mapping for the version, or it was made from another binary
     * @throws MemoryException           if the process cannot be opened
     */
    public static KrakenApi attach(long pid, Path exe, Path mappingsDir) throws IOException {
        ClientBuild build = ClientBuild.of(exe);
        Path file = mappingsDir.resolve(build.version() + ".json");
        if (!Files.isRegularFile(file)) {
            throw new UnsupportedBuildException(build, "no " + file.getFileName() + " in " + mappingsDir);
        }
        return attach(pid, build, Mapping.load(file), file.getFileName().toString());
    }

    private static KrakenApi attach(long pid, ClientBuild build, Mapping mapping, String name) {
        if (!mapping.sha256().equalsIgnoreCase(build.sha256())) {
            throw new UnsupportedBuildException(build, name + " was made from a different binary (sha256 "
                    + mapping.sha256().substring(0, 16) + "); re-run the updater for this one");
        }
        ProcessMemory memory = ProcessMemory.attach((int) pid, MODULE);
        log.info("Attached to " + MODULE + " pid " + pid + " (client " + build.version() + ", base 0x"
                + Long.toHexString(memory.moduleBase()) + ")");
        return new KrakenApi(memory, mapping);
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
            try {
                poller.poll();

                long now = System.currentTimeMillis();
                Runnable listener = pollListener;
                if (listener != null && now - lastRender >= RENDER_INTERVAL_MS) {
                    lastRender = now;
                    listener.run();
                }
            } catch (MemoryException e) {
                // Transient bad read (e.g. mid-transition). Keep going unless the process is gone.
                if (!memory.isValid()) {
                    break;
                }
            } catch (RuntimeException e) {
                log.log(Level.WARNING, "Poll iteration failed", e);
            }
        }
        running = false;
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
