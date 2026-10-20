package com.kraken.client.ui.devtools.tools;

import com.kraken.api.Client;
import com.kraken.api.GameSession;
import com.kraken.api.definition.VarbitComposition;
import com.kraken.api.eventbus.EventBus;
import com.kraken.api.eventbus.Subscriber;
import com.kraken.api.events.VarbitChanged;
import com.kraken.client.ui.devtools.Category;
import com.kraken.client.ui.devtools.DevTool;
import com.kraken.client.ui.devtools.ToolOption;
import com.kraken.client.ui.devtools.view.LogView;
import com.kraken.client.ui.devtools.view.ToolView;
import org.kordamp.ikonli.materialdesign2.MaterialDesignV;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * A log of var changes as they happen. Varps and varbits come from {@link VarbitChanged} events on the bus (a varp's
 * own event after the varbits on it, each with the varbit's new value; the varp's old value is kept from its last
 * event). Varcs have no event, so they're compared each refresh. Flip a setting in game (run, auto-retaliate...) to
 * see its varp and varbits change.
 */
final class VarInspectorTool extends DevTool<List<String>> {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final int MAX_PENDING = 2_000;
    private static final int MAX_STRING = 60;

    private final ToolOption varps = option("varps", "Varps", true);
    private final ToolOption varbits = option("varbits", "Varbits", true);
    private final ToolOption varcs = option("varcs", "Varcs", true);
    private final Queue<String> pending = new ConcurrentLinkedQueue<>();

    /**
     * Set on the EDT at start, then only read on the poll thread.
     */
    private Client client;
    private EventBus bus;
    private Subscriber subscription;

    /**
     * Varp values as last logged; written on the EDT at start, then only on the poll thread.
     */
    private volatile int[] lastVarps;

    // Poll thread only
    private Map<Integer, Integer> lastIntVarcs;
    private Map<Integer, String> lastStringVarcs;

    VarInspectorTool() {
        super("vars", "Var inspector", Category.STATE, MaterialDesignV.VARIABLE,
                "A live log of varp, varbit and varc changes with their old and new values.");
    }

    @Override
    public Support windowSupport() {
        return Support.READY;
    }

    @Override
    protected void onStart(GameSession session) {
        client = session.client();
        bus = session.eventBus();
        lastVarps = client.getVarps();
        lastIntVarcs = null;
        lastStringVarcs = null;
        // The client posts a varp's varbits before the varp, so lastVarps still holds the old varp values for them
        subscription = bus.register(VarbitChanged.class, this::varChanged, 0f);
        boolean hasVarcs = !client.getVarcMap().isEmpty();
        add(hasVarcs ? "Watching " + lastVarps.length + " varps, their varbits and the varcs"
                : "Watching " + lastVarps.length + " varps and their varbits (no varcs readable)");
    }

    @Override
    protected void onStop() {
        if (bus != null && subscription != null) {
            bus.unregister(subscription);
        }
        subscription = null;
        pending.clear();
    }

    @Override
    protected List<String> read(GameSession session) {
        diffVarcs(session.client());
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

    /**
     * Poll thread: a varbit's change, or a varp's own change (its varbit id is -1).
     *
     * @param event the event
     */
    private void varChanged(VarbitChanged event) {
        if (event.getVarbitId() == -1) {
            varpChanged(event.getVarpId(), event.getValue());
        } else {
            varbitChanged(event.getVarbitId(), event.getValue());
        }
    }

    /**
     * Poll thread: a varp's new value.
     *
     * @param id  the varp
     * @param now its new value
     */
    private void varpChanged(int id, int now) {
        int[] last = lastVarps;
        if (id >= last.length) {
            last = Arrays.copyOf(last, id + 1);
        }
        if (varps.isOn()) {
            add("t" + client.getTickCount() + "  varp " + id + "  " + last[id] + " → " + now);
        }
        last[id] = now;
        lastVarps = last;
    }

    /**
     * Poll thread: a varbit's new value.
     *
     * @param id    the varbit
     * @param value its new value
     */
    private void varbitChanged(int id, int value) {
        if (!varbits.isOn()) {
            return;
        }
        VarbitComposition type = client.getVarbit(id);
        int[] last = lastVarps;
        String was = type == null || type.getIndex() < 0 || type.getIndex() >= last.length ? "?"
                : String.valueOf(type.extract(last[type.getIndex()]));
        String where = type == null ? ""
                : "  (varp " + type.getIndex() + " bits " + type.getLeastSignificantBit() + "–"
                + type.getMostSignificantBit() + ")";
        add("t" + client.getTickCount() + "  varbit " + id + "  " + was + " → " + value + where);
    }

    /**
     * Poll thread: compares the varcs with the previous refresh.
     *
     * @param client reads the varcs
     */
    private void diffVarcs(Client client) {
        if (!varcs.isOn()) {
            lastIntVarcs = null;
            lastStringVarcs = null;
            return;
        }
        Map<Integer, Integer> ints = new HashMap<>();
        Map<Integer, String> strings = new HashMap<>();
        for (Map.Entry<Integer, Object> entry : client.getVarcMap().entrySet()) {
            if (entry.getValue() instanceof Integer value) {
                ints.put(entry.getKey(), value);
            } else if (entry.getValue() instanceof String value) {
                strings.put(entry.getKey(), value);
            }
        }
        if (lastIntVarcs != null) {
            int tick = client.getTickCount();
            for (Integer id : union(lastIntVarcs, ints)) {
                Integer was = lastIntVarcs.get(id);
                Integer now = ints.get(id);
                if (!Objects.equals(was, now)) {
                    add("t" + tick + "  varc " + id + "  " + (was == null ? "unset" : was) + " → "
                            + (now == null ? "unset" : now));
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

    /**
     * The ids in either map, in order.
     *
     * @param a the first map
     * @param b the second map
     * @return the ids
     */
    private static TreeSet<Integer> union(Map<Integer, ?> a, Map<Integer, ?> b) {
        TreeSet<Integer> ids = new TreeSet<>(a.keySet());
        ids.addAll(b.keySet());
        return ids;
    }

    /**
     * A string varc's value for the log, quoted and shortened.
     *
     * @param value the value, or null if it isn't set
     * @return the quoted value, or "unset"
     */
    private static String quote(String value) {
        if (value == null) {
            return "unset";
        }
        String shown = value.length() > MAX_STRING ? value.substring(0, MAX_STRING) + "…" : value;
        return "\"" + shown + "\"";
    }

    /**
     * Queues a timestamped line for the log, dropping the oldest past the limit.
     *
     * @param line the line
     */
    private void add(String line) {
        pending.add(TIME.format(LocalTime.now()) + "  " + line);
        while (pending.size() > MAX_PENDING) {
            pending.poll();
        }
    }
}
