package com.kraken.api.internal.event;

import com.kraken.api.events.ItemDespawned;
import com.kraken.api.events.ItemQuantityChanged;
import com.kraken.api.events.ItemSpawned;
import com.kraken.api.internal.GroundObj;
import com.kraken.api.internal.MapCoord;
import com.kraken.api.scene.Tile;
import com.kraken.api.scene.TileItem;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link ItemSpawned}, {@link ItemDespawned} and {@link ItemQuantityChanged}, from a read of every pile. Each
 * tile's pile is matched against the last read: a stack with the same item and quantity is the same stack (it keeps
 * its handle); one with the same item and another quantity changed size; what is left over went or came.
 */
final class ItemTracker extends SceneTracker {

    private Map<MapCoord, List<TileItem>> last = Map.of();

    ItemTracker(TrackerContext context) {
        super(context, ItemSpawned.class, ItemDespawned.class, ItemQuantityChanged.class);
    }

    @Override
    Set<String> triggerNames() {
        return Set.of(NativeEvent.OBJ_ADD, NativeEvent.OBJ_DEL, NativeEvent.OBJ_COUNT);
    }

    @Override
    boolean due(boolean ticked) {
        return ticked || afterTick() || triggered();
    }

    @Override
    void load(MapCoord base, boolean post) {
        Map<MapCoord, List<TileItem>> loaded = new LinkedHashMap<>();
        read().forEach((tile, objs) -> {
            Tile at = Tile.at(client, tile, base.x(), base.z());
            List<TileItem> items = new ArrayList<>(objs.size());
            for (GroundObj obj : objs) {
                items.add(new TileItem(obj.id(), obj.count(), at));
            }
            loaded.put(tile, items);
        });
        last = loaded;
        if (post) {
            loaded.forEach((tile, items) -> items.forEach(item -> bus.post(new ItemSpawned(item.getTile(), item))));
        }
    }

    @Override
    void compare(MapCoord base) {
        Map<MapCoord, List<GroundObj>> now = read();
        Map<MapCoord, List<TileItem>> before = last;
        Map<MapCoord, List<TileItem>> next = new LinkedHashMap<>();
        List<Object> despawns = new ArrayList<>();
        List<Object> changes = new ArrayList<>();
        List<Object> spawns = new ArrayList<>();
        Set<MapCoord> tiles = new LinkedHashSet<>(before.keySet());
        tiles.addAll(now.keySet());
        for (MapCoord tile : tiles) {
            List<TileItem> old = new ArrayList<>(before.getOrDefault(tile, List.of()));
            List<GroundObj> current = now.getOrDefault(tile, List.of());
            TileItem[] kept = new TileItem[current.size()];
            for (int i = 0; i < kept.length; i++) {
                kept[i] = take(old, current.get(i), true);
            }
            Tile at = Tile.at(client, tile, base.x(), base.z());
            for (int i = 0; i < kept.length; i++) {
                if (kept[i] == null) {
                    kept[i] = take(old, current.get(i), false);
                    if (kept[i] != null) {
                        int was = kept[i].getQuantity();
                        kept[i].setQuantity(current.get(i).count());
                        changes.add(new ItemQuantityChanged(kept[i], at, was, current.get(i).count()));
                    }
                }
            }
            old.forEach(item -> despawns.add(new ItemDespawned(at, item)));
            for (int i = 0; i < kept.length; i++) {
                if (kept[i] == null) {
                    kept[i] = new TileItem(current.get(i).id(), current.get(i).count(), at);
                    spawns.add(new ItemSpawned(at, kept[i]));
                }
            }
            if (kept.length > 0) {
                next.put(tile, List.of(kept));
            }
        }
        last = next;
        despawns.forEach(bus::post);
        changes.forEach(bus::post);
        spawns.forEach(bus::post);
    }

    @Override
    void clear() {
        last = Map.of();
    }

    /**
     * Takes the first stack from the last read that matches one in this read.
     *
     * @param old       the last read's stacks on the tile not matched yet; the match is removed
     * @param obj       a stack in this read
     * @param sameCount whether the quantity must match too
     * @return the matching handle, or null
     */
    private TileItem take(List<TileItem> old, GroundObj obj, boolean sameCount) {
        for (int i = 0; i < old.size(); i++) {
            TileItem item = old.get(i);
            if (item.getId() == obj.id() && (!sameCount || item.getQuantity() == obj.count())) {
                return old.remove(i);
            }
        }
        return null;
    }

    private Map<MapCoord, List<GroundObj>> read() {
        Map<MapCoord, List<GroundObj>> piles = new LinkedHashMap<>();
        for (GroundObj obj : client.getSceneReader().allObjs(client.getSceneReader().topWorld())) {
            piles.computeIfAbsent(obj.coord(), tile -> new ArrayList<>()).add(obj);
        }
        return piles;
    }
}
