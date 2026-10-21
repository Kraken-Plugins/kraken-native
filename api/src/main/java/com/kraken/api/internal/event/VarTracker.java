package com.kraken.api.internal.event;

import com.kraken.api.definition.VarbitComposition;
import com.kraken.api.events.VarbitChanged;

import java.util.Map;
import java.util.Set;

/**
 * {@link VarbitChanged} for each changed varp of the client's working copy, after one for each varbit on it whose
 * value changed.
 */
final class VarTracker extends Tracker {

    private int[] last;
    private Map<Integer, int[]> varbitsByVarp;

    VarTracker(TrackerContext context) {
        super(context, VarbitChanged.class);
    }

    @Override
    Set<String> triggerNames() {
        return Set.of("ON_VAR_TRANSMIT", "ON_VARBIT_UPDATE");
    }

    @Override
    boolean baseline(boolean replay) {
        int[] now = client.getVarps();
        if (now.length == 0) {
            return false;
        }
        last = now;
        varbitsByVarp = client.getConfigTypes().varbitsByVarp(); // builds the index once (a fraction of a second)
        return true;
    }

    @Override
    void update(boolean ticked) {
        int[] now = client.getVarps();
        if (now.length == 0) {
            return;
        }
        int[] before = last;
        last = now;
        if (before.length != now.length) {
            return;
        }
        for (int varp = 0; varp < now.length; varp++) {
            if (now[varp] != before[varp]) {
                postVarbits(varp, before[varp], now[varp]);
                post(varp, -1, now[varp]);
            }
        }
    }

    @Override
    void forget() {
        last = null;
    }

    private void postVarbits(int varp, int before, int now) {
        if (varbitsByVarp == null) {
            varbitsByVarp = client.getConfigTypes().varbitsByVarp();
        }
        int[] varbits = varbitsByVarp == null ? null : varbitsByVarp.get(varp);
        if (varbits == null) {
            return;
        }
        for (int varbit : varbits) {
            VarbitComposition type = client.getVarbit(varbit);
            if (type != null && type.extract(now) != type.extract(before)) {
                post(varp, varbit, type.extract(now));
            }
        }
    }

    private void post(int varp, int varbit, int value) {
        VarbitChanged event = new VarbitChanged();
        event.setVarpId(varp);
        event.setVarbitId(varbit);
        event.setValue(value);
        bus.post(event);
    }
}
