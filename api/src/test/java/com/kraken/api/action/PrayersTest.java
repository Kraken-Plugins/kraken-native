package com.kraken.api.action;

import com.kraken.api.Client;
import com.kraken.api.ConfigFile;
import com.kraken.api.GameFixture;
import com.kraken.api.MenuAction;
import com.kraken.api.gameval.InterfaceID;
import com.kraken.api.gameval.VarbitID;
import com.kraken.api.internal.ConfigTypes;
import com.kraken.api.internal.invoke.NativeCall;
import com.kraken.api.widgets.WidgetType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Prayers}: prayers read their varbits, a click sends once within the tick lag, the prayer book has to be shown,
 * and the quick prayers switch with the orb shown.
 */
class PrayersTest {

    private static final int PRAYER_BOOK = InterfaceID.PRAYERBOOK;
    private static final int ORBS = InterfaceID.Orbs.PRAYERBUTTON >>> 16;
    private static final int NOMAP_ORBS = InterfaceID.OrbsNomap.PRAYERBUTTON >>> 16;
    private static final int PRAYER_VARP = 41;
    private static final int QUICK_VARP = 42;

    private GameFixture game;
    private Client client;
    private Prayers prayers;
    private final List<List<NativeCall>> sent = new ArrayList<>();
    private Actions reference;
    private final List<List<NativeCall>> expected = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        game = new GameFixture();
        for (Prayer prayer : Prayer.values()) {
            game.putConfigFile(ConfigTypes.VARBIT, prayer.getVarbit(),
                    ConfigFile.varbit(PRAYER_VARP, prayer.ordinal(), prayer.ordinal()));
        }
        game.putConfigFile(ConfigTypes.VARBIT, VarbitID.QUICKPRAYER_ACTIVE, ConfigFile.varbit(QUICK_VARP, 0, 0));
        client = new Client(game.memory, game.map);
        client.getActions().setInvoker(sent::add);
        client.getInteractions().setClickPolicy(ClickPolicy.NONE);
        prayers = client.getPrayers();
        reference = new Client(game.memory, game.map).getActions();
        reference.setInvoker(expected::add);

        long[] book = group(PRAYER_BOOK, 39);
        for (Prayer prayer : Prayer.values()) {
            long address = game.addComponent(prayer.getComponentId(), -1, -1, -1, WidgetType.GRAPHIC, 0, 0, 36, 32);
            game.setOps(address, "Activate");
            book[prayer.getComponentId() & 0xffff] = address;
        }
        game.setGroup(PRAYER_BOOK, book);
        game.openGroup(PRAYER_BOOK, 0, 0);

        long[] orbs = group(ORBS, 21);
        orbs[20] = orb(InterfaceID.Orbs.PRAYERBUTTON);
        game.setGroup(ORBS, orbs);
        game.openGroup(ORBS, 0, 0);
    }

    private long[] group(int group, int size) {
        long[] components = new long[size];
        for (int child = 0; child < size; child++) {
            components[child] = game.addComponent((group << 16) | child, -1, -1, -1, WidgetType.GRAPHIC, 0, 0, 36, 32);
        }
        return components;
    }

    private long orb(int componentId) {
        long address = game.addComponent(componentId, -1, -1, -1, WidgetType.GRAPHIC, 0, 0, 30, 30);
        game.setOps(address, "Quick-prayers");
        return address;
    }

    private static int bit(Prayer prayer) {
        return 1 << prayer.ordinal();
    }

    private void assertSentAsReference() {
        assertEquals(1, sent.size(), "one batch");
        assertEquals(expected.get(expected.size() - 1), sent.get(0), "the batch the matching Actions call sends");
    }

    @Test
    void aPrayerIsOnWhenItsVarbitIsOne() {
        assertFalse(prayers.isActive(Prayer.THICK_SKIN));
        game.setVarp(PRAYER_VARP, bit(Prayer.THICK_SKIN) | bit(Prayer.SHARP_EYE));
        assertTrue(prayers.isActive(Prayer.THICK_SKIN));
        assertTrue(prayers.isActive(Prayer.SHARP_EYE));
        assertFalse(prayers.isActive(Prayer.PRESERVE));
    }

    @Test
    void aPrayerClickSendsOnceWithinTheTickLag() {
        game.setTick(10);
        assertEquals(ActionResult.SENT, prayers.activate(Prayer.THICK_SKIN));
        reference.menuAction(-1, Prayer.THICK_SKIN.getComponentId(), MenuAction.CC_OP, 1, -1);
        assertSentAsReference();
        assertTrue(prayers.isActive(Prayer.THICK_SKIN), "what was sent stands in while the varbit is due");

        game.setTick(11);
        assertEquals(ActionResult.SENT, prayers.activate(Prayer.THICK_SKIN));
        assertEquals(1, sent.size(), "no second click within the lag");

        game.setTick(12);
        assertEquals(ActionResult.SENT, prayers.activate(Prayer.THICK_SKIN));
        assertEquals(2, sent.size(), "the lag is over and the varbit never moved");
    }

    @Test
    void deactivateClicksOnlyWhenThePrayerIsOn() {
        assertEquals(ActionResult.SENT, prayers.deactivate(Prayer.THICK_SKIN));
        assertTrue(sent.isEmpty(), "already off");

        game.setVarp(PRAYER_VARP, bit(Prayer.THICK_SKIN));
        assertEquals(ActionResult.SENT, prayers.deactivate(Prayer.THICK_SKIN));
        reference.menuAction(-1, Prayer.THICK_SKIN.getComponentId(), MenuAction.CC_OP, 1, -1);
        assertSentAsReference();
    }

    @Test
    void theVarbitMovingEndsTheLag() {
        game.setTick(20);
        assertEquals(ActionResult.SENT, prayers.activate(Prayer.THICK_SKIN));
        assertEquals(1, sent.size());

        game.setVarp(PRAYER_VARP, bit(Prayer.THICK_SKIN));
        assertEquals(ActionResult.SENT, prayers.activate(Prayer.THICK_SKIN));
        assertEquals(1, sent.size(), "the server answered: already on");
        assertEquals(ActionResult.SENT, prayers.deactivate(Prayer.THICK_SKIN));
        assertEquals(2, sent.size());
    }

    @Test
    void aPrayerNeedsItsBookLoadedAndShown() throws IOException {
        game.closeGroup(PRAYER_BOOK);
        assertEquals(ActionResult.NOT_FOUND, prayers.setActive(Prayer.THICK_SKIN, true));
        assertTrue(sent.isEmpty());

        GameFixture fresh = new GameFixture();
        Client bare = new Client(fresh.memory, fresh.map);
        assertEquals(ActionResult.NOT_FOUND, bare.getPrayers().setActive(Prayer.THICK_SKIN, true),
                "the book was never loaded");
    }

    @Test
    void quickPrayersSwitchWithTheOrb() {
        assertFalse(prayers.isQuickPrayerActive());
        assertEquals(ActionResult.SENT, prayers.setQuickPrayer(true));
        reference.menuAction(-1, InterfaceID.Orbs.PRAYERBUTTON, MenuAction.CC_OP, 1, -1);
        assertSentAsReference();

        game.setVarp(QUICK_VARP, 1);
        assertTrue(prayers.isQuickPrayerActive());
        assertEquals(ActionResult.SENT, prayers.setQuickPrayer(true));
        assertEquals(1, sent.size(), "already on: nothing sent");
        assertEquals(ActionResult.SENT, prayers.setQuickPrayer(false));
        assertEquals(2, sent.size());
    }

    @Test
    void theShownOrbIsPreferredOverTheMinimapOne() {
        game.closeGroup(ORBS);
        long[] nomap = group(NOMAP_ORBS, 19);
        nomap[18] = orb(InterfaceID.OrbsNomap.PRAYERBUTTON);
        game.setGroup(NOMAP_ORBS, nomap);
        game.openGroup(NOMAP_ORBS, 0, 0);

        assertEquals(ActionResult.SENT, prayers.setQuickPrayer(true));
        reference.menuAction(-1, InterfaceID.OrbsNomap.PRAYERBUTTON, MenuAction.CC_OP, 1, -1);
        assertSentAsReference();
    }

    @Test
    void quickPrayersNeedAnOrbLoaded() throws IOException {
        GameFixture fresh = new GameFixture();
        Client bare = new Client(fresh.memory, fresh.map);
        assertEquals(ActionResult.NOT_FOUND, bare.getPrayers().setQuickPrayer(true), "no orb is loaded");
    }

    @Test
    void theTickLagAppliesToTheQuickPrayers() {
        game.setTick(30);
        assertEquals(ActionResult.SENT, prayers.setQuickPrayer(true));
        assertEquals(ActionResult.SENT, prayers.setQuickPrayer(true));
        assertEquals(1, sent.size(), "the second call is within the lag");
        assertTrue(prayers.isQuickPrayerActive());
    }
}
