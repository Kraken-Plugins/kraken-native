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
import com.kraken.client.callback.ClientThread;
import com.kraken.client.events.ConfigChanged;
import com.kraken.client.plugins.Plugin;
import com.kraken.client.plugins.PluginDescriptor;
import com.kraken.client.plugins.config.ConfigManager;
import com.kraken.client.ui.overlay.OverlayManager;
import lombok.Getter;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The overlay example plugin: outlines the NPCs whose names are listed in its settings. It shows the threading a plugin
 * can rely on:
 *
 * <ul>
 *   <li>{@link #highlighted} is a plain set, because the spawn events, the {@link ClientThread} tasks and the overlay's
 *       {@code render} all run on the client thread;
 *   <li>{@code startUp}, {@code shutDown} and {@code onConfigChanged} run on the EDT, so they hand set changes to the
 *       client thread and swap {@link #names} as a whole immutable set.
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
     * The NPCs being outlined. Client thread only.
     */
    @Getter
    private final Set<NPC> highlighted = new HashSet<>();

    private volatile Set<String> names = Set.of();

    @Provides
    NpcHighlightConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(NpcHighlightConfig.class);
    }

    @Override
    protected void startUp() {
        names = parse(config.npcNames());
        overlayManager.add(overlay);
        clientThread.invoke(this::rebuild);
    }

    @Override
    protected void shutDown() {
        overlayManager.remove(overlay);
        clientThread.invoke(highlighted::clear);
    }

    @Subscribe
    public void onNpcSpawned(NpcSpawned event) {
        NPC npc = event.getNpc();
        if (matches(npc)) {
            highlighted.add(npc);
        }
    }

    @Subscribe
    public void onNpcDespawned(NpcDespawned event) {
        highlighted.remove(event.getNpc());
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event) {
        if (event.getGameState() == GameState.LOADING) {
            highlighted.clear();
        }
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event) {
        if (event.getGroup().equals(NpcHighlightConfig.GROUP)) {
            names = parse(config.npcNames());
            clientThread.invoke(this::rebuild);
        }
    }

    private void rebuild() {
        highlighted.clear();
        for (NPC npc : client.getNpcs()) {
            if (matches(npc)) {
                highlighted.add(npc);
            }
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
