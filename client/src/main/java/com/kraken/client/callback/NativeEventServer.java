package com.kraken.client.callback;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import com.kraken.api.GameSession;
import com.kraken.api.internal.event.NativeEvent;
import com.kraken.api.mapping.Mapping;
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
import java.nio.file.StandardCopyOption;
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
 * decides when the API reads. It lives in the session injector, so it exists once the API has attached and the mapping
 * for the running build is known. The client then calls {@link #install}, which injects {@code kraken_hook.dll} into
 * {@code osclient.exe} and hands it, in a handshake, the raise functions to hook for the events in
 * {@link NativeEvents} (their RVAs, ids and capture plans come from the mapping). The DLL sends one message per raise;
 * this parses each into a {@link NativeEvent} and hands it to the API on the client thread with
 * {@link GameSession#onNative}, where the matching tracker reads and posts the {@code com.kraken.api.events} event.
 * Plugins see only those events, exactly as from the poll path.
 *
 * <p>This is the one place the client writes to the game process, and it installs on every launch. The DLL ships in
 * the jar (staged there by the {@code buildNativeHook} Gradle task) and is extracted to a temporary file before
 * injection, since {@code LoadLibraryA} needs a path on disk; {@code -Dkraken.hook.dll} points at a DLL on disk
 * instead. If neither can be found the launch continues without the hook, and the events come from the timer poll
 * alone.
 *
 * <p>Nothing here is hard-coded per client build: every RVA, event id and capture plan comes from the mapping and is
 * handed to the DLL in the handshake. See {@code docs/event-hooks-design.md}.
 */
@Log(topic = "kraken")
@Singleton
public final class NativeEventServer {

    /** Loopback port the injected DLL connects back on; must match {@code KRAKEN_PORT} in kraken_hook.cpp. */
    static final int PORT = 42069;
    private static final String DLL_PROP = "kraken.hook.dll";
    private static final String STEP_PROP = "kraken.hook.projectileStep";

    /** Synthetic ids for the function hooks, which carry no {@code osrs.Events} id; kept clear of the real ids. */
    private static final int FUNCTION_ID_BASE = 1000;

    private final GameSession session;
    private final Mapping mapping;
    private final long pid;
    private final AtomicBoolean armed = new AtomicBoolean();
    private final List<Tap> taps = new CopyOnWriteArrayList<>();

    /**
     * Creates the server for the attached game.
     *
     * @param session the attached API, which the hook's events are handed to
     * @param mapping the attached build's mapping, which says what to hook
     * @param pid     the {@code osclient.exe} process id to inject into
     */
    @Inject
    NativeEventServer(GameSession session, Mapping mapping, @Named("pid") long pid) {
        this.session = session;
        this.mapping = mapping;
        this.pid = pid;
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
     * Installs the hook on a thread of its own ({@code kraken-hook-install}): finds the DLL, starts the loopback
     * server and injects the DLL into the game. The client calls this once the API attaches; only the first call does
     * anything. If the DLL can't be found or injected, it logs why and the events come from the timer poll alone.
     */
    public void install() {
        if (armed.compareAndSet(false, true)) {
            Thread thread = new Thread(this::inject, "kraken-hook-install");
            thread.setDaemon(true);
            thread.start();
        }
    }

    /**
     * Builds the handshake from the mapping, starts the server and injects the DLL. Runs on the install thread.
     */
    private void inject() {
        Path dll = locateDll();
        if (dll == null) {
            return;
        }
        Build build = build();
        if (build.slots.isEmpty()) {
            log.warning("Native event hook: mapping " + mapping.version()
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
            Thread serverThread = new Thread(() -> serve(bound, handshake, build.idToName), "kraken-hook-server");
            serverThread.setDaemon(true);
            serverThread.start();

            log.info("Injecting native event hook into pid " + pid + " (" + build.slots.size() + " slots for client "
                    + mapping.version() + ")");
            CallbackInjector.inject((int) pid, dll);
        } catch (IOException | RuntimeException e) {
            log.log(Level.WARNING, "Could not install the native event hook", e);
            closeQuietly(server);
        }
    }

    /**
     * Finds the DLL to inject.
     *
     * @return the absolute path to the DLL on disk, or null if it could not be found
     */
    private Path locateDll() {
        Path extracted = extractBundledDll();
        if (extracted == null) {
            log.warning("kraken_hook.dll was not found on the classpath "
                    + "(build and bundle it with ./gradlew :client:buildNativeHook) Game Events will not be received.");
        }
        return extracted;
    }

    /**
     * Copies the bundled DLL out of the jar to a temporary file so it can be injected. The file is marked for deletion
     * on exit; the game process keeps it mapped while the hook lives, so the delete is best-effort.
     *
     * @return the absolute path to the extracted DLL, or null if it is not on the classpath or could not be written
     */
    private Path extractBundledDll() {
        try (InputStream in = NativeEventServer.class.getResourceAsStream("/native/kraken_hook.dll")) {
            if (in == null) {
                return null;
            }
            Path dll = Files.createTempFile("kraken_hook", ".dll");
            dll.toFile().deleteOnExit();
            Files.copy(in, dll, StandardCopyOption.REPLACE_EXISTING);
            return dll.toAbsolutePath();
        } catch (IOException e) {
            log.log(Level.WARNING, "Native event hook: could not extract kraken_hook.dll from the classpath", e);
            return null;
        }
    }

    /**
     * The slot table and the id-to-name map built from one mapping.
     *
     * @param slots    the hooks to install
     * @param idToName an event id to its name, for both the real {@code enums.events} ids and the function hooks
     */
    private record Build(List<NativeProtocol.Slot> slots, Map<Integer, String> idToName) {}

    /**
     * Builds the slot table and id-to-name map from the mapping: a slot per distinct raiser of each wanted event in
     * {@link NativeEvents}, plus the function hooks. Events and functions the mapping lacks are logged and skipped;
     * their trackers then fall back to the timer poll.
     *
     * @return the slots and the id-to-name map
     */
    private Build build() {
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
     */
    private void serve(ServerSocket server, byte[] handshake, Map<Integer, String> idToName) {
        try (ServerSocket toClose = server) {
            while (!Thread.currentThread().isInterrupted()) {
                try (Socket socket = server.accept();
                     InputStream in = socket.getInputStream()) {
                    OutputStream out = socket.getOutputStream();
                    out.write(handshake);
                    out.flush();
                    session.setHookConnected(true);
                    log.info("Native event hook connected from " + socket.getRemoteSocketAddress());
                    read(new DataInputStream(in), idToName);
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
     * @throws IOException if the connection breaks
     */
    private void read(DataInputStream in, Map<Integer, String> idToName) throws IOException {
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
