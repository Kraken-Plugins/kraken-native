package com.kraken.api.internal.reader;

import com.kraken.api.Client;
import com.kraken.api.coords.LocalPoint;
import com.kraken.api.mapping.Mapping;
import com.kraken.api.memory.Memory;
import com.kraken.api.scene.GraphicsObject;
import com.kraken.api.scene.WorldView;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Reads the top-level world's graphics objects ({@code World.graphicsObjects}): an eastl::list of shared pointers, one
 * node per spot anim the server is playing on a tile. The client appends to it when the server plays one, and every
 * frame drops those whose animation has ended or whose level isn't the world's plane any more.
 *
 * <p>The graphics object keys are optional: with a mapping that lacks them, nothing is playing.
 */
public final class GraphicsObjectReader extends GameReader {

    /**
     * Most graphics objects walked in one read; the list is never near this long.
     */
    private static final int MAX_OBJECTS = 1024;

    private final Client client;
    private final SceneReader scene;
    private final int wGraphicsObjects;
    private final int listNext;
    private final int listValue;

    private final int spotAnimId;
    private final int level;
    private final int fineX;
    private final int fineZ;
    private final int height;
    private final int startCycle;
    private final int animSeqType;
    private final int animFrame;

    /**
     * The fields above sit together, so each object is read in one block.
     */
    private final int blockStart;
    private final int blockLength;

    /**
     * Resolves the graphics object keys.
     *
     * @param memory  the client process's memory
     * @param mapping the client build's mapping
     * @param client  the client the snapshots answer {@code finished} through
     * @param scene   the reader that finds the top-level world
     */
    public GraphicsObjectReader(Memory memory, Mapping mapping, Client client, SceneReader scene) {
        super(memory, mapping);
        this.client = client;
        this.scene = scene;
        this.wGraphicsObjects = optionalOffset("World.graphicsObjects");
        this.listNext = containerOr("list.node.next", 0);
        this.listValue = containerOr("list.node.value", 0x18);

        this.spotAnimId = optionalOffset("GraphicsObject.spotAnimId");
        this.level = optionalOffset("GraphicsObject.level");
        this.fineX = optionalOffset("GraphicsObject.fineX");
        this.fineZ = optionalOffset("GraphicsObject.fineZ");
        this.height = optionalOffset("GraphicsObject.height");
        this.startCycle = optionalOffset("GraphicsObject.startCycle");
        this.animSeqType = optionalOffset("GraphicsObject.animSeqType");
        this.animFrame = optionalOffset("GraphicsObject.animFrame");

        int[] ints = {spotAnimId, level, fineX, fineZ, height, startCycle};
        boolean complete = IntStream.of(ints).allMatch(off -> off >= 0) && animSeqType >= 0 && animFrame >= 0
                && wGraphicsObjects >= 0;
        int end = Math.max(IntStream.of(ints).max().orElse(0) + 4, Math.max(animSeqType + 8, animFrame + 2));
        this.blockStart = complete ? Math.min(IntStream.of(ints).min().orElse(0), Math.min(animSeqType, animFrame)) : -1;
        this.blockLength = complete ? end - blockStart : 0;
    }

    /**
     * Every graphics object in the top-level world's list, in list order.
     *
     * @return one snapshot per object (empty when none are playing, logged out, or the mapping lacks a graphics object
     *         key); null if the list can't be read right now
     */
    public List<GraphicsObject> graphicsObjects() {
        if (blockStart < 0) {
            return List.of();
        }
        long world = scene.topWorld();
        if (world == 0) {
            return List.of();
        }
        long[] addresses = addresses(world);
        if (addresses == null) {
            return null;
        }
        List<GraphicsObject> out = new ArrayList<>(addresses.length);
        for (long address : addresses) {
            GraphicsObject object = read(address);
            if (object == null) {
                return null;
            }
            out.add(object);
        }
        return out;
    }

    /**
     * Whether a graphics object is done: no longer in the top-level world's list (the client dropped it), or still
     * there with its animation ended (the client drops it on the next frame).
     *
     * @param object the snapshot to look for
     * @return true once it is done; as of the snapshot's read if the list can't be read right now
     */
    public boolean finished(GraphicsObject object) {
        if (blockStart < 0) {
            return true;
        }
        long world = scene.topWorld();
        if (world == 0) {
            return true;
        }
        long[] addresses = addresses(world);
        if (addresses == null) {
            return object.isAnimationEnded();
        }
        for (long address : addresses) {
            if (address == object.getAddress()) {
                GraphicsObject now = read(address);
                return now == null ? object.isAnimationEnded() : !now.equals(object) || now.isAnimationEnded();
            }
        }
        return true;
    }

    /**
     * Reads one graphics object.
     *
     * @param address where it is
     * @return the snapshot, or null if it can't be read
     */
    private GraphicsObject read(long address) {
        ByteBuffer b = readBlock(address + blockStart, blockLength);
        if (b == null) {
            return null;
        }
        LocalPoint location = new LocalPoint(i32(b, fineX), i32(b, fineZ), WorldView.TOPLEVEL);
        return new GraphicsObject(client, address, i32(b, spotAnimId), location, i32(b, level), i32(b, height),
                i32(b, startCycle), b.getShort(animFrame - blockStart), b.getLong(animSeqType - blockStart) == 0);
    }

    private int i32(ByteBuffer b, int offset) {
        return b.getInt(offset - blockStart);
    }

    /**
     * The objects the list's nodes point at, walking from the sentinel until it comes round again.
     *
     * @param world the world whose list to walk
     * @return their addresses; null if a node can't be read or the walk doesn't come back to the sentinel (the client
     *         changed the list under the read)
     */
    private long[] addresses(long world) {
        long sentinel = world + wGraphicsObjects;
        long[] found = new long[16];
        int n = 0;
        long node = safePointer(sentinel + listNext);
        for (int guard = 0; guard <= MAX_OBJECTS; guard++) {
            if (node == sentinel) {
                return Arrays.copyOf(found, n);
            }
            if (node == 0) {
                return null;
            }
            long object = safePointer(node + listValue);
            if (object == 0) {
                return null;
            }
            if (n == found.length) {
                found = Arrays.copyOf(found, n * 2);
            }
            found[n++] = object;
            node = safePointer(node + listNext);
        }
        return null;
    }
}
