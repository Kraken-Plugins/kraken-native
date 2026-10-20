package com.kraken.api.lua.types;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Mirrors the Lua {@code osrs.ObjType}: one item definition, decoded by {@link ObjTypeDecoder} exactly as the client's
 * loader builds it (noted, bought and placeholder variants already merged with their templates, members items already
 * adjusted on a free world). The {@code ClientOp.oc*} functions read the same type.
 *
 * <p>An id without a config file gets the client's default type: empty name, cost 1, not stackable, ground op 3
 * "Take" and inventory op 5 "Drop".
 */
public final class ObjType implements TypeData {

    /**
     * Reads the current value of a varp or varbit, to resolve conditional ops.
     */
    public interface VarReader {

        /**
         * A varp's value. The client resolves conditional ops against its server-side varp array
         * ({@code global.varpsBits}, the one varbits read), not {@code global.varps}.
         *
         * @param id the varp id
         * @return the varp's current value
         */
        int varp(int id);

        /**
         * A varbit's value, as {@code ClientState.getVarbit} returns it.
         *
         * @param id the varbit id
         * @return the varbit's current value
         */
        int varbit(int id);
    }

    /**
     * An alternative text for a ground op, shown while a var is in range (config opcode 201).
     *
     * @param varp   the varp to test, used when {@code varbit} is -1
     * @param varbit the varbit to test, or -1 to test {@code varp}
     * @param min    lowest value (inclusive) that selects {@code text}
     * @param max    highest value (inclusive) that selects {@code text}
     * @param text   the op text while the condition holds
     */
    public record OpCondition(int varp, int varbit, int min, int max, String text) {

        /**
         * Whether the condition holds for the current vars.
         *
         * @param vars where to read the varp or varbit from
         * @return true if the var's value is within {@code min..max}
         */
        public boolean test(VarReader vars) {
            int value = varbit != -1 ? vars.varbit(varbit) : vars.varp(varp);
            return value >= min && value <= max;
        }
    }

    /**
     * One ground op slot: its text ("" when unset) and any conditional alternatives, tried in order.
     *
     * @param text       the op's text when no condition holds
     * @param conditions alternatives; the first whose condition holds replaces {@code text}
     */
    public record Op(String text, List<OpCondition> conditions) {

        /**
         * The text the client shows for the current vars.
         *
         * @param vars where to read the conditions' vars from
         * @return the first matching condition's text, else {@link #text()}
         */
        public String resolve(VarReader vars) {
            for (OpCondition condition : conditions) {
                if (condition.test(vars)) {
                    return condition.text();
                }
            }
            return text;
        }
    }

    private static final int OP_SLOTS = 5;

    private final int id;
    private final String name;
    private final String desc;
    private final int modelId;
    private final int certId;
    private final int certTemplateId;
    private final int placeholderId;
    private final int placeholderTemplateId;
    private final int canonicalId;
    private final Map<Integer, Object> params;
    private final List<String> iops;
    private final List<Op> groundOps;
    private final int weight;
    private final int cost;
    private final int stackable;
    private final boolean tradeable;
    private final int wearPos1;
    private final int wearPos2;
    private final boolean members;
    private final int category;

    ObjType(int id, String name, String desc, int modelId, int certId, int certTemplateId, int placeholderId,
            int placeholderTemplateId, int canonicalId, Map<Integer, Object> params, List<String> iops,
            List<Op> groundOps, int weight, int cost, int stackable, boolean tradeable, int wearPos1, int wearPos2,
            boolean members, int category) {
        this.id = id;
        this.name = name;
        this.desc = desc;
        this.modelId = modelId;
        this.certId = certId;
        this.certTemplateId = certTemplateId;
        this.placeholderId = placeholderId;
        this.placeholderTemplateId = placeholderTemplateId;
        this.canonicalId = canonicalId;
        this.params = Collections.unmodifiableMap(params);
        this.iops = List.copyOf(iops);
        this.groundOps = List.copyOf(groundOps);
        this.weight = weight;
        this.cost = cost;
        this.stackable = stackable;
        this.tradeable = tradeable;
        this.wearPos1 = wearPos1;
        this.wearPos2 = wearPos2;
        this.members = members;
        this.category = category;
    }

    /**
     * The id this type was looked up with (the Lua object keeps the id it was made for).
     *
     * @return the item id
     */
    @Override
    public int getId() {
        return id;
    }

    /**
     * The item's name, what {@code ClientOp.ocName} returns.
     *
     * @return the name; "" for an id without a file; on a free world a members item reads "&lt;name&gt; (Members)"
     */
    @Override
    public String getName() {
        return name;
    }

    /**
     * The kind of entity this type describes.
     *
     * @return always {@link TypecodeEntityType#OBJ}
     */
    @Override
    public TypecodeEntityType getEntityType() {
        return TypecodeEntityType.OBJ;
    }

    /**
     * The examine text.
     *
     * @return the text; noted items have the client's note text, members items on a free world a members notice
     */
    public String getDesc() {
        return desc;
    }

    /**
     * The inventory model id (config opcode 44).
     *
     * @return the model id, or 0 if none
     */
    public int getModelId() {
        return modelId;
    }

    /**
     * The id of this item's noted (or unnoted) counterpart.
     *
     * @return the linked item's id, or -1 if it has none
     */
    public int getCertId() {
        return certId;
    }

    /**
     * The note template this item is drawn with (config opcode 98) when it is a noted item. Not a Lua function.
     *
     * @return the template's item id (799 in the current cache), or -1 if this isn't a noted item
     */
    public int getCertTemplateId() {
        return certTemplateId;
    }

    /**
     * The id of this item's bank placeholder (or the item a placeholder stands for).
     *
     * @return the linked item's id, or -1 if none
     */
    public int getPlaceholderId() {
        return placeholderId;
    }

    /**
     * The placeholder template this item is drawn with (config opcode 149) when it is a bank placeholder. Not a Lua
     * function.
     *
     * @return the template's item id, or -1 if this isn't a placeholder
     */
    public int getPlaceholderTemplateId() {
        return placeholderTemplateId;
    }

    /**
     * The plain item behind a noted item or placeholder, as the client resolves it: the linked item's id if this is
     * noted, else the linked item's id if this is a placeholder, else this type's own id.
     *
     * @return the plain item's id; -1 for a link to an id without a file, or for a type that has no file itself
     */
    public int getCanonicalId() {
        return canonicalId;
    }

    /**
     * The item's params.
     *
     * @return param id to {@code Integer}, {@code Long} or {@code String} value; empty if none
     */
    public Map<Integer, Object> getParams() {
        return params;
    }

    /**
     * Mirrors the client's {@code getOps}: the five <em>inventory</em> ops ("Wield", "Eat", ..., op 5 usually "Drop").
     * Note the client binds these to {@code getOps} and the ground ops to {@link #getIOps()}, the other way round from
     * what the Lua docs describe.
     *
     * @return five texts, index 0 being Lua's {@code op1}, "" for empty slots
     */
    public List<String> getOps() {
        return iops;
    }

    /**
     * Mirrors the client's {@code getIOps}: the five <em>ground</em> ops ("Take", ...), with conditional ops resolved
     * against {@code vars} as the client does.
     *
     * @param vars where to read conditional ops' vars from, or null to ignore conditions
     * @return five texts, index 0 being Lua's {@code op1}, "" for empty slots
     */
    public List<String> getIOps(VarReader vars) {
        List<String> out = new ArrayList<>(OP_SLOTS);
        for (int slot = 0; slot < OP_SLOTS; slot++) {
            out.add(groundOp(slot, vars));
        }
        return out;
    }

    /**
     * {@link #getIOps(VarReader)} without var state: each slot's base text, ignoring conditional ops.
     *
     * @return five texts, index 0 being Lua's {@code op1}, "" for empty slots
     */
    public List<String> getIOps() {
        return getIOps(null);
    }

    /**
     * The ground op in a 0-based slot, resolved as the client does: the first conditional text whose var is in range,
     * else the slot's own text. {@code ClientOp.ocOp(objId, index)} is {@code groundOp(index - 1, vars)}.
     *
     * @param slot the 0-based slot (Lua's op number minus one)
     * @param vars where to read conditional ops' vars from, or null to ignore conditions
     * @return the op text; "" outside the slots the type has
     */
    public String groundOp(int slot, VarReader vars) {
        if (slot < 0 || slot >= groundOps.size()) {
            return "";
        }
        Op op = groundOps.get(slot);
        return vars == null ? op.text() : op.resolve(vars);
    }

    /**
     * Every ground op slot the type has, with its conditional alternatives. Not a Lua function.
     *
     * @return the slots in order (at least three; slot 2 is "Take" unless the file changes it)
     */
    public List<Op> getGroundOps() {
        return groundOps;
    }

    /**
     * The item's weight. The loader zeroes it for items that stack (stack mode 1).
     *
     * @return the weight in grams
     */
    public int getWeight() {
        return weight;
    }

    /**
     * The item's value (its store price), what {@code ClientOp.ocCost} returns.
     *
     * @return the value in coins; 1 for an id without a file, 0 for bought and placeholder items
     */
    public int getValue() {
        return cost;
    }

    /**
     * Whether the item stacks, what {@code ClientOp.ocStackable} returns.
     *
     * @return true only for stack mode 1 (stack mode 2 reads false, as in the client)
     */
    public boolean isStackable() {
        return stackable == 1;
    }

    /**
     * Whether the item can be traded.
     *
     * @return true if the item's file marks it tradeable (noted items don't inherit it)
     */
    public boolean isTradeable() {
        return tradeable;
    }

    /**
     * Whether the item can be worn.
     *
     * @return true if the item has a worn slot
     */
    public boolean isEquipable() {
        return wearPos1 >= 0;
    }

    /**
     * Whether the item is a two-handed weapon.
     *
     * @return true if it's worn in the weapon slot (3) and also takes the shield slot (5)
     */
    public boolean isTwoHanded() {
        return wearPos1 == 3 && wearPos2 == 5;
    }

    /**
     * The worn slot (see {@code Client.WornSlots}).
     *
     * @return the slot index, or -1 if the item can't be worn
     */
    public int getSlot() {
        return wearPos1;
    }

    /**
     * Whether the item is members-only. Not a Lua function.
     *
     * @return true for a members item
     */
    public boolean isMembers() {
        return members;
    }

    /**
     * The item's category. Not a Lua function.
     *
     * @return the category id, or -1 if none
     */
    public int getCategory() {
        return category;
    }

    @Override
    public String toString() {
        return "ObjType[" + id + " " + name + "]";
    }
}
