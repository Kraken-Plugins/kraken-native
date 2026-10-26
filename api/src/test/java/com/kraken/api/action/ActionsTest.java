package com.kraken.api.action;

import com.kraken.api.Client;
import com.kraken.api.GameFixture;
import com.kraken.api.MenuAction;
import com.kraken.api.MenuEntry;
import com.kraken.api.coords.WorldPoint;
import com.kraken.api.internal.invoke.NativeCall;
import com.kraken.api.internal.invoke.NativeCall.Arg;
import com.kraken.api.internal.invoke.NativeCall.Kind;
import com.kraken.api.mapping.Mapping;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Actions}: the calls it hands the hook for a mouse click and for each kind of menu action, built from the seed
 * mapping, and what it refuses.
 */
class ActionsTest {

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

    private static int[] ints(NativeCall call) {
        ByteBuffer buf = ByteBuffer.wrap(call.data()).order(ByteOrder.LITTLE_ENDIAN);
        int[] out = new int[buf.remaining() / 4];
        for (int i = 0; i < out.length; i++) {
            out[i] = buf.getInt();
        }
        return out;
    }

    @Test
    void theSeedMappingSupportsTheWorldSubjectsAndTheClick() {
        assertTrue(actions.isAvailable());
        assertTrue(actions.isMouseClickSupported());
        for (MenuAction action : List.of(MenuAction.NPC_FIRST_OPTION, MenuAction.NPC_FIFTH_OPTION,
                MenuAction.GAME_OBJECT_FIRST_OPTION, MenuAction.GAME_OBJECT_FIFTH_OPTION,
                MenuAction.GROUND_ITEM_THIRD_OPTION, MenuAction.PLAYER_EIGHTH_OPTION)) {
            assertTrue(actions.isSupported(action), action + " is mapped");
        }
        for (MenuAction action : List.of(MenuAction.WALK, MenuAction.CC_OP, MenuAction.EXAMINE_NPC,
                MenuAction.WIDGET_TARGET_ON_NPC, MenuAction.CANCEL, MenuAction.UNKNOWN)) {
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
    void aClickSendsTheMouseClickThenTheAction() {
        assertTrue(actions.click(300, 200, 0, 0, MenuAction.NPC_FIRST_OPTION, 5));
        List<NativeCall> batch = sent.get(0);
        assertEquals(7, batch.size(), "six click calls, then the action, in one batch");
        assertEquals(fn("packetCreate"), batch.get(0).rva());
        assertEquals(Arg.imm(300), batch.get(2).args().get(1));
        assertEquals(Arg.imm(200), batch.get(3).args().get(1));
        assertEquals(fn("menuNpcOp"), batch.get(6).rva());
        assertArrayEquals(new int[]{5, 1, 0}, ints(batch.get(6)));
    }

    @Test
    void unsupportedActionsAndAMissingHookSendNothing() {
        assertFalse(actions.menuAction(0, 0, MenuAction.WALK, 0));
        assertFalse(actions.click(1, 1, 0, 0, MenuAction.CC_OP, 1));
        assertTrue(sent.isEmpty(), "nothing goes out for an action without a row body");

        actions.setInvoker(null);
        assertFalse(actions.isAvailable());
        assertFalse(actions.menuAction(0, 0, MenuAction.NPC_FIRST_OPTION, 1));
        actions.mouseClick(1, 1, false);
        assertTrue(sent.isEmpty(), "actions are for the moment they are asked: nothing is queued for a later hook");
    }

    @Test
    void theClientsMenuActionDelegates() {
        client.menuAction(0, 0, MenuAction.NPC_FOURTH_OPTION, 77, -1, "Attack", "Moss giant");
        assertArrayEquals(new int[]{77, 4, 0}, ints(sent.get(0).get(0)));
    }
}
