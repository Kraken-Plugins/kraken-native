package com.kraken.api.action;

import com.kraken.api.Client;
import com.kraken.api.gameval.InterfaceID;
import com.kraken.api.gameval.VarbitID;
import com.kraken.api.internal.action.Toggle;
import com.kraken.api.widgets.Widget;

import java.util.EnumMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Turning prayers and quick prayers on and off. Get it from {@code client.getPrayers()} or inject it.
 *
 * <p>A prayer's state only changes when the server's answer arrives, a tick or so after the click, so each setter
 * remembers what it last sent and takes that as the state until the varbit changes or two ticks pass: two calls in a
 * row don't toggle twice.
 */
public final class Prayers {

    private static final Logger log = Logger.getLogger("kraken.api");

    /**
     * The prayer orbs the quick prayers can be switched with, the minimap's first.
     */
    private static final int[] ORBS = {InterfaceID.Orbs.PRAYERBUTTON, InterfaceID.OrbsNomap.PRAYERBUTTON};

    private final Client client;
    private final Map<Prayer, Toggle> toggles = new EnumMap<>(Prayer.class);
    private final Toggle quickPrayers;

    /**
     * Creates the service. {@code Client} makes the one plugins use.
     *
     * @param client the client
     */
    public Prayers(Client client) {
        this.client = client;
        for (Prayer prayer : Prayer.values()) {
            toggles.put(prayer, new Toggle(client,
                    () -> client.getVarbitValue(prayer.getVarbit()), value -> value == 1));
        }
        quickPrayers = new Toggle(client,
                () -> client.getVarbitValue(VarbitID.QUICKPRAYER_ACTIVE), value -> value == 1);
    }

    /**
     * Whether a prayer is on: its varbit, or what {@link #setActive} last sent while the server's answer is due.
     *
     * @param prayer the prayer
     * @return true if it is on
     */
    public boolean isActive(Prayer prayer) {
        return toggles.get(prayer).isOn();
    }

    /**
     * Turns a prayer on or off with its prayer book component, only when the state differs.
     *
     * @param prayer the prayer
     * @param active true to turn it on
     * @return {@link ActionResult#SENT}, also when it is already in the state asked for (nothing is sent then); or why
     *         nothing was sent, {@link ActionResult#NOT_FOUND} if the prayer book isn't loaded. It needn't be the
     *         side tab shown: the client keeps it loaded behind the others.
     */
    public ActionResult setActive(Prayer prayer, boolean active) {
        Widget widget = client.getWidget(prayer.getComponentId());
        if (widget == null) {
            log.fine(() -> prayer + " can't be switched: the prayer book isn't loaded");
            return ActionResult.NOT_FOUND;
        }
        return toggles.get(prayer).set(active, widget::click);
    }

    /**
     * Turns a prayer on: {@code setActive(prayer, true)}.
     *
     * @param prayer the prayer
     * @return the result of {@link #setActive}
     */
    public ActionResult activate(Prayer prayer) {
        return setActive(prayer, true);
    }

    /**
     * Turns a prayer off: {@code setActive(prayer, false)}.
     *
     * @param prayer the prayer
     * @return the result of {@link #setActive}
     */
    public ActionResult deactivate(Prayer prayer) {
        return setActive(prayer, false);
    }

    /**
     * Whether the quick prayers are on: varbit {@code QUICKPRAYER_ACTIVE}, or what {@link #setQuickPrayer} last sent
     * while the server's answer is due.
     *
     * @return true if they are on
     */
    public boolean isQuickPrayerActive() {
        return quickPrayers.isOn();
    }

    /**
     * Turns the quick prayers on or off with the prayer orb by the minimap, only when the state differs.
     *
     * @param active true to turn them on
     * @return {@link ActionResult#SENT}, also when they are already in the state asked for (nothing is sent then); or
     *         why nothing was sent, {@link ActionResult#NOT_FOUND} if the orb isn't loaded
     */
    public ActionResult setQuickPrayer(boolean active) {
        Widget orb = client.getInteractions().firstShown(ORBS);
        if (orb == null) {
            log.fine(() -> "no prayer orb is loaded for the quick prayers");
            return ActionResult.NOT_FOUND;
        }
        return quickPrayers.set(active, orb::click);
    }
}
