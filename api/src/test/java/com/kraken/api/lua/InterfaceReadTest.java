package com.kraken.api.lua;

import com.kraken.api.GameFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static com.kraken.api.GameFixture.CLIENT;
import static com.kraken.api.GameFixture.LONG_TEXT;
import static com.kraken.api.GameFixture.RENDER_CYCLE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Section 7 reads over the {@link GameFixture}: {@code retrieveIfType}/{@code retrieveIfGroup} and the
 * {@code osrs.IfType} getters, including the ones that follow links (parent, children, screen position).
 */
class InterfaceReadTest {

    private GameFixture game;
    private InterfaceQuery interfaces;

    @BeforeEach
    void setUp() throws IOException {
        game = new GameFixture();
        interfaces = new InterfaceQuery(game.memory, game.map);
    }

    @Test
    void retrievesAStaticComponent() {
        IfType all = interfaces.retrieveIfType(162, 1);
        assertEquals(162, all.getId());
        assertEquals(1, all.getComponentIndex());
        assertEquals((162 << 16) | 1, all.getPackedId());
        assertEquals(-1, all.getSubId());
        assertTrue(all.isStatic());
        assertEquals(IfType.TEXT, all.getType());
        assertEquals("All", all.getText());
        assertEquals(new Colour(255, 255, 255, 0), all.getColour());
        assertEquals(List.of("Switch tab"), all.getOpNames());
        assertEquals(162 << 16, all.getLayerId());
        assertEquals(5, all.getX());
        assertEquals(56, all.getWidth());
        assertFalse(all.isHidden());
        assertEquals("162:1", all.toString());
    }

    @Test
    void readsHeapTextAndFlags() {
        IfType line = interfaces.retrieveIfType(162, 2);
        assertEquals(LONG_TEXT, line.getText());
        assertTrue(line.isHidden());
        assertEquals("", line.getText2());
        assertEquals(List.of(), line.getOpNames());
    }

    @Test
    void missesGiveNull() {
        assertNull(interfaces.retrieveIfType(162, 3), "past the group's last component");
        assertNull(interfaces.retrieveIfType(151, 0), "a group without components (not loaded)");
        assertNull(interfaces.retrieveIfType(GameFixture.IF_GROUP_COUNT, 0), "a group outside the store");
        assertNull(interfaces.retrieveIfType(-1, 0));
        assertNull(interfaces.retrieveIfGroup(151));
    }

    @Test
    void retrievesAGroupWithItsCoordinates() {
        IfGroup chat = interfaces.retrieveIfGroup(162);
        assertEquals(3, chat.components().size());
        assertEquals("All", chat.components().get(1).getText());
        assertEquals(0, chat.coordinates().offX());
        assertEquals(461, chat.coordinates().offY());
        assertEquals(2, chat.coordinates().scriptOffX());

        IfGroup closed = interfaces.retrieveIfGroup(150);
        assertEquals(1, closed.components().size());
        assertEquals(InterfaceCoordinates.NONE, closed.coordinates());
    }

    @Test
    void listsOpenGroups() {
        assertEquals(List.of(149, 162), interfaces.getOpenGroups());
        assertEquals(970, interfaces.getCoordinates(149).offX());
        assertNull(interfaces.getCoordinates(150));
    }

    @Test
    void childrenAreTheDynamicComponentsUnderEachLayer() {
        IfType inv = interfaces.retrieveIfType(149, 0);
        List<IfType> children = inv.getChildren();
        assertEquals(List.of("149:0[0]", "149:0[1]"), children.stream().map(IfType::toString).toList());

        IfType item = children.get(0);
        assertEquals(995, item.getLinkObjType());
        assertEquals(1000, item.getLinkObjCount());
        assertEquals("Coins", item.getBaseOpName());
        assertEquals(List.of("Use", "", "Drop"), item.getOpNames());
        assertEquals(List.of(), item.getChildren(), "not a layer");

        List<IfType> nested = children.get(1).getChildren();
        assertEquals(1, nested.size());
        assertEquals("Nested", nested.get(0).getText());
        assertEquals(List.of(), interfaces.retrieveIfType(162, 0).getChildren(), "static children aren't listed");
    }

    @Test
    void parentsFollowTheLayerIds() {
        IfType nested = interfaces.retrieveIfType(149, 0).getChildren().get(1).getChildren().get(0);
        assertEquals("149:0[1]", nested.getParent().toString());
        assertEquals("149:0", nested.getStaticParent().toString());
        assertEquals("149:0", nested.getParent().getParent().toString());
        assertEquals("162:0", interfaces.retrieveIfType(162, 1).getParent().toString());
        assertNull(interfaces.retrieveIfType(162, 0).getParent(), "top level");
        assertEquals("162:1", interfaces.retrieveIfType(162, 1).getStaticParent().toString());
    }

    @Test
    void screenCoordsSumTheChainAndTheGroupOffsets() {
        assertEquals(new Vector2(5 + 2, 142 + 461 + 1), interfaces.retrieveIfType(162, 1).getScreenCoords());
        IfType nested = interfaces.retrieveIfType(149, 0).getChildren().get(1).getChildren().get(0);
        assertEquals(new Vector2(3 + 58 + 970, 4 + 8 + 328), nested.getScreenCoords());
        assertEquals(new Vector2(0, 0), interfaces.retrieveIfType(150, 0).getScreenCoords(), "not open: no offset");
    }

    @Test
    void renderingComparesTheDrawStampWithTheFrame() {
        assertTrue(interfaces.retrieveIfType(162, 1).isRendering());
        assertFalse(interfaces.retrieveIfType(162, 2).isRendering());
        game.memory.putInt(CLIENT + game.map.offset("Client.renderCycle"), RENDER_CYCLE + 1);
        assertFalse(interfaces.retrieveIfType(162, 1).isRendering());
    }

    @Test
    void aSnapshotKeepsItsValues() {
        IfType all = interfaces.retrieveIfType(162, 1);
        long width = all.getAddress() + game.map.offset("IfType.width");
        game.memory.putInt(width, 99);
        assertEquals(56, all.getWidth());
        assertEquals(99, interfaces.retrieveIfType(162, 1).getWidth());
    }
}
