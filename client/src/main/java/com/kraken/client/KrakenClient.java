package com.kraken.client;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.kraken.client.callback.NativeEventServer;
import com.kraken.client.plugins.Plugin;
import com.kraken.client.plugins.PluginManager;
import com.kraken.client.ui.KrakenFrame;
import com.kraken.client.ui.Theme;
import com.kraken.client.ui.overlay.FpsOverlay;
import com.kraken.client.ui.overlay.OverlayRenderer;
import lombok.extern.java.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import javax.swing.*;

/**
 * Launches osclient.exe, re-parents its window into a Swing frame, and runs the plugins.
 *
 * <p>Usage: {@code KrakenClient [path\to\osclient.exe] [--plugin <class>]... [--developer-mode]}
 * <ul>
 *   <li>{@code --plugin} loads a plugin class besides the built-in ones (repeat it for several);
 *   <li>{@code --developer-mode} also loads plugins marked {@code developerPlugin}.
 * </ul>
 */
@Log(topic = "kraken")
public class KrakenClient {
    private static final String DEFAULT_EXE =
            "C:\\Program Files (x86)\\Jagex Launcher\\Games\\Old School RuneScape\\Client\\osclient.exe";

    /**
     * Launches Kraken with the built-in plugins and any given with {@code --plugin}.
     *
     * @param args the command line (see the class description)
     * @throws Exception if the frame can't be built
     */
    public static void main(String[] args) throws Exception {
        launch(args);
    }

    /**
     * Launches Kraken with extra plugins besides the built-in ones, for running a plugin under development. Call it
     * from a {@code main} next to the plugin under development:
     *
     * <pre>{@code
     * public static void main(String[] args) throws Exception {
     *     KrakenClient.launch(args, MyPlugin.class);
     * }
     * }</pre>
     *
     * @param args    the command line (see the class description)
     * @param plugins plugin classes to load; they start if they're on, like any plugin
     * @throws Exception if the frame can't be built
     */
    @SafeVarargs
    public static void launch(String[] args, Class<? extends Plugin>... plugins) throws Exception {
        Options options = Options.parse(args, plugins);
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> log.log(Level.SEVERE, "Uncaught exception on " + t.getName(), e));
        Injector injector = Guice.createInjector(new KrakenModule(options.exe(), options.developerMode()));

        // Requesting the frame creates every Swing component, so it has to happen on the EDT and after the theme
        SwingUtilities.invokeAndWait(() -> {
            Theme.install();
            injector.getInstance(KrakenFrame.class).setVisible(true);
        });
        log.info("Kraken starting on Java " + Runtime.version());
        options.problems().forEach(log::warning);

        // Draws the plugins' overlays once the API attaches, and the frame rates when the settings ask for them
        injector.getInstance(OverlayRenderer.class);
        injector.getInstance(FpsOverlay.class);

        // Plugins read the game, so they load and start only once the API attaches, under the session injector that
        // binds the attached game; if it never attaches (unsupported build or a failed attach), none load.
        PluginManager pluginManager = injector.getInstance(PluginManager.class);
        ProcessAttacher attacher = injector.getInstance(ProcessAttacher.class);
        attacher.addAttachListener(session -> {
            pluginManager.loadCorePlugins(session);
            pluginManager.loadPlugins(session, options.plugins());
            pluginManager.startPlugins();
        });

        // Then inject the native event hook, which pushes the client's event raises so the API reads when they happen
        // instead of on its timer poll. It installs on a thread of its own and falls back to the poll if it can't.
        attacher.addAttachListener(session -> session.getInstance(NativeEventServer.class).install());

        injector.getInstance(GameLauncher.class).launch();
    }

    /**
     * The parsed command line.
     *
     * @param exe           the game binary
     * @param plugins       plugin classes to load besides the built-in ones
     * @param developerMode whether developer plugins load
     * @param problems      what couldn't be understood, logged once the console exists
     */
    private record Options(File exe, List<Class<?>> plugins, boolean developerMode, List<String> problems) {

        static Options parse(String[] args, Class<?>[] builtins) {
            String exe = DEFAULT_EXE;
            boolean developerMode = false;
            List<Class<?>> plugins = new ArrayList<>(List.of(builtins));
            List<String> problems = new ArrayList<>();
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--developer-mode" -> developerMode = true;
                    case "--plugin" -> {
                        if (++i >= args.length) {
                            problems.add("--plugin needs a class name");
                        } else {
                            try {
                                plugins.add(Class.forName(args[i]));
                            } catch (ClassNotFoundException | LinkageError e) {
                                problems.add("Can't load plugin class " + args[i] + ": " + e);
                            }
                        }
                    }
                    default -> {
                        if (args[i].startsWith("--")) {
                            problems.add("Unknown option " + args[i]);
                        } else {
                            exe = args[i];
                        }
                    }
                }
            }
            return new Options(new File(exe), plugins, developerMode, problems);
        }
    }
}
