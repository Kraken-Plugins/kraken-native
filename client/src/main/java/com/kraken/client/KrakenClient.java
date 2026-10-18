package com.kraken.client;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.kraken.client.ui.KrakenFrame;
import com.kraken.client.ui.Theme;
import lombok.extern.java.Log;

import javax.swing.*;
import java.io.File;
import java.util.logging.Level;

/**
 * Proof of concept: launches osclient.exe and re-parents its window into a RuneLite-style Swing frame.
 * Usage: KrakenClient [path\to\osclient.exe]
 */
@Log(topic = "kraken")
public class KrakenClient {
    private static final String DEFAULT_EXE =
            "C:\\Program Files (x86)\\Jagex Launcher\\Games\\Old School RuneScape\\Client\\osclient.exe";

    public static void main(String[] args) throws Exception {
        File exe = new File(args.length > 0 ? args[0] : DEFAULT_EXE);
        Injector injector = Guice.createInjector(new KrakenModule(exe));

        // Requesting the frame creates every Swing component, so it has to happen on the EDT and after the theme
        SwingUtilities.invokeAndWait(() -> {
            Theme.install();
            injector.getInstance(KrakenFrame.class).setVisible(true);
        });

        Thread.setDefaultUncaughtExceptionHandler((t, e) -> log.log(Level.SEVERE, "Uncaught exception on " + t.getName(), e));
        log.info("Kraken starting on Java " + Runtime.version());
        injector.getInstance(GameLauncher.class).launch();
    }
}
