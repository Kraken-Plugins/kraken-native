package com.kraken.client.ui.devtools.tools;

import com.kraken.api.KrakenApi;
import com.kraken.api.lua.ClientOp;
import com.kraken.api.lua.ClientOp.WornSlots;
import com.kraken.api.lua.InvItem;
import com.kraken.api.lua.Inventory;
import com.kraken.client.ui.devtools.Category;
import com.kraken.client.ui.devtools.DevTool;
import com.kraken.client.ui.devtools.ToolOption;
import com.kraken.client.ui.devtools.view.TableView;
import com.kraken.client.ui.devtools.view.ToolView;
import org.kordamp.ikonli.materialdesign2.MaterialDesignB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Every slot of every inventory the client holds: your backpack, worn equipment, and the bank or a shop once you've
 * opened it, with item names ({@code ocName}) and each inventory's defined size ({@code invSize}).
 */
final class InventoryTool extends DevTool<List<InventoryTool.Row>> {
    record Row(long key, int inv, String inventory, int size, int slot, String wornSlot, int item, String name,
               int count, Boolean stackable) {
    }

    private final ToolOption empty = option("empty", "Show empty slots", false);

    InventoryTool() {
        super("inventories", "Inventories", Category.STATE, MaterialDesignB.BAG_PERSONAL_OUTLINE,
                "Every inventory the client holds (backpack, worn, bank…) with each slot's item, name and count, "
                        + "and the inventory's size.");
    }

    @Override
    public Support windowSupport() {
        return Support.READY;
    }

    @Override
    protected List<Row> read(KrakenApi api) {
        ClientOp client = api.lua().clientOp();
        List<Inventory> inventories = new ArrayList<>(client.getInventories());
        inventories.sort(Comparator.comparingInt(Inventory::id));
        boolean showEmpty = empty.isOn();
        List<Row> rows = new ArrayList<>();
        for (Inventory inventory : inventories) {
            int size = client.invSize(inventory.id());
            for (InvItem item : inventory.items()) {
                if (item.isEmpty() && !showEmpty) {
                    continue;
                }
                WornSlots worn = inventory.id() == ClientOp.INV_WORN ? WornSlots.of(item.slot()) : null;
                String itemName = item.isEmpty() ? "" : client.ocName(item.id());
                Boolean stacks = item.isEmpty() ? null : client.ocStackable(item.id());
                rows.add(new Row((long) inventory.id() << 32 | item.slot(), inventory.id(), name(inventory.id()), size,
                        item.slot(), worn == null ? "" : worn.name(), item.id(), itemName, item.count(), stacks));
            }
        }
        return rows;
    }

    @Override
    protected ToolView<List<Row>> createView() {
        return TableView.<Row>builder("slots")
                .column("Inv", Integer.class, 50, Row::inv)
                .column("Inventory", String.class, 90, Row::inventory)
                .column("Size", Integer.class, 46, Row::size)
                .column("Slot", Integer.class, 46, Row::slot)
                .column("Worn slot", String.class, 80, Row::wornSlot)
                .column("Item id", Integer.class, 70, Row::item)
                .column("Item", String.class, 170, Row::name)
                .column("Count", Integer.class, 80, Row::count)
                .column("Stacks", Boolean.class, 56, Row::stackable)
                .key(Row::key)
                .build();
    }

    private static String name(int inv) {
        return switch (inv) {
            case ClientOp.INV_BACKPACK -> "Backpack";
            case ClientOp.INV_WORN -> "Worn";
            case ClientOp.INV_BANK -> "Bank";
            default -> "";
        };
    }
}
