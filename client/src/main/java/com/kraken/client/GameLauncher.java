package com.kraken.client;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import com.kraken.client.ui.DevToolsPanel;
import com.sun.jna.platform.win32.WinDef.HWND;
import lombok.AllArgsConstructor;
import lombok.extern.java.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Starts osclient.exe, embeds its window in the {@link GameView} and keeps it fitted there until the client exits. */
@Log(topic = "kraken")
@Singleton
@AllArgsConstructor(onConstructor_ = @Inject)
public final class GameLauncher {
    @Named("exe")
    private final File exe;
    private final GameView view;
    private final HomePanel home;
    private final DevToolsPanel devTools;

    /** Blocks while the client runs. If it can't be started, logs why and says so on the game view. */
    void launch() {
        try {
            run();
        } catch (Exception e) {
            log.log(Level.SEVERE, "Could not launch " + exe, e);
            view.setMessage("Could not launch the client, see the console for details");
        }
    }

    private void run() throws Exception {
        String build = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(exe.toPath())), 0, 8);
        home.set("Build", build);
        log.info("Launching " + exe + " (build " + build + ")");

        Process process = new ProcessBuilder(exe.getPath()).directory(exe.getParentFile()).redirectErrorStream(true).start();
        Runtime.getRuntime().addShutdownHook(new Thread(process::destroy));
        process.onExit().thenRun(() -> System.exit(0));
        Logger clientLog = Logger.getLogger("osclient");
        Thread output = new Thread(() -> new BufferedReader(new InputStreamReader(process.getInputStream())).lines().forEach(clientLog::info), "osclient-output");
        output.setDaemon(true);
        output.start();
        home.set("PID", String.valueOf(process.pid()));
        log.info("osclient started (pid " + process.pid() + "), waiting for its window");

        HWND hwnd;
        while ((hwnd = GameView.find(process.pid())) == null) {
            Thread.sleep(100);
        }
        view.embed(hwnd);

        // Attach the native API dev tools to the running client (read-only; safe if it isn't logged in yet)
        devTools.start(process.pid());

        // The client resizes itself (restoring its saved size, fixed mode, ...), so keep it pinned to the canvas
        while (process.isAlive()) {
            Thread.sleep(250);
            view.fit();
        }
    }
}
