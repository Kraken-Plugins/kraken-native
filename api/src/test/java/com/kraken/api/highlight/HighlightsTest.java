package com.kraken.api.highlight;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.kraken.api.Client;
import com.kraken.api.GameFixture;
import com.kraken.api.actor.NPC;
import com.kraken.api.coords.WorldPoint;
import com.kraken.api.internal.invoke.NativeCall;
import com.kraken.api.internal.invoke.NativeCall.Arg;
import com.kraken.api.mapping.Mapping;
import com.kraken.api.scene.GameObject;
import com.kraken.api.scene.TileItem;
import com.kraken.api.scene.TileObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Color;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Highlights} and {@link HighlightChannel}: the calls they hand the hook, built from the seed mapping, and the
 * state they keep across connections.
 */
class HighlightsTest {

    private GameFixture game;
    private Mapping map;
    private Highlights highlights;
    private final List<List<NativeCall>> sent = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        game = new GameFixture();
        map = game.map;
        highlights = new Highlights(map);
        highlights.setInvoker(sent::add);
    }

    private int manager() {
        return map.offset("Client.highlightManager");
    }

    private long fn(String key) {
        return map.function(key);
    }

    private List<NativeCall> calls() {
        List<NativeCall> all = new ArrayList<>();
        sent.forEach(all::addAll);
        return all;
    }

    private static byte[] coord(int plane, int x, int y) {
        return ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putInt(plane).putInt(x).putInt(y).array();
    }

    @Test
    void theSeedMappingHasEveryCategory() {
        for (HighlightCategory category : HighlightCategory.values()) {
            assertTrue(highlights.isSupported(category), category + " is mapped");
        }
        assertTrue(highlights.isAvailable());
        assertEquals(31, highlights.channelMax());
    }

    @Test
    void aChannelIsTheHighestFreeOneAndGetsTheDefaultStyle() {
        HighlightChannel first = highlights.channel(HighlightCategory.NPC);
        HighlightChannel second = highlights.channel(HighlightCategory.NPC);
        HighlightChannel tiles = highlights.channel(HighlightCategory.TILE);
        assertEquals(31, first.getChannel());
        assertEquals(30, second.getChannel());
        assertEquals(31, tiles.getChannel(), "each category numbers its own channels");

        NativeCall configure = calls().get(0);
        assertEquals(fn("highlightChannelConfigure"), configure.rva());
        assertEquals(List.of(Arg.imm(0), Arg.imm(0), Arg.imm(31), Arg.data(0)), configure.args(),
                "(unused, NPC, channel, &config)");
        byte[] config = configure.data();
        int colour = map.offset("HighlightConfig.colour");
        assertEquals((byte) 255, config[colour], "white");
        assertEquals((byte) 255, config[colour + 3], "opaque");
        assertEquals(1, config[map.offset("HighlightConfig.outlineWidth")]);
        assertEquals(1, config[map.offset("HighlightConfig.enableModelOutline")]);
        assertEquals(0, config[map.offset("HighlightConfig.enableTileOutline")]);
        assertEquals(7, map.enumValue("highlightCategory", "TILE", -1));
        assertEquals(Arg.imm(7), calls().get(2).args().get(1), "the tile channel is styled as TILE");
    }

    @Test
    void aGivenChannelCanBeTakenOnceAndMustBeInRange() {
        highlights.channel(HighlightCategory.TILE, 0);
        assertThrows(IllegalStateException.class, () -> highlights.channel(HighlightCategory.TILE, 0));
        assertThrows(IllegalArgumentException.class, () -> highlights.channel(HighlightCategory.TILE, 32));
        assertEquals(Map.of(HighlightCategory.TILE, Set.of(0)), highlights.heldChannels());
    }

    @Test
    void everyStyleFieldLandsWhereTheMappingSays() {
        HighlightChannel channel = highlights.channel(HighlightCategory.OBJ);
        sent.clear();
        HighlightConfig config = new HighlightConfig(new Color(10, 20, 30, 40), 300, false, true, true, false, true,
                false, true, false, true, false, true);
        channel.configure(config);
        channel.configure(config);
        assertEquals(1, calls().size(), "an unchanged style sends nothing");
        ByteBuffer data = ByteBuffer.wrap(calls().get(0).data()).order(ByteOrder.LITTLE_ENDIAN);
        int colour = map.offset("HighlightConfig.colour");
        assertEquals(10, data.get(colour));
        assertEquals(20, data.get(colour + 1));
        assertEquals(30, data.get(colour + 2));
        assertEquals(40, data.get(colour + 3));
        assertEquals(300, data.getShort(map.offset("HighlightConfig.outlineWidth")));
        String[] fields = {"enableModelOutline", "enableModelFill", "enableTileOutline", "enableTileFill",
                "alwaysOnTop", "showInsideEdges", "snapToGrid", "swOnly", "ignoreRebuild", "saveToFile",
                "disableModel"};
        boolean[] values = {false, true, true, false, true, false, true, false, true, false, true};
        for (int i = 0; i < fields.length; i++) {
            assertEquals(values[i] ? 1 : 0, data.get(map.offset("HighlightConfig." + fields[i])), fields[i]);
        }
        assertEquals(config, channel.getConfig());
    }

    @Test
    void eachTargetKindCallsItsFunctionWithTheStoreItsKeyAndTheChannel() {
        WorldPoint tile = new WorldPoint(3210, 3220, 1);
        int mgr = manager();
        record Case(HighlightTarget target, String on, String off, List<Arg> onArgs, List<Arg> offArgs, byte[] data) {
        }
        List<Case> cases = List.of(
                new Case(HighlightTarget.npcType(3029), "highlightNpcTypeOn", "highlightNpcTypeOff",
                        List.of(Arg.client(mgr), Arg.imm(3029), Arg.imm(31), Arg.imm(0)),
                        List.of(Arg.client(mgr), Arg.imm(3029), Arg.imm(31)), new byte[0]),
                new Case(new HighlightTarget.Npc(5, 1000), "highlightNpcOn", "highlightNpcOff",
                        List.of(Arg.client(mgr), Arg.imm(5), Arg.imm(1000), Arg.imm(31)),
                        List.of(Arg.client(mgr), Arg.imm(5), Arg.imm(1000), Arg.imm(31)), new byte[0]),
                new Case(HighlightTarget.tile(tile), "highlightTileOn", "highlightTileOff",
                        List.of(Arg.client(mgr), Arg.data(0), Arg.imm(31), Arg.imm(0)),
                        List.of(Arg.client(mgr), Arg.data(0), Arg.imm(31), Arg.imm(0)), coord(1, 3210, 3220)),
                new Case(new HighlightTarget.Loc(1600, tile), "highlightLocOn", "highlightLocOff",
                        List.of(Arg.client(mgr), Arg.imm(1600), Arg.data(0), Arg.imm(31), Arg.imm(0)),
                        List.of(Arg.client(mgr), Arg.imm(1600), Arg.data(0), Arg.imm(31), Arg.imm(0)),
                        coord(1, 3210, 3220)),
                new Case(HighlightTarget.locType(1600), "highlightLocTypeOn", "highlightLocTypeOff",
                        List.of(Arg.client(mgr), Arg.imm(1600), Arg.imm(31), Arg.imm(0)),
                        List.of(Arg.client(mgr), Arg.imm(1600), Arg.imm(31)), new byte[0]),
                new Case(new HighlightTarget.Obj(995, tile), "highlightObjOn", "highlightObjOff",
                        List.of(Arg.client(mgr), Arg.imm(995), Arg.data(0), Arg.imm(31), Arg.imm(0)),
                        List.of(Arg.client(mgr), Arg.imm(995), Arg.data(0), Arg.imm(31), Arg.imm(0)),
                        coord(1, 3210, 3220)),
                new Case(HighlightTarget.objType(995), "highlightObjTypeOn", "highlightObjTypeOff",
                        List.of(Arg.client(mgr), Arg.imm(995), Arg.imm(31), Arg.imm(0)),
                        List.of(Arg.client(mgr), Arg.imm(995), Arg.imm(31)), new byte[0]));
        for (Case c : cases) {
            HighlightChannel channel = highlights.channel(c.target().category());
            sent.clear();
            channel.add(c.target());
            channel.remove(c.target());
            List<NativeCall> calls = calls();
            assertEquals(2, calls.size(), c.target() + ": one on, one off");
            assertEquals(fn(c.on()), calls.get(0).rva(), c.on());
            assertEquals(c.onArgs(), calls.get(0).args(), c.on());
            assertArrayEquals(c.data(), calls.get(0).data(), c.on());
            assertEquals(fn(c.off()), calls.get(1).rva(), c.off());
            assertEquals(c.offArgs(), calls.get(1).args(), c.off());
            assertArrayEquals(c.data(), calls.get(1).data(), c.off());
            channel.release();
        }
    }

    @Test
    void playersGoByNameAsAShortString() {
        HighlightChannel channel = highlights.channel(HighlightCategory.PLAYER);
        sent.clear();
        channel.add(HighlightTarget.player("Zezima"));
        NativeCall on = calls().get(0);
        assertEquals(fn("highlightPlayerOn"), on.rva());
        assertEquals(List.of(Arg.client(manager()), Arg.data(0), Arg.imm(31), Arg.imm(0)), on.args());
        byte[] string = on.data();
        assertEquals(map.container("string.size"), string.length);
        assertEquals("Zezima", new String(string, 0, 6, StandardCharsets.UTF_8));
        assertEquals(0, string[6], "NUL-terminated");
        assertEquals(map.container("string.ssoCapacity") - 6, string[map.container("string.heapFlagByte")],
                "the spare capacity, heap bit clear");

        channel.remove(HighlightTarget.player("Zezima"));
        assertEquals(List.of(Arg.client(manager()), Arg.data(0), Arg.imm(31)), calls().get(1).args(),
                "off takes no trailing flag");
    }

    @Test
    void aNameTooLongForAShortStringIsLeftOut() {
        HighlightChannel channel = highlights.channel(HighlightCategory.PLAYER);
        sent.clear();
        channel.add(HighlightTarget.player("x".repeat(24)));
        assertTrue(calls().isEmpty());
        assertEquals(1, channel.getTargets().size(), "it stays a target");
    }

    @Test
    void setSendsOnlyTheDifferences() {
        HighlightChannel channel = highlights.channel(HighlightCategory.NPC_TYPE);
        channel.set(List.of(HighlightTarget.npcType(1), HighlightTarget.npcType(2)));
        sent.clear();
        channel.set(List.of(HighlightTarget.npcType(2), HighlightTarget.npcType(3)));
        List<NativeCall> calls = calls();
        assertEquals(2, calls.size());
        assertEquals(fn("highlightNpcTypeOff"), calls.get(0).rva());
        assertEquals(Arg.imm(1), calls.get(0).args().get(1));
        assertEquals(fn("highlightNpcTypeOn"), calls.get(1).rva());
        assertEquals(Arg.imm(3), calls.get(1).args().get(1));
        assertEquals(1, sent.size(), "in one batch");

        sent.clear();
        channel.set(List.of(HighlightTarget.npcType(3), HighlightTarget.npcType(2)));
        assertTrue(sent.isEmpty(), "the same set sends nothing");
        assertEquals(Set.of(HighlightTarget.npcType(2), HighlightTarget.npcType(3)), channel.getTargets());
    }

    @Test
    void aTargetOfAnotherCategoryIsRefused() {
        HighlightChannel channel = highlights.channel(HighlightCategory.NPC);
        assertThrows(IllegalArgumentException.class, () -> channel.add(HighlightTarget.npcType(1)));
        assertThrows(IllegalArgumentException.class, () -> channel.set(List.of(HighlightTarget.npcType(1))));
    }

    @Test
    void clearAndReleaseDropTheChannelInTheClient() {
        HighlightChannel channel = highlights.channel(HighlightCategory.TILE);
        channel.add(HighlightTarget.tile(new WorldPoint(1, 2, 0)));
        sent.clear();
        channel.clear();
        NativeCall clear = calls().get(0);
        assertEquals(fn("highlightChannelClear"), clear.rva());
        assertEquals(List.of(Arg.client(manager()), Arg.imm(7), Arg.imm(31)), clear.args());
        assertTrue(channel.getTargets().isEmpty());

        sent.clear();
        channel.clear();
        assertTrue(sent.isEmpty(), "clearing an empty channel sends nothing");

        channel.release();
        assertEquals(fn("highlightChannelClear"), calls().get(0).rva());
        assertTrue(channel.isReleased());
        assertThrows(IllegalStateException.class, () -> channel.add(HighlightTarget.tile(new WorldPoint(1, 2, 0))));
        assertEquals(31, highlights.channel(HighlightCategory.TILE).getChannel(), "a released channel is free again");
    }

    @Test
    void stateSetWhileDisconnectedIsAppliedOnConnect() {
        highlights.setInvoker(null);
        sent.clear();
        HighlightChannel channel = highlights.channel(HighlightCategory.NPC_TYPE);
        channel.configure(HighlightConfig.tileOutline(Color.RED, 2, true));
        channel.add(HighlightTarget.npcType(3029));
        assertFalse(highlights.isAvailable());
        assertTrue(sent.isEmpty(), "nothing is sent without a connection");

        highlights.setInvoker(sent::add);
        assertEquals(1, sent.size(), "one batch on connect");
        List<NativeCall> calls = calls();
        assertEquals(List.of(fn("highlightChannelClear"), fn("highlightChannelConfigure"), fn("highlightNpcTypeOn")),
                calls.stream().map(NativeCall::rva).toList(), "the channel is cleared, styled, then lit");
        assertEquals(1, calls.get(1).data()[map.offset("HighlightConfig.enableTileFill")]);

        sent.clear();
        highlights.setInvoker(sent::add);
        assertEquals(3, calls().size(), "a reconnect applies it all again");
    }

    @Test
    void aBrokenConnectionKeepsTheState() {
        highlights.setInvoker(batch -> {
            throw new UncheckedIOException(new IOException("gone"));
        });
        HighlightChannel channel = highlights.channel(HighlightCategory.OBJ_TYPE);
        channel.add(HighlightTarget.objType(995));
        assertEquals(Set.of(HighlightTarget.objType(995)), channel.getTargets());
        highlights.setInvoker(sent::add);
        assertEquals(fn("highlightObjTypeOn"), calls().get(2).rva());
    }

    @Test
    void aChannelReleasedWhileDisconnectedIsClearedOnConnect() {
        HighlightChannel channel = highlights.channel(HighlightCategory.TILE);
        channel.add(HighlightTarget.tile(new WorldPoint(1, 2, 0)));
        highlights.setInvoker(null);
        channel.release();
        sent.clear();

        highlights.setInvoker(sent::add);
        NativeCall clear = calls().get(0);
        assertEquals(fn("highlightChannelClear"), clear.rva());
        assertEquals(List.of(Arg.client(manager()), Arg.imm(7), Arg.imm(31)), clear.args(),
                "the client still shows what the released channel lit, so the reconnect clears it");

        sent.clear();
        highlights.setInvoker(sent::add);
        assertTrue(sent.isEmpty(), "it is cleared once");
    }

    @Test
    void releaseAllTurnsEveryChannelOff() {
        highlights.channel(HighlightCategory.NPC);
        highlights.channel(HighlightCategory.TILE);
        sent.clear();
        highlights.releaseAll();
        assertEquals(2, calls().size());
        assertTrue(highlights.heldChannels().isEmpty());
    }

    @Test
    void targetsComeFromTheApiObjects() {
        Client client = new Client(game.memory, map);
        NPC goblin = client.getNpcs().stream().filter(n -> n.getIndex() == 5).findFirst().orElseThrow();
        assertEquals(new HighlightTarget.Npc(5, 1000), HighlightTarget.npc(goblin));

        GameObject oak = client.getScene().getTileObjects().stream()
                .filter(o -> o instanceof GameObject && o.getId() == GameFixture.TREE_LOC)
                .map(GameObject.class::cast).findFirst().orElseThrow();
        WorldPoint origin = new WorldPoint(GameFixture.BASE_X + oak.getSceneMinLocation().getX(),
                GameFixture.BASE_Z + oak.getSceneMinLocation().getY(), oak.getPlane());
        assertEquals(new HighlightTarget.Loc(GameFixture.TREE_LOC, origin), HighlightTarget.loc(oak),
                "a game object is keyed by its south-west tile, not its centre");

        TileObject door = client.getScene().getTileObjects().stream()
                .filter(o -> o.getId() == GameFixture.DOOR_LOC).findFirst().orElseThrow();
        assertEquals(new HighlightTarget.Loc(GameFixture.DOOR_LOC, door.getWorldLocation()), HighlightTarget.loc(door));

        TileItem coins = client.getScene().getGroundItems().stream().findFirst().orElseThrow();
        assertEquals(new HighlightTarget.Obj(coins.getId(), coins.getTile().getWorldLocation()),
                HighlightTarget.obj(coins));
    }

    @Test
    void aMappingWithoutTheHighlightKeysDrawsNothing(@TempDir Path dir) throws IOException {
        JsonObject json;
        try (InputStreamReader in = new InputStreamReader(Mapping.class.getClassLoader()
                .getResourceAsStream(Mapping.RESOURCE_DIR + map.version() + ".json"), StandardCharsets.UTF_8)) {
            json = new Gson().fromJson(in, JsonObject.class);
        }
        json.getAsJsonObject("structs").getAsJsonObject("Client").remove("highlightManager");
        Path file = dir.resolve(map.version() + ".json");
        Files.writeString(file, json.toString());
        Highlights older = new Highlights(Mapping.load(file));
        older.setInvoker(sent::add);
        sent.clear();
        HighlightChannel channel = older.channel(HighlightCategory.NPC_TYPE);
        channel.add(HighlightTarget.npcType(1));
        assertFalse(older.isAvailable());
        assertFalse(older.isSupported(HighlightCategory.NPC_TYPE));
        assertTrue(sent.isEmpty(), "no call can be built without the store");
    }
}
