package com.kraken.api.internal.event;

import com.kraken.api.events.WidgetClosed;
import com.kraken.api.events.WidgetLoaded;
import com.kraken.api.internal.OpenInterfaces;
import com.kraken.api.internal.reader.WidgetReader;
import com.kraken.api.widgets.WidgetNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link WidgetClosed} and {@link WidgetLoaded} as interfaces close and open, from the groups opened in components (the
 * client's component table) and the top-level interface, compared every poll. A group gone from its component posts a
 * close, a group come into one posts a load, and a group that moved to another component posts neither. A new
 * top-level interface posts a load, and the one it replaces posts nothing. When tracking starts as the API attaches,
 * the top-level interface and every group open in a component post a load.
 *
 * <p>With the native hook connected, the client's open and close functions report each call ({@code ifOpenSub},
 * {@code ifCloseSub}) with the component, the group, and the mode or the unload flag. The tracker applies the reports
 * in order before it compares, so it also sees what a comparison can't: a group closed and opened again in the same
 * component (a dialogue's next line: a close that doesn't unload, then a load), or one opened and closed between two
 * polls. A report of a change a read already showed is passed over. The hooks run as the functions start, so a read
 * can land just before the change; the components the reports touched sit out that poll's comparison.
 *
 * <p>A load waits until the client holds the group's components (it loads them from the cache as it opens the group),
 * for at most {@link #MAX_LOAD_WAIT} polls, and the events behind it wait with it, so they keep their order.
 */
final class WidgetTracker extends Tracker {

    /**
     * The most polls a load waits for the client to hold the group's components.
     */
    private static final int MAX_LOAD_WAIT = 50;

    /**
     * The most hook reports kept between two polls. Past it they are dropped, and the comparison covers what they
     * report.
     */
    private static final int MAX_REPORTS = 512;

    private final WidgetReader reader;
    private final List<NativeEvent> reports = new ArrayList<>();
    private final Deque<Object> pending = new ArrayDeque<>();
    private Map<Integer, WidgetNode> open = new LinkedHashMap<>();
    private int topLevel = -1;
    private int waited;

    WidgetTracker(TrackerContext context) {
        super(context, WidgetLoaded.class, WidgetClosed.class);
        this.reader = client.getWidgetReader();
    }

    @Override
    Set<String> triggerNames() {
        return Set.of(NativeEvent.IF_OPEN_SUB, NativeEvent.IF_CLOSE_SUB);
    }

    @Override
    void onTrigger(NativeEvent event) {
        if (reports.size() < MAX_REPORTS) {
            reports.add(event);
        }
    }

    @Override
    boolean baseline(boolean replay) {
        reports.clear(); // the read covers what they report
        OpenInterfaces now = reader.readOpenInterfaces();
        if (now == null) {
            return false;
        }
        topLevel = now.topLevel();
        open = new LinkedHashMap<>(now.components());
        if (replay) {
            if (topLevel != -1) {
                pending.add(loaded(topLevel));
            }
            open.values().forEach(node -> pending.add(loaded(node.getId())));
            flush();
        }
        return true;
    }

    @Override
    void update(boolean ticked) {
        OpenInterfaces now = reader.readOpenInterfaces();
        if (now != null) {
            compareTopLevel(now.topLevel());
        }
        Set<Integer> touched = applyReports();
        if (now != null) {
            compare(now.components(), touched);
        }
        flush();
    }

    @Override
    void forget() {
        reports.clear();
        pending.clear();
        open = new LinkedHashMap<>();
        topLevel = -1;
        waited = 0;
    }

    /**
     * Queues a load for a new top-level interface.
     *
     * @param top the top-level interface read now, -1 for none
     */
    private void compareTopLevel(int top) {
        if (top == topLevel) {
            return;
        }
        topLevel = top;
        if (top != -1) {
            pending.add(loaded(top));
        }
    }

    /**
     * Applies the hook's reports in the order the client made the calls: an open queues a load unless a read already
     * saw the group in that component, a close queues a close if the group is still held there.
     *
     * @return the components the reports touched
     */
    private Set<Integer> applyReports() {
        Set<Integer> touched = new HashSet<>();
        for (NativeEvent report : reports) {
            long[] regs = report.regs();
            int component = (int) regs[NativeEvent.RDX];
            int group = (int) regs[NativeEvent.R8];
            WidgetNode held = open.get(component);
            touched.add(component);
            if (NativeEvent.IF_OPEN_SUB.equals(report.name())) {
                if (held != null && held.getId() == group) {
                    continue;
                }
                if (held != null) {
                    pending.add(closed(held, true)); // the client closes what a component holds before it opens another
                }
                open.put(component, new WidgetNode(group, (int) regs[NativeEvent.R9]));
                pending.add(loaded(group));
            } else if (held != null && held.getId() == group) {
                open.remove(component);
                pending.add(closed(held, (regs[NativeEvent.R9] & 0xff) != 0));
            }
        }
        reports.clear();
        return touched;
    }

    /**
     * Compares the component table read now with the groups held: closes for the groups gone, then loads for the
     * groups come, leaving out the groups that only moved between components and the components the reports touched.
     *
     * @param now     the component table read now
     * @param touched the components the reports touched in this poll
     */
    private void compare(Map<Integer, WidgetNode> now, Set<Integer> touched) {
        List<Integer> gone = new ArrayList<>();
        for (Map.Entry<Integer, WidgetNode> entry : open.entrySet()) {
            WidgetNode current = now.get(entry.getKey());
            if (!touched.contains(entry.getKey()) && (current == null || current.getId() != entry.getValue().getId())) {
                gone.add(entry.getKey());
            }
        }
        List<Integer> came = new ArrayList<>();
        for (Map.Entry<Integer, WidgetNode> entry : now.entrySet()) {
            WidgetNode held = open.get(entry.getKey());
            if (touched.contains(entry.getKey())) {
                continue;
            }
            if (held == null || held.getId() != entry.getValue().getId()) {
                came.add(entry.getKey());
            } else if (held.getModalMode() != entry.getValue().getModalMode()) {
                open.put(entry.getKey(), entry.getValue());
            }
        }
        List<WidgetNode> closed = new ArrayList<>();
        for (int component : gone) {
            closed.add(open.remove(component));
        }
        List<WidgetNode> loaded = new ArrayList<>();
        for (int component : came) {
            WidgetNode node = now.get(component);
            open.put(component, node);
            loaded.add(node);
        }
        closed.removeIf(node -> moved(node, loaded));
        closed.forEach(node -> pending.add(closed(node, true)));
        loaded.forEach(node -> pending.add(loaded(node.getId())));
    }

    /**
     * Whether a group gone from one component came into another, taking the one it came as out of {@code loaded}.
     *
     * @param node   the group gone
     * @param loaded the groups come
     * @return true if the group moved
     */
    private static boolean moved(WidgetNode node, List<WidgetNode> loaded) {
        for (int i = 0; i < loaded.size(); i++) {
            if (loaded.get(i).getId() == node.getId()) {
                loaded.remove(i);
                return true;
            }
        }
        return false;
    }

    /**
     * Posts the queued events in order, stopping at a load whose group the client doesn't hold the components of yet.
     */
    private void flush() {
        while (!pending.isEmpty()) {
            if (pending.peek() instanceof WidgetLoaded load && holdsBack(load.getGroupId())) {
                waited++;
                return;
            }
            waited = 0;
            bus.post(pending.poll());
        }
    }

    /**
     * Whether a load waits: the group is still open, the client holds none of its components yet, and the load hasn't
     * waited {@link #MAX_LOAD_WAIT} polls.
     *
     * @param group the group loaded
     * @return true to wait for the next poll
     */
    private boolean holdsBack(int group) {
        if (waited >= MAX_LOAD_WAIT || reader.getGroupSize(group) != 0) {
            return false;
        }
        return group == topLevel || open.values().stream().anyMatch(node -> node.getId() == group);
    }

    private static WidgetLoaded loaded(int group) {
        WidgetLoaded event = new WidgetLoaded();
        event.setGroupId(group);
        return event;
    }

    private static WidgetClosed closed(WidgetNode node, boolean unload) {
        return new WidgetClosed(node.getId(), node.getModalMode(), unload);
    }
}
