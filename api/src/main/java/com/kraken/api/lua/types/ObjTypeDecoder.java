package com.kraken.api.lua.types;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

/**
 * Decodes item definitions (config group 10) the way the 241.3 client does: its opcode parser (0x5f6520) and its
 * loader (0x5f5cd0), which after decoding a file
 * <ol>
 *   <li>zeroes the weight of stackable items,
 *   <li>merges a noted item with its note template and the item it notes (opcodes 97/98),
 *   <li>merges a bought item with its template and linked item (opcodes 139/140),
 *   <li>merges a placeholder with its template and linked item (opcodes 148/149),
 *   <li>on a free world, rewrites members items (name suffix, members notice, ops reduced to "Take"/op 5).
 * </ol>
 * Templates and linked items are themselves loaded the same way. An id without a file gets the client's default type.
 *
 * <p>This client's format differs from the older one RuneLite documents: model ids are 32-bit (opcodes 44-54 instead
 * of 1, 23-26, 78-79, 90-93), and ground ops can have sub-ops (200, 202) and var-conditional texts (201). Sub-ops
 * (200, 202, and inventory sub-ops 43) are read but not kept: no Lua function returns them.
 */
public final class ObjTypeDecoder {

    // Strings the client's loader writes (literals in 241.3's .rdata).
    static final String MEMBERS_SUFFIX = " (Members)";
    static final String MEMBERS_DESC = "Login to a members' server to use this object.";
    static final String NOTE_DESC = "Swap this note at any bank for the equivalent item";
    static final String TAKE = "Take";
    static final String DROP = "Drop";
    static final String DISCARD = "Discard";
    static final String HIDDEN = "Hidden";

    /**
     * Templates only ever link one level deep; anything deeper is a cycle the client would recurse forever on.
     */
    private static final int MAX_DEPTH = 8;

    private ObjTypeDecoder() {
    }

    /**
     * Loads item {@code id} as the client's loader would.
     *
     * @param id          the item id
     * @param files       config group 10's file bytes by id, null for an id without a file
     * @param membersWorld whether the client is on a members world (it rewrites members items otherwise)
     * @return the type; the client's default type for an id without a file (the client never yields "no type")
     * @throws ConfigFormatException if a file uses an opcode the client doesn't handle or runs past its end
     */
    public static ObjType load(int id, IntFunction<byte[]> files, boolean membersWorld) {
        return load(id, files, membersWorld, 0).build(id);
    }

    /**
     * Decodes one file without the loader's merging, e.g. for tests.
     *
     * @param id   the item id to give the type
     * @param data the file's bytes, from its first opcode
     * @return the type as the file alone describes it (no template merges, no free-world rewrite)
     * @throws ConfigFormatException if the file uses an opcode the client doesn't handle or runs past its end
     */
    public static ObjType decode(int id, byte[] data) {
        Raw raw = new Raw();
        raw.decode(data);
        raw.id = id;
        raw.canonicalId = id;
        return raw.build(id);
    }

    private static Raw load(int id, IntFunction<byte[]> files, boolean membersWorld, int depth) {
        if (depth > MAX_DEPTH) {
            throw new ConfigFormatException("obj " + id + ": template chain deeper than " + MAX_DEPTH);
        }
        Raw t = new Raw();
        byte[] data = files.apply(id);
        if (data != null) {
            t.decode(data);
            t.id = id;
        }
        if (t.stackable == 1) {
            t.weight = 0;
        }
        t.canonicalId = t.id;
        if (t.certTemplate != -1) {
            Raw linked = load(t.certLink, files, membersWorld, depth + 1);
            Raw template = load(t.certTemplate, files, membersWorld, depth + 1);
            t.mergeCert(template, linked);
            t.canonicalId = linked.id;
        }
        if (t.boughtTemplate != -1) {
            Raw linked = load(t.boughtLink, files, membersWorld, depth + 1);
            Raw template = load(t.boughtTemplate, files, membersWorld, depth + 1);
            t.mergeBought(template, linked);
        }
        if (t.placeholderTemplate != -1) {
            Raw linked = load(t.placeholderLink, files, membersWorld, depth + 1);
            Raw template = load(t.placeholderTemplate, files, membersWorld, depth + 1);
            t.mergePlaceholder(template, linked);
            if (t.certTemplate == -1) {
                t.canonicalId = linked.id;
            }
        }
        if (!membersWorld && t.members) {
            t.freeWorld();
        }
        return t;
    }

    /**
     * The loader's working copy of a type, field for field with the client's ObjType (offsets noted).
     */
    private static final class Raw {
        int id = -1;                                  // +0x0, only set when a file was decoded
        int model;                                    // +0x4
        String name = "";                             // +0x8
        String desc = "";                             // +0x20
        String text9 = "";                            // +0x38, opcode 9
        int stackable;                                // +0xd4: 1 stackable, 2 set by opcode 160
        int cost = 1;                                 // +0xd8
        int wearPos1 = -1;                            // +0xdc
        int wearPos2 = -1;                            // +0xe0
        int wearPos3 = -1;                            // +0xe4
        boolean members;                              // +0xe8
        List<OpSlot> groundOps = new ArrayList<>();   // +0xf0
        String[] iops = {"", "", "", "", DROP};       // +0x108
        int category = -1;                            // +0x278
        int certLink = -1;                            // +0x2b0
        int certTemplate = -1;                        // +0x2b4
        int weight;                                   // +0x2d0
        Map<Integer, Object> params = new LinkedHashMap<>(); // +0x2d8
        boolean flag308 = true;                       // +0x308, cleared by opcode 15
        boolean tradeable;                            // +0x309
        boolean flag30a;                              // +0x30a, set with tradeable
        int boughtLink = -1;                          // +0x30c
        int boughtTemplate = -1;                      // +0x310
        int placeholderLink = -1;                     // +0x314
        int placeholderTemplate = -1;                 // +0x318
        int canonicalId = -1;

        Raw() {
            setOp(2, TAKE);
        }

        void decode(byte[] data) {
            ConfigBuffer in = new ConfigBuffer(data);
            for (int opcode = in.g1(); opcode != 0; opcode = in.g1()) {
                decodeOpcode(in, opcode);
            }
        }

        private void decodeOpcode(ConfigBuffer in, int opcode) {
            switch (opcode) {
                case 2 -> name = in.gjstr();
                case 3 -> desc = in.gjstr();
                case 4, 5, 6 -> in.g2();                    // 2D zoom, x/y angle
                case 7, 8 -> in.g2s();                      // 2D x/y offset
                case 9 -> text9 = in.gjstr();
                case 11 -> stackable = 1;
                case 12 -> cost = in.g4();
                case 13 -> wearPos1 = in.g1();
                case 14 -> wearPos2 = in.g1();
                case 15 -> flag308 = false;
                case 16 -> members = true;
                case 27 -> wearPos3 = in.g1();
                case 30, 31, 32, 33, 34 -> {
                    String text = in.gjstr();
                    if (!text.equalsIgnoreCase(HIDDEN)) {    // "Hidden" leaves the slot as it was
                        setOp(opcode - 30, text);
                    }
                }
                case 35, 36, 37, 38, 39 -> iops[opcode - 35] = in.gjstr();
                case 40, 41 -> {                            // recolours, retextures: count, then (from, to) pairs
                    int count = in.g1();
                    in.skip(count * 4);
                }
                case 42 -> in.g1s();                        // shift-click op
                case 43 -> {                                // inventory sub-ops: op, then (sub id + 1, text) until 0
                    in.g1();
                    while (in.g1() != 0) {
                        in.gjstr();
                    }
                }
                case 44 -> model = in.g4();
                case 45, 48 -> {                            // worn model 1 and its offset
                    in.g4();
                    in.g1();
                }
                case 46, 47, 49, 50, 51, 52, 53, 54 -> in.g4(); // other worn and head models
                case 65 -> {
                    tradeable = true;
                    flag30a = true;
                }
                case 75 -> weight = in.g2s();
                case 94 -> category = in.g2();
                case 95, 99, 110, 111, 112 -> in.g2();      // 2D z angle, unknown, model resize x/y/z
                case 97 -> certLink = in.g2();
                case 98 -> certTemplate = in.g2();
                case 100, 101, 102, 103, 104, 105, 106, 107, 108, 109 -> in.skip(4); // stack variants: id, count
                case 113, 114 -> in.g1s();                  // ambient, contrast
                case 115 -> in.g1();                        // team
                case 139 -> boughtLink = in.g2();
                case 140 -> boughtTemplate = in.g2();
                case 148 -> placeholderLink = in.g2();
                case 149 -> placeholderTemplate = in.g2();
                case 160 -> stackable = 2;
                case 161 -> in.skip(in.g2() * 2);
                case 200 -> {                               // ground sub-op: op, sub id, text
                    in.g1();
                    in.g1();
                    in.gjstr();
                }
                case 201 -> {                               // conditional ground op
                    int slot = in.g1();
                    int varp = in.g2();
                    int varbit = in.g2();
                    int min = in.g4();
                    int max = in.g4();
                    String text = in.gjstr();
                    opSlot(slot).conditions.add(new ObjType.OpCondition(varp, varbit == 0xffff ? -1 : varbit, min,
                            max, text));
                }
                case 202 -> {                               // conditional ground sub-op
                    in.g1();
                    in.g2();
                    in.g2();
                    in.g2();
                    in.g4();
                    in.g4();
                    in.gjstr();
                }
                case 249 -> params = in.params();
                case 251 -> {
                    // sets a flag at +0x30b that nothing in the Lua API reads
                }
                default -> throw new ConfigFormatException("obj opcode " + opcode + " at " + (in.position() - 1));
            }
        }

        /**
         * The client's op setter: grows the slots to {@code slot}, replaces the text and drops any conditions.
         *
         * @param slot the 0-based ground op slot
         * @param text the op's new text
         */
        void setOp(int slot, String text) {
            OpSlot op = opSlot(slot);
            op.text = text;
            op.conditions.clear();
        }

        /**
         * A slot, growing the list to reach it. The client attaches conditions without growing (it assumes the op
         * was set first); growing here only avoids its out-of-bounds write.
         *
         * @param slot the 0-based ground op slot
         * @return the slot (new slots have text "" and no conditions)
         */
        OpSlot opSlot(int slot) {
            while (groundOps.size() <= slot) {
                groundOps.add(new OpSlot(""));
            }
            return groundOps.get(slot);
        }

        /**
         * 0x5f70c0: a noted item looks like its template, and takes name, value and members from the item.
         *
         * @param template the note template (opcode 98), loaded
         * @param linked   the item this notes (opcode 97), loaded
         */
        void mergeCert(Raw template, Raw linked) {
            model = template.model;
            name = linked.name;
            desc = NOTE_DESC;
            members = linked.members;
            cost = linked.cost;
            flag308 = linked.flag308;
            text9 = linked.text9;
            stackable = 1;
        }

        /**
         * 0x5f72c0: a bought item copies most of the linked item but is worth nothing; its op 5 is "Discard".
         *
         * @param template the bought template (opcode 140), loaded
         * @param linked   the item it stands for (opcode 139), loaded
         */
        void mergeBought(Raw template, Raw linked) {
            model = template.model;
            name = linked.name;
            desc = linked.desc;
            members = linked.members;
            stackable = linked.stackable;
            text9 = linked.text9;
            wearPos1 = linked.wearPos1;
            wearPos2 = linked.wearPos2;
            wearPos3 = linked.wearPos3;
            groundOps = new ArrayList<>();
            for (OpSlot op : linked.groundOps) {
                groundOps.add(op.copy());
            }
            weight = linked.weight;
            iops = new String[]{linked.iops[0], linked.iops[1], linked.iops[2], linked.iops[3], DISCARD};
            cost = 0;
            flag308 = false;
        }

        /**
         * 0x5f7820: a placeholder looks like its template and has the linked item's name, but no value or trade.
         *
         * @param template the placeholder template (opcode 149), loaded
         * @param linked   the item it holds the place of (opcode 148), loaded
         */
        void mergePlaceholder(Raw template, Raw linked) {
            model = template.model;
            category = template.category;
            stackable = template.stackable;
            name = linked.name;
            desc = linked.desc;
            text9 = linked.text9;
            cost = 0;
            members = false;
            flag308 = false;
            tradeable = false;
            flag30a = false;
        }

        /**
         * The loader's free-world rewrite of a members item.
         */
        void freeWorld() {
            if (certTemplate == -1 && boughtTemplate == -1 && placeholderTemplate == -1) {
                name = name + MEMBERS_SUFFIX;
            }
            desc = MEMBERS_DESC;
            flag30a = false;
            flag308 = false;
            groundOps = new ArrayList<>();
            setOp(2, TAKE);
            iops = new String[]{"", "", "", "", iops[4]};
            category = -1;
        }

        ObjType build(int requestedId) {
            List<ObjType.Op> ops = new ArrayList<>(groundOps.size());
            for (OpSlot op : groundOps) {
                ops.add(new ObjType.Op(op.text, List.copyOf(op.conditions)));
            }
            return new ObjType(requestedId, name, desc, model, certLink, certTemplate, placeholderLink,
                    placeholderTemplate, canonicalId, new LinkedHashMap<>(params), List.of(iops), ops, weight, cost,
                    stackable, tradeable, wearPos1, wearPos2, members, category);
        }
    }

    /**
     * A ground op slot while loading (mutable, as the client's is).
     */
    private static final class OpSlot {
        String text;
        final List<ObjType.OpCondition> conditions = new ArrayList<>();

        OpSlot(String text) {
            this.text = text;
        }

        OpSlot copy() {
            OpSlot copy = new OpSlot(text);
            copy.conditions.addAll(conditions);
            return copy;
        }
    }
}
