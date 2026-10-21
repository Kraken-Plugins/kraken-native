package com.kraken.api.internal.event;

import com.kraken.api.MenuEntry;
import com.kraken.api.events.MenuOptionClicked;
import com.kraken.api.internal.reader.MenuReader;

import java.util.Set;

/**
 * {@link MenuOptionClicked} each time the client runs a menu entry. Running one copies it into the minimenu and zeroes
 * a counter that every client cycle increments, so a poll sees a new run when the counter went down or the copy
 * changed, and posts the copy. Runs aren't replayed when tracking starts.
 *
 * <p>The client raises an op event for most runs ({@code ON_NPC_OP}, {@code ON_LOC_OP}, {@code ON_TILE_OP},
 * {@code ON_PLAYER_OP}, {@code ON_COMPONENT_OP}; ground items' {@code ON_OBJ_OP} isn't hooked), which wakes a poll
 * right after the run. The event only says when to look: what was run comes from the copy, so a raise for a run a poll
 * already posted posts nothing more.
 */
final class MenuClickTracker extends Tracker {

    private final MenuReader reader;
    private int lastCycles = -1;
    private MenuEntry lastClicked;

    MenuClickTracker(TrackerContext context) {
        super(context, MenuOptionClicked.class);
        this.reader = client.getMenuReader();
    }

    @Override
    Set<String> triggerNames() {
        return Set.of("ON_NPC_OP", "ON_LOC_OP", "ON_TILE_OP", "ON_PLAYER_OP", "ON_OBJ_OP", "ON_COMPONENT_OP");
    }

    @Override
    boolean baseline(boolean replay) {
        int cycles = reader.clickCycles();
        if (cycles < 0) {
            return false;
        }
        lastCycles = cycles;
        lastClicked = reader.clicked();
        return true;
    }

    @Override
    void update(boolean ticked) {
        int cycles = reader.clickCycles();
        MenuEntry clicked = reader.clicked();
        if (cycles < 0 || clicked == null) {
            return;
        }
        boolean ranAgain = cycles < lastCycles || !clicked.equals(lastClicked);
        lastCycles = cycles;
        lastClicked = clicked;
        if (ranAgain) {
            bus.post(new MenuOptionClicked(clicked));
        }
    }

    @Override
    void forget() {
        lastCycles = -1;
        lastClicked = null;
    }
}
