package com.kraken.api.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.function.IntUnaryOperator;

/**
 * Decodes NPC types (config group 9) the way the client's loader does ({@code ConfigLoader.9}, 0x5f18f0 in 241.3):
 * start from the constructor's defaults, apply each opcode until opcode 0, then fill in the footprint size if no
 * opcode set it. Like the client, an id without a file still yields a type (all defaults, name ""), never null.
 *
 * <p>Opcodes the client's decoder doesn't handle (it silently reads nothing for them, which would desync the rest of
 * the file) throw {@link ConfigFormatException} here instead.
 */
public final class NpcTypeDecoder {

    private NpcTypeDecoder() {
    }

    /**
     * The NPC type for {@code id}, reading its file (and nothing else) through {@code files}.
     *
     * @param id    the NPC type id
     * @param files config group 9's file bytes by id, null for an id without a file
     * @return the type; all defaults (name "") for an id without a file, never null
     * @throws ConfigFormatException if the file uses an opcode the client doesn't handle or runs past its end
     */
    public static NpcType load(int id, IntFunction<byte[]> files) {
        byte[] data = files.apply(id);
        return decode(id, data);
    }

    /**
     * Decodes one NPC type from its file bytes.
     *
     * @param id   the NPC type id, kept on the result
     * @param data the file's bytes from its first opcode, or null for an absent file (gives the defaults)
     * @return the decoded type, with the footprint size filled in when no opcode set it
     * @throws ConfigFormatException if the file uses an opcode the client doesn't handle or runs past its end
     */
    public static NpcType decode(int id, byte[] data) {
        NpcType.Builder t = new NpcType.Builder(id);
        if (data != null) {
            ConfigBuffer in = new ConfigBuffer(data);
            for (int opcode = in.g1(); opcode != 0; opcode = in.g1()) {
                read(t, opcode, in);
            }
        }
        if (t.footprintSize == -1) {
            // 0x5f1ce0: the default footprint scales with the tile size (truncated float maths, stored as a short)
            t.footprintSize = (short) (int) ((float) (t.size << 7) * 0.4f);
        }
        return t.build();
    }

    static void read(NpcType.Builder t, int opcode, ConfigBuffer in) {
        switch (opcode) {
            case 2 -> t.name = in.gjstr();
            case 12 -> t.size = in.g1();
            case 13 -> t.standingAnimation = in.g2();
            case 14 -> t.walkAnimation = in.g2();
            case 15 -> t.idleRotateLeftAnimation = in.g2();
            case 16 -> t.idleRotateRightAnimation = in.g2();
            case 17 -> {
                t.walkAnimation = in.g2();
                t.rotate180Animation = in.g2();
                t.rotateLeftAnimation = in.g2();
                t.rotateRightAnimation = in.g2();
            }
            case 18 -> t.category = in.g2();
            case 30, 31, 32, 33, 34, 251, 252, 253 -> t.ops.read(opcode, in, 251, 252, 253);
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
            case 61 -> t.models = ints(in);
            case 62 -> t.chatheadModels = ints(in);
            case 74 -> t.attack = in.g2();
            case 75 -> t.defence = in.g2();
            case 76 -> t.strength = in.g2();
            case 77 -> t.hitpoints = in.g2();
            case 78 -> t.ranged = in.g2();
            case 79 -> t.magic = in.g2();
            case 93 -> t.minimapVisible = false;
            case 95 -> t.combatLevel = in.g2();
            case 97 -> t.widthScale = in.g2();
            case 98 -> t.heightScale = in.g2();
            case 99 -> t.renderPriority = 0;
            case 100 -> t.ambient = in.g1s();
            case 101 -> t.contrast = in.g1s() * 5;
            case 102 -> readHeadIcons(t, in);
            case 103 -> t.rotationSpeed = in.g2();
            case 106, 118 -> {
                t.multiVarbit = minusOne(in.g2());
                t.multiVarp = minusOne(in.g2());
                int fallback = opcode == 118 ? minusOne(in.g2()) : -1;
                int n = in.g1();
                int[] children = new int[n + 2];
                for (int i = 0; i <= n; i++) {
                    children[i] = minusOne(in.g2());
                }
                children[n + 1] = fallback;
                t.multiChildren = children;
            }
            case 107 -> t.interactable = false;
            case 109 -> t.rotationFlag = false;
            case 111 -> t.renderPriority = 2;
            case 114 -> t.runAnimation = in.g2();
            case 115 -> {
                t.runAnimation = in.g2();
                t.runRotate180Animation = in.g2();
                t.runRotateLeftAnimation = in.g2();
                t.runRotateRightAnimation = in.g2();
            }
            case 116 -> t.crawlAnimation = in.g2();
            case 117 -> {
                t.crawlAnimation = in.g2();
                t.crawlRotate180Animation = in.g2();
                t.crawlRotateLeftAnimation = in.g2();
                t.crawlRotateRightAnimation = in.g2();
            }
            case 122 -> t.follower = true;
            case 123 -> t.lowPriorityFollowerOps = true;
            case 124 -> t.height = in.g2();
            case 126 -> t.footprintSize = (short) in.g2();
            case 129, 130, 145, 147 -> {
                // flags the API doesn't use yet (no operands)
            }
            case 146 -> in.g2();
            case 148 -> {
                in.g2();
                in.g1();
                in.g1();
            }
            case 149, 151 -> in.g1();
            case 150 -> {
                in.g2();
                in.g1();
                in.g2();
                in.g1();
            }
            case 152 -> {
                in.g2();
                in.g2();
                in.g1();
                in.g1();
                int n = in.g1();
                in.skip(n * 2);
            }
            case 249 -> t.params = in.params();
            default -> throw new ConfigFormatException("NPC " + t.id + ": unknown opcode " + opcode
                    + " at " + (in.position() - 1));
        }
    }

    /**
     * Opcode 102: a bitmask byte, then for each set bit a head icon's sprite archive (big smart) and its index in that
     * archive (smart, minus one); bits that aren't set leave -1 in both.
     *
     * @param t  the type being decoded; gets the archive and index arrays
     * @param in the file, positioned just after the opcode
     */
    private static void readHeadIcons(NpcType.Builder t, ConfigBuffer in) {
        int mask = in.g1();
        int count = 32 - Integer.numberOfLeadingZeros(mask);
        int[] archives = new int[count];
        int[] indices = new int[count];
        for (int i = 0; i < count; i++) {
            if ((mask & 1 << i) == 0) {
                archives[i] = -1;
                indices[i] = -1;
            } else {
                archives[i] = in.gBigSmart();
                indices[i] = (short) (in.gSmart1or2() - 1);
            }
        }
        t.headIconArchives = archives;
        t.headIconIndices = indices;
    }

    /**
     * A count byte, then that many 32-bit ids (the int model lists that replaced opcodes 1 and 60).
     *
     * @param in the file, positioned just after the opcode
     * @return the ids, in file order
     */
    static int[] ints(ConfigBuffer in) {
        int n = in.g1();
        int[] values = new int[n];
        for (int i = 0; i < n; i++) {
            values[i] = in.g4();
        }
        return values;
    }

    /**
     * 0xFFFF, the "none" value of 16-bit id fields, as -1.
     *
     * @param u16 an unsigned 16-bit id as read
     * @return the id, or -1 for 0xFFFF
     */
    static int minusOne(int u16) {
        return u16 == 0xffff ? -1 : u16;
    }

    /**
     * The op block NPC and loc types share (the client's reader is 0x5e60b0; its getter 0x5e63b0): up to five op
     * texts, each with an optional list of alternative texts that apply while a varp or varbit is within a range.
     *
     * <ul>
     *   <li>Opcodes 30-34 set op 1-5's text, unless it is "Hidden" (any case), which leaves the op unset. Setting a
     *       text also drops that op's alternatives.
     *   <li>The "conditional op" opcode (252 for NPCs, 101 for locs) adds an alternative to an op that is already set:
     *       op index, varp, varbit (0xFFFF = use the varp), min, max, text. An alternative for an unset op is lost, as
     *       in the client (it writes past the end of its op list).
     *   <li>The sub-op opcodes (251/253 for NPCs, 100/102 for locs) add right-click sub-menu entries. They are read
     *       but not kept: no Lua getter exposes them.
     * </ul>
     */
    static final class Ops {
        static final int COUNT = 5;

        record Alternative(int varp, int varbit, int min, int max, String text) {
        }

        private final List<String> texts = new ArrayList<>(COUNT);
        private final List<List<Alternative>> alternatives = new ArrayList<>(COUNT);

        void read(int opcode, ConfigBuffer in, int subOp, int conditionalOp, int conditionalSubOp) {
            if (opcode >= 30 && opcode <= 34) {
                String text = in.gjstr();
                if (!text.equalsIgnoreCase("Hidden")) {
                    set(opcode - 30, text);
                }
            } else if (opcode == subOp) {
                in.g1();     // op index
                in.g1();     // sub-op index, 1-based
                in.gjstr();
            } else if (opcode == conditionalOp) {
                int index = in.g1();
                int varp = in.g2();
                int varbit = in.g2();
                int min = in.g4();
                int max = in.g4();
                String text = in.gjstr();
                if (index < texts.size()) {
                    alternatives.get(index).add(new Alternative(varp, varbit, min, max, text));
                }
            } else if (opcode == conditionalSubOp) {
                in.g1();     // op index
                in.g2();     // sub-op index, 1-based
                in.g2();     // varp
                in.g2();     // varbit
                in.g4();     // min
                in.g4();     // max
                in.gjstr();
            } else {
                throw new IllegalArgumentException("not an op opcode: " + opcode);
            }
        }

        private void set(int index, String text) {
            while (texts.size() <= index) {
                texts.add("");
                alternatives.add(new ArrayList<>());
            }
            texts.set(index, text);
            alternatives.set(index, new ArrayList<>());
        }

        /**
         * The five op texts with no alternative applied.
         *
         * @return op1..op5 in order, "" for an unset op
         */
        List<String> base() {
            String[] out = new String[COUNT];
            for (int i = 0; i < COUNT; i++) {
                out[i] = i < texts.size() ? texts.get(i) : "";
            }
            return List.of(out);
        }

        /**
         * The five op texts as the client's getter resolves them: per op, the first alternative whose var value is
         * within [min, max] (signed), else the op's own text.
         *
         * @param varp   a varp's value, as the client reads it for this ({@code global.varpsBits[id]})
         * @param varbit a varbit's value ({@code ClientState.getVarbit})
         * @return op1..op5 in order, "" for an unset op
         */
        List<String> resolve(IntUnaryOperator varp, IntUnaryOperator varbit) {
            String[] out = new String[COUNT];
            for (int i = 0; i < COUNT; i++) {
                out[i] = i < texts.size() ? resolve(i, varp, varbit) : "";
            }
            return List.of(out);
        }

        private String resolve(int index, IntUnaryOperator varp, IntUnaryOperator varbit) {
            for (Alternative alt : alternatives.get(index)) {
                int value = alt.varbit() != 0xffff ? varbit.applyAsInt(alt.varbit()) : varp.applyAsInt(alt.varp());
                if (value >= alt.min() && value <= alt.max()) {
                    return alt.text();
                }
            }
            return texts.get(index);
        }

        /**
         * Whether any op has a text (the loc post-step treats such a loc as active).
         *
         * @return true if at least one op text is non-empty
         */
        boolean anySet() {
            for (String text : texts) {
                if (!text.isEmpty()) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Whether any op has var-conditioned alternatives.
         *
         * @return true if at least one op has an alternative
         */
        boolean hasAlternatives() {
            for (List<Alternative> list : alternatives) {
                if (!list.isEmpty()) {
                    return true;
                }
            }
            return false;
        }

        /**
         * An unmodifiable copy, for a finished type.
         *
         * @return a copy whose alternative lists can't be changed
         */
        Ops freeze() {
            Ops copy = new Ops();
            copy.texts.addAll(texts);
            for (List<Alternative> list : alternatives) {
                copy.alternatives.add(List.copyOf(list));
            }
            return copy;
        }
    }

    static int[] copy(int[] values) {
        return values == null ? new int[0] : Arrays.copyOf(values, values.length);
    }

    static Map<Integer, Object> params(Map<Integer, Object> params) {
        return params == null ? Map.of() : Collections.unmodifiableMap(params);
    }
}
