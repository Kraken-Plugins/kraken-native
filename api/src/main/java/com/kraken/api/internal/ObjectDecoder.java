package com.kraken.api.internal;

import lombok.Getter;
import com.kraken.api.Client;
import com.kraken.api.definition.ObjectComposition;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.Map;
import java.util.function.IntFunction;

import static com.kraken.api.internal.NpcDecoder.minusOne;

/**
 * Decodes loc (scenery) types, config group 6, the way the client's loader does ({@code ConfigLoader.6}, 0x5ed080 in
 * 241.3): constructor defaults (0x5ecdb0), each opcode until opcode 0 (0x5ed530), the post-step (0x5ed440) that
 * derives {@code active} and {@code supportItems} when no opcode set them, and finally the hollow fix-up (a hollow loc
 * neither blocks movement nor projectiles). Like the client, an id without a file still yields a type, never null.
 *
 * <p>Opcodes the client's decoder doesn't handle (it silently reads nothing for them) throw
 * {@link ConfigFormatException} here instead.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ObjectDecoder {

    /**
     * The loc type for {@code id}, with the client's normal (not low-detail) model handling.
     *
     * @param id     the loc id
     * @param files  config group 6's file bytes by id, null for an id without a file
     * @param client the client the definition's actions resolve vars against, or null to resolve none
     * @return the definition; all defaults (name "") for an id without a file, never null
     * @throws ConfigFormatException if the file uses an opcode the client doesn't handle or runs past its end
     */
    public static ObjectComposition load(int id, IntFunction<byte[]> files, Client client) {
        return decode(id, files.apply(id), false, client);
    }

    /**
     * The loc type for {@code id}.
     *
     * @param id        the loc id
     * @param files     config group 6's file bytes by id, null for an id without a file
     * @param lowDetail the client's low-detail setting (copied from {@code Client+0x2448} when the loc loader is set
     *                  up). With it off, a second model opcode is skipped when a model list is already set; with it
     *                  on, the later list replaces the earlier one.
     * @param client    the client the definition's actions resolve vars against, or null to resolve none
     * @return the definition; all defaults (name "") for an id without a file, never null
     * @throws ConfigFormatException if the file uses an opcode the client doesn't handle or runs past its end
     */
    public static ObjectComposition load(int id, IntFunction<byte[]> files, boolean lowDetail, Client client) {
        return decode(id, files.apply(id), lowDetail, client);
    }

    /**
     * Decodes one loc type from its file bytes, then applies the client's post-step and hollow fix-up.
     *
     * @param id        the loc id, kept on the result
     * @param data      the file's bytes from its first opcode, or null for an absent file (gives the defaults)
     * @param lowDetail the client's low-detail setting; see {@link #load(int, IntFunction, boolean, Client)}
     * @param client    the client the definition's actions resolve vars against, or null to resolve none
     * @return the decoded definition
     * @throws ConfigFormatException if the file uses an opcode the client doesn't handle or runs past its end
     */
    public static ObjectComposition decode(int id, byte[] data, boolean lowDetail, Client client) {
        Builder t = new Builder(id);
        if (data != null) {
            ConfigBuffer in = new ConfigBuffer(data);
            for (int opcode = in.g1(); opcode != 0; opcode = in.g1()) {
                read(t, opcode, in, lowDetail);
            }
        }
        post(t);
        if (t.hollow) {
            t.interactType = 0;
            t.blocksProjectile = false;
        }
        return t.build(client);
    }

    /**
     * 0x5ed440: fill in {@code active} and {@code supportItems} when the definition left them at -1.
     *
     * @param t the decoded type, updated in place
     */
    private static void post(Builder t) {
        if (t.active == -1) {
            t.active = 0;
            if (t.models.length > 0 && (t.shapes.length == 0 || t.shapes[0] == 10)) {
                t.active = 1;
            }
            if (t.ops.anySet()) {
                t.active = 1;
            }
        }
        if (t.supportItems == -1) {
            t.supportItems = t.interactType != 0 ? 1 : 0;
        }
    }

    public static void read(Builder t, int opcode, ConfigBuffer in, boolean lowDetail) {
        switch (opcode) {
            case 2 -> t.name = in.gjstr();
            case 6 -> {
                // models with a shape each (32-bit id + shape byte); replaced opcode 1
                int n = in.g1();
                if (n > 0) {
                    if (t.models.length > 0 && !lowDetail) {
                        in.skip(n * 5);
                    } else {
                        t.models = new int[n];
                        t.shapes = new int[n];
                        for (int i = 0; i < n; i++) {
                            t.models[i] = in.g4();
                            t.shapes[i] = in.g1();
                        }
                    }
                }
            }
            case 7 -> {
                // models without shapes (32-bit ids); replaced opcode 5
                int n = in.g1();
                if (n > 0) {
                    if (t.models.length > 0 && !lowDetail) {
                        in.skip(n * 4);
                    } else {
                        t.shapes = new int[0];
                        t.models = new int[n];
                        for (int i = 0; i < n; i++) {
                            t.models[i] = in.g4();
                        }
                    }
                }
            }
            case 14 -> t.width = in.g1();
            case 15 -> t.length = in.g1();
            case 17 -> {
                t.interactType = 0;
                t.blocksProjectile = false;
            }
            case 18 -> t.blocksProjectile = false;
            case 19 -> t.active = in.g1();
            case 21 -> t.contouredGround = 0;
            case 22 -> t.nonFlatShading = true;
            case 23 -> t.modelClipped = true;
            case 24 -> t.animationId = minusOne(in.g2());
            case 27 -> t.interactType = 1;
            case 28 -> t.wallWidth = in.g1();
            case 29 -> t.ambient = in.g1s();
            case 30, 31, 32, 33, 34, 100, 101, 102 -> t.ops.read(opcode, in, 100, 101, 102);
            case 39 -> t.contrast = in.g1s() * 5;
            case 40 -> {
                int n = in.g1();
                t.recolorFrom = new int[n];
                t.recolorTo = new int[n];
                for (int i = 0; i < n; i++) {
                    t.recolorFrom[i] = in.g2();
                    t.recolorTo[i] = in.g2();
                }
            }
            case 41 -> {
                int n = in.g1();
                t.retextureFrom = new int[n];
                t.retextureTo = new int[n];
                for (int i = 0; i < n; i++) {
                    t.retextureFrom[i] = in.g2();
                    t.retextureTo[i] = in.g2();
                }
            }
            case 42 -> in.g2(); // a 16-bit value (default -1) the API doesn't use yet
            case 61 -> t.category = in.g2();
            case 62 -> t.rotated = true;
            case 64 -> t.shadow = false;
            case 65 -> t.modelSizeX = in.g2();
            case 66 -> t.modelSizeHeight = in.g2();
            case 67 -> t.modelSizeY = in.g2();
            case 68 -> t.mapSceneId = in.g2();
            case 69 -> t.blockingMask = in.g1();
            case 70 -> t.offsetX = in.g2s();
            case 71 -> t.offsetHeight = in.g2s();
            case 72 -> t.offsetY = in.g2s();
            case 73 -> t.obstructsGround = true;
            case 74 -> t.hollow = true;
            case 75 -> t.supportItems = in.g1();
            case 77, 92 -> readMultiChildren(t, in, opcode == 92, false);
            case 97, 98 -> readMultiChildren(t, in, opcode == 98, true);
            case 78 -> {
                t.ambientSoundId = in.g2();
                t.ambientSoundDistance = in.g1();
                t.ambientSoundRetain = in.g1();
            }
            case 79 -> {
                in.g2();
                in.g2();
                t.ambientSoundDistance = in.g1();
                t.ambientSoundRetain = in.g1();
                int n = in.g1();
                in.skip(n * 2);
            }
            case 81 -> t.contouredGround = in.g1() << 8;
            case 82 -> t.mapAreaId = in.g2();
            case 89 -> t.randomizeAnimStart = false;
            case 90, 94 -> {
                // flags the API doesn't use yet (no operands)
            }
            case 91, 95 -> in.g1();
            case 93 -> {
                in.g2();
                in.g1();
                in.g2();
                in.g1();
            }
            case 96 -> t.decorHeightOffset = in.g1();
            case 249 -> t.params = in.params();
            default -> throw new ConfigFormatException("Loc " + t.id + ": unknown opcode " + opcode
                    + " at " + (in.position() - 1));
        }
    }

    /**
     * Reads a multi-loc's transforms: a varbit and a varp (0xFFFF for none), for opcodes 92 and 98 a fallback id, then
     * a count and count + 1 child ids, with the fallback (-1 without one) appended last. Opcodes 77 and 92 carry 16-bit
     * ids (0xFFFF for none); 97 and 98 carry signed 24-bit ids (0xFFFFFF for none; handler at 0x5ff3c9 in 243.1).
     *
     * @param t            the type being decoded, updated in place
     * @param in           the file, positioned after the opcode
     * @param withFallback whether a fallback id follows the varp
     * @param wideIds      whether the ids are 24-bit rather than 16-bit
     */
    private static void readMultiChildren(Builder t, ConfigBuffer in, boolean withFallback, boolean wideIds) {
        t.multiVarbit = minusOne(in.g2());
        t.multiVarp = minusOne(in.g2());
        int fallback = withFallback ? childId(in, wideIds) : -1;
        int n = in.g1();
        int[] children = new int[n + 2];
        for (int i = 0; i <= n; i++) {
            children[i] = childId(in, wideIds);
        }
        children[n + 1] = fallback;
        t.multiChildren = children;
    }

    /**
     * Reads one multi-loc child id.
     *
     * @param in      the file
     * @param wideIds whether the id is signed 24-bit rather than 16-bit
     * @return the id, -1 for none
     */
    private static int childId(ConfigBuffer in, boolean wideIds) {
        return wideIds ? in.g3s() : minusOne(in.g2());
    }

    /**
     * Mutable fields with the client constructor's defaults (0x5ecdb0); the decoder fills it in.
     */
    @Getter
    public static final class Builder {
        final int id;
        String name = "";
        int[] models = new int[0];
        int[] shapes = new int[0];
        int width = 1;
        int length = 1;
        int interactType = 2;
        boolean blocksProjectile = true;
        int active = -1;
        int contouredGround = -1;
        boolean nonFlatShading;
        boolean modelClipped;
        int animationId = -1;
        int wallWidth = 16;
        int ambient;
        int contrast;
        final NpcDecoder.Ops ops = new NpcDecoder.Ops();
        int[] recolorFrom;
        int[] recolorTo;
        int[] retextureFrom;
        int[] retextureTo;
        int category = -1;
        boolean rotated;
        boolean shadow = true;
        int modelSizeX = 128;
        int modelSizeHeight = 128;
        int modelSizeY = 128;
        int mapSceneId = -1;
        int mapAreaId = -1;
        int blockingMask;
        int offsetX;
        int offsetHeight;
        int offsetY;
        boolean obstructsGround;
        boolean hollow;
        int supportItems = -1;
        int multiVarbit = -1;
        int multiVarp = -1;
        int[] multiChildren;
        int ambientSoundId = -1;
        int ambientSoundDistance;
        int ambientSoundRetain;
        boolean randomizeAnimStart = true;
        int decorHeightOffset;
        Map<Integer, Object> params;

        /**
         * A builder with the constructor's defaults.
         *
         * @param id the type id
         */
        Builder(int id) {
            this.id = id;
        }

        /**
         * The finished definition.
         *
         * @param client the client its actions resolve vars against, or null to resolve none
         * @return the definition
         */
        public ObjectComposition build(Client client) {
            return new ObjectComposition(this, client);
        }
    }
}
