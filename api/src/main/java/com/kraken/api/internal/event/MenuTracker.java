package com.kraken.api.internal.event;

import com.kraken.api.MenuEntry;
import com.kraken.api.events.MenuEntryAdded;
import com.kraken.api.events.MenuEntryRemoved;
import com.kraken.api.events.MenuOpened;
import com.kraken.api.internal.reader.MenuReader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link MenuEntryRemoved} for each entry that left the menu and {@link MenuEntryAdded} for each that came, comparing
 * the menu's rows every poll, then {@link MenuOpened} when the menu has opened since the last poll. Entries compare by
 * value, so the client rebuilding an unchanged menu every cycle posts nothing, and two equal entries count twice.
 * When tracking starts as the API attaches, every entry in the menu is posted as added.
 */
final class MenuTracker extends Tracker {

    private final MenuReader reader;
    private List<MenuEntry> last = List.of();
    private boolean wasOpen;

    MenuTracker(TrackerContext context) {
        super(context, MenuEntryAdded.class, MenuEntryRemoved.class, MenuOpened.class);
        this.reader = client.getMenuReader();
    }

    @Override
    boolean baseline(boolean replay) {
        last = reader.rows();
        wasOpen = reader.isOpen();
        if (replay) {
            for (MenuEntry entry : last) {
                bus.post(new MenuEntryAdded(entry));
            }
        }
        return true;
    }

    @Override
    void update(boolean ticked) {
        List<MenuEntry> now = reader.rows();
        boolean open = reader.isOpen();
        List<MenuEntry> before = last;
        last = now;
        for (MenuEntry gone : minus(before, now)) {
            bus.post(new MenuEntryRemoved(gone));
        }
        for (MenuEntry came : minus(now, before)) {
            bus.post(new MenuEntryAdded(came));
        }
        if (open && !wasOpen) {
            List<MenuEntry> entries = new ArrayList<>(now);
            Collections.reverse(entries);
            bus.post(new MenuOpened(entries.toArray(new MenuEntry[0])));
        }
        wasOpen = open;
    }

    @Override
    void forget() {
        last = List.of();
        wasOpen = false;
    }

    /**
     * The entries of {@code from} that {@code other} doesn't have, counting duplicates, in {@code from}'s order.
     *
     * @param from  the entries to keep from
     * @param other the entries to take away
     * @return what is left
     */
    private static List<MenuEntry> minus(List<MenuEntry> from, List<MenuEntry> other) {
        Map<MenuEntry, Integer> counts = new HashMap<>();
        for (MenuEntry entry : other) {
            counts.merge(entry, 1, Integer::sum);
        }
        List<MenuEntry> left = new ArrayList<>();
        for (MenuEntry entry : from) {
            Integer count = counts.get(entry);
            if (count == null || count == 0) {
                left.add(entry);
            } else {
                counts.put(entry, count - 1);
            }
        }
        return left;
    }
}
