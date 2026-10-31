package com.kraken.api.internal.action;

import com.kraken.api.Client;
import com.kraken.api.action.ActionResult;

import java.util.Objects;
import java.util.function.IntPredicate;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * An on/off state the server keeps in a var, such as running or a prayer, and the click that flips it. The var only
 * changes when the server's answer arrives, a tick or so after the click, so two clicks in that window would flip it
 * twice. A toggle remembers what it last sent and takes that as the state until the var changes or
 * {@link #LAG_TICKS} ticks pass. Internal: the services' setters use it.
 *
 * <p>Thread-safe.
 */
public final class Toggle {

    /**
     * How many game ticks a sent state stands in for the var's, unless the var changes first.
     */
    public static final int LAG_TICKS = 2;

    private final Client client;
    private final IntSupplier var;
    private final IntPredicate on;

    private boolean pending;
    private boolean sentState;
    private int sentVar;
    private int sentTick;

    /**
     * Creates a toggle over a var.
     *
     * @param client the client, for the tick count
     * @param var    reads the var
     * @param on     whether a value of the var means on
     */
    public Toggle(Client client, IntSupplier var, IntPredicate on) {
        this.client = Objects.requireNonNull(client, "client");
        this.var = Objects.requireNonNull(var, "var");
        this.on = Objects.requireNonNull(on, "on");
    }

    /**
     * The state: what was last sent while the server's answer is due, else the var's.
     *
     * @return true for on
     */
    public synchronized boolean isOn() {
        int value = var.getAsInt();
        if (pending && value == sentVar && client.getTickCount() - sentTick < LAG_TICKS) {
            return sentState;
        }
        pending = false;
        return on.test(value);
    }

    /**
     * Flips the state with a click when it isn't the one wanted, and remembers what was sent.
     *
     * @param wanted the state wanted
     * @param click  sends the click that flips it
     * @return {@link ActionResult#SENT}, also when the state is already the one wanted (nothing is sent then); else
     *         the click's result
     */
    public synchronized ActionResult set(boolean wanted, Supplier<ActionResult> click) {
        if (isOn() == wanted) {
            return ActionResult.SENT;
        }
        ActionResult result = click.get();
        if (result.isSent()) {
            pending = true;
            sentState = wanted;
            sentVar = var.getAsInt();
            sentTick = client.getTickCount();
        }
        return result;
    }
}
