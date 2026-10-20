package com.kraken.api.definition;

import com.kraken.api.Client;
import com.kraken.api.internal.Definitions;
import com.kraken.api.internal.ItemDecoder;
import com.kraken.api.item.EquipmentInventorySlot;
import lombok.AccessLevel;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.IntUnaryOperator;

/**
 * An item's definition, as RuneLite's {@code ItemComposition}: the client's obj type (config group 10), decoded by
 * {@link ItemDecoder} as the client's loader builds it (noted, bought and placeholder items merged with the items they
 * stand for, members items rewritten on a free world). Get one from {@link Client#getItemDefinition}.
 *
 * <p>An id without a config file gets the client's default type: empty name, price 1, not stackable, ground action 3
 * "Take" and inventory action 5 "Drop".
 *
 * <p>RuneLite's {@code ParamHolder} methods are here directly; an unset param reads -1 or "" (Kraken doesn't decode
 * the param definitions that hold RuneLite's defaults).
 */
@Getter
public final class ItemComposition {

    /**
     * The fraction of an item's value high alchemy pays, as in RuneLite.
     */
    public static final float HIGH_ALCHEMY_MULTIPLIER = 0.6f;

    private static final int OP_SLOTS = 5;

    @Getter(AccessLevel.NONE)
    private final Client client;

    /**
     * The id this definition was looked up with.
     */
    private final int id;

    /**
     * The item's name (Lua's {@code ClientOp.ocName}): "" for an id without a definition; "&lt;name&gt; (Members)" for
     * a members item on a free world.
     */
    private final String name;

    /**
     * The examine text: noted items have the client's note text, members items on a free world a members notice.
     * Kraken extra.
     */
    private final String examine;

    /**
     * The inventory model id (config opcode 44), or 0 if none.
     */
    private final int inventoryModel;

    /**
     * The noted version of this item, or the unnoted one if this is noted; -1 if it has none.
     */
    private final int linkedNoteId;

    /**
     * The note template this item is drawn with (config opcode 98), as RuneLite reports whether an item is noted: the
     * template's id (799) if this is a noted item, else -1.
     */
    private final int note;

    /**
     * This item's bank placeholder, or the item a placeholder stands for; -1 if none.
     */
    private final int placeholderId;

    /**
     * The placeholder template this item is drawn with (config opcode 149): its id if this is a placeholder, else -1.
     */
    private final int placeholderTemplateId;

    /**
     * The plain item behind a noted item or placeholder, as the client resolves it: the linked item's id if this is
     * noted, else the linked item's id if this is a placeholder, else this item's own id. -1 for a link to an id
     * without a file, or for an id that has no file itself. Kraken extra.
     */
    private final int canonicalId;

    /**
     * The item's params: param id to {@code Integer}, {@code Long} or {@code String} value; empty if none. Kraken
     * extra.
     */
    private final Map<Integer, Object> params;

    @Getter(AccessLevel.NONE)
    private final List<String> inventoryOps;

    @Getter(AccessLevel.NONE)
    private final List<ItemDecoder.Op> groundOps;

    /**
     * The item's weight in grams. The loader zeroes it for items that stack.
     */
    private final int weight;

    /**
     * The item's value (its store price, Lua's {@code ClientOp.ocCost}) in coins: 1 for an id without a file, 0 for
     * bought and placeholder items.
     */
    private final int price;

    @Getter(AccessLevel.NONE)
    private final int stackable;

    /**
     * Whether the item can be traded: true if the item's file marks it tradeable (noted items don't inherit it).
     */
    private final boolean tradeable;

    @Getter(AccessLevel.NONE)
    private final int wearPos1;

    @Getter(AccessLevel.NONE)
    private final int wearPos2;

    /**
     * Whether the item is members-only.
     */
    private final boolean members;

    /**
     * The item's category, or -1 if none.
     */
    private final int category;

    /**
     * A decoded definition. Internal: {@link ItemDecoder} makes them.
     *
     * @param client                the client the ground actions resolve vars against, or null to resolve none
     * @param id                    the item id
     * @param name                  the name
     * @param examine               the examine text
     * @param inventoryModel        the inventory model id
     * @param linkedNoteId          the noted or unnoted counterpart's id
     * @param note                  the note template's id, or -1
     * @param placeholderId         the placeholder counterpart's id
     * @param placeholderTemplateId the placeholder template's id, or -1
     * @param canonicalId           the plain item's id
     * @param params                the params
     * @param inventoryOps          the five inventory op texts
     * @param groundOps             the ground op slots, with their conditional texts
     * @param weight                the weight in grams
     * @param price                 the value in coins
     * @param stackable             the stack mode (1 stacks)
     * @param tradeable             whether it can be traded
     * @param wearPos1              the worn slot, or -1
     * @param wearPos2              the second slot it takes, or -1
     * @param members               whether it is members-only
     * @param category              the category, or -1
     */
    public ItemComposition(Client client, int id, String name, String examine, int inventoryModel, int linkedNoteId,
                           int note, int placeholderId, int placeholderTemplateId, int canonicalId,
                           Map<Integer, Object> params, List<String> inventoryOps, List<ItemDecoder.Op> groundOps,
                           int weight, int price, int stackable, boolean tradeable, int wearPos1, int wearPos2,
                           boolean members, int category) {
        this.client = client;
        this.id = id;
        this.name = name;
        this.examine = examine;
        this.inventoryModel = inventoryModel;
        this.linkedNoteId = linkedNoteId;
        this.note = note;
        this.placeholderId = placeholderId;
        this.placeholderTemplateId = placeholderTemplateId;
        this.canonicalId = canonicalId;
        this.params = Collections.unmodifiableMap(params);
        this.inventoryOps = List.copyOf(inventoryOps);
        this.groundOps = List.copyOf(groundOps);
        this.weight = weight;
        this.price = price;
        this.stackable = stackable;
        this.tradeable = tradeable;
        this.wearPos1 = wearPos1;
        this.wearPos2 = wearPos2;
        this.members = members;
        this.category = category;
    }

    /**
     * What high alchemy pays for the item.
     *
     * @return {@link #getPrice()} × {@link #HIGH_ALCHEMY_MULTIPLIER}, rounded down
     */
    public int getHaPrice() {
        return (int) (price * HIGH_ALCHEMY_MULTIPLIER);
    }

    /**
     * Whether the item stacks in an inventory slot (Lua's {@code ClientOp.ocStackable}).
     *
     * @return true only for stack mode 1 (stack mode 2 reads false, as in the client)
     */
    public boolean isStackable() {
        return stackable == 1;
    }

    /**
     * The item's inventory actions ("Wield", "Eat", …, usually "Drop" last), which the client binds to Lua's
     * {@code ObjType.getOps}.
     *
     * @return five entries, index 0 being the first action; null for an empty slot, as in RuneLite
     */
    public String[] getInventoryActions() {
        return Definitions.actions(inventoryOps);
    }

    /**
     * The item's ground actions ("Take", …), with conditional actions resolved against the current vars, as the
     * client binds them to Lua's {@code ObjType.getIOps}. Kraken extra.
     *
     * @return five entries, index 0 being the first action; null for an empty slot
     */
    public String[] getGroundActions() {
        return client == null ? Definitions.actions(getIOps(null, null))
                : Definitions.actions(getIOps(client::getVarpValue, client::getVarbitValue));
    }

    /**
     * Whether the item can be worn. Kraken extra.
     *
     * @return true if the item has a worn slot
     */
    public boolean isEquipable() {
        return wearPos1 >= 0;
    }

    /**
     * Whether the item is a two-handed weapon. Kraken extra.
     *
     * @return true if it's worn in the weapon slot (3) and also takes the shield slot (5)
     */
    public boolean isTwoHanded() {
        return wearPos1 == 3 && wearPos2 == 5;
    }

    /**
     * The worn slot ({@link EquipmentInventorySlot}). Kraken extra.
     *
     * @return the slot index, or -1 if the item can't be worn
     */
    public int getSlot() {
        return wearPos1;
    }

    /**
     * An integer param, as RuneLite's {@code ParamHolder.getIntValue}.
     *
     * @param paramId the param id
     * @return the param's value, or -1 if the definition doesn't set it (or sets a string)
     */
    public int getIntValue(int paramId) {
        return Definitions.intValue(params, paramId);
    }

    /**
     * A string param, as RuneLite's {@code ParamHolder.getStringValue}.
     *
     * @param paramId the param id
     * @return the param's value, or "" if the definition doesn't set it (or sets an integer)
     */
    public String getStringValue(int paramId) {
        return Definitions.stringValue(params, paramId);
    }

    /**
     * The five inventory op texts, "" for an empty slot.
     *
     * @return op1..op5 in order
     */
    List<String> getOps() {
        return inventoryOps;
    }

    /**
     * The five ground op texts, with conditional texts resolved against the given vars.
     *
     * @param varp   reads a varp, or null to ignore conditions
     * @param varbit reads a varbit
     * @return op1..op5 in order, "" for an empty slot
     */
    List<String> getIOps(IntUnaryOperator varp, IntUnaryOperator varbit) {
        List<String> out = new ArrayList<>(OP_SLOTS);
        for (int slot = 0; slot < OP_SLOTS; slot++) {
            out.add(groundOp(slot, varp, varbit));
        }
        return out;
    }

    /**
     * The ground op in a 0-based slot, resolved as the client does: the first conditional text whose var is in range,
     * else the slot's own text.
     *
     * @param slot   the 0-based slot
     * @param varp   reads a varp, or null to ignore conditions
     * @param varbit reads a varbit
     * @return the op text; "" outside the slots the type has
     */
    String groundOp(int slot, IntUnaryOperator varp, IntUnaryOperator varbit) {
        if (slot < 0 || slot >= groundOps.size()) {
            return "";
        }
        ItemDecoder.Op op = groundOps.get(slot);
        return varp == null ? op.text() : op.resolve(varp, varbit);
    }

    @Override
    public String toString() {
        return "ItemComposition[" + id + " " + name + "]";
    }
}
