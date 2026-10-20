package com.kraken.client.hook;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.kraken.api.mapping.Mapping;
import com.kraken.client.ApiConnection;
import lombok.extern.java.Log;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * Push-based {@code ON_GAME_TICK}, as an alternative to {@code EventPoller}'s poll-and-diff. Once the
 * API has attached (so the mapping for the running build is known), this injects
 * {@code kraken_hook.dll} into {@code osclient.exe} and inline-hooks the client's event dispatcher.
 * The DLL signals back over a loopback socket the instant the client dispatches a game tick, and
 * each signal runs the registered {@link #onTick listeners}.
 *
 * <p>This is the one place the client writes to the game process, so it is <b>off by default</b> and
 * only arms when {@code -Dkraken.hook=true} is set. The DLL is located via {@code -Dkraken.hook.dll}
 * (default {@value #DEFAULT_DLL}); if it is missing the launch continues without the hook.
 *
 * <p>The hook's RVA and the tick event id are not hard-coded here: they come from the mapping
 * ({@code functions.dispatchEvent} and {@code enums.events.ON_GAME_TICK}) and are handed to the DLL
 * in a short handshake, so this stays correct across client builds the same way the rest of the API
 * does.
 */
@Log(topic = "kraken")
@Singleton
public final class GameTickHook {

    /** Loopback port the injected DLL connects back on; must match {@code KRAKEN_PORT} in kraken_hook.cpp. */
    private static final int PORT = 42069;
    private static final String ENABLE_PROP = "kraken.hook";
    private static final String DLL_PROP = "kraken.hook.dll";
    private static final String DEFAULT_DLL = "client/src/main/native/kraken_hook/kraken_hook.dll";
    private static final String DISPATCH_FN = "dispatchEvent";

    private final ApiConnection api;
    private final AtomicBoolean armed = new AtomicBoolean();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private volatile long ticks;

    @Inject
    GameTickHook(ApiConnection api) {
        this.api = api;
    }

    /**
     * Registers a listener run on the hook thread for every pushed game tick. It must hand any UI
     * work to the EDT.
     *
     * @param listener the callback to run per tick
     */
    public void onTick(Runnable listener) {
        listeners.add(listener);
    }

    /**
     * Game ticks seen since the hook connected.
     *
     * @return the running tick count
     */
    public long tickCount() {
        return ticks;
    }

    /**
     * Arms the hook for the given client process: injects once the API reaches
     * {@link ApiConnection.State#ATTACHED}. A no-op unless {@code -Dkraken.hook=true}. Safe to call
     * once per launch.
     *
     * @param pid the {@code osclient.exe} process id
     */
    public void installWhenAttached(long pid) {
        if (!Boolean.getBoolean(ENABLE_PROP)) {
            return;
        }
        api.addStateListener(() -> maybeInstall(pid));
        maybeInstall(pid); // in case it attached between api.start() and here
    }

    private void maybeInstall(long pid) {
        if (api.getState() == ApiConnection.State.ATTACHED && armed.compareAndSet(false, true)) {
            Thread thread = new Thread(() -> install(pid), "kraken-hook-install");
            thread.setDaemon(true);
            thread.start();
        }
    }

    private void install(long pid) {
        Path dll = Path.of(System.getProperty(DLL_PROP, DEFAULT_DLL)).toAbsolutePath();
        if (!Files.isReadable(dll)) {
            log.warning("Game-tick hook is enabled but the DLL was not found: " + dll
                    + " (build it with client/src/main/native/kraken_hook/build.ps1). Continuing without it.");
            return;
        }
        Mapping mapping = api.api().mapping();
        if (!mapping.hasFunction(DISPATCH_FN)) {
            log.warning("Mapping " + mapping.version() + " has no functions." + DISPATCH_FN
                    + "; cannot install the game-tick hook.");
            return;
        }
        long rva = mapping.function(DISPATCH_FN);
        int tickEventId = mapping.enumValue("events", "ON_GAME_TICK", 44);
        ServerSocket server = null;
        try {
            server = new ServerSocket();
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), PORT));
            Thread serverThread = new Thread(serveTask(server, rva, tickEventId), "kraken-hook-server");
            serverThread.setDaemon(true);
            serverThread.start();

            log.info("Injecting game-tick hook into pid " + pid + " (" + DISPATCH_FN + " 0x"
                    + Long.toHexString(rva) + ", ON_GAME_TICK " + tickEventId + ")");
            HookInjector.inject((int) pid, dll);
        } catch (IOException | RuntimeException e) {
            log.log(Level.WARNING, "Could not install the game-tick hook", e);
            closeQuietly(server); // unblock the waiting accept() so the port is freed
        }
    }

    private Runnable serveTask(ServerSocket server, long rva, int tickEventId) {
        return () -> serve(server, rva, tickEventId);
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

    /**
     * Accepts the DLL's connection, sends the handshake telling it what to hook, then fires a tick
     * for every byte it sends back.
     *
     * @param server      the already-bound loopback server socket
     * @param rva         the dispatcher's RVA for this build
     * @param tickEventId the {@code ON_GAME_TICK} event id for this build
     */
    private void serve(ServerSocket server, long rva, int tickEventId) {
        try (ServerSocket toClose = server;
             Socket socket = server.accept();
             InputStream in = socket.getInputStream()) {
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.write(ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putLong(rva).putInt(tickEventId).array());
            out.flush();
            log.info("Game-tick hook connected from " + socket.getRemoteSocketAddress());

            while (in.read() != -1) {
                ticks++;
                fire();
            }
            log.info("Game-tick hook disconnected after " + ticks + " ticks");
        } catch (IOException e) {
            log.log(Level.WARNING, "Game-tick hook server error", e);
        }
    }

    private void fire() {
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                log.log(Level.WARNING, "Game-tick listener failed", e);
            }
        }
    }
}
