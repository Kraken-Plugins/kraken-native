package com.kraken.api.internal.event;

import com.kraken.api.events.DecorativeObjectDespawned;
import com.kraken.api.events.DecorativeObjectSpawned;
import com.kraken.api.events.GameObjectDespawned;
import com.kraken.api.events.GameObjectSpawned;
import com.kraken.api.events.GroundObjectDespawned;
import com.kraken.api.events.GroundObjectSpawned;
import com.kraken.api.events.WallObjectDespawned;
import com.kraken.api.events.WallObjectSpawned;
import com.kraken.api.internal.Loc;
import com.kraken.api.internal.MapCoord;
import com.kraken.api.internal.reader.SceneReader;
import com.kraken.api.scene.DecorativeObject;
import com.kraken.api.scene.GameObject;
import com.kraken.api.scene.GroundObject;
import com.kraken.api.scene.Tile;
import com.kraken.api.scene.TileObject;
import com.kraken.api.scene.WallObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * The four object spawn and despawn events, from a read of every loc in the scene once per game tick. Objects are
 * keyed by their slot, as Lua's {@code ON_LOC_LOAD} keys them: a tile and layer, plus the id for game objects (a
 * tile can hold several). A slot whose object changed (a door opening) posts the old one's despawn and the new
 * one's spawn. All despawns go before all spawns, as the client replaces a loc.
 */
final class LocTracker extends SceneTracker {

    private static final Logger log = Logger.getLogger("kraken.api");
    private static final int MAX_TARGETS = 256;

    private Map<Long, Loc> last = Map.of();
    private final List<Integer> targetedCoords = new ArrayList<>();
    private boolean targetingLogged;

    LocTracker(TrackerContext context) {
        super(context, GameObjectSpawned.class, GameObjectDespawned.class, WallObjectSpawned.class,
                WallObjectDespawned.class, DecorativeObjectSpawned.class, DecorativeObjectDespawned.class,
                GroundObjectSpawned.class, GroundObjectDespawned.class);
    }

    @Override
    boolean due(boolean ticked) {
        return ticked;
    }

    @Override
    Set<String> triggerNames() {
        return Set.of("ON_LOC_LOAD", "ON_LOC_UNLOAD");
    }

    @Override
    void onTrigger(NativeEvent event) {
        Integer packed = event.i32(NativeEvent.RDX);
        if (packed != null && targetedCoords.size() < MAX_TARGETS) {
            targetedCoords.add(packed);
        }
    }

    @Override
    void load(MapCoord base, boolean post) {
        targetedCoords.clear(); // the full load covers the whole scene
        last = read();
        if (post) {
            last.values().forEach(loc -> post(loc, base, true));
        }
    }

    @Override
    void compare(MapCoord base) {
        Map<Long, Loc> now = read();
        if (now.isEmpty() && !last.isEmpty()) {
            return; // a scene doesn't empty without a reload: a failed read, so keep the baseline
        }
        Map<Long, Loc> before = last;
        last = now;
        for (Map.Entry<Long, Loc> entry : before.entrySet()) {
            if (!entry.getValue().equals(now.get(entry.getKey()))) {
                post(entry.getValue(), base, false);
            }
        }
        for (Map.Entry<Long, Loc> entry : now.entrySet()) {
            if (!entry.getValue().equals(before.get(entry.getKey()))) {
                post(entry.getValue(), base, true);
            }
        }
    }

    @Override
    void targeted(MapCoord base, boolean fullCompareRan) {
        if (targetedCoords.isEmpty()) {
            return;
        }
        List<Integer> coords = new ArrayList<>(targetedCoords);
        targetedCoords.clear();
        if (fullCompareRan) {
            return; // the whole-scene compare this poll already covered these tiles
        }
        SceneReader scene = client.getSceneReader();
        long world = scene.topWorld();
        long sceneHandle = scene.sceneOf(world);
        int[] size = scene.worldSize(world);
        if (sceneHandle == 0 || size == null) {
            return;
        }
        logTargeting();
        for (int packed : coords) {
            int level = (packed >>> 28) & 0x3;
            int a = (packed >>> 14) & 0x3fff;
            int b = packed & 0x3fff;
            // The coord packing and whether it is scene- or world-based are unconfirmed (design §7): read it as
            // scene-relative when it lands in the scene, else as a world tile with the base subtracted.
            int x = a < size[0] && b < size[1] ? a : a - base.x();
            int z = a < size[0] && b < size[1] ? b : b - base.z();
            if (scene.inWorld(world, level, x, z)) {
                compareTile(sceneHandle, base, level, x, z);
            }
        }
    }

    /**
     * Reads one tile's locs and posts what changed there against the last whole-scene read, then folds the result
     * back into that read so the next whole-scene compare doesn't repost it.
     *
     * @param sceneHandle the scene
     * @param base        the scene's map base
     * @param level       the tile level
     * @param x           the scene tile x
     * @param z           the scene tile z
     */
    private void compareTile(long sceneHandle, MapCoord base, int level, int x, int z) {
        Map<Long, Loc> nowTile = new LinkedHashMap<>();
        for (Loc loc : client.getSceneReader().locs(sceneHandle, level, x, z, x, z)) {
            nowTile.put(slot(loc), loc);
        }
        Map<Long, Loc> updated = new LinkedHashMap<>(last);
        for (Map.Entry<Long, Loc> entry : last.entrySet()) {
            MapCoord at = entry.getValue().coord();
            if (at.level() == level && at.x() == x && at.z() == z
                    && !entry.getValue().equals(nowTile.get(entry.getKey()))) {
                post(entry.getValue(), base, false);
                updated.remove(entry.getKey());
            }
        }
        for (Map.Entry<Long, Loc> entry : nowTile.entrySet()) {
            if (!entry.getValue().equals(last.get(entry.getKey()))) {
                post(entry.getValue(), base, true);
                updated.put(entry.getKey(), entry.getValue());
            }
        }
        last = updated;
    }

    /**
     * Logs, once, that loc targeting is live and on which unconfirmed assumption, so a live check can confirm or
     * refute the coord packing (design §7). The per-tick whole-scene read is the fallback meanwhile.
     */
    private void logTargeting() {
        if (!targetingLogged) {
            targetingLogged = true;
            log.info("Loc hook targeting active: decoding ON_LOC_LOAD/UNLOAD coord as plane<<28|x<<14|z, scene-"
                    + "relative where in range else world-minus-base. Confirm the packing and base on a live "
                    + "client (design §7); the per-tick whole-scene read remains the fallback.");
        }
    }

    @Override
    void clear() {
        last = Map.of();
        targetedCoords.clear();
    }

    private Map<Long, Loc> read() {
        Map<Long, Loc> locs = new LinkedHashMap<>();
        SceneReader scene = client.getSceneReader();
        for (Loc loc : scene.allLocs(scene.sceneOf(scene.topWorld()))) {
            locs.put(slot(loc), loc);
        }
        return locs;
    }

    /**
     * Posts one object's spawn or despawn, if anyone listens to that kind.
     *
     * @param loc     the object
     * @param base    the map base it was read with
     * @param spawned true for a spawn, false for a despawn
     */
    private void post(Loc loc, MapCoord base, boolean spawned) {
        Class<?> type = eventType(loc.layer(), spawned);
        if (!bus.hasSubscribers(type)) {
            return;
        }
        Tile tile = Tile.at(client, loc.coord(), base.x(), base.z());
        TileObject object = TileObject.of(client, loc, base.x(), base.z());
        bus.post(switch (loc.layer()) {
            case Loc.WALL -> spawned ? wallSpawned(tile, object) : wallDespawned(tile, object);
            case Loc.WALL_DECOR -> spawned ? decorSpawned(tile, object) : decorDespawned(tile, object);
            case Loc.GAME_OBJECT -> spawned ? gameSpawned(tile, object) : gameDespawned(tile, object);
            default -> spawned ? groundSpawned(tile, object) : groundDespawned(tile, object);
        });
    }

    private Class<?> eventType(int layer, boolean spawned) {
        return switch (layer) {
            case Loc.WALL -> spawned ? WallObjectSpawned.class : WallObjectDespawned.class;
            case Loc.WALL_DECOR -> spawned ? DecorativeObjectSpawned.class : DecorativeObjectDespawned.class;
            case Loc.GAME_OBJECT -> spawned ? GameObjectSpawned.class : GameObjectDespawned.class;
            default -> spawned ? GroundObjectSpawned.class : GroundObjectDespawned.class;
        };
    }

    private Object wallSpawned(Tile tile, TileObject object) {
        WallObjectSpawned event = new WallObjectSpawned();
        event.setTile(tile);
        event.setWallObject((WallObject) object);
        return event;
    }

    private Object wallDespawned(Tile tile, TileObject object) {
        WallObjectDespawned event = new WallObjectDespawned();
        event.setTile(tile);
        event.setWallObject((WallObject) object);
        return event;
    }

    private Object decorSpawned(Tile tile, TileObject object) {
        DecorativeObjectSpawned event = new DecorativeObjectSpawned();
        event.setTile(tile);
        event.setDecorativeObject((DecorativeObject) object);
        return event;
    }

    private Object decorDespawned(Tile tile, TileObject object) {
        DecorativeObjectDespawned event = new DecorativeObjectDespawned();
        event.setTile(tile);
        event.setDecorativeObject((DecorativeObject) object);
        return event;
    }

    private Object gameSpawned(Tile tile, TileObject object) {
        GameObjectSpawned event = new GameObjectSpawned();
        event.setTile(tile);
        event.setGameObject((GameObject) object);
        return event;
    }

    private Object gameDespawned(Tile tile, TileObject object) {
        GameObjectDespawned event = new GameObjectDespawned();
        event.setTile(tile);
        event.setGameObject((GameObject) object);
        return event;
    }

    private Object groundSpawned(Tile tile, TileObject object) {
        GroundObjectSpawned event = new GroundObjectSpawned();
        event.setTile(tile);
        event.setGroundObject((GroundObject) object);
        return event;
    }

    private Object groundDespawned(Tile tile, TileObject object) {
        GroundObjectDespawned event = new GroundObjectDespawned();
        event.setTile(tile);
        event.setGroundObject((GroundObject) object);
        return event;
    }

    /**
     * A loc's slot: its tile and layer, plus its id for a game object, since a tile can hold several.
     *
     * @param loc the loc
     * @return a key unique per slot
     */
    private static long slot(Loc loc) {
        MapCoord at = loc.coord();
        long key = (long) (at.level() & 3) << 62 | (long) (at.x() & 0x3ff) << 52 | (long) (at.z() & 0x3ff) << 42
                | (long) (loc.layer() & 3) << 40;
        return loc.layer() == Loc.GAME_OBJECT ? key | (loc.id() & 0xffffffffL) : key;
    }
}
