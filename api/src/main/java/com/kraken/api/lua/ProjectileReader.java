package com.kraken.api.lua;

import com.kraken.api.mapping.Mapping;
import com.kraken.api.memory.Memory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Reads the client's projectile list ({@code Client.projectiles}): an eastl::list of shared pointers, one node per
 * projectile in flight. {@link ClientOp} uses it for {@code areProjectilesRendered} and {@code getProjectiles}.
 *
 * <p>The projectile keys are optional: with a mapping that lacks them, nothing is in flight.
 */
final class ProjectileReader extends GameReader {

    /**
     * Most projectiles walked in one read; the list is never near this long.
     */
    private static final int MAX_PROJECTILES = 1024;

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

    ProjectileReader(Memory memory, Mapping mapping) {
        super(memory, mapping);
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
    }

    /**
     * Mirrors {@code ClientOp.areProjectilesRendered}: walks the list for a projectile without the hidden flag.
     *
     * @return true as soon as one projectile in flight is drawn; false for none, or if the list isn't mapped
     */
    boolean anyRendered() {
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
     * Every projectile in flight, in list order.
     *
     * @return one snapshot per projectile; empty when none are in flight, logged out, or the mapping lacks a projectile
     *         key
     */
    List<Projectile> projectiles() {
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

    private Projectile read(long address, int cycle) {
        ByteBuffer b = readBlock(address + blockStart, blockLength);
        if (b == null) {
            return null;
        }
        Projectile.Endpoint source = Projectile.Endpoint.decode(i32(b, sourceLevel), i32(b, sourceX), i32(b, sourceZ),
                i32(b, startHeight), i32(b, sourceActor));
        Projectile.Endpoint target = Projectile.Endpoint.decode(i32(b, targetLevel), i32(b, targetX), i32(b, targetZ),
                i32(b, endHeight), i32(b, targetActor));
        return new Projectile(i32(b, spotAnimId), source, target, i32(b, startCycle), i32(b, endCycle), i32(b, slope),
                i32(b, startDistance), b.get(started - blockStart) != 0, f64(b, fineX), f64(b, fineY), f64(b, fineZ),
                i32(b, yaw), i32(b, pitch), safeU8(address + hidden, 0) != 0, cycle);
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
}
