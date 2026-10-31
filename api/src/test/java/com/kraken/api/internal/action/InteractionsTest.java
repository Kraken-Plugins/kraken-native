package com.kraken.api.internal.action;

import com.kraken.api.Client;
import com.kraken.api.ConfigFile;
import com.kraken.api.GameFixture;
import com.kraken.api.MenuAction;
import com.kraken.api.Point;
import com.kraken.api.action.ActionResult;
import com.kraken.api.action.Actions;
import com.kraken.api.action.ClickPolicy;
import com.kraken.api.actor.NPC;
import com.kraken.api.actor.Player;
import com.kraken.api.coords.WorldPoint;
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

import static com.kraken.api.GameFixture.BASE_X;
import static com.kraken.api.GameFixture.BASE_Z;
import static com.kraken.api.GameFixture.DOOR_LOC;
import static com.kraken.api.GameFixture.TREE_LOC;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Interactions} through the entity methods: for each kind of subject, the batch an interaction sends equals the
 * batch the matching {@link Actions} call sends, recorded with fake invokers, so the rules hold whatever the offsets.
 * Then each {@link ActionResult} a kind can give, and the click policies.
 */
class InteractionsTest {

    private static final int INVENTORY = 149 << 16;
    private static final int COINS = 995;
    private static final int LOGS = 1511;
    private static final int GOBLIN = 3029;
    private static final int GOBLIN_CHIEF = 3030;

    private GameFixture game;
    private Client client;
    private Interactions interactions;
    private final List<List<NativeCall>> sent = new ArrayList<>();
    private Actions reference;
    private final List<List<NativeCall>> expected = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        game = new GameFixture();
        game.putConfigFile(ConfigTypes.NPC, GOBLIN, new ConfigFile().op(2).str("Goblin").op(31).str("Attack")
                .op(32).str("Pick-pocket").op(18).g2(311).end());
        game.putConfigFile(ConfigTypes.NPC, GOBLIN_CHIEF, new ConfigFile().op(2).str("Goblin chief").op(30)
                .str("Talk-to").end());
        // The oak three tiles across, so its centre isn't the tile it was placed on
        game.putConfigFile(ConfigTypes.LOC, TREE_LOC, new ConfigFile().op(2).str("Oak").op(14).g1(3).op(15).g1(3)
                .op(30).str("Chop down").end());
        client = new Client(game.memory, game.map);
        client.getActions().setInvoker(sent::add);
        interactions = client.getInteractions();
        interactions.setClickPolicy(ClickPolicy.NONE);
        reference = new Client(game.memory, game.map).getActions();
        reference.setInvoker(expected::add);
    }

    private void assertSentAsReference() {
        assertEquals(1, sent.size(), "one batch");
        assertEquals(expected.get(expected.size() - 1), sent.get(sent.size() - 1),
                "the batch the matching Actions call sends");
    }

    private TileObject object(int id) {
        return client.getScene().getTileObjects().stream().filter(o -> o.getId() == id).findFirst().orElseThrow();
    }

    private TileItem groundItem(int id) {
        return client.getScene().getGroundItems().stream().filter(i -> i.getId() == id).findFirst().orElseThrow();
    }

    private Widget inventorySlot() {
        return client.getWidget(INVENTORY).getChild(0);
    }

    @Test
    void anNpcOptionRunsItsRowByIndex() {
        NPC goblin = client.npc(5);
        assertArrayEquals(new String[]{null, "Attack", "Pick-pocket", null, null}, goblin.getActions());

        assertEquals(ActionResult.SENT, goblin.interact("attack"));
        reference.menuAction(0, 0, MenuAction.NPC_SECOND_OPTION, 5);
        assertSentAsReference();

        sent.clear();
        assertEquals(ActionResult.SENT, goblin.interact("pick pocket"));
        reference.menuAction(0, 0, MenuAction.NPC_THIRD_OPTION, 5);
        assertSentAsReference();

        sent.clear();
        assertEquals(ActionResult.SENT, goblin.interact(2));
        reference.menuAction(0, 0, MenuAction.NPC_SECOND_OPTION, 5);
        assertSentAsReference();
    }

    @Test
    void anNpcWithoutTheOptionSendsNothing() {
        NPC goblin = client.npc(5);
        assertEquals(ActionResult.NO_SUCH_OPTION, goblin.interact("Atack"));
        assertEquals(ActionResult.NO_SUCH_OPTION, goblin.interact(1), "an empty slot");
        assertEquals(ActionResult.NO_SUCH_OPTION, goblin.interact(6), "NPCs have five ops");
        assertEquals(ActionResult.NO_SUCH_OPTION, goblin.interact(0));
        assertTrue(sent.isEmpty());
        assertThrows(NullPointerException.class, () -> goblin.interact(null));
    }

    @Test
    void aTransformedNpcOffersItsShownTypesOptions() {
        NPC multi = client.npc(6);
        game.setVarp(11, 1);
        assertArrayEquals(new String[]{"Talk-to", null, null, null, null}, multi.getActions(), "the chief's");
        assertEquals(ActionResult.SENT, multi.interact("talk-to"));
        reference.menuAction(0, 0, MenuAction.NPC_FIRST_OPTION, 6);
        assertSentAsReference();
        assertEquals(ActionResult.NO_SUCH_OPTION, multi.interact("Attack"), "the base type's options don't count");

        game.setVarp(11, 0);
        assertEquals(ActionResult.SENT, multi.interact("Attack"), "now shown as the goblin");
    }

    @Test
    void aGoneNpcIsGone() {
        NPC goblin = client.npc(5);
        game.setNpcLoaded(5, false);
        assertEquals(ActionResult.GONE, goblin.interact("Attack"));
        assertEquals(ActionResult.GONE, goblin.interact(2));
        assertTrue(sent.isEmpty());
    }

    @Test
    void aPlayerOptionIsOneTheServerSet() {
        game.setPlayerOption(1, "Follow", false);
        game.setPlayerOption(4, "Trade with", false);
        Player bob = client.player(9);
        assertEquals("Trade with", bob.getActions()[3]);

        assertEquals(ActionResult.SENT, bob.interact("trade-with"));
        reference.menuAction(0, 0, MenuAction.PLAYER_FOURTH_OPTION, 9);
        assertSentAsReference();

        sent.clear();
        assertEquals(ActionResult.SENT, bob.interact(1));
        reference.menuAction(0, 0, MenuAction.PLAYER_FIRST_OPTION, 9);
        assertSentAsReference();

        sent.clear();
        assertEquals(ActionResult.NO_SUCH_OPTION, bob.interact("Attack"), "not set by the server");
        assertEquals(ActionResult.NO_SUCH_OPTION, bob.interact(9), "players have eight ops");
        assertEquals(ActionResult.NO_SUCH_OPTION, bob.interact(2), "an option the server hasn't set");
        assertTrue(sent.isEmpty());
    }

    @Test
    void aLocRowNamesItsIdAndTheTileItWasPlacedOn() {
        TileObject oak = object(TREE_LOC);
        assertEquals("Oak", oak.getName());
        assertEquals(new WorldPoint(BASE_X + 6, BASE_Z + 6, 0), oak.getWorldLocation(), "the footprint's centre");

        assertEquals(ActionResult.SENT, oak.interact("Chop-Down"));
        reference.menuAction(5, 5, MenuAction.GAME_OBJECT_FIRST_OPTION, TREE_LOC);
        assertSentAsReference();
    }

    @Test
    void aLocRowNamesTheLocsOwnPlaneNotThePlayers() {
        game.putConfigFile(ConfigTypes.LOC, GameFixture.LAMP_LOC, new ConfigFile().op(2).str("Lamp").op(30)
                .str("Light").end());
        Client fresh = new Client(game.memory, game.map);
        fresh.getActions().setInvoker(sent::add);
        fresh.getInteractions().setClickPolicy(ClickPolicy.NONE);
        TileObject lamp = fresh.getScene().getTileObjects().stream().filter(o -> o.getId() == GameFixture.LAMP_LOC).findFirst()
                .orElseThrow();
        assertEquals(1, lamp.getPlane());

        assertEquals(ActionResult.SENT, lamp.interact("Light"));
        reference.menuAction(lamp.getTile().getSceneLocation().getX(), lamp.getTile().getSceneLocation().getY(),
                MenuAction.GAME_OBJECT_FIRST_OPTION, GameFixture.LAMP_LOC);
        assertNotEquals(expected.get(0), sent.get(0), "Actions puts the loc on the top-level world's plane");
        int[] captures = ints(sent.get(0).get(0));
        assertEquals(1, captures[0], "the lamp's own plane");
        assertEquals(BASE_X + 6, captures[1]);
        assertEquals(BASE_Z + 6, captures[2]);
    }

    @Test
    void aLocMatchesItsConditionalOps() {
        TileObject door = object(DOOR_LOC);
        game.setVarp(10, 100);
        assertEquals("Close", door.getActions()[0]);
        assertEquals(ActionResult.SENT, door.interact("close"));
        assertEquals(ActionResult.NO_SUCH_OPTION, door.interact("Open"), "Open reads Close while varp 10 is 100");
        assertEquals(ActionResult.SENT, door.interact("knock<3"), "markup removed, the escape kept");
    }

    @Test
    void aLocShowsAsItsImpostor() {
        int gate = 1900;
        game.putConfigFile(ConfigTypes.LOC, gate, new ConfigFile().op(2).str("Gate").op(77).g2(2).g2(0xffff).g1(1)
                .g2(DOOR_LOC).g2(TREE_LOC).end());
        game.setWall(0, 2, 2, gate);
        Client fresh = new Client(game.memory, game.map);
        fresh.getActions().setInvoker(sent::add);
        fresh.getInteractions().setClickPolicy(ClickPolicy.NONE);
        TileObject wall = fresh.getScene().getTileObjects().stream().filter(o -> o.getId() == gate).findFirst()
                .orElseThrow();

        game.setVarp(11, 1);
        assertEquals("Oak", wall.getName(), "the impostor's name");
        assertEquals(ActionResult.SENT, wall.interact("Chop down"));
        reference.menuAction(2, 2, MenuAction.GAME_OBJECT_FIRST_OPTION, gate);
        assertEquals(expected.get(0), sent.get(0), "the row names the base id");
    }

    @Test
    void aLocGoneFromItsTileIsGone() {
        TileObject oak = object(TREE_LOC);
        game.removeGameObjects(0, 5, 5);
        assertEquals(ActionResult.GONE, oak.interact("Chop down"));
        assertTrue(sent.isEmpty());
    }

    @Test
    void aGroundItemRowNamesItsIdAndTile() {
        TileItem coins = groundItem(COINS);
        assertEquals("Coins", coins.getName());
        assertEquals("Take", coins.getActions()[2]);

        assertEquals(ActionResult.SENT, coins.take());
        reference.menuAction(3, 3, MenuAction.GROUND_ITEM_THIRD_OPTION, COINS);
        assertSentAsReference();

        sent.clear();
        assertEquals(ActionResult.SENT, groundItem(LOGS).interact("Light"));
        reference.menuAction(3, 3, MenuAction.GROUND_ITEM_THIRD_OPTION, LOGS);
        assertSentAsReference();
    }

    @Test
    void aTakenGroundItemIsGone() {
        TileItem logs = groundItem(LOGS);
        game.setPile(0, 3, 3, new int[]{COINS}, new int[]{100});
        assertEquals(ActionResult.GONE, logs.interact("Light"));
        assertEquals(ActionResult.SENT, groundItem(COINS).take(), "the coins are still there");
    }

    @Test
    void aComponentOpRunsWithItsSlotAndItem() {
        Widget slot = inventorySlot();
        assertEquals(ActionResult.SENT, slot.interact("drop"));
        reference.menuAction(0, INVENTORY, MenuAction.CC_OP, 3, COINS);
        assertSentAsReference();

        sent.clear();
        assertEquals(ActionResult.SENT, slot.interact("Drop", "all"));
        reference.menuAction(0, INVENTORY, MenuAction.CC_OP, 3, COINS, 2);
        assertSentAsReference();

        sent.clear();
        assertEquals(ActionResult.SENT, slot.interact(3, 1));
        reference.menuAction(0, INVENTORY, MenuAction.CC_OP, 3, COINS, 1);
        assertSentAsReference();
    }

    @Test
    void aComponentWithoutTheOptionOrSubOptionSendsNothing() {
        Widget slot = inventorySlot();
        assertEquals(ActionResult.NO_SUCH_OPTION, slot.interact("Eat"));
        assertEquals(ActionResult.NO_SUCH_OPTION, slot.interact(2), "an empty slot");
        assertEquals(ActionResult.NO_SUCH_OPTION, slot.interact(11), "components have ten ops");
        assertEquals(ActionResult.NO_SUCH_OPTION, slot.interact("Drop", "Some"));
        assertEquals(ActionResult.NO_SUCH_OPTION, slot.interact(3, 3));
        assertEquals(ActionResult.NO_SUCH_OPTION, slot.interact("Use", "One"), "Use has no sub-menu");
        assertTrue(sent.isEmpty());
    }

    @Test
    void aSlotHoldingAnotherItemIsGone() {
        Widget slot = inventorySlot();
        game.memory.putInt(game.inventoryItem() + game.map.offset("IfType.linkObjType"), LOGS);
        assertEquals(ActionResult.GONE, slot.interact("Drop"));
        assertEquals(ActionResult.GONE, slot.click());
        assertTrue(sent.isEmpty());
    }

    @Test
    void aClosedComponentIsGone() {
        Widget chatTab = client.getWidget(162, 1);
        game.setGroup(162);
        assertEquals(ActionResult.GONE, chatTab.interact("Switch tab"));
    }

    @Test
    void anItemSlotWithoutOpsOfItsOwnOffersUseThenTheItemsActions() {
        game.memory.putInt(game.inventoryItem() + game.map.offset("IfType.opCount"), 0);
        Widget slot = inventorySlot();
        assertNull(slot.getActions());
        assertArrayEquals(new String[]{"Use", null, null, null, null, "Drop"}, interactions.actions(slot));
        assertEquals(ActionResult.SENT, slot.interact("Drop"));
        reference.menuAction(0, INVENTORY, MenuAction.CC_OP, 6, COINS);
        assertSentAsReference();
    }

    @Test
    void clickRunsTheFirstOpThenThePauseButtonThenTheOldStyleButton() {
        assertEquals(ActionResult.SENT, inventorySlot().click());
        reference.menuAction(0, INVENTORY, MenuAction.CC_OP, 1, COINS);
        assertSentAsReference();

        long layer = game.addComponent(300 << 16, -1, -1, -1, WidgetType.LAYER, 0, 0, 10, 10);
        long button = game.addComponent(300 << 16 | 1, -1, -1, 300 << 16, WidgetType.GRAPHIC, 0, 0, 10, 10);
        long pause = game.addComponent(300 << 16 | 2, -1, -1, 300 << 16, WidgetType.TEXT, 0, 0, 10, 10);
        long plain = game.addComponent(300 << 16 | 3, -1, -1, 300 << 16, WidgetType.TEXT, 0, 0, 10, 10);
        game.setGroup(300, layer, button, pause, plain);
        game.memory.putInt(button + game.map.offset("IfType.event"), 4);
        game.memory.putInt(pause + game.map.offset("IfType.event"), 6);

        sent.clear();
        assertEquals(ActionResult.SENT, client.getWidget(300, 1).click());
        reference.menuAction(-1, 300 << 16 | 1, MenuAction.WIDGET_TYPE_4, 0);
        assertSentAsReference();

        sent.clear();
        assertEquals(ActionResult.SENT, client.getWidget(300, 2).click());
        reference.resumePause(300 << 16 | 2, -1);
        assertSentAsReference();

        sent.clear();
        assertEquals(ActionResult.NO_SUCH_OPTION, client.getWidget(300, 3).click());
        assertTrue(sent.isEmpty());
    }

    @Test
    void anItemIsUsedOnAnNpcInOneBatch() {
        assertEquals(ActionResult.SENT, inventorySlot().useOn(client.npc(5)));
        reference.useItemOn(INVENTORY, 0, COINS, 0, 0, MenuAction.WIDGET_TARGET_ON_NPC, 5, -1);
        assertSentAsReference();
        assertEquals(2, sent.get(0).size(), "the select and the targeted row");
    }

    @Test
    void theTargetMaskDecidesWhatASelectionCanBeUsedOn() {
        Widget slot = inventorySlot();
        assertEquals(ActionResult.NO_SUCH_OPTION, slot.useOn(client.player(9)), "no USE_PLAYER in the slot's mask");
        assertEquals(ActionResult.NO_SUCH_OPTION, slot.useOn(object(TREE_LOC)));
        assertEquals(ActionResult.NO_SUCH_OPTION, slot.useOn(groundItem(COINS)));
        assertEquals(ActionResult.NO_SUCH_OPTION, slot.useOn(slot), "no USE_WIDGET");
        assertTrue(sent.isEmpty());

        game.setServerEvents(new int[]{INVENTORY, 0, 0, GameFixture.INVENTORY_SLOT_EVENTS | WidgetConfig.USE_WIDGET
                | WidgetConfig.USE_OBJECT | WidgetConfig.USE_GROUND_ITEM | WidgetConfig.USE_PLAYER});
        assertEquals(ActionResult.SENT, slot.useOn(slot));
        reference.useItemOnItem(INVENTORY, 0, COINS, 0, COINS);
        assertSentAsReference();

        sent.clear();
        assertEquals(ActionResult.SENT, slot.useOn(object(TREE_LOC)));
        reference.useItemOn(INVENTORY, 0, COINS, 5, 5, MenuAction.WIDGET_TARGET_ON_GAME_OBJECT, TREE_LOC, -1);
        assertSentAsReference();

        sent.clear();
        assertEquals(ActionResult.SENT, slot.useOn(groundItem(LOGS)));
        reference.useItemOn(INVENTORY, 0, COINS, 3, 3, MenuAction.WIDGET_TARGET_ON_GROUND_ITEM, LOGS, -1);
        assertSentAsReference();

        sent.clear();
        assertEquals(ActionResult.SENT, slot.useOn(client.player(9)));
        reference.useItemOn(INVENTORY, 0, COINS, 0, 0, MenuAction.WIDGET_TARGET_ON_PLAYER, 9, -1);
        assertSentAsReference();
    }

    @Test
    void aWidgetTargetMustTakeASelection() {
        game.setServerEvents(new int[]{INVENTORY, 0, 0, WidgetConfig.USE_WIDGET});
        Widget slot = inventorySlot();
        assertEquals(ActionResult.NO_SUCH_OPTION, slot.useOn(slot), "the slot lost WIDGET_USE_TARGET");
    }

    @Test
    void aSpellSelectsWithNoSlotAndNoItem() {
        int spell = 150 << 16;
        game.memory.putInt(firstComponent(150) + game.map.offset("IfType.events"), WidgetConfig.USE_NPC);
        assertEquals(ActionResult.SENT, client.getWidget(spell).useOn(client.npc(5)));
        reference.castSpellOn(spell, 0, 0, MenuAction.WIDGET_TARGET_ON_NPC, 5, -1);
        assertSentAsReference();

        sent.clear();
        assertEquals(ActionResult.NO_SUCH_OPTION, client.getWidget(spell).useOn(client.player(9)));
        assertTrue(sent.isEmpty());
    }

    @Test
    void aGoneSourceOrTargetSendsNothing() {
        Widget slot = inventorySlot();
        NPC goblin = client.npc(5);
        game.setNpcLoaded(5, false);
        assertEquals(ActionResult.GONE, slot.useOn(goblin));
        game.setNpcLoaded(5, true);
        game.memory.putInt(game.inventoryItem() + game.map.offset("IfType.linkObjType"), LOGS);
        assertEquals(ActionResult.GONE, slot.useOn(client.npc(5)));
        assertTrue(sent.isEmpty());
    }

    @Test
    void aWalkStaysInTheLoadedScene() {
        WorldPoint near = new WorldPoint(BASE_X + 2, BASE_Z + 3, 0);
        assertEquals(ActionResult.SENT, interactions.walk(near));
        reference.walk(near);
        assertSentAsReference();

        sent.clear();
        assertEquals(ActionResult.OUT_OF_SCENE, interactions.walk(new WorldPoint(BASE_X + 200, BASE_Z, 0)));
        assertTrue(sent.isEmpty());
    }

    @Test
    void withoutTheHookNothingIsSent() {
        client.getActions().setInvoker(null);
        assertEquals(ActionResult.NOT_CONNECTED, client.npc(5).interact("Attack"));
        assertEquals(ActionResult.NOT_CONNECTED, inventorySlot().useOn(client.npc(5)));
        assertEquals(ActionResult.NOT_CONNECTED, inventorySlot().interact("Drop"));
    }

    @Test
    void theClickPolicyDecidesWhereTheClickGoes() {
        game.setClientSize(1200, 800, 1200, 800);
        Widget slot = inventorySlot();

        interactions.setClickPolicy(ClickPolicy.ON_SCREEN);
        assertEquals(ActionResult.SENT, slot.interact("Drop"));
        assertClick(sent.get(0), 986 + 18, 336 + 16);

        game.setClientSize(765, 503, 1530, 1006);
        assertEquals(ActionResult.SENT, slot.interact("Drop"));
        assertClick(sent.get(1), 0, 0);

        interactions.setStaticClickPoint(new Point(5, 7));
        assertEquals(ActionResult.SENT, slot.interact("Drop"));
        assertClick(sent.get(2), 5, 7);

        game.setClientSize(1200, 800, 1200, 800);
        interactions.setClickPolicy(ClickPolicy.STATIC);
        assertEquals(ActionResult.SENT, slot.interact("Drop"));
        assertClick(sent.get(3), 5, 7);

        interactions.setClickPolicy(ClickPolicy.NONE);
        assertEquals(ActionResult.SENT, slot.interact("Drop"));
        assertEquals(1, sent.get(4).size(), "the row alone");
    }

    @Test
    void aUseOnClicksTheSourceThenTheTarget() {
        interactions.setClickPolicy(ClickPolicy.STATIC);
        assertEquals(ActionResult.SENT, inventorySlot().useOn(client.npc(5)));
        List<NativeCall> batch = sent.get(0);
        assertEquals(14, batch.size(), "a click, the select, a click, the targeted row");
        assertEquals(game.map.function("menuComponentSelect"), batch.get(6).rva());
        assertEquals(game.map.function("packetCreate"), batch.get(7).rva());
        assertEquals(game.map.function("menuNpcTargetOp"), batch.get(13).rva());
    }

    @Test
    void theDefaultPolicyClicksOnScreen() {
        assertEquals(ClickPolicy.ON_SCREEN, new Client(game.memory, game.map).getInteractions().getClickPolicy());
        assertEquals(new Point(0, 0), new Client(game.memory, game.map).getInteractions().getStaticClickPoint());
    }

    private long firstComponent(int group) {
        return client.getWidget(group, 0).getAddress();
    }

    private void assertClick(List<NativeCall> batch, int x, int y) {
        assertEquals(7, batch.size(), "the click packet's six calls, then the row");
        assertEquals(game.map.function("packetCreate"), batch.get(0).rva());
        assertEquals(x, batch.get(2).args().get(1).value());
        assertEquals(y, batch.get(3).args().get(1).value());
        assertEquals(game.map.function("menuComponentOp"), batch.get(6).rva());
    }

    private static int[] ints(NativeCall call) {
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(call.data()).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int[] out = new int[buf.remaining() / 4];
        for (int i = 0; i < out.length; i++) {
            out[i] = buf.getInt();
        }
        return out;
    }
}
