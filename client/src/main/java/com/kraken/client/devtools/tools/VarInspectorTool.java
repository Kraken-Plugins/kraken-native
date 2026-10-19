package com.kraken.client.devtools.tools;

import com.kraken.api.KrakenApi;
import com.kraken.api.events.Events;
import com.kraken.api.game.ClientState;
import com.kraken.client.devtools.Category;
import com.kraken.client.devtools.DevTool;
import com.kraken.client.devtools.ToolOption;
import com.kraken.client.devtools.view.LogView;
import com.kraken.client.devtools.view.ToolView;
import org.kordamp.ikonli.materialdesign2.MaterialDesignV;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * A log of var changes as they happen, like RuneLite's var inspector. Varps come from {@code ON_VAR_TRANSMIT} (the ids
 * that changed) and {@code getVarp} (their new values); varcs have no event, so they're compared each refresh. Flip a
 * setting in game (run, auto-retaliate…) to see its varp change.
 */
final class VarInspectorTool extends DevTool<List<String>> {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final int MAX_PENDING = 2_000;
    private static final int MAX_STRING = 60;

    private final ToolOption varps = option("varps", "Varps", true);
    private final ToolOption varcs = option("varcs", "Varcs", true);
    private final Queue<String> pending = new ConcurrentLinkedQueue<>();
    private Events events;
    private int subscription;
    /** Varp values as last logged; written on the EDT at start, then only on the poll thread. */
    private volatile int[] lastVarps;
    // Poll thread only
    private Map<Integer, Integer> lastIntVarcs;
    private Map<Integer, String> lastStringVarcs;

    VarInspectorTool() {
        super("vars", "Var inspector", Category.STATE, MaterialDesignV.VARIABLE,
                "A live log of varp and varc changes with their old and new values (getVarp, getIntegerVarc, getStringVarc).");
    }

    @Override
    public Support windowSupport() {
        return Support.READY;
    }

    @Override
    protected void onStart(KrakenApi api) {
        ClientState state = api.clientState();
        events = api.events();
        lastVarps = state.getVarps();
        lastIntVarcs = null;
        lastStringVarcs = null;
        subscription = events.subscribe(Events.ON_VAR_TRANSMIT, ids -> varpsChanged(state, ids));
        add(state.hasVarcs() ? "Watching " + lastVarps.length + " varps and the varcs"
                : "Watching " + lastVarps.length + " varps (this build's mapping has no varc keys, so no varcs)");
    }

    @Override
    protected void onStop() {
        events.unsubscribe(subscription);
        pending.clear();
    }

    @Override
    protected List<String> read(KrakenApi api) {
        diffVarcs(api.clientState());
        if (pending.isEmpty()) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        for (String line; (line = pending.poll()) != null; ) {
            lines.add(line);
        }
        return lines;
    }

    @Override
    protected ToolView<List<String>> createView() {
        return new LogView();
    }

    /** Poll thread: an ON_VAR_TRANSMIT event. */
    private void varpsChanged(ClientState state, int[] ids) {
        int[] last = lastVarps;
        int maxId = Arrays.stream(ids).max().orElse(-1);
        if (maxId >= last.length) {
            last = Arrays.copyOf(last, maxId + 1);
        }
        int tick = state.getTickCount();
        for (int id : ids) {
            int now = state.getVarp(id);
            if (varps.isOn()) {
                add("t" + tick + "  varp " + id + "  " + last[id] + " → " + now);
            }
            last[id] = now;
        }
        lastVarps = last;
    }

    /** Poll thread: compares the varcs with the previous refresh. */
    private void diffVarcs(ClientState state) {
        if (!varcs.isOn() || !state.hasVarcs()) {
            lastIntVarcs = null;
            lastStringVarcs = null;
            return;
        }
        Map<Integer, Integer> ints = state.getIntegerVarcs();
        Map<Integer, String> strings = state.getStringVarcs();
        if (lastIntVarcs != null) {
            int tick = state.getTickCount();
            for (Integer id : union(lastIntVarcs, ints)) {
                Integer was = lastIntVarcs.get(id);
                Integer now = ints.get(id);
                if (!Objects.equals(was, now)) {
                    add("t" + tick + "  varc " + id + "  " + (was == null ? "unset" : was) + " → " + (now == null ? "unset" : now));
                }
            }
            for (Integer id : union(lastStringVarcs, strings)) {
                String was = lastStringVarcs.get(id);
                String now = strings.get(id);
                if (!Objects.equals(was, now)) {
                    add("t" + tick + "  varc " + id + " (string)  " + quote(was) + " → " + quote(now));
                }
            }
        }
        lastIntVarcs = ints;
        lastStringVarcs = strings;
    }

    private static TreeSet<Integer> union(Map<Integer, ?> a, Map<Integer, ?> b) {
        TreeSet<Integer> ids = new TreeSet<>(a.keySet());
        ids.addAll(b.keySet());
        return ids;
    }

    private static String quote(String value) {
        if (value == null) {
            return "unset";
        }
        String shown = value.length() > MAX_STRING ? value.substring(0, MAX_STRING) + "…" : value;
        return "\"" + shown + "\"";
    }

    private void add(String line) {
        pending.add(TIME.format(LocalTime.now()) + "  " + line);
        while (pending.size() > MAX_PENDING) {
            pending.poll();
        }
    }
}
