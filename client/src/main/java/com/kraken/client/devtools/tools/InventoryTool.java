package com.kraken.client.devtools.tools;

import com.kraken.api.KrakenApi;
import com.kraken.api.game.Client;
import com.kraken.api.game.Client.WornSlots;
import com.kraken.api.game.InvItem;
import com.kraken.api.game.Inventory;
import com.kraken.client.devtools.Category;
import com.kraken.client.devtools.DevTool;
import com.kraken.client.devtools.ToolOption;
import com.kraken.client.devtools.view.TableView;
import com.kraken.client.devtools.view.ToolView;
import org.kordamp.ikonli.materialdesign2.MaterialDesignB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Every slot of every inventory the client holds: your backpack, worn equipment, and the bank or a shop once you've
 * opened it. Item names need the item definitions ({@code ocName}), which the API can't read yet, so items show by id.
 */
final class InventoryTool extends DevTool<List<InventoryTool.Row>> {
    record Row(long key, int inv, String inventory, int slot, String wornSlot, int item, int count) {
    }

    private final ToolOption empty = option("empty", "Show empty slots", false);

    InventoryTool() {
        super("inventories", "Inventories", Category.STATE, MaterialDesignB.BAG_PERSONAL_OUTLINE,
                "Every inventory the client holds (backpack, worn, bank…) with each slot's item id and count.");
    }

    @Override
    public Support windowSupport() {
        return Support.READY;
    }

    @Override
    protected List<Row> read(KrakenApi api) {
        List<Inventory> inventories = new ArrayList<>(api.client().getInventories());
        inventories.sort(Comparator.comparingInt(Inventory::id));
        boolean showEmpty = empty.isOn();
        List<Row> rows = new ArrayList<>();
        for (Inventory inventory : inventories) {
            for (InvItem item : inventory.items()) {
                if (item.isEmpty() && !showEmpty) {
                    continue;
                }
                WornSlots worn = inventory.id() == Client.INV_WORN ? WornSlots.of(item.slot()) : null;
                rows.add(new Row((long) inventory.id() << 32 | item.slot(), inventory.id(), name(inventory.id()),
                        item.slot(), worn == null ? "" : worn.name(), item.id(), item.count()));
            }
        }
        return rows;
    }

    @Override
    protected ToolView<List<Row>> createView() {
        return TableView.<Row>builder("slots")
                .column("Inv", Integer.class, 50, Row::inv)
                .column("Inventory", String.class, 90, Row::inventory)
                .column("Slot", Integer.class, 46, Row::slot)
                .column("Worn slot", String.class, 80, Row::wornSlot)
                .column("Item id", Integer.class, 70, Row::item)
                .column("Count", Integer.class, 80, Row::count)
                .key(Row::key)
                .build();
    }

    private static String name(int inv) {
        return switch (inv) {
            case Client.INV_BACKPACK -> "Backpack";
            case Client.INV_WORN -> "Worn";
            case Client.INV_BANK -> "Bank";
            default -> "";
        };
    }
}
