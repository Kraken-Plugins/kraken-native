package com.kraken.api.internal.event;

import com.kraken.api.events.VarClientIntChanged;
import com.kraken.api.events.VarClientStrChanged;
import com.kraken.api.internal.VarcValues;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.IntFunction;

/**
 * {@link VarClientIntChanged} and {@link VarClientStrChanged} as varcs change, from the client's varc store compared
 * every poll: an int varc whose value changed, or that was set or dropped, posts an int event, and a string varc a
 * string event, in id order. Tracking that starts posts nothing for the current values.
 *
 * <p>With the native hook connected, the store's two setters report each set a script makes: {@code setVarcInt} with
 * the id and the value, {@code setVarcString} with the id. The tracker applies the reports in order before it compares.
 * An int report posts if its value differs from the last one the tracker knows, so the changes made between two polls
 * post one event each; a string report carries no value, so the tracker takes the varc's value from the poll's read and
 * posts if it changed. A set to the value the varc holds, or a change a read already showed, posts nothing. The hooks
 * run as the setters start, so a read can land just before the write: the varcs the reports touched sit out that
 * poll's comparison, and a string write the read missed posts on the next poll.
 */
final class VarcTracker extends Tracker {

    /**
     * The most hook reports kept between two polls. Past it they are dropped, and the comparison covers what they
     * report.
     */
    private static final int MAX_REPORTS = 1024;

    private final List<NativeEvent> reports = new ArrayList<>();
    private Map<Integer, Integer> ints = new HashMap<>();
    private Map<Integer, String> strings = new HashMap<>();

    VarcTracker(TrackerContext context) {
        super(context, VarClientIntChanged.class, VarClientStrChanged.class);
    }

    @Override
    Set<String> triggerNames() {
        return Set.of(NativeEvent.SET_VARC_INT, NativeEvent.SET_VARC_STRING);
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
        VarcValues now = client.readVarcs();
        if (now == null) {
            return false;
        }
        ints = new HashMap<>(now.ints());
        strings = new HashMap<>(now.strings());
        return true;
    }

    @Override
    void update(boolean ticked) {
        VarcValues now = client.readVarcs();
        if (now == null) {
            return; // the reports wait for a poll that can read the store
        }
        Set<Integer> touchedInts = new HashSet<>();
        Set<Integer> touchedStrings = new HashSet<>();
        for (NativeEvent report : reports) {
            int id = (int) report.reg(NativeEvent.RDX);
            if (NativeEvent.SET_VARC_INT.equals(report.name())) {
                touchedInts.add(id);
                apply(ints, id, (int) report.reg(NativeEvent.R8), VarClientIntChanged::new);
            } else {
                touchedStrings.add(id);
                apply(strings, id, now.strings().get(id), VarClientStrChanged::new);
            }
        }
        reports.clear();
        compare(ints, now.ints(), touchedInts, VarClientIntChanged::new);
        compare(strings, now.strings(), touchedStrings, VarClientStrChanged::new);
    }

    @Override
    void forget() {
        reports.clear();
        ints = new HashMap<>();
        strings = new HashMap<>();
    }

    /**
     * Takes a varc's value from a report, posting if it differs from the one the tracker knows.
     *
     * @param known the values the tracker knows, by varc id
     * @param id    the varc
     * @param value its value now, or null if it isn't set
     * @param event makes the event for a varc id
     * @param <T>   the kind of value
     */
    private <T> void apply(Map<Integer, T> known, int id, T value, IntFunction<Object> event) {
        if (!Objects.equals(known.get(id), value)) {
            put(known, id, value);
            bus.post(event.apply(id));
        }
    }

    /**
     * Compares a read of the varcs with the values the tracker knows, posting for each that changed in id order and
     * passing over the ones reports touched.
     *
     * @param known   the values the tracker knows, by varc id; updated to the read
     * @param now     the values read now, by varc id
     * @param touched the varcs the poll's reports touched
     * @param event   makes the event for a varc id
     * @param <T>     the kind of value
     */
    private <T> void compare(Map<Integer, T> known, Map<Integer, T> now, Set<Integer> touched,
                             IntFunction<Object> event) {
        List<Integer> changed = new ArrayList<>();
        for (Map.Entry<Integer, T> entry : now.entrySet()) {
            if (!touched.contains(entry.getKey()) && !entry.getValue().equals(known.get(entry.getKey()))) {
                changed.add(entry.getKey());
            }
        }
        for (Integer id : known.keySet()) {
            if (!touched.contains(id) && !now.containsKey(id)) {
                changed.add(id);
            }
        }
        Collections.sort(changed);
        for (int id : changed) {
            put(known, id, now.get(id));
            bus.post(event.apply(id));
        }
    }

    /**
     * Sets or drops a varc's known value.
     *
     * @param known the values the tracker knows, by varc id
     * @param id    the varc
     * @param value its value, or null if it isn't set
     * @param <T>   the kind of value
     */
    private static <T> void put(Map<Integer, T> known, int id, T value) {
        if (value == null) {
            known.remove(id);
        } else {
            known.put(id, value);
        }
    }
}
