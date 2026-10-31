package com.kraken.api.action;

import com.kraken.api.Client;
import com.kraken.api.GameFixture;
import com.kraken.api.MenuAction;
import com.kraken.api.MenuEntry;
import com.kraken.api.World;
import com.kraken.api.coords.WorldPoint;
import com.kraken.api.gameval.InterfaceID;
import com.kraken.api.internal.invoke.NativeCall;
import com.kraken.api.internal.invoke.NativeCall.Arg;
import com.kraken.api.internal.invoke.NativeCall.Kind;
import com.kraken.api.mapping.Mapping;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Actions}: the calls it hands the hook for a mouse click, for each kind of menu action and for the dialog
 * answers, built from the seed mapping, and what it refuses.
 */
class ActionsTest {

    private static final int INVENTORY = 149 << 16;
    private static final int SPELL = InterfaceID.MagicSpellbook.HIGH_ALCHEMY;

    private GameFixture game;
    private Mapping map;
    private Client client;
    private Actions actions;
    private final List<List<NativeCall>> sent = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        game = new GameFixture();
        map = game.map;
        client = new Client(game.memory, game.map);
        actions = client.getActions();
        actions.setInvoker(sent::add);
    }

    private long fn(String key) {
        return map.function(key);
    }

    private int opcode(String name) {
        return map.enumValue("clientProt", name, -1);
    }

    private static int[] ints(NativeCall call) {
        ByteBuffer buf = ByteBuffer.wrap(call.data()).order(ByteOrder.LITTLE_ENDIAN);
        int[] out = new int[buf.remaining() / 4];
        for (int i = 0; i < out.length; i++) {
            out[i] = buf.getInt();
        }
        return out;
    }

    @Test
    void theSeedMappingSupportsTheSubjectsTheClickAndTheDialogs() {
        assertTrue(actions.isAvailable());
        assertTrue(actions.isMouseClickSupported());
        assertTrue(actions.isDialogSupported());
        for (MenuAction action : List.of(MenuAction.NPC_FIRST_OPTION, MenuAction.NPC_FIFTH_OPTION,
                MenuAction.GAME_OBJECT_FIRST_OPTION, MenuAction.GAME_OBJECT_FIFTH_OPTION,
                MenuAction.GROUND_ITEM_THIRD_OPTION, MenuAction.PLAYER_EIGHTH_OPTION, MenuAction.WALK,
                MenuAction.CC_OP, MenuAction.CC_OP_LOW_PRIORITY, MenuAction.WIDGET_TARGET, MenuAction.WIDGET_CONTINUE,
                MenuAction.WIDGET_TARGET_ON_WIDGET, MenuAction.WIDGET_TARGET_ON_NPC, MenuAction.WIDGET_TARGET_ON_PLAYER,
                MenuAction.WIDGET_TARGET_ON_GAME_OBJECT, MenuAction.WIDGET_TARGET_ON_GROUND_ITEM,
                MenuAction.WIDGET_TYPE_1, MenuAction.WIDGET_TYPE_4, MenuAction.WIDGET_TYPE_5, MenuAction.WIDGET_CLOSE)) {
            assertTrue(actions.isSupported(action), action + " is mapped");
        }
        for (MenuAction action : List.of(MenuAction.EXAMINE_NPC, MenuAction.EXAMINE_OBJECT, MenuAction.CANCEL,
                MenuAction.UNKNOWN)) {
            assertFalse(actions.isSupported(action), action + " has no row body");
        }
    }

    @Test
    void aMouseClickBuildsWritesSendsAndReleasesThePacketInTheScratch() {
        actions.mouseClick(123, 456, true);
        List<NativeCall> calls = sent.get(0);
        assertEquals(6, calls.size());
        int connection = map.offset("Client.connection");
        int cipher = map.offset("Connection.outCipher");

        NativeCall create = calls.get(0);
        assertEquals(fn("packetCreate"), create.rva());
        assertEquals(List.of(Arg.scratch(0), Arg.imm(7), Arg.indirect(connection, cipher)), create.args(),
                "(packet in the scratch, EVENT_MOUSE_CLICK, connection + cipher)");
        assertEquals(Kind.INDIRECT, create.args().get(2).kind());
        assertEquals(((long) cipher << 32) | connection, create.args().get(2).value(),
                "the pointer's offset in the low word, what to add in the high");

        for (int i = 1; i <= 3; i++) {
            assertEquals(fn("packetWriteShort"), calls.get(i).rva());
            assertEquals(Arg.scratch(8), calls.get(i).args().get(0), "the buffer inside the packet");
        }
        long flags = calls.get(1).args().get(1).value();
        assertEquals(1, flags & 1, "right button");
        assertEquals(0x7fff, flags >> 1, "a first click reports the longest delay");
        assertEquals(Arg.imm(123), calls.get(2).args().get(1));
        assertEquals(Arg.imm(456), calls.get(3).args().get(1));

        assertEquals(fn("packetSend"), calls.get(4).rva());
        assertEquals(List.of(Arg.indirect(connection, 0), Arg.scratch(0)), calls.get(4).args());
        assertEquals(fn("packetRelease"), calls.get(5).rva());
        assertEquals(List.of(Arg.scratch(8)), calls.get(5).args());

        actions.mouseClick(1, 2, false);
        long again = sent.get(1).get(1).args().get(1).value();
        assertEquals(0, again & 1, "left button");
        assertTrue(again >> 1 < 0x7fff, "the delay since the first click");
    }

    @Test
    void anNpcActionCallsTheNpcRowBodyWithIndexAndOp() {
        assertTrue(actions.menuAction(0, 0, MenuAction.NPC_SECOND_OPTION, 1234));
        NativeCall call = sent.get(0).get(0);
        assertEquals(fn("menuNpcOp"), call.rva());
        assertEquals(List.of(Arg.imm(0), Arg.data(0)), call.args(), "(unused, &captures)");
        assertArrayEquals(new int[]{1234, 2, 0}, ints(call), "{index, op, flag}");
    }

    @Test
    void aPlayerActionCallsThePlayerRowBodyWithIndexAndOp() {
        assertTrue(actions.menuAction(0, 0, MenuAction.PLAYER_THIRD_OPTION, 17));
        NativeCall call = sent.get(0).get(0);
        assertEquals(fn("menuPlayerOp"), call.rva());
        assertArrayEquals(new int[]{17, 3}, ints(call), "{index, op}");
    }

    @Test
    void locAndItemActionsTakeTheEntrysWorldTile() {
        WorldPoint tile = new WorldPoint(3200, 3300, 1);
        MenuEntry loc = new MenuEntry(client, "Open", "Door", 1535, MenuAction.GAME_OBJECT_FIRST_OPTION, 50, 52, tile);
        assertTrue(actions.menuAction(loc));
        NativeCall call = sent.get(0).get(0);
        assertEquals(fn("menuLocOp"), call.rva());
        assertArrayEquals(new int[]{1, 3200, 3300, 1535, 1, 0}, ints(call), "{level, x, z, locId, op, flag}");

        MenuEntry item = new MenuEntry(client, "Take", "Bones", 526, MenuAction.GROUND_ITEM_THIRD_OPTION, 50, 52, tile);
        assertTrue(actions.menuAction(item));
        call = sent.get(1).get(0);
        assertEquals(fn("menuObjOp"), call.rva());
        assertArrayEquals(new int[]{1, 3200, 3300, 526, 0, 3, 0}, ints(call), "{level, x, z, objId, count, op, flag}");
    }

    @Test
    void aLocActionFromParametersAddsTheMapBase() {
        assertTrue(actions.menuAction(10, 20, MenuAction.GAME_OBJECT_FIFTH_OPTION, 999));
        int[] captures = ints(sent.get(0).get(0));
        assertEquals(GameFixture.BASE_X + 10, captures[1], "scene x plus the base");
        assertEquals(GameFixture.BASE_Z + 20, captures[2], "scene y plus the base");
        assertEquals(999, captures[3]);
        assertEquals(5, captures[4], "the fifth op");
    }

    @Test
    void aWalkCallsTheWalkRowBodyWithTheWorldTile() {
        assertTrue(actions.walk(new WorldPoint(3222, 3218, 0)));
        NativeCall call = sent.get(0).get(0);
        assertEquals(fn("menuWalk"), call.rva());
        assertEquals(List.of(Arg.imm(0), Arg.data(0)), call.args(), "(unused, &captures)");
        assertArrayEquals(new int[]{0, 3222, 3218}, ints(call), "{level, x, z}");

        assertTrue(actions.menuAction(10, 20, MenuAction.WALK, 0), "Walk here from an entry's scene coordinates");
        assertArrayEquals(new int[]{0, GameFixture.BASE_X + 10, GameFixture.BASE_Z + 20}, ints(sent.get(1).get(0)));
    }

    @Test
    void aComponentOpCallsTheComponentRowBodyWithSlotOpAndItem() {
        assertTrue(actions.menuAction(3, INVENTORY, MenuAction.CC_OP, 2, 1511));
        NativeCall call = sent.get(0).get(0);
        assertEquals(fn("menuComponentOp"), call.rva());
        assertArrayEquals(new int[]{INVENTORY, 3, 2, 0, 1511, 0}, ints(call), "{component, slot, op, subOp, objId, flag}");

        assertTrue(actions.menuAction(3, INVENTORY, MenuAction.CC_OP_LOW_PRIORITY, 7), "the item read from the widget");
        assertArrayEquals(new int[]{INVENTORY, 3, 7, 0, -1, 0}, ints(sent.get(1).get(0)), "no widget in the fixture: -1");

        assertFalse(actions.menuAction(3, INVENTORY, MenuAction.CC_OP, 0, 1511), "a component op is 1-based");
        assertEquals(2, sent.size());
    }

    @Test
    void aSubOptionGoesIntoTheComponentOpsCaptures() {
        assertTrue(actions.menuAction(3, INVENTORY, MenuAction.CC_OP, 2, 1511, 1));
        assertEquals(fn("menuComponentOp"), sent.get(0).get(0).rva());
        assertArrayEquals(new int[]{INVENTORY, 3, 2, 1, 1511, 0}, ints(sent.get(0).get(0)),
                "{component, slot, op, subOp, objId, flag}: the first sub-option of the second op");

        MenuEntry one = null;
        for (MenuEntry entry : client.getMenuEntries()) {
            if ("One".equals(entry.getOption())) {
                one = entry;
            }
        }
        assertTrue(one != null && one.getSubOp() == 1, "the fixture's Drop row has a sub-menu");
        assertTrue(actions.menuAction(one), "a sub-menu row's entry runs its op with the sub-option");
        assertArrayEquals(new int[]{INVENTORY, 0, 3, 1, 995, 0}, ints(sent.get(1).get(0)), "the item read from the slot");

        assertTrue(actions.click(10, 20, 3, INVENTORY, MenuAction.CC_OP, 2, 1511, 2));
        assertEquals(7, sent.get(2).size());
        assertArrayEquals(new int[]{INVENTORY, 3, 2, 2, 1511, 0}, ints(sent.get(2).get(6)));

        assertFalse(actions.menuAction(3, INVENTORY, MenuAction.CC_OP, 2, 1511, -1), "a sub-option is 1-based");
        assertEquals(3, sent.size());
    }

    @Test
    void oldStyleButtonsCallTheirBodiesWithTheComponent() {
        int button = 0x1180004;
        assertTrue(actions.menuAction(-1, button, MenuAction.WIDGET_TYPE_1, 0));
        NativeCall plain = sent.get(0).get(0);
        assertEquals(fn("menuComponentButton"), plain.rva());
        assertEquals(List.of(Arg.imm(0), Arg.data(0)), plain.args(), "(unused, &captures)");
        assertArrayEquals(new int[]{button}, ints(plain), "{component}: the body looks the component up with no slot");

        assertTrue(actions.menuAction(-1, button, MenuAction.WIDGET_TYPE_4, 0));
        assertEquals(fn("menuComponentToggle"), sent.get(1).get(0).rva());
        assertArrayEquals(new int[]{button}, ints(sent.get(1).get(0)));
        assertTrue(actions.menuAction(-1, button, MenuAction.WIDGET_TYPE_5, 0));
        assertEquals(fn("menuComponentSet"), sent.get(2).get(0).rva());
        assertArrayEquals(new int[]{button}, ints(sent.get(2).get(0)));

        assertTrue(actions.menuAction(-1, button, MenuAction.WIDGET_CLOSE, 0));
        NativeCall close = sent.get(3).get(0);
        assertEquals(fn("menuComponentClose"), close.rva());
        assertEquals(List.of(Arg.imm(0), Arg.imm(0)), close.args(), "the close body reads no captures");
        assertEquals(0, close.data().length);
        assertTrue(actions.closeInterface());
        assertEquals(close, sent.get(4).get(0));

        MenuEntry entry = new MenuEntry(client, "Close", "", 0, MenuAction.WIDGET_CLOSE, -1, button, null);
        assertTrue(actions.click(5, 6, entry));
        assertEquals(7, sent.get(5).size(), "the click, then the close");
        assertEquals(fn("menuComponentClose"), sent.get(5).get(6).rva());
    }

    @Test
    void castSpellOnSelectsTheSpellThenRunsTheTargetedBody() {
        assertTrue(actions.castSpellOn(SPELL, 3, INVENTORY, MenuAction.WIDGET_TARGET_ON_WIDGET, 0, 1511),
                "High Alchemy on an inventory item");
        List<NativeCall> batch = sent.get(0);
        assertEquals(2, batch.size());
        assertEquals(fn("menuComponentSelect"), batch.get(0).rva());
        assertArrayEquals(new int[]{SPELL, -1, -1}, ints(batch.get(0)),
                "the spell's component with no slot and no item, as the spellbook's row selects it");
        assertEquals(fn("menuComponentTargetOp"), batch.get(1).rva());
        assertArrayEquals(new int[]{INVENTORY, 3, 1511}, ints(batch.get(1)), "the item: {component, slot, objId}");

        MenuEntry npc = new MenuEntry(client, "Attack", "Goblin", 77, MenuAction.NPC_SECOND_OPTION, 0, 0, null);
        assertTrue(actions.castSpellOn(SPELL, npc), "a plain NPC entry stands for its subject");
        assertEquals(fn("menuComponentSelect"), sent.get(1).get(0).rva());
        assertArrayEquals(new int[]{SPELL, -1, -1}, ints(sent.get(1).get(0)));
        assertEquals(fn("menuNpcTargetOp"), sent.get(1).get(1).rva());
        assertArrayEquals(new int[]{77}, ints(sent.get(1).get(1)), "{index}");

        assertTrue(actions.castSpellOn(SPELL, 0, 0, MenuAction.WIDGET_TARGET_ON_PLAYER, 9, -1));
        assertEquals(fn("menuPlayerTargetOp"), sent.get(2).get(1).rva());

        MenuEntry walk = new MenuEntry(client, "Walk here", "", 0, MenuAction.WALK, 50, 52, new WorldPoint(1, 2, 0));
        assertFalse(actions.castSpellOn(SPELL, walk), "a tile takes no selection");
        assertEquals(3, sent.size());
    }

    @Test
    void useItemOnItemSelectsThenUsesInOneBatch() {
        assertTrue(actions.useItemOnItem(INVENTORY, 1, 1511, 2, 590));
        List<NativeCall> batch = sent.get(0);
        assertEquals(2, batch.size());
        assertEquals(fn("menuComponentSelect"), batch.get(0).rva());
        assertArrayEquals(new int[]{INVENTORY, 1, 1511}, ints(batch.get(0)), "the logs: {component, slot, objId}");
        assertEquals(fn("menuComponentTargetOp"), batch.get(1).rva());
        assertArrayEquals(new int[]{INVENTORY, 2, 590}, ints(batch.get(1)), "the tinderbox: {component, slot, objId}");
    }

    @Test
    void useItemOnASubjectTakesItsTargetedRowBody() {
        MenuEntry npc = new MenuEntry(client, "Attack", "Goblin", 77, MenuAction.NPC_SECOND_OPTION, 0, 0, null);
        assertTrue(actions.useItemOn(INVENTORY, 0, 1511, npc), "a plain NPC entry stands for its subject");
        List<NativeCall> batch = sent.get(0);
        assertEquals(fn("menuComponentSelect"), batch.get(0).rva());
        assertEquals(fn("menuNpcTargetOp"), batch.get(1).rva());
        assertArrayEquals(new int[]{77}, ints(batch.get(1)), "{index}");

        WorldPoint tile = new WorldPoint(3200, 3300, 1);
        MenuEntry loc = new MenuEntry(client, "Use", "Logs -> Fire", 1535, MenuAction.WIDGET_TARGET_ON_GAME_OBJECT, 50, 52,
                tile);
        assertTrue(actions.useItemOn(INVENTORY, 0, 1511, loc));
        assertEquals(fn("menuLocTargetOp"), sent.get(1).get(1).rva());
        assertArrayEquals(new int[]{1, 3200, 3300, 1535}, ints(sent.get(1).get(1)), "{level, x, z, locId}");

        assertTrue(actions.useItemOn(INVENTORY, 0, 1511, 5, 50, MenuAction.GROUND_ITEM_FIRST_OPTION, 526, -1));
        assertEquals(fn("menuObjTargetOp"), sent.get(2).get(1).rva());
        assertTrue(actions.useItemOn(INVENTORY, 0, 1511, 0, 0, MenuAction.PLAYER_FIRST_OPTION, 9, -1));
        assertEquals(fn("menuPlayerTargetOp"), sent.get(3).get(1).rva());
        assertArrayEquals(new int[]{9}, ints(sent.get(3).get(1)));

        MenuEntry walk = new MenuEntry(client, "Walk here", "", 0, MenuAction.WALK, 50, 52, tile);
        assertFalse(actions.useItemOn(INVENTORY, 0, 1511, walk), "a tile takes no selection");
        assertEquals(4, sent.size());
    }

    @Test
    void aPauseButtonCallsThePauseRowBody() {
        assertTrue(actions.resumePause(0xe60002, -1));
        NativeCall call = sent.get(0).get(0);
        assertEquals(fn("menuComponentPause"), call.rva());
        assertArrayEquals(new int[]{0xe60002, -1}, ints(call), "{component, slot}");

        assertTrue(actions.menuAction(-1, 0xe60002, MenuAction.WIDGET_CONTINUE, 0));
        assertEquals(fn("menuComponentPause"), sent.get(1).get(0).rva());
    }

    @Test
    void theWorldHopperOpsAreComponentOpsOnTheLogoutPanelAndTheSwitcher() {
        client.openWorldHopper();
        NativeCall open = sent.get(0).get(0);
        assertEquals(fn("menuComponentOp"), open.rva());
        assertArrayEquals(new int[]{InterfaceID.Logout.WORLD_SWITCHER, -1, 1, 0, -1, 0}, ints(open),
                "the World Switcher button's first op, no slot");

        client.hopToWorld(World.builder().id(330).build());
        NativeCall hop = sent.get(1).get(0);
        assertEquals(fn("menuComponentOp"), hop.rva());
        assertArrayEquals(new int[]{InterfaceID.Worldswitcher.BUTTONS, 330, 1, 0, -1, 0}, ints(hop),
                "the Switch op on the switcher's buttons, the world as the slot");
    }

    @Test
    void dialogAnswersBuildTheirPacketsWithTheWriters() {
        int connection = map.offset("Client.connection");
        int cipher = map.offset("Connection.outCipher");

        assertTrue(actions.resumeCountDialog(28));
        List<NativeCall> count = sent.get(0);
        assertEquals(4, count.size(), "create, one int, send, release");
        assertEquals(List.of(Arg.scratch(0), Arg.imm(opcode("RESUME_P_COUNTDIALOG")), Arg.indirect(connection, cipher)),
                count.get(0).args());
        assertEquals(fn("packetWriteInt"), count.get(1).rva());
        assertEquals(List.of(Arg.scratch(8), Arg.imm(28)), count.get(1).args());
        assertEquals(fn("packetSend"), count.get(2).rva());
        assertEquals(fn("packetRelease"), count.get(3).rva());

        assertTrue(actions.resumeObjDialog(995));
        List<NativeCall> obj = sent.get(1);
        assertEquals(Arg.imm(opcode("RESUME_P_OBJDIALOG")), obj.get(0).args().get(1));
        assertEquals(fn("packetWriteShort"), obj.get(1).rva());
        assertEquals(Arg.imm(995), obj.get(1).args().get(1));

        assertTrue(actions.resumeNameDialog("Zezima"));
        List<NativeCall> name = sent.get(2);
        assertEquals(5, name.size(), "create, the size byte, the string, send, release");
        assertEquals(Arg.imm(opcode("RESUME_P_NAMEDIALOG")), name.get(0).args().get(1));
        assertEquals(fn("packetWriteByte"), name.get(1).rva());
        assertEquals(Arg.imm(7), name.get(1).args().get(1), "the characters plus the terminator");
        NativeCall string = name.get(2);
        assertEquals(fn("packetWriteString"), string.rva());
        assertEquals(List.of(Arg.scratch(8), Arg.data(0), Arg.imm(6), Arg.imm(0)), string.args(),
                "(buffer, utf-8 chars, their length, not versioned)");
        assertArrayEquals("Zezima".getBytes(StandardCharsets.UTF_8), string.data());

        assertTrue(actions.resumeStringDialog("h" + (char) 0xe9 + "llo"));
        List<NativeCall> text = sent.get(3);
        assertEquals(Arg.imm(opcode("RESUME_P_STRINGDIALOG")), text.get(0).args().get(1));
        assertEquals(Arg.imm(6), text.get(1).args().get(1), "five characters, counted as the game's code page will");
        assertEquals(Arg.imm(6), text.get(2).args().get(2), "six bytes of utf-8 for the writer to transcode");

        assertFalse(actions.resumeStringDialog("x".repeat(255)), "longer than the size byte allows");
        assertFalse(actions.resumeNameDialog(new String(Character.toChars(0x1f600))), "a character outside the basic plane");
        assertEquals(4, sent.size());
    }

    @Test
    void aClickSendsTheMouseClickThenTheAction() {
        assertTrue(actions.click(300, 200, 0, 0, MenuAction.NPC_FIRST_OPTION, 5));
        List<NativeCall> batch = sent.get(0);
        assertEquals(7, batch.size(), "six click calls, then the action, in one batch");
        assertEquals(fn("packetCreate"), batch.get(0).rva());
        assertEquals(Arg.imm(300), batch.get(2).args().get(1));
        assertEquals(Arg.imm(200), batch.get(3).args().get(1));
        assertEquals(fn("menuNpcOp"), batch.get(6).rva());
        assertArrayEquals(new int[]{5, 1, 0}, ints(batch.get(6)));

        assertTrue(actions.click(10, 20, 4, INVENTORY, MenuAction.CC_OP, 3, 4151));
        assertEquals(fn("menuComponentOp"), sent.get(1).get(6).rva());
        assertArrayEquals(new int[]{INVENTORY, 4, 3, 0, 4151, 0}, ints(sent.get(1).get(6)));
    }

    @Test
    void unsupportedActionsAndAMissingHookSendNothing() {
        assertFalse(actions.menuAction(0, 0, MenuAction.EXAMINE_NPC, 0));
        assertFalse(actions.click(1, 1, 0, 0, MenuAction.CANCEL, 1));
        assertTrue(sent.isEmpty(), "nothing goes out for an action without a row body");

        actions.setInvoker(null);
        assertFalse(actions.isAvailable());
        assertFalse(actions.menuAction(0, 0, MenuAction.NPC_FIRST_OPTION, 1));
        assertFalse(actions.walk(new WorldPoint(1, 2, 0)));
        assertFalse(actions.resumeCountDialog(1));
        assertFalse(actions.closeInterface());
        assertFalse(actions.castSpellOn(SPELL, 0, 0, MenuAction.NPC_FIRST_OPTION, 1, -1));
        actions.mouseClick(1, 1, false);
        assertTrue(sent.isEmpty(), "actions are for the moment they are asked: nothing is queued for a later hook");
    }

    @Test
    void theClientsMenuActionDelegates() {
        client.menuAction(0, 0, MenuAction.NPC_FOURTH_OPTION, 77, -1, "Attack", "Moss giant");
        assertArrayEquals(new int[]{77, 4, 0}, ints(sent.get(0).get(0)));
        client.menuAction(3, INVENTORY, MenuAction.CC_OP, 1, 4151, "Wield", "Abyssal whip");
        assertArrayEquals(new int[]{INVENTORY, 3, 1, 0, 4151, 0}, ints(sent.get(1).get(0)), "the item id passed on");
    }
}
