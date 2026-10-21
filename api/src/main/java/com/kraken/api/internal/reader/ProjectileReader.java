package com.kraken.api.internal.reader;

import com.kraken.api.Client;
import com.kraken.api.internal.ProjectileEndpoint;
import com.kraken.api.mapping.Mapping;
import com.kraken.api.memory.Memory;
import com.kraken.api.scene.Projectile;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.ToIntFunction;
import java.util.stream.IntStream;

/**
 * Reads the client's projectiles: the list {@code Client.projectiles} (an eastl::list of shared pointers, one node per
 * projectile in flight) and the map {@code Client.keyedProjectiles} (an eastl::hash_map from the key the server sent a
 * projectile with to the projectile, for those it can retarget in flight). {@link Client} uses it for
 * {@code areProjectilesRendered} and {@code getProjectiles}.
 *
 * <p>The projectile keys are optional: with a mapping that lacks the list's, or the map's, those projectiles aren't in
 * flight.
 */
public final class ProjectileReader extends GameReader {

    /**
     * Most projectiles walked in one read of the list or the map; neither is ever near this long.
     */
    private static final int MAX_PROJECTILES = 1024;

    /**
     * Most buckets the keyed map is read with; the client's is far smaller.
     */
    private static final int MAX_BUCKETS = 1 << 16;

    private final Client client;
    private final int cProjectiles;
    private final int listNext;
    private final int listValue;
    private final long cycleRva;
    private final int hidden;

    private final int spotAnimId;
    private final int sourceLevel;
    private final int sourceX;
    private final int sourceZ;
    private final int sourceActor;
    private final int startHeight;
    private final int targetLevel;
    private final int targetX;
    private final int targetZ;
    private final int targetActor;
    private final int endHeight;
    private final int startCycle;
    private final int endCycle;
    private final int slope;
    private final int startDistance;
    private final int started;
    private final int fineX;
    private final int fineY;
    private final int fineZ;
    private final int yaw;
    private final int pitch;

    /**
     * The fields above (all but {@link #hidden}) sit together, so each projectile is read in one block.
     */
    private final int blockStart;
    private final int blockLength;

    private final int cKeyed;
    private final int mapBuckets;
    private final int mapBucketCount;
    private final int nodeKey;
    private final int nodeValue;
    private final int nodeNext;
    private final KeyedLayout keyed;

    /**
     * Resolves the projectile keys.
     *
     * @param memory  the client process's memory
     * @param mapping the client build's mapping
     * @param client  the client the projectiles look their actors up in
     */
    public ProjectileReader(Memory memory, Mapping mapping, Client client) {
        super(memory, mapping);
        this.client = client;
        this.cProjectiles = optionalOffset("Client.projectiles");
        this.listNext = containerOr("list.node.next", 0);
        this.listValue = containerOr("list.node.value", 0x18);
        this.cycleRva = optionalGlobal("cycle");
        this.hidden = optionalOffset("Projectile.hidden");

        this.spotAnimId = optionalOffset("Projectile.spotAnimId");
        this.sourceLevel = optionalOffset("Projectile.sourceLevel");
        this.sourceX = optionalOffset("Projectile.sourceX");
        this.sourceZ = optionalOffset("Projectile.sourceZ");
        this.sourceActor = optionalOffset("Projectile.sourceActor");
        this.startHeight = optionalOffset("Projectile.startHeight");
        this.targetLevel = optionalOffset("Projectile.targetLevel");
        this.targetX = optionalOffset("Projectile.targetX");
        this.targetZ = optionalOffset("Projectile.targetZ");
        this.targetActor = optionalOffset("Projectile.targetActor");
        this.endHeight = optionalOffset("Projectile.endHeight");
        this.startCycle = optionalOffset("Projectile.startCycle");
        this.endCycle = optionalOffset("Projectile.endCycle");
        this.slope = optionalOffset("Projectile.slope");
        this.startDistance = optionalOffset("Projectile.startDistance");
        this.started = optionalOffset("Projectile.started");
        this.fineX = optionalOffset("Projectile.fineX");
        this.fineY = optionalOffset("Projectile.fineY");
        this.fineZ = optionalOffset("Projectile.fineZ");
        this.yaw = optionalOffset("Projectile.yaw");
        this.pitch = optionalOffset("Projectile.pitch");

        int[] block = {spotAnimId, sourceLevel, sourceX, sourceZ, sourceActor, startHeight, targetLevel, targetX, targetZ,
                targetActor, endHeight, startCycle, endCycle, slope, startDistance, started, fineX, fineY, fineZ, yaw, pitch};
        boolean complete = IntStream.of(block).allMatch(off -> off >= 0) && cProjectiles >= 0 && hidden >= 0
                && cycleRva >= 0;
        this.blockStart = complete ? IntStream.of(block).min().orElse(0) : -1;
        this.blockLength = complete ? IntStream.of(block).max().orElse(0) + 8 - blockStart : 0;

        this.cKeyed = optionalOffset("Client.keyedProjectiles");
        this.mapBuckets = containerOr("hash_map.buckets", 0x8);
        this.mapBucketCount = containerOr("hash_map.bucketCount", 0x10);
        this.nodeKey = containerOr("hash_map.node.key", 0);
        this.nodeValue = containerOr("hash_map.node.value", 0x10);
        this.nodeNext = containerOr("hash_map.node.next", 0x18);
        KeyedLayout layout = new KeyedLayout(this::optionalOffset);
        this.keyed = cKeyed >= 0 && cycleRva >= 0 && layout.complete() ? layout : null;
    }

    /**
     * Lua's {@code ClientOp.areProjectilesRendered}: walks the list for a projectile without the hidden flag. Like the
     * Lua handler, it doesn't look at the keyed projectiles.
     *
     * @return true as soon as one projectile in flight is drawn; false for none, or if the list isn't mapped
     */
    public boolean anyRendered() {
        if (cProjectiles < 0 || hidden < 0) {
            return false;
        }
        for (long projectile : projectileAddresses()) {
            if (safeU8(projectile + hidden, 1) == 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Every projectile in flight: the list's in list order, then the keyed ones.
     *
     * @return one snapshot per projectile; empty when none are in flight or logged out. A part the mapping lacks keys
     *         for, or that can't be read right now, is left out.
     */
    public List<Projectile> projectiles() {
        List<Projectile> out = new ArrayList<>(listed());
        List<Projectile> keyedNow = keyed();
        if (keyedNow != null) {
            out.addAll(keyedNow);
        }
        return out;
    }

    /**
     * The projectiles in the client's list, in list order.
     *
     * @return one snapshot per projectile; empty when none are in flight, logged out, or the mapping lacks a projectile
     *         key
     */
    public List<Projectile> listed() {
        if (blockStart < 0) {
            return List.of();
        }
        long[] addresses = projectileAddresses();
        if (addresses.length == 0) {
            return List.of();
        }
        int cycle = safeInt(memory.moduleBase() + cycleRva, 0);
        List<Projectile> out = new ArrayList<>(addresses.length);
        for (long address : addresses) {
            Projectile projectile = read(address, cycle);
            if (projectile != null) {
                out.add(projectile);
            }
        }
        return out;
    }

    /**
     * The keyed projectiles, in the map's bucket order.
     *
     * @return one snapshot per projectile (empty when none are in flight, logged out, or the mapping lacks a keyed
     *         projectile key); null if the map can't be read right now
     */
    public List<Projectile> keyed() {
        if (keyed == null) {
            return List.of();
        }
        long base = clientBase();
        if (base == 0) {
            return List.of();
        }
        long map = base + cKeyed;
        long buckets = safePointer(map + mapBuckets);
        long count = safeLong(map + mapBucketCount, -1);
        if (count == 0) {
            return List.of();
        }
        if (buckets == 0 || count < 0 || count > MAX_BUCKETS) {
            return null;
        }
        ByteBuffer heads = readBlock(buckets, (int) count * 8);
        if (heads == null) {
            return null;
        }
        int cycle = safeInt(memory.moduleBase() + cycleRva, 0);
        List<Projectile> out = new ArrayList<>();
        for (int b = 0; b < count; b++) {
            for (long node = heads.getLong(b * 8); node != 0; node = safePointer(node + nodeNext)) {
                ByteBuffer entry = readBlock(node, Math.max(nodeKey + 4, nodeValue + 8));
                long projectile = entry == null ? 0 : entry.getLong(nodeValue);
                Projectile read = projectile == 0 ? null : readKeyed(projectile, entry.getInt(nodeKey), cycle);
                if (read == null || out.size() == MAX_PROJECTILES) {
                    return null;
                }
                out.add(read);
            }
        }
        return out;
    }

    private Projectile read(long address, int cycle) {
        ByteBuffer b = readBlock(address + blockStart, blockLength);
        if (b == null) {
            return null;
        }
        ProjectileEndpoint source = ProjectileEndpoint.decode(i32(b, sourceLevel), i32(b, sourceX), i32(b, sourceZ),
                i32(b, startHeight), i32(b, sourceActor));
        ProjectileEndpoint target = ProjectileEndpoint.decode(i32(b, targetLevel), i32(b, targetX), i32(b, targetZ),
                i32(b, endHeight), i32(b, targetActor));
        return new Projectile(client, address, -1, i32(b, spotAnimId), source, target, i32(b, startCycle),
                i32(b, endCycle), i32(b, slope), i32(b, startDistance), b.get(started - blockStart) != 0, f64(b, fineX),
                f64(b, fineZ), f64(b, fineY), i32(b, yaw), i32(b, pitch), safeU8(address + hidden, 0) != 0, cycle);
    }

    /**
     * Reads one keyed projectile.
     *
     * @param address where it is
     * @param key     the key it is filed under
     * @param cycle   the cycle of the read
     * @return the snapshot, or null if it can't be read
     */
    private Projectile readKeyed(long address, int key, int cycle) {
        KeyedLayout k = keyed;
        ByteBuffer b = readBlock(address + k.start, k.length);
        if (b == null) {
            return null;
        }
        ProjectileEndpoint source = ProjectileEndpoint.decode(k.i32(b, k.sourceLevel), k.i32(b, k.sourceX),
                k.i32(b, k.sourceZ), k.i32(b, k.sourceOffsetX), k.i32(b, k.startHeight), k.i32(b, k.sourceOffsetZ),
                k.i32(b, k.sourceActor));
        ProjectileEndpoint target = ProjectileEndpoint.decode(k.i32(b, k.targetLevel), k.i32(b, k.targetX),
                k.i32(b, k.targetZ), k.i32(b, k.targetOffsetX), k.i32(b, k.endHeight), k.i32(b, k.targetOffsetZ),
                k.i32(b, k.targetActor));
        return new Projectile(client, address, key, k.i32(b, k.spotAnimId), source, target, k.i32(b, k.startCycle),
                k.i32(b, k.endCycle), 0, 0, b.get(k.started - k.start) != 0, k.f32(b, k.fineX), k.f32(b, k.fineZ),
                k.f32(b, k.fineY), k.i32(b, k.yaw), k.i32(b, k.pitch), false, cycle);
    }

    private int i32(ByteBuffer b, int offset) {
        return b.getInt(offset - blockStart);
    }

    private double f64(ByteBuffer b, int offset) {
        return b.getDouble(offset - blockStart);
    }

    /**
     * The projectiles the list's nodes point at, walking from the sentinel until it comes round again.
     *
     * @return their addresses, empty if the list can't be read
     */
    private long[] projectileAddresses() {
        long client = clientBase();
        if (client == 0) {
            return new long[0];
        }
        long sentinel = client + cProjectiles;
        long[] found = new long[16];
        int n = 0;
        long node = safePointer(sentinel + listNext);
        for (int guard = 0; node != 0 && node != sentinel && guard < MAX_PROJECTILES; guard++) {
            long projectile = safePointer(node + listValue);
            if (projectile != 0) {
                if (n == found.length) {
                    found = Arrays.copyOf(found, n * 2);
                }
                found[n++] = projectile;
            }
            node = safePointer(node + listNext);
        }
        return Arrays.copyOf(found, n);
    }

    /**
     * Where a keyed projectile keeps what the API reads, resolved from the {@code KeyedProjectile} keys. The fields sit
     * together, so each projectile is read in one block.
     */
    private static final class KeyedLayout {

        final int spotAnimId;
        final int sourceLevel;
        final int sourceX;
        final int sourceZ;
        final int sourceActor;
        final int sourceOffsetX;
        final int startHeight;
        final int sourceOffsetZ;
        final int targetLevel;
        final int targetX;
        final int targetZ;
        final int targetActor;
        final int targetOffsetX;
        final int endHeight;
        final int targetOffsetZ;
        final int startCycle;
        final int endCycle;
        final int started;
        final int fineX;
        final int fineY;
        final int fineZ;
        final int yaw;
        final int pitch;
        final int start;
        final int length;

        /**
         * Resolves the keys.
         *
         * @param offsets the mapping's offset for a key, or -1 if it lacks the key
         */
        KeyedLayout(ToIntFunction<String> offsets) {
            spotAnimId = offsets.applyAsInt("KeyedProjectile.spotAnimId");
            sourceLevel = offsets.applyAsInt("KeyedProjectile.sourceLevel");
            sourceX = offsets.applyAsInt("KeyedProjectile.sourceX");
            sourceZ = offsets.applyAsInt("KeyedProjectile.sourceZ");
            sourceActor = offsets.applyAsInt("KeyedProjectile.sourceActor");
            sourceOffsetX = offsets.applyAsInt("KeyedProjectile.sourceOffsetX");
            startHeight = offsets.applyAsInt("KeyedProjectile.startHeight");
            sourceOffsetZ = offsets.applyAsInt("KeyedProjectile.sourceOffsetZ");
            targetLevel = offsets.applyAsInt("KeyedProjectile.targetLevel");
            targetX = offsets.applyAsInt("KeyedProjectile.targetX");
            targetZ = offsets.applyAsInt("KeyedProjectile.targetZ");
            targetActor = offsets.applyAsInt("KeyedProjectile.targetActor");
            targetOffsetX = offsets.applyAsInt("KeyedProjectile.targetOffsetX");
            endHeight = offsets.applyAsInt("KeyedProjectile.endHeight");
            targetOffsetZ = offsets.applyAsInt("KeyedProjectile.targetOffsetZ");
            startCycle = offsets.applyAsInt("KeyedProjectile.startCycle");
            endCycle = offsets.applyAsInt("KeyedProjectile.endCycle");
            started = offsets.applyAsInt("KeyedProjectile.started");
            fineX = offsets.applyAsInt("KeyedProjectile.fineX");
            fineY = offsets.applyAsInt("KeyedProjectile.fineY");
            fineZ = offsets.applyAsInt("KeyedProjectile.fineZ");
            yaw = offsets.applyAsInt("KeyedProjectile.yaw");
            pitch = offsets.applyAsInt("KeyedProjectile.pitch");
            int[] all = fields();
            start = IntStream.of(all).min().orElse(0);
            length = IntStream.of(all).max().orElse(0) + 4 - start;
        }

        private int[] fields() {
            return new int[]{spotAnimId, sourceLevel, sourceX, sourceZ, sourceActor, sourceOffsetX, startHeight,
                    sourceOffsetZ, targetLevel, targetX, targetZ, targetActor, targetOffsetX, endHeight, targetOffsetZ,
                    startCycle, endCycle, started, fineX, fineY, fineZ, yaw, pitch};
        }

        /**
         * Whether the mapping has every key.
         *
         * @return true if no offset is missing
         */
        boolean complete() {
            return IntStream.of(fields()).allMatch(off -> off >= 0);
        }

        int i32(ByteBuffer b, int offset) {
            return b.getInt(offset - start);
        }

        double f32(ByteBuffer b, int offset) {
            return b.getFloat(offset - start);
        }
    }
}
