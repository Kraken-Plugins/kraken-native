package com.kraken.client.callback;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import com.kraken.api.GameSession;
import com.kraken.api.internal.event.NativeEvent;
import com.kraken.api.internal.event.ScriptHook;
import com.kraken.api.internal.invoke.NativeCall;
import com.kraken.api.internal.menu.MenuHook;
import com.kraken.api.internal.menu.WireRule;
import com.kraken.api.mapping.Mapping;
import com.kraken.client.FrameRates;
import com.kraken.client.input.KeyHook;
import com.kraken.client.input.KeyManager;
import com.kraken.client.ui.overlay.GameSurface;
import lombok.extern.java.Log;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
 * <p>The same connection carries the drawing ({@code docs/in-game-drawing.md}). The handshake also names the
 * per-frame step the DLL runs calls in and the present it draws the overlay in. Once the DLL reports its game-thread
 * pump, the API's highlights get a {@link com.kraken.api.internal.invoke.NativeInvoker} that sends their calls here;
 * once it reports the present hook and a framebuffer, {@link GameSurface} hands it overlay frames. It also reports the
 * game's frame rate to {@link FrameRates}, and is told the frame cap to hold the game to from there.
 *
 * <p>The handshake also names the script runtime ({@link NativeProtocol#scriptLayout}). Once the DLL reports its script
 * hooks, the API gets a {@link ScriptHook} that tells the DLL which script events to send, and each script event the
 * DLL sends goes to {@link GameSession#onScript}, which posts {@code ScriptPreFired} or {@code ScriptPostFired}.
 *
 * <p>It names the minimenu ({@link NativeProtocol#menuLayout}) and the game window's procedure
 * ({@link NativeProtocol#keyLayout}) too. Once the DLL reports its menu hooks, the API's menu edits get a
 * {@link MenuHook} to send their rules through, and the clicks and expiries the DLL reports go to
 * {@link GameSession#onMenuClick} and {@link GameSession#onMenuExpired}; once it reports the key hook, the
 * {@link KeyManager} gets a {@link KeyHook} for its remapping, and the DLL's key reports go back to it.
 *
 * <p>Nothing here is hard-coded per client build: every RVA, event id and capture plan comes from the mapping and is
 * handed to the DLL in the handshake. See {@code docs/event-hooks-design.md}.
 */
@Log(topic = "kraken")
@Singleton
public final class NativeEventServer {

    /**
     * Loopback port the injected DLL connects back on; must match {@code KRAKEN_PORT} in the DLL's src/protocol.h.
     */
    static final int PORT = 42069;
    private static final String DLL_PROP = "kraken.hook.dll";
    private static final String STEP_PROP = "kraken.hook.projectileStep";

    /**
     * Synthetic ids for the function hooks, which carry no {@code osrs.Events} id; kept clear of the real ids.
     */
    private static final int FUNCTION_ID_BASE = 1000;

    /**
     * How long Kraken waits on exit for the DLL to run the calls it sent last (the plugins turning their highlights
     * off), since the DLL drops queued calls when the connection closes.
     */
    private static final long EXIT_FLUSH_MS = 1000;

    private final GameSession session;
    private final Mapping mapping;
    private final long pid;
    private final GameSurface surface;
    private final FrameRates frameRates;
    private final KeyManager keyManager;
    private final AtomicBoolean armed = new AtomicBoolean();
    private final List<Tap> taps = new CopyOnWriteArrayList<>();
    private final AtomicInteger batches = new AtomicInteger();

    /**
     * The batch last sent and the last one the DLL reported run, for the exit flush. Guarded by {@code this}.
     */
    private int lastSent;
    private int lastDone;
    private volatile Connection connection;

    /**
     * The connection whose DLL paces frames, which the frame cap goes to; null while there is none.
     */
    private volatile Connection pacing;

    /**
     * Creates the server for the attached game.
     *
     * @param session the attached API, which the hook's events are handed to
     * @param mapping the attached build's mapping, which says what to hook
     * @param pid     the {@code osclient.exe} process id to inject into
     * @param surface    the overlay surface, handed to the DLL once it draws
     * @param frameRates where the game's frame rate goes, and the frame cap comes from
     * @param keyManager the key manager, handed the DLL's key remapping
     */
    @Inject
    NativeEventServer(GameSession session, Mapping mapping, @Named("pid") long pid, GameSurface surface,
                      FrameRates frameRates, KeyManager keyManager) {
        this.session = session;
        this.mapping = mapping;
        this.pid = pid;
        this.surface = surface;
        this.frameRates = frameRates;
        this.keyManager = keyManager;
        frameRates.addFrameLimitListener(this::sendFrameLimit);
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
        NativeProtocol.Drawing drawing = drawing();
        byte[] scripts = NativeProtocol.scriptLayout(mapping);
        if (scripts.length == 0) {
            log.info("Native event hook: no script runtime in mapping " + mapping.version()
                    + "; ScriptPreFired and ScriptPostFired won't be posted.");
        }
        byte[] menu = NativeProtocol.menuLayout(mapping);
        if (menu.length == 0) {
            log.info("Native event hook: no minimenu functions in mapping " + mapping.version()
                    + "; the right-click menu can't be edited.");
        }
        byte[] keys = NativeProtocol.keyLayout(mapping);
        if (keys.length == 0) {
            log.info("Native event hook: no window procedure in mapping " + mapping.version()
                    + "; keys can't be remapped.");
        }
        if (build.slots.isEmpty() && drawing.pumpFn() == 0 && drawing.presentFn() == 0 && scripts.length == 0
                && menu.length == 0 && keys.length == 0) {
            log.warning("Native event hook: mapping " + mapping.version()
                    + " has no hookable events or drawing functions; continuing on the timer poll.");
            return;
        }
        byte[] handshake = NativeProtocol.handshake(build.slots, drawing, scripts, menu, keys);
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
            Runtime.getRuntime().addShutdownHook(new Thread(this::flushOnExit, "kraken-hook-flush"));
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
     * The drawing functions the handshake names, from the mapping. A function the mapping lacks is sent as 0, and the
     * DLL leaves that part off: no calls without the pump, no overlay without the present.
     *
     * @return the client pointer, the pump and the present
     */
    private NativeProtocol.Drawing drawing() {
        long client = mapping.hasGlobal("client") ? mapping.global("client") : 0;
        long pump = mapping.hasFunction("appUpdate") ? mapping.function("appUpdate") : 0;
        long present = mapping.hasFunction("present") ? mapping.function("present") : 0;
        if (pump == 0) {
            log.info("Native event hook: no appUpdate in mapping " + mapping.version()
                    + "; highlights won't show in the game.");
        }
        if (present == 0) {
            log.info("Native event hook: no present in mapping " + mapping.version()
                    + "; overlays stay in the window over the game.");
        }
        return new NativeProtocol.Drawing(client, pump, present);
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
                    socket.setTcpNoDelay(true);
                    Connection open = new Connection(socket.getOutputStream());
                    open.send(handshake);
                    connection = open;
                    session.setHookConnected(true);
                    log.info("Native event hook connected from " + socket.getRemoteSocketAddress());
                    read(new DataInputStream(in), idToName, open);
                    log.info("Native event hook disconnected; back on the timer poll until it reconnects.");
                } catch (IOException e) {
                    log.log(Level.FINE, "Native event hook connection ended", e);
                } catch (RuntimeException e) {
                    // A malformed message shouldn't take the accept loop down; drop the connection and wait for the
                    // DLL to reconnect, polling meanwhile.
                    log.log(Level.WARNING, "Native event hook connection failed", e);
                } finally {
                    connection = null;
                    pacing = null;
                    frameRates.gameDisconnected();
                    session.setNativeInvoker(null);
                    session.setScriptHook(null);
                    session.setMenuHook(null);
                    keyManager.setKeyHook(null);
                    surface.disconnected();
                    session.setHookConnected(false);
                    synchronized (this) {
                        notifyAll();
                    }
                }
            }
        } catch (IOException e) {
            log.log(Level.WARNING, "Native event hook server error", e);
        }
    }

    /**
     * Reads framed messages from one connection, parses each and hands it to the API and the taps, until EOF. Script
     * events, menu clicks and expiries and key reports go where they belong; the DLL's status messages (the other
     * reserved negative ids) set up the drawing instead.
     *
     * @param in       the connection's input
     * @param idToName an event id to its name
     * @param open     the connection, for the drawing's commands
     * @throws IOException if the connection breaks
     */
    private void read(DataInputStream in, Map<Integer, String> idToName, Connection open) throws IOException {
        long expectedSeq = -1;
        for (int len = readLength(in); len >= 0; len = readLength(in)) {
            byte[] body = new byte[len];
            in.readFully(body);
            int id = NativeProtocol.eventId(body);
            if (id < 0 && !ringReserved(id)) {
                // Status messages skip the DLL's ring, so their sequence numbers interleave with the raises'
                status(NativeProtocol.parse(body, idToName::get), open);
                continue;
            }
            long seq = NativeProtocol.seq(body);
            if (expectedSeq >= 0 && seq != expectedSeq) {
                log.fine("Native event hook: " + (seq - expectedSeq) + " message(s) dropped before seq " + seq);
            }
            expectedSeq = seq + 1;
            if (id == NativeProtocol.SCRIPT_EVENT_ID) {
                session.onScript(NativeProtocol.parseScript(body));
                continue;
            }
            NativeEvent event = NativeProtocol.parse(body, idToName::get);
            if (id == NativeProtocol.MENU_CLICK_EVENT_ID) {
                session.onMenuClick(NativeProtocol.parseMenuClick(event));
                continue;
            }
            if (id == NativeProtocol.MENU_EXPIRED_EVENT_ID) {
                session.onMenuExpired((int) event.reg(NativeEvent.RCX));
                continue;
            }
            if (id == NativeProtocol.KEY_EVENT_ID) {
                keyManager.onKeyReport(NativeProtocol.parseKey(event));
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

    /**
     * Whether a reserved id's messages go through the DLL's ring, in sequence with the raises, rather than straight
     * from the writer thread.
     *
     * @param id the reserved id
     * @return true for script events, menu clicks and expiries and key reports
     */
    private static boolean ringReserved(int id) {
        return id == NativeProtocol.SCRIPT_EVENT_ID || id == NativeProtocol.MENU_CLICK_EVENT_ID
                || id == NativeProtocol.MENU_EXPIRED_EVENT_ID || id == NativeProtocol.KEY_EVENT_ID;
    }

    /**
     * Acts on one of the DLL's status messages: the hooks and features after the handshake, the framebuffer for the
     * overlay, the result of a batch of calls, and the game's frame rate.
     *
     * @param event the status message
     * @param open  the connection it came on
     */
    private void status(NativeEvent event, Connection open) {
        switch (event.id()) {
            case NativeProtocol.STATUS_EVENT_ID -> {
                long features = event.reg(NativeEvent.R8);
                log.info("Native event hook installed " + event.reg(NativeEvent.RCX) + " of "
                        + event.reg(NativeEvent.RDX) + " requested hooks (protocol " + event.reg(NativeEvent.R9)
                        + ", calls " + ((features & NativeProtocol.FEATURE_CALLS) != 0) + ", overlay "
                        + ((features & NativeProtocol.FEATURE_OVERLAY) != 0) + ", frame rate "
                        + ((features & NativeProtocol.FEATURE_FRAME_RATE) != 0) + ", scripts "
                        + ((features & NativeProtocol.FEATURE_SCRIPTS) != 0) + ", menu "
                        + ((features & NativeProtocol.FEATURE_MENU) != 0) + ", keys "
                        + ((features & NativeProtocol.FEATURE_KEYS) != 0) + ").");
                if ((features & NativeProtocol.FEATURE_CALLS) != 0) {
                    session.setNativeInvoker(batch -> sendCalls(open, batch));
                }
                if ((features & NativeProtocol.FEATURE_SCRIPTS) != 0) {
                    session.setScriptHook(scriptHook(open));
                }
                if ((features & NativeProtocol.FEATURE_MENU) != 0) {
                    session.setMenuHook(menuHook(open));
                }
                if ((features & NativeProtocol.FEATURE_KEYS) != 0) {
                    keyManager.setKeyHook(keyHook(open));
                }
                if ((features & NativeProtocol.FEATURE_OVERLAY) != 0) {
                    surface.connected((generation, name) -> sendSurface(open, generation, name), pid);
                }
                long pacingFeatures = NativeProtocol.FEATURE_CALLS | NativeProtocol.FEATURE_FRAME_RATE;
                if ((features & pacingFeatures) == pacingFeatures) {
                    // The DLL starts each connection uncapped; set first, so a change from here on is sent too
                    pacing = open;
                    sendFrameLimit(frameRates.getFrameLimit());
                }
            }
            case NativeProtocol.FPS_EVENT_ID -> frameRates.gameFrames(event.reg(NativeEvent.RCX),
                    event.reg(NativeEvent.RDX));
            case NativeProtocol.FRAME_EVENT_ID -> {
                int width = (int) event.reg(NativeEvent.RCX);
                int height = (int) event.reg(NativeEvent.RDX);
                long version = event.reg(NativeEvent.R8);
                int generation = (int) event.reg(NativeEvent.R9);
                log.fine("Native event hook: framebuffer " + width + "x" + height + ", GL " + (version >>> 16) + "."
                        + (version & 0xffff) + ", drawing surface " + generation);
                surface.frame(width, height, (int) version, generation);
            }
            case NativeProtocol.CALLS_DONE_EVENT_ID -> {
                int batch = (int) event.reg(NativeEvent.RCX);
                long failed = event.reg(NativeEvent.R8);
                if (failed != 0) {
                    log.warning("Native event hook: " + failed + " of the calls in batch " + batch
                            + " faulted or were skipped (" + event.reg(NativeEvent.RDX) + " ran)");
                }
                synchronized (this) {
                    lastDone = batch;
                    notifyAll();
                }
            }
            default -> log.fine("Native event hook: unknown status " + event.id());
        }
    }

    /**
     * Sends a batch of calls on a connection.
     *
     * @param open  the connection
     * @param calls the calls
     * @throws UncheckedIOException if the connection broke
     */
    private void sendCalls(Connection open, List<NativeCall> calls) {
        int batch = batches.incrementAndGet();
        try {
            open.send(NativeProtocol.calls(batch, calls));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        synchronized (this) {
            lastSent = batch;
        }
    }

    /**
     * Tells the DLL which overlay surface to draw.
     *
     * @param open       the connection
     * @param generation the surface's generation
     * @param name       its file mapping's name
     * @throws UncheckedIOException if the connection broke
     */
    private static void sendSurface(Connection open, int generation, String name) {
        try {
            open.send(NativeProtocol.surface(generation, name));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The switch the API turns the DLL's script events on and off with, over one connection.
     *
     * @param open the connection
     * @return the hook; it throws {@link UncheckedIOException} if the connection broke
     */
    private static ScriptHook scriptHook(Connection open) {
        return (pre, post, roots) -> {
            try {
                open.send(NativeProtocol.scriptCapture(pre, post, roots));
                log.fine("Native event hook: script events " + (pre ? "on start" : "") + (pre && post ? " and " : "")
                        + (post ? "on return" : "") + (pre || post ? "" : "off")
                        + (roots.length > 0 ? ", root scripts " + Arrays.toString(roots) : ""));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
    }

    /**
     * The sender the API's menu edits push their rules through, over one connection.
     *
     * @param open the connection
     * @return the hook; it throws {@link UncheckedIOException} if the connection broke
     */
    private static MenuHook menuHook(Connection open) {
        return new MenuHook() {
            @Override
            public void replace(List<WireRule> rules) {
                write(NativeProtocol.menuRules(NativeProtocol.MENU_REPLACE, rules));
            }

            @Override
            public void upsert(List<WireRule> rules) {
                write(NativeProtocol.menuRules(NativeProtocol.MENU_UPSERT, rules));
            }

            @Override
            public void remove(int[] ids) {
                write(NativeProtocol.menuRemove(ids));
            }

            private void write(byte[] frame) {
                try {
                    open.send(frame);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        };
    }

    /**
     * The sender the key manager pushes its remapping through, over one connection.
     *
     * @param open the connection
     * @return the hook; it throws {@link UncheckedIOException} if the connection broke
     */
    private static KeyHook keyHook(Connection open) {
        return new KeyHook() {
            @Override
            public void table(List<Mode> modes) {
                write(NativeProtocol.keysTable(modes));
            }

            @Override
            public void mode(int mode) {
                write(NativeProtocol.keysMode(mode));
            }

            @Override
            public void report(boolean on) {
                write(NativeProtocol.keysReport(on));
            }

            private void write(byte[] frame) {
                try {
                    open.send(frame);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        };
    }

    /**
     * Tells the DLL the frame rate to hold the game to, if a DLL that paces frames is connected.
     *
     * @param fps frames a second, or 0 for no cap
     */
    private void sendFrameLimit(int fps) {
        Connection open = pacing;
        if (open == null) {
            return;
        }
        try {
            open.send(NativeProtocol.frameLimit(fps));
            log.fine("Native event hook: frame cap " + (fps > 0 ? fps + " fps" : "off"));
        } catch (IOException e) {
            log.log(Level.FINE, "Native event hook: could not send the frame cap", e);
        }
    }

    /**
     * Waits, as Kraken exits, for the DLL to run the calls sent last: the plugins turn their highlights off as they
     * stop, and the DLL drops calls still queued when the connection closes.
     */
    private void flushOnExit() {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(EXIT_FLUSH_MS);
        synchronized (this) {
            while (connection != null && lastDone - lastSent < 0) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    log.fine("Native event hook: exiting before the last calls ran");
                    return;
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(this, left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /**
     * One connection's output. Writes are whole frames, from whichever thread sends (the handshake, highlights from
     * plugins, the overlay surface), so they never interleave.
     */
    private static final class Connection {

        private final OutputStream out;

        Connection(OutputStream out) {
            this.out = out;
        }

        synchronized void send(byte[] frame) throws IOException {
            out.write(frame);
            out.flush();
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
