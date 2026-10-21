package com.kraken.client.ui.devtools.tools;

import com.kraken.api.Client;
import com.kraken.api.GameSession;
import com.kraken.api.definition.ItemComposition;
import com.kraken.api.gameval.InventoryID;
import com.kraken.api.item.EquipmentInventorySlot;
import com.kraken.api.item.Item;
import com.kraken.api.item.ItemContainer;
import com.kraken.client.ui.devtools.Category;
import com.kraken.client.ui.devtools.DevTool;
import com.kraken.client.ui.devtools.ToolOption;
import com.kraken.client.ui.devtools.view.MappedView;
import com.kraken.client.ui.devtools.view.TabbedView;
import com.kraken.client.ui.devtools.view.TableView;
import com.kraken.client.ui.devtools.view.ToolView;
import org.kordamp.ikonli.materialdesign2.MaterialDesignB;
import org.kordamp.ikonli.materialdesign2.MaterialDesignT;
import org.kordamp.ikonli.materialdesign2.MaterialDesignV;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Every slot of every inventory the client holds: your backpack, worn equipment, and the bank or a shop once you've
 * opened it, with item names, each inventory's defined size, and the equipment slot a worn item sits in. The window
 * has two tabs: a table of every slot, and the worn equipment and backpack drawn as the game lays them out.
 */
final class InventoryTool extends DevTool<InventoryTool.Snapshot> {
    /**
     * One slot's row.
     *
     * @param key       the inventory id and slot, unique among the rows
     * @param inv       the inventory's id
     * @param inventory the inventory's name
     * @param size      the inventory's defined size
     * @param slot      the slot
     * @param wornSlot  the equipment slot, for a worn item, or ""
     * @param item      the item id, -1 for an empty slot
     * @param name      the item's name, or ""
     * @param count     the stack's quantity
     * @param stackable whether the item stacks, or null for an empty slot
     */
    record Row(long key, int inv, String inventory, int size, int slot, String wornSlot, int item, String name,
               int count, Boolean stackable) {
    }

    /**
     * One read of the inventories.
     *
     * @param rows     the table's rows: every filled slot, plus the empty ones when they're shown
     * @param worn     the worn equipment's filled slots, or null when the client holds no worn inventory
     * @param backpack the backpack's filled slots, or null when the client holds no backpack
     */
    record Snapshot(List<Row> rows, List<Row> worn, List<Row> backpack) {
    }

    private final ToolOption empty = option("empty", "Show empty slots", false);

    /**
     * The window tab last picked, so the window reopens on it. EDT only.
     */
    private int tab;

    InventoryTool() {
        super("inventories", "Inventories", Category.STATE, MaterialDesignB.BAG_PERSONAL_OUTLINE,
                "Every inventory the client holds (backpack, worn, bank...) with each slot's item, name and count, "
                        + "and the inventory's size.");
    }

    @Override
    public Support windowSupport() {
        return Support.READY;
    }

    @Override
    protected Snapshot read(GameSession session) {
        Client client = session.client();
        List<ItemContainer> containers = new ArrayList<>(client.getItemContainers());
        containers.sort(Comparator.comparingInt(ItemContainer::getId));
        boolean showEmpty = empty.isOn();
        List<Row> rows = new ArrayList<>();
        List<Row> worn = null;
        List<Row> backpack = null;
        for (ItemContainer container : containers) {
            int inv = container.getId();
            List<Row> filled = new ArrayList<>();
            Item[] items = container.getItems();
            for (int slot = 0; slot < items.length; slot++) {
                Item item = items[slot];
                boolean isEmpty = item == null || item.getId() == -1;
                if (isEmpty && !showEmpty) {
                    continue;
                }
                ItemComposition definition = isEmpty ? null : client.getItemDefinition(item.getId());
                Row row = new Row((long) inv << 32 | slot, inv, name(inv), container.size(), slot,
                        inv == InventoryID.WORN ? wornSlot(slot) : "", isEmpty ? -1 : item.getId(),
                        isEmpty ? "" : definition.getName(), isEmpty ? 0 : item.getQuantity(),
                        isEmpty ? null : definition.isStackable());
                rows.add(row);
                if (!isEmpty) {
                    filled.add(row);
                }
            }
            if (inv == InventoryID.WORN) {
                worn = List.copyOf(filled);
            } else if (inv == InventoryID.INV) {
                backpack = List.copyOf(filled);
            }
        }
        return new Snapshot(List.copyOf(rows), worn, backpack);
    }

    @Override
    protected ToolView<Snapshot> createView() {
        TableView<Row> table = TableView.<Row>builder("slots")
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
        return TabbedView.<Snapshot>builder()
                .tab("Table", MaterialDesignT.TABLE, new MappedView<>(table, Snapshot::rows))
                .tab("Slots", MaterialDesignV.VIEW_GRID_OUTLINE, new InventorySlotsView())
                .selected(tab)
                .onSelect(index -> tab = index)
                .build();
    }

    /**
     * The equipment slot a worn item's slot index stands for.
     *
     * @param slot the slot index in the worn inventory
     * @return the equipment slot's name, or "" if none
     */
    private static String wornSlot(int slot) {
        for (EquipmentInventorySlot equipment : EquipmentInventorySlot.values()) {
            if (equipment.getSlotIdx() == slot) {
                return equipment.name();
            }
        }
        return "";
    }

    /**
     * An inventory's name.
     *
     * @param inv the inventory's id
     * @return "Backpack", "Worn", "Bank", or "" for another inventory
     */
    private static String name(int inv) {
        return switch (inv) {
            case InventoryID.INV -> "Backpack";
            case InventoryID.WORN -> "Worn";
            case InventoryID.BANK -> "Bank";
            default -> "";
        };
    }
}
