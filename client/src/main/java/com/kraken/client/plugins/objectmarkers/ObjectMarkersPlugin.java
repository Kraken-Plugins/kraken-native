package com.kraken.client.plugins.objectmarkers;

import com.google.inject.Inject;
import com.google.inject.Provides;
import com.kraken.api.Client;
import com.kraken.api.GameState;
import com.kraken.api.ObjectComposition;
import com.kraken.api.Scene;
import com.kraken.api.TileObject;
import com.kraken.api.eventbus.Subscribe;
import com.kraken.api.events.DecorativeObjectDespawned;
import com.kraken.api.events.DecorativeObjectSpawned;
import com.kraken.api.events.GameObjectDespawned;
import com.kraken.api.events.GameObjectSpawned;
import com.kraken.api.events.GameStateChanged;
import com.kraken.api.events.GroundObjectDespawned;
import com.kraken.api.events.GroundObjectSpawned;
import com.kraken.api.events.WallObjectDespawned;
import com.kraken.api.events.WallObjectSpawned;
import com.kraken.client.callback.ClientThread;
import com.kraken.client.config.ConfigManager;
import com.kraken.client.events.ConfigChanged;
import com.kraken.client.plugins.Plugin;
import com.kraken.client.plugins.PluginDescriptor;
import com.kraken.client.ui.overlay.OverlayManager;
import lombok.Getter;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The scene example plugin: a lite RuneLite Object Markers. RuneLite's marks objects you pick from their right-click
 * menu; Kraken has no menus, so this marks the objects whose names or ids are listed in its settings. The rest is
 * RuneLite plugin code with Kraken imports:
 *
 * <ul>
 *   <li>objects are learnt from the spawn events, which also fire for every object of a newly loaded scene, and
 *       forgotten on their despawn events or when the scene starts {@code LOADING};
 *   <li>{@link #marked} is a plain set, because the events, the {@link ClientThread} tasks and the overlay's
 *       {@code render} all run on the client thread; {@code startUp}, {@code shutDown} and {@code onConfigChanged}
 *       run on the EDT and hand their changes to the client thread.
 * </ul>
 *
 * <p>Started mid-session, it reads the scene once with the Kraken extra {@link Scene#getTileObjects()}, since the
 * spawn events only replay the scene when the API attaches or the scene loads. One handler taking the Kraken extra
 * {@code TileObjectSpawned} could replace the four spawn handlers; they're spelt out here as in RuneLite.
 */
@PluginDescriptor(
        name = "Object Markers",
        description = "Outlines objects whose names or ids you list",
        tags = {"object", "marker", "highlight", "overlay", "example"},
        enabledByDefault = false
)
public class ObjectMarkersPlugin extends Plugin {

    @Inject
    private Client client;

    @Inject
    private ObjectMarkersConfig config;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private ObjectMarkersOverlay overlay;

    @Inject
    private ClientThread clientThread;

    /**
     * The objects being marked. Client thread only.
     */
    @Getter
    private final Set<TileObject> marked = new HashSet<>();

    /**
     * Whether each object id seen so far is listed, so a scene load decodes each definition once. Client thread only.
     */
    private final Map<Integer, Boolean> listed = new HashMap<>();

    /**
     * Each marked object id's name, for the overlay. Client thread only.
     */
    private final Map<Integer, String> names = new HashMap<>();

    private volatile Targets targets = Targets.NONE;

    @Provides
    ObjectMarkersConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(ObjectMarkersConfig.class);
    }

    @Override
    protected void startUp() {
        targets = Targets.parse(config.objects());
        overlayManager.add(overlay);
        clientThread.invoke(this::rebuild);
    }

    @Override
    protected void shutDown() {
        overlayManager.remove(overlay);
        clientThread.invoke(() -> {
            marked.clear();
            listed.clear();
        });
    }

    @Subscribe
    public void onGameObjectSpawned(GameObjectSpawned event) {
        consider(event.getGameObject());
    }

    @Subscribe
    public void onGameObjectDespawned(GameObjectDespawned event) {
        marked.remove(event.getGameObject());
    }

    @Subscribe
    public void onWallObjectSpawned(WallObjectSpawned event) {
        consider(event.getWallObject());
    }

    @Subscribe
    public void onWallObjectDespawned(WallObjectDespawned event) {
        marked.remove(event.getWallObject());
    }

    @Subscribe
    public void onDecorativeObjectSpawned(DecorativeObjectSpawned event) {
        consider(event.getDecorativeObject());
    }

    @Subscribe
    public void onDecorativeObjectDespawned(DecorativeObjectDespawned event) {
        marked.remove(event.getDecorativeObject());
    }

    @Subscribe
    public void onGroundObjectSpawned(GroundObjectSpawned event) {
        consider(event.getGroundObject());
    }

    @Subscribe
    public void onGroundObjectDespawned(GroundObjectDespawned event) {
        marked.remove(event.getGroundObject());
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event) {
        if (event.getGameState() == GameState.LOADING) {
            marked.clear();
        }
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event) {
        if (event.getGroup().equals(ObjectMarkersConfig.GROUP)) {
            targets = Targets.parse(config.objects());
            clientThread.invoke(this::rebuild);
        }
    }

    /**
     * A marked object's name. Client thread only.
     *
     * @param object the object
     * @return its definition's name, "" if it can't be read
     */
    String nameOf(TileObject object) {
        return names.computeIfAbsent(object.getId(), id -> {
            ObjectComposition composition = client.getObjectDefinition(id);
            return composition == null ? "" : composition.getName();
        });
    }

    /**
     * Marks the scene's listed objects afresh, from one read of the whole scene.
     */
    private void rebuild() {
        marked.clear();
        listed.clear();
        if (targets.isEmpty() || client.getGameState() != GameState.LOGGED_IN) {
            return;
        }
        Scene scene = client.getScene();
        if (scene != null) {
            scene.getTileObjects().forEach(this::consider);
        }
    }

    private void consider(TileObject object) {
        if (listed.computeIfAbsent(object.getId(), this::isListed)) {
            marked.add(object);
        }
    }

    /**
     * Whether an object id is in the settings, by id or by its definition's name.
     *
     * @param id the object id
     * @return true to mark objects with this id
     */
    private boolean isListed(int id) {
        Targets now = targets;
        if (now.ids().contains(id)) {
            return true;
        }
        if (now.names().isEmpty()) {
            return false;
        }
        ObjectComposition composition = client.getObjectDefinition(id);
        return composition != null && now.names().contains(composition.getName().toLowerCase(Locale.ROOT));
    }

    /**
     * What the settings list.
     *
     * @param names the names, lower-cased
     * @param ids   the ids
     */
    private record Targets(Set<String> names, Set<Integer> ids) {

        static final Targets NONE = new Targets(Set.of(), Set.of());

        /**
         * Splits the setting into names and ids.
         *
         * @param list comma-separated names and ids
         * @return the names (trimmed, lower-cased) and ids
         */
        static Targets parse(String list) {
            Set<String> names = new HashSet<>();
            Set<Integer> ids = new HashSet<>();
            for (String entry : list.split(",")) {
                String target = entry.trim();
                if (target.isEmpty()) {
                    continue;
                }
                try {
                    ids.add(Integer.parseInt(target));
                } catch (NumberFormatException e) {
                    names.add(target.toLowerCase(Locale.ROOT));
                }
            }
            return new Targets(Set.copyOf(names), Set.copyOf(ids));
        }

        boolean isEmpty() {
            return names.isEmpty() && ids.isEmpty();
        }
    }
}
