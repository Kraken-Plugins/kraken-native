package com.kraken.client.callback;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.kraken.api.GameSession;
import com.kraken.api.internal.NativeEvent;
import com.kraken.api.mapping.Mapping;
import com.kraken.client.ProcessAttacher;
import lombok.extern.java.Log;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Pushes the client's own event raises to the API, replacing {@code EventSource}'s timer poll as the thing that
 * decides when the API reads. Once the API has attached (so the mapping for the running build is known), this injects
 * {@code kraken_hook.dll} into {@code osclient.exe} and hands it, in a handshake, the raise functions to hook for the
 * events in {@link NativeEvents} (their RVAs, ids and capture plans come from the mapping). The DLL sends one message
 * per raise; this parses each into a {@link NativeEvent} and hands it to the API on the client thread with
 * {@link GameSession#onNative}, where the matching tracker reads and posts the {@code com.kraken.api.events} event.
 * Plugins see only those events, exactly as from the poll path.
 *
 * <p>This is the one place the client writes to the game process, so it is <b>off by default</b> and only arms when
 * {@code -Dkraken.hook=true} is set. The DLL is located via {@code -Dkraken.hook.dll} (default {@value #DEFAULT_DLL});
 * if it is missing the launch continues without the hook, and the events come from the timer poll alone.
 *
 * <p>Nothing here is hard-coded per client build: every RVA, event id and capture plan comes from the mapping and is
 * handed to the DLL in the handshake. See {@code docs/event-hooks-design.md}.
 */
@Log(topic = "kraken")
@Singleton
public final class NativeEventServer {

    /** Loopback port the injected DLL connects back on; must match {@code KRAKEN_PORT} in kraken_hook.cpp. */
    static final int PORT = 42069;
    private static final String ENABLE_PROP = "kraken.hook";
    private static final String DLL_PROP = "kraken.hook.dll";
    private static final String STEP_PROP = "kraken.hook.projectileStep";
    private static final String DEFAULT_DLL = "client/src/main/native/kraken_hook/kraken_hook.dll";

    /** Synthetic ids for the function hooks, which carry no {@code osrs.Events} id; kept clear of the real ids. */
    private static final int FUNCTION_ID_BASE = 1000;

    private final ProcessAttacher attacher;
    private final AtomicBoolean armed = new AtomicBoolean();
    private final List<Tap> taps = new CopyOnWriteArrayList<>();

    @Inject
    NativeEventServer(ProcessAttacher attacher) {
        this.attacher = attacher;
    }

    /**
     * A read-only listener for raw native messages, one kind or all, run on the reader thread.
     *
     * @param name     the event name to tap, or null for every native message
     * @param consumer the listener
     */
    private record Tap(String name, Consumer<NativeEvent> consumer) {
    }

    /**
     * Taps the raw native messages for diagnostics (the Event log dev tool). The listener runs on the reader thread
     * and must hand any UI work to its own thread; nothing the API delivers to plugins goes through it.
     *
     * @param name     the event name to tap, or null for every native message
     * @param consumer the listener
     */
    public void onEvent(String name, Consumer<NativeEvent> consumer) {
        taps.add(new Tap(name, consumer));
    }

    /**
     * Arms the hook for the given client process: injects once the API reaches {@link ProcessAttacher.State#ATTACHED}.
     * A no-op unless {@code -Dkraken.hook=true}. Safe to call once per launch.
     *
     * @param pid the {@code osclient.exe} process id
     */
    public void installWhenAttached(long pid) {
        if (!Boolean.getBoolean(ENABLE_PROP)) {
            log.info("Native event hook disabled (set -D" + ENABLE_PROP + "=true to push events instead of polling).");
            return;
        }
        attacher.addStateListener(() -> maybeInstall(pid));
        maybeInstall(pid); // in case it attached between attacher.start() and here
    }

    private void maybeInstall(long pid) {
        if (attacher.getState() == ProcessAttacher.State.ATTACHED && armed.compareAndSet(false, true)) {
            Thread thread = new Thread(() -> install(pid), "kraken-hook-install");
            thread.setDaemon(true);
            thread.start();
        }
    }

    private void install(long pid) {
        Path dll = Path.of(System.getProperty(DLL_PROP, DEFAULT_DLL)).toAbsolutePath();
        if (!Files.isReadable(dll)) {
            log.warning("Native event hook is enabled but the DLL was not found: " + dll
                    + " (build it with client/src/main/native/kraken_hook/build.ps1). Continuing without it.");
            return;
        }
        GameSession session = attacher.attached();
        if (session == null) {
            log.warning("Native event hook armed but the API is not attached; cannot install.");
            return;
        }
        Build build = build(session.mapping());
        if (build.slots.isEmpty()) {
            log.warning("Native event hook: mapping " + session.mapping().version()
                    + " has no hookable events; continuing on the timer poll.");
            return;
        }
        byte[] handshake = NativeProtocol.handshake(build.slots);
        ServerSocket server = null;
        try {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), PORT));
            ServerSocket bound = server;
            Thread serverThread = new Thread(() -> serve(bound, handshake, build.idToName, session),
                    "kraken-hook-server");
            serverThread.setDaemon(true);
            serverThread.start();

            log.info("Injecting native event hook into pid " + pid + " (" + build.slots.size() + " slots for client "
                    + session.mapping().version() + ")");
            CallbackInjector.inject((int) pid, dll);
        } catch (IOException | RuntimeException e) {
            log.log(Level.WARNING, "Could not install the native event hook", e);
            closeQuietly(server);
        }
    }

    /**
     * The slot table and the id-to-name map built from one mapping.
     *
     * @param slots    the hooks to install
     * @param idToName an event id to its name, for both the real {@code enums.events} ids and the function hooks
     */
    private record Build(List<NativeProtocol.Slot> slots, Map<Integer, String> idToName) {
    }

    /**
     * Builds the slot table and id-to-name map from the mapping: a slot per distinct raiser of each wanted event in
     * {@link NativeEvents}, plus the function hooks. Events and functions the mapping lacks are logged and skipped;
     * their trackers then fall back to the timer poll.
     *
     * @param mapping the attached client's mapping
     * @return the slots and the id-to-name map
     */
    private Build build(Mapping mapping) {
        List<NativeProtocol.Slot> slots = new ArrayList<>();
        Map<Long, Integer> byFn = new HashMap<>();
        Map<Integer, String> idToName = new HashMap<>();
        mapping.enumTable("events").forEach((name, id) -> idToName.put(id, name));

        for (Map.Entry<String, List<NativeProtocol.Plan>> entry : NativeEvents.LUA.entrySet()) {
            String name = entry.getKey();
            List<Mapping.EventHook> hooks = mapping.eventHooks(name);
            if (hooks.isEmpty()) {
                log.info("Native event hook: no raise site for " + name + " in mapping " + mapping.version()
                        + "; it falls back to the poll.");
                continue;
            }
            for (Mapping.EventHook hook : hooks) {
                int fixedId = hook.idFixed() ? hook.id() : -1;
                int idArg = hook.idFixed() ? 0 : hook.idArg();
                addSlot(slots, byFn, hook.fn(), fixedId, idArg, hook.args(), entry.getValue());
            }
        }

        List<String> functions = new ArrayList<>(NativeEvents.FUNCTIONS.keySet());
        if (Boolean.getBoolean(STEP_PROP)) {
            functions.add(NativeEvent.PROJECTILE_STEP);
        }
        int syntheticId = FUNCTION_ID_BASE;
        for (String name : functions) {
            if (!mapping.hasFunction(name)) {
                log.info("Native event hook: no function " + name + " in mapping " + mapping.version()
                        + "; it falls back to the poll.");
                continue;
            }
            int id = syntheticId++;
            idToName.put(id, name);
            addSlot(slots, byFn, mapping.function(name), id, 0, 4,
                    NativeEvents.FUNCTIONS.getOrDefault(name, List.of()));
        }
        return new Build(slots, idToName);
    }

    /**
     * Adds a slot for a raiser, or folds it into an existing one when a raiser is shared (the DLL hooks each distinct
     * function once). Shared raisers in the wanted set agree on their id configuration and plan; the longer plan and
     * larger argument count win.
     *
     * @param slots   the slots so far
     * @param byFn    raiser RVA to its slot index
     * @param fn      the raiser's RVA
     * @param fixedId the fixed event id, or -1 to read it from {@code idArg}
     * @param idArg   the argument register holding the id when {@code fixedId} is -1
     * @param args    the argument registers to forward
     * @param plan    the capture plan
     */
    private static void addSlot(List<NativeProtocol.Slot> slots, Map<Long, Integer> byFn, long fn, int fixedId,
                                int idArg, int args, List<NativeProtocol.Plan> plan) {
        Integer index = byFn.get(fn);
        if (index != null) {
            NativeProtocol.Slot existing = slots.get(index);
            List<NativeProtocol.Plan> mergedPlan = existing.plan().size() >= plan.size() ? existing.plan() : plan;
            int mergedArgs = Math.max(existing.args(), args);
            slots.set(index, new NativeProtocol.Slot(fn, existing.fixedId(), existing.idArg(), mergedArgs, mergedPlan));
            return;
        }
        byFn.put(fn, slots.size());
        slots.add(new NativeProtocol.Slot(fn, fixedId, idArg, args, plan));
    }

    /**
     * Accepts the DLL's connections, sends the handshake on each and reads its messages until it disconnects, then
     * waits to accept the next (the DLL reconnects after a dropped socket). Marks the API hooked while a connection
     * is up, so its timer poll slows to a safety-net rate and speeds back up when the hook drops.
     *
     * @param server    the already-bound loopback server socket
     * @param handshake the handshake bytes to send on each connection
     * @param idToName  an event id to its name
     * @param session   the attached API
     */
    private void serve(ServerSocket server, byte[] handshake, Map<Integer, String> idToName, GameSession session) {
        try (ServerSocket toClose = server) {
            while (!Thread.currentThread().isInterrupted()) {
                try (Socket socket = server.accept();
                     InputStream in = socket.getInputStream()) {
                    OutputStream out = socket.getOutputStream();
                    out.write(handshake);
                    out.flush();
                    session.setHookConnected(true);
                    log.info("Native event hook connected from " + socket.getRemoteSocketAddress());
                    read(new DataInputStream(in), idToName, session);
                    log.info("Native event hook disconnected; back on the timer poll until it reconnects.");
                } catch (IOException e) {
                    log.log(Level.FINE, "Native event hook connection ended", e);
                } catch (RuntimeException e) {
                    // A malformed message shouldn't take the accept loop down; drop the connection and wait for the
                    // DLL to reconnect, polling meanwhile.
                    log.log(Level.WARNING, "Native event hook connection failed", e);
                } finally {
                    session.setHookConnected(false);
                }
            }
        } catch (IOException e) {
            log.log(Level.WARNING, "Native event hook server error", e);
        }
    }

    /**
     * Reads framed messages from one connection, parses each and hands it to the API and the taps, until EOF.
     *
     * @param in       the connection's input
     * @param idToName an event id to its name
     * @param session  the attached API
     * @throws IOException if the connection breaks
     */
    private void read(DataInputStream in, Map<Integer, String> idToName, GameSession session) throws IOException {
        long expectedSeq = -1;
        for (int len = readLength(in); len >= 0; len = readLength(in)) {
            byte[] body = new byte[len];
            in.readFully(body);
            NativeEvent event = NativeProtocol.parse(body, idToName::get);
            if (expectedSeq >= 0 && event.seq() != expectedSeq) {
                log.fine("Native event hook: " + (event.seq() - expectedSeq) + " message(s) dropped before seq "
                        + event.seq());
            }
            expectedSeq = event.seq() + 1;
            if (event.id() == NativeProtocol.STATUS_EVENT_ID) {
                log.info("Native event hook installed " + event.reg(NativeEvent.RCX) + " of "
                        + event.reg(NativeEvent.RDX) + " requested hooks.");
                continue;
            }
            session.onNative(event);
            for (Tap tap : taps) {
                if (tap.name() == null || tap.name().equals(event.name())) {
                    deliver(tap, event);
                }
            }
        }
    }

    private void deliver(Tap tap, NativeEvent event) {
        try {
            tap.consumer().accept(event);
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "Native event tap failed", e);
        }
    }

    /**
     * Reads a message's 4-byte little-endian length frame.
     *
     * @param in the connection's input
     * @return the body length, or -1 at end of stream
     * @throws IOException if the connection breaks mid-frame
     */
    private static int readLength(DataInputStream in) throws IOException {
        byte[] frame = new byte[4];
        int read = 0;
        while (read < frame.length) {
            int n = in.read(frame, read, frame.length - read);
            if (n < 0) {
                return -1;
            }
            read += n;
        }
        return ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private static void closeQuietly(ServerSocket server) {
        if (server != null) {
            try {
                server.close();
            } catch (IOException ignored) {
                // nothing useful to do if closing the idle server fails
            }
        }
    }
}
