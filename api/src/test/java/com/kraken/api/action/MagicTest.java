package com.kraken.api.action;

import com.kraken.api.Client;
import com.kraken.api.ConfigFile;
import com.kraken.api.GameFixture;
import com.kraken.api.MenuAction;
import com.kraken.api.gameval.InterfaceID;
import com.kraken.api.gameval.VarbitID;
import com.kraken.api.internal.ConfigTypes;
import com.kraken.api.internal.invoke.NativeCall;
import com.kraken.api.scene.TileItem;
import com.kraken.api.scene.TileObject;
import com.kraken.api.widgets.Widget;
import com.kraken.api.widgets.WidgetConfig;
import com.kraken.api.widgets.WidgetType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Magic}: the spellbook shown decides which spells can be cast, a spell's mask decides its targets, and each
 * cast's batch matches the one {@link Actions} sends for the same selection and target.
 */
class MagicTest {

    private static final int SPELLBOOK_GROUP = InterfaceID.MagicSpellbook.UNIVERSE >>> 16;
    private static final int SPELLBOOK_SIZE = 0xd3;
    private static final int SPELLBOOK_VARP = 40;
    private static final int INVENTORY = InterfaceID.Inventory.ITEMS;
    private static final int COINS = 995;
    private static final int LOGS = 1511;

    private GameFixture game;
    private Client client;
    private Magic magic;
    private final List<List<NativeCall>> sent = new ArrayList<>();
    private Actions reference;
    private final List<List<NativeCall>> expected = new ArrayList<>();
    private long[] book;

    @BeforeEach
    void setUp() throws IOException {
        game = new GameFixture();
        game.putConfigFile(ConfigTypes.VARBIT, VarbitID.SPELLBOOK, ConfigFile.varbit(SPELLBOOK_VARP, 0, 1));
        client = new Client(game.memory, game.map);
        client.getActions().setInvoker(sent::add);
        client.getInteractions().setClickPolicy(ClickPolicy.NONE);
        magic = client.getMagic();
        reference = new Client(game.memory, game.map).getActions();
        reference.setInvoker(expected::add);

        book = new long[SPELLBOOK_SIZE];
        for (int child = 0; child < SPELLBOOK_SIZE; child++) {
            book[child] = game.addComponent((SPELLBOOK_GROUP << 16) | child, -1, -1, -1, WidgetType.GRAPHIC, 0, 0,
                    36, 32);
        }
        place(Spell.WIND_STRIKE, WidgetConfig.USE_NPC, "Cast");
        place(Spell.LUMBRIDGE_TELEPORT, 0, "Cast");
        place(Spell.VARROCK_TELEPORT, 0, "", "Teleport");
        place(Spell.SNARE, 0);
        place(Spell.HIGH_ALCHEMY, WidgetConfig.USE_WIDGET, "Cast");
        place(Spell.ICE_BARRAGE, WidgetConfig.USE_NPC, "Cast");
        game.setGroup(SPELLBOOK_GROUP, book);
        game.openGroup(SPELLBOOK_GROUP, 0, 0);
    }

    private void place(Spell spell, int events, String... ops) {
        int child = spell.getComponentId() & 0xffff;
        long address = game.addComponent(spell.getComponentId(), -1, -1, -1, WidgetType.GRAPHIC, 0, 0, 36, 32);
        book[child] = address;
        game.memory.putInt(address + game.map.offset("IfType.events"), events);
        game.setOps(address, ops);
    }

    private void assertSentAsReference() {
        assertEquals(1, sent.size(), "one batch");
        assertEquals(expected.get(expected.size() - 1), sent.get(0), "the batch the matching Actions call sends");
    }

    private TileObject object(int id) {
        return client.getScene().getTileObjects().stream().filter(o -> o.getId() == id).findFirst().orElseThrow();
    }

    private TileItem groundItem(int id) {
        return client.getScene().getGroundItems().stream().filter(i -> i.getId() == id).findFirst().orElseThrow();
    }

    @Test
    void aSpellFromAnotherSpellbookIsNotFound() {
        assertEquals(Spellbook.STANDARD, magic.getSpellbook());
        assertTrue(magic.isAvailable(Spell.WIND_STRIKE));
        assertFalse(magic.isAvailable(Spell.ICE_BARRAGE));
        assertEquals(ActionResult.NOT_FOUND, magic.cast(Spell.ICE_BARRAGE, client.npc(5)));
        assertEquals(ActionResult.NOT_FOUND, magic.cast(Spell.ICE_BARRAGE, COINS));
        assertTrue(sent.isEmpty());

        game.setVarp(SPELLBOOK_VARP, Spellbook.ANCIENT.getId());
        assertEquals(Spellbook.ANCIENT, magic.getSpellbook());
        assertTrue(magic.isAvailable(Spell.ICE_BARRAGE));
        assertEquals(ActionResult.NOT_FOUND, magic.cast(Spell.WIND_STRIKE, client.npc(5)));
        assertEquals(ActionResult.SENT, magic.cast(Spell.ICE_BARRAGE, client.npc(5)));
    }

    @Test
    void theSpellbookShownIsReadFromTheVarbit() {
        assertEquals(Spellbook.STANDARD, magic.getSpellbook());
        game.setVarp(SPELLBOOK_VARP, Spellbook.LUNAR.getId());
        assertEquals(Spellbook.LUNAR, magic.getSpellbook());
        game.setVarp(SPELLBOOK_VARP, Spellbook.ARCEUUS.getId());
        assertEquals(Spellbook.ARCEUUS, magic.getSpellbook());
        assertNull(Spellbook.of(4), "a value no spellbook has");
    }

    @Test
    void aNoTargetSpellRunsItsCastOption() {
        assertEquals(ActionResult.SENT, magic.cast(Spell.LUMBRIDGE_TELEPORT));
        reference.menuAction(-1, Spell.LUMBRIDGE_TELEPORT.getComponentId(), MenuAction.CC_OP, 1, -1);
        assertSentAsReference();
    }

    @Test
    void aSpellWithoutACastOptionRunsItsFirstNamedOne() {
        assertEquals(ActionResult.SENT, magic.cast(Spell.VARROCK_TELEPORT));
        reference.menuAction(-1, Spell.VARROCK_TELEPORT.getComponentId(), MenuAction.CC_OP, 2, -1);
        assertSentAsReference();

        sent.clear();
        assertEquals(ActionResult.NO_SUCH_OPTION, magic.cast(Spell.SNARE), "a spell with no options");
        assertTrue(sent.isEmpty());
    }

    @Test
    void aSpellCastsOnAnNpcButNotOnAPlayerOrAnObject() {
        assertEquals(ActionResult.SENT, magic.cast(Spell.WIND_STRIKE, client.npc(5)));
        reference.castSpellOn(Spell.WIND_STRIKE.getComponentId(), 0, 0, MenuAction.WIDGET_TARGET_ON_NPC, 5, -1);
        assertSentAsReference();

        sent.clear();
        assertEquals(ActionResult.NO_SUCH_OPTION, magic.cast(Spell.WIND_STRIKE, client.player(9)));
        assertEquals(ActionResult.NO_SUCH_OPTION, magic.cast(Spell.WIND_STRIKE, object(GameFixture.TREE_LOC)));
        assertEquals(ActionResult.NO_SUCH_OPTION, magic.cast(Spell.WIND_STRIKE, groundItem(LOGS)));
        assertTrue(sent.isEmpty());
    }

    @Test
    void aSpellCastsOnAnInventoryItemThroughItsSlot() {
        assertEquals(ActionResult.SENT, magic.cast(Spell.HIGH_ALCHEMY, COINS));
        reference.castSpellOn(Spell.HIGH_ALCHEMY.getComponentId(), 0, INVENTORY, MenuAction.WIDGET_TARGET_ON_WIDGET, 0,
                COINS);
        assertSentAsReference();

        sent.clear();
        assertEquals(ActionResult.NOT_FOUND, magic.cast(Spell.HIGH_ALCHEMY, LOGS), "held, but no slot shows it");
        assertEquals(ActionResult.NO_SUCH_OPTION, magic.cast(Spell.WIND_STRIKE, COINS), "no USE_WIDGET in its mask");
        assertTrue(sent.isEmpty());
    }

    @Test
    void theSpellWidgetIsReadAndHiddenByItself() {
        Widget widget = magic.getSpell(Spell.WIND_STRIKE);
        assertEquals(Spell.WIND_STRIKE.getComponentId(), widget.getId());
        game.memory.putByte(widget.getAddress() + game.map.offset("IfType.hidden"), 1);
        assertFalse(magic.isAvailable(Spell.WIND_STRIKE));
        assertEquals(ActionResult.NOT_FOUND, magic.cast(Spell.WIND_STRIKE, client.npc(5)));
        assertTrue(sent.isEmpty());
    }
}
