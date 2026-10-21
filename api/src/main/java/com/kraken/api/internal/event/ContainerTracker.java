package com.kraken.api.internal.event;

import com.kraken.api.events.ItemContainerChanged;
import com.kraken.api.item.ItemContainer;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * {@link ItemContainerChanged}, with the whole container, for each inventory whose slots changed or that the client
 * started holding. Posted in inventory id order.
 */
final class ContainerTracker extends Tracker {

    private Map<Integer, ItemContainer> last;

    ContainerTracker(TrackerContext context) {
        super(context, ItemContainerChanged.class);
    }

    @Override
    Set<String> triggerNames() {
        return Set.of("ON_INV_TRANSMIT");
    }

    @Override
    boolean baseline(boolean replay) {
        last = read();
        if (replay) {
            last.values().forEach(this::post);
        }
        return true;
    }

    @Override
    void update(boolean ticked) {
        Map<Integer, ItemContainer> now = read();
        Map<Integer, ItemContainer> before = last;
        last = now;
        for (ItemContainer inventory : now.values()) {
            if (!inventory.equals(before.get(inventory.getId()))) {
                post(inventory);
            }
        }
    }

    @Override
    void forget() {
        last = null;
    }

    private Map<Integer, ItemContainer> read() {
        Map<Integer, ItemContainer> inventories = new TreeMap<>();
        for (ItemContainer inventory : client.getItemContainers()) {
            inventories.put(inventory.getId(), inventory);
        }
        return inventories;
    }

    private void post(ItemContainer inventory) {
        bus.post(new ItemContainerChanged(inventory.getId(), inventory));
    }
}
