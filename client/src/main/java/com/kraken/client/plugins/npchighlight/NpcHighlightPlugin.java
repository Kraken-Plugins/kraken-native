package com.kraken.client.plugins.npchighlight;

import com.google.inject.Inject;
import com.google.inject.Provides;
import com.kraken.api.Client;
import com.kraken.api.GameState;
import com.kraken.api.actor.NPC;
import com.kraken.api.eventbus.Subscribe;
import com.kraken.api.events.GameStateChanged;
import com.kraken.api.events.NpcDespawned;
import com.kraken.api.events.NpcSpawned;
import com.kraken.api.highlight.HighlightCategory;
import com.kraken.api.highlight.HighlightChannel;
import com.kraken.api.highlight.HighlightConfig;
import com.kraken.api.highlight.HighlightTarget;
import com.kraken.client.callback.ClientThread;
import com.kraken.client.events.ConfigChanged;
import com.kraken.client.plugins.Plugin;
import com.kraken.client.plugins.PluginDescriptor;
import com.kraken.client.plugins.config.ConfigManager;
import com.kraken.client.ui.overlay.OverlayManager;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The overlay example plugin: outlines the NPCs whose names are listed in its settings. The outlines are the game's own
 * highlights, on an NPC {@link HighlightChannel} the plugin holds while it runs; the names, and the tile outlines while
 * the native hook isn't connected, come from its overlay. It shows the threading a plugin can rely on:
 *
 * <ul>
 *   <li>{@link #highlighted} is a plain map, because the spawn events, the {@link ClientThread} tasks and the overlay's
 *       {@code render} all run on the client thread;
 *   <li>{@code startUp}, {@code shutDown} and {@code onConfigChanged} run on the EDT, so they hand set changes to the
 *       client thread and swap {@link #names} as a whole immutable set. The channel is thread-safe.
 * </ul>
 */
@PluginDescriptor(
        name = "NPC Highlight",
        description = "Outlines NPCs whose names you list",
        tags = {"npc", "highlight", "overlay", "example"},
        enabledByDefault = false
)
public class NpcHighlightPlugin extends Plugin {

    @Inject
    private Client client;

    @Inject
    private NpcHighlightConfig config;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private NpcHighlightOverlay overlay;

    @Inject
    private ClientThread clientThread;

    /**
     * The NPCs being outlined, each with the highlight it was lit with (keyed by its creation cycle as it was then).
     * Client thread only.
     */
    private final Map<NPC, HighlightTarget> highlighted = new HashMap<>();

    private volatile Set<String> names = Set.of();

    /**
     * The game's highlight channel the outlines are drawn on, held from {@code startUp} to {@code shutDown}.
     */
    private volatile HighlightChannel channel;

    @Provides
    NpcHighlightConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(NpcHighlightConfig.class);
    }

    @Override
    protected void startUp() {
        names = parse(config.npcNames());
        HighlightChannel taken = client.getHighlights().channel(HighlightCategory.NPC);
        taken.configure(style());
        channel = taken;
        overlayManager.add(overlay);
        clientThread.invoke(this::rebuild);
    }

    @Override
    protected void shutDown() {
        overlayManager.remove(overlay);
        HighlightChannel held = channel;
        channel = null;
        if (held != null) {
            held.release();
        }
        clientThread.invoke(highlighted::clear);
    }

    /**
     * The NPCs being outlined. Client thread only.
     *
     * @return a view of them
     */
    Set<NPC> getHighlighted() {
        return highlighted.keySet();
    }

    /**
     * Whether the game draws the outlines now, so the overlay only adds the names.
     *
     * @return true while the native hook is connected
     */
    boolean isDrawnInGame() {
        return client.getHighlights().isAvailable();
    }

    @Subscribe
    public void onNpcSpawned(NpcSpawned event) {
        NPC npc = event.getNpc();
        if (matches(npc)) {
            HighlightTarget target = HighlightTarget.npc(npc);
            highlighted.put(npc, target);
            HighlightChannel held = channel;
            if (held != null) {
                held.add(target);
            }
        }
    }

    @Subscribe
    public void onNpcDespawned(NpcDespawned event) {
        HighlightTarget target = highlighted.remove(event.getNpc());
        HighlightChannel held = channel;
        if (target != null && held != null) {
            held.remove(target);
        }
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event) {
        if (event.getGameState() == GameState.LOADING) {
            highlighted.clear();
            HighlightChannel held = channel;
            if (held != null) {
                held.clear();
            }
        }
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event) {
        if (event.getGroup().equals(NpcHighlightConfig.GROUP)) {
            names = parse(config.npcNames());
            HighlightChannel held = channel;
            if (held != null) {
                held.configure(style());
            }
            clientThread.invoke(this::rebuild);
        }
    }

    /**
     * The game's highlight style from the settings.
     *
     * @return the style
     */
    private HighlightConfig style() {
        return HighlightConfig.DEFAULT
                .withHighlightColour(config.colour())
                // The settings page keeps the width in range; a hand-edited settings file might not
                .withOutlineWidth(Math.max(1, Math.min(config.width(), 16)))
                .withEnableModelOutline(config.outlineModel())
                .withEnableTileOutline(config.outlineTile())
                .withAlwaysOnTop(config.alwaysOnTop());
    }

    private void rebuild() {
        highlighted.clear();
        for (NPC npc : client.getNpcs()) {
            if (matches(npc)) {
                highlighted.put(npc, HighlightTarget.npc(npc));
            }
        }
        HighlightChannel held = channel;
        if (held != null) {
            held.set(highlighted.values());
        }
    }

    private boolean matches(NPC npc) {
        return names.contains(npc.getName().toLowerCase(Locale.ROOT));
    }

    /**
     * The names in the setting.
     *
     * @param list comma-separated names
     * @return the names, trimmed and lower-cased
     */
    private static Set<String> parse(String list) {
        return Arrays.stream(list.split(","))
                .map(name -> name.trim().toLowerCase(Locale.ROOT))
                .filter(name -> !name.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }
}
