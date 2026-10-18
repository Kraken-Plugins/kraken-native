package com.kraken.api;

import com.kraken.api.events.Events;
import com.kraken.api.game.Client;
import com.kraken.api.mapping.Mapping;
import com.kraken.api.memory.MemoryException;
import com.kraken.api.memory.ProcessMemory;
import lombok.Setter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Entry point for the native API. {@link #attach} opens the running {@code osclient.exe}, selects the
 * mapping for its client version ({@code mappings/<version>.json}), and exposes a {@link Client} (reads) and {@link Events} (subscriptions).
 * {@link #start()} runs a background poll loop that synthesises events by diffing successive snapshots.
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
    private final Client client;
    private final Events events = new Events();

    private volatile boolean running;
    /**
     * -- SETTER --
     *  A callback run once per poll cycle on the poll thread (throttled), after events are dispatched. The
     *  dev tools use it to refresh the live view. It may read through
     * ; it must marshal any
     *  UI work to its own thread.
     */
    @Setter
    private volatile Runnable pollListener;
    private Thread thread;

    private KrakenApi(ProcessMemory memory, Mapping mapping) {
        this.memory = memory;
        this.mapping = mapping;
        this.client = new Client(memory, mapping);
    }

    /**
     * Attaches to the given process using the mapping bundled with the API for its client version (the classpath
     * resource {@code mappings/<version>.json}).
     *
     * @param pid the {@code osclient.exe} process id
     * @param exe path to that binary on disk (its version picks the mapping; its hash must match it)
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

    public Client client() {
        return client;
    }

    public Events events() {
        return events;
    }

    public Mapping mapping() {
        return mapping;
    }

    public boolean isAttached() {
        return memory.isValid();
    }

    /** Starts the background poll loop. Idempotent. */
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
        int previousTick = -1;
        Map<Integer, String> loadedNpcs = new HashMap<>(); // index -> name, so we can name unload events
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
                int tick = client.getTickCount();
                if (tick != previousTick) {
                    previousTick = tick;
                    events.fireTick();
                }
                diffNpcs(loadedNpcs);

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

    private void diffNpcs(Map<Integer, String> loaded) {
        int[] current = client.getNpcIdAll();
        Set<Integer> currentSet = new HashSet<>(current.length * 2);
        for (int index : current) {
            currentSet.add(index);
            if (!loaded.containsKey(index)) {
                String name = client.npcName(index);
                loaded.put(index, name);
                events.fireNpc(Events.ON_NPC_LOAD, name, index);
            }
        }
        for (Iterator<Map.Entry<Integer, String>> it = loaded.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, String> entry = it.next();
            if (!currentSet.contains(entry.getKey())) {
                events.fireNpc(Events.ON_NPC_UNLOAD, entry.getValue(), entry.getKey());
                it.remove();
            }
        }
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
