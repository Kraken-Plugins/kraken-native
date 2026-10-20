package com.kraken.api.internal;

import com.kraken.api.Client;
import com.kraken.api.item.Item;
import com.kraken.api.item.ItemContainer;
import com.kraken.api.mapping.Mapping;
import com.kraken.api.memory.Memory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reads the client's inventories: a global hash table keyed by inventory id, each node holding the slots' item ids and
 * counts as two {@code eastl::vector<int>}. {@link Client#getItemContainer} and {@link Client#getItemContainers} use
 * it, as Lua's {@code ClientOp.inv*} functions read the same table.
 */
public final class InventoryReader extends GameReader {

    private static final long MAX_BUCKETS = 1 << 16;
    private static final int MAX_CHAIN = 10_000;
    private static final int MAX_SLOTS = 4096;

    private final ConfigTypes configs;
    private final long bucketsRva;
    private final long bucketCountRva;
    private final int invId;
    private final int invNext;
    private final int invIds;
    private final int invIdsEnd;
    private final int invCounts;
    private final int invCountsEnd;
    private final int nodeSize;

    /**
     * Resolves the inventory keys.
     *
     * @param memory  the client process's memory
     * @param mapping the client build's mapping
     * @param configs the config types the inventories' sizes come from
     */
    public InventoryReader(Memory memory, Mapping mapping, ConfigTypes configs) {
        super(memory, mapping);
        this.configs = configs;
        this.bucketsRva = mapping.global("inventories.buckets");
        this.bucketCountRva = mapping.global("inventories.bucketCount");
        this.invId = mapping.offset("Inventory.id");
        this.invNext = mapping.offset("Inventory.next");
        this.invIds = mapping.offset("Inventory.ids");
        this.invIdsEnd = mapping.offset("Inventory.idsEnd");
        this.invCounts = mapping.offset("Inventory.counts");
        this.invCountsEnd = mapping.offset("Inventory.countsEnd");
        this.nodeSize = 8 + Collections.max(List.of(invId, invNext, invIds, invIdsEnd, invCounts, invCountsEnd));
    }

    /**
     * One inventory, as the client holds it now.
     *
     * @param id the inventory id
     * @return a snapshot of its slots, or null if the client doesn't hold it
     */
    public ItemContainer inventory(int id) {
        long node = node(id);
        return node == 0 ? null : read(node);
    }

    /**
     * Every inventory the client holds now (backpack, worn equipment, bank once opened, shops…).
     *
     * @return snapshots in no particular order, empty if the table can't be read
     */
    public List<ItemContainer> inventories() {
        long base = memory.moduleBase();
        long buckets = safePointer(base + bucketsRva);
        long count = safeLong(base + bucketCountRva, 0) & 0xffffffffL; // the client divides by the low 32 bits
        if (buckets == 0 || count == 0 || count > MAX_BUCKETS) {
            return List.of();
        }
        ByteBuffer heads = readBlock(buckets, (int) count * 8);
        if (heads == null) {
            return List.of();
        }
        List<ItemContainer> inventories = new ArrayList<>();
        int guard = 0;
        for (int b = 0; b < count; b++) {
            long node = heads.getLong(b * 8);
            while (node != 0 && guard++ < MAX_CHAIN) {
                ItemContainer inventory = read(node);
                if (inventory == null) {
                    break;
                }
                inventories.add(inventory);
                node = safePointer(node + invNext);
            }
        }
        return inventories;
    }

    /**
     * The table's node for an id. Same walk as the client's own lookup: the bucket is the sign-extended id modulo the
     * (32-bit) bucket count, then the chain is followed through {@code Inventory.next}.
     *
     * @param id the inventory id
     * @return the node's address, or 0 if the inventory isn't loaded
     */
    private long node(int id) {
        long base = memory.moduleBase();
        long buckets = safePointer(base + bucketsRva);
        long count = safeLong(base + bucketCountRva, 0) & 0xffffffffL;
        if (buckets == 0 || count == 0 || count > MAX_BUCKETS) {
            return 0;
        }
        long node = safePointer(buckets + Long.remainderUnsigned(id, count) * 8);
        for (int guard = 0; node != 0 && guard < MAX_CHAIN; guard++) {
            if (safeInt(node + invId, Integer.MIN_VALUE) == id) {
                return node;
            }
            node = safePointer(node + invNext);
        }
        return 0;
    }

    /**
     * Reads one node's slots.
     *
     * @param node the node's address
     * @return the snapshot, sized by the inventory's definition; null if the node can't be read
     */
    private ItemContainer read(long node) {
        ByteBuffer n = readBlock(node, nodeSize);
        if (n == null) {
            return null;
        }
        int id = n.getInt(invId);
        int[] ids = readVector(n.getLong(invIds), n.getLong(invIdsEnd));
        int[] counts = readVector(n.getLong(invCounts), n.getLong(invCountsEnd));
        if (ids == null || counts == null) {
            return null;
        }
        Item[] items = new Item[ids.length];
        for (int slot = 0; slot < ids.length; slot++) {
            items[slot] = new Item(ids[slot], slot < counts.length ? counts[slot] : 0);
        }
        return new ItemContainer(id, items, configs.invSize(id));
    }

    /**
     * An {@code eastl::vector<int>} given its begin/end pointers.
     *
     * @param begin address of the first element
     * @param end   address just past the last element
     * @return the elements, or null if the vector looks corrupt or can't be read
     */
    private int[] readVector(long begin, long end) {
        long length = (end - begin) >> 2;
        if (length < 0 || length > MAX_SLOTS || (length > 0 && begin == 0)) {
            return null;
        }
        return readInts(begin, (int) length);
    }
}
