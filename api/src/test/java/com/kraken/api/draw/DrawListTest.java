package com.kraken.api.draw;

import com.kraken.api.Client;
import com.kraken.api.GameFixture;
import com.kraken.api.actor.NPC;
import com.kraken.api.internal.draw.DrawHook;
import com.kraken.api.internal.draw.WireDrawList;
import com.kraken.api.internal.draw.WireSprite;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DrawList}, {@link DrawLayer} and {@link Anchor} over the {@link GameFixture}: how layers combine into what the
 * hook is sent, when it is sent, and how sprites are placed in the atlas and sent before the items that use them.
 */
class DrawListTest {

    private Client client;
    private DrawList drawList;
    private NPC goblin;
    private final Hook hook = new Hook();

    /**
     * A hook that keeps what it is sent, in order, and can be made to fail.
     */
    private static final class Hook implements DrawHook {
        final List<Object> sent = new ArrayList<>();
        int failures;

        @Override
        public void sprites(List<WireSprite> sprites) {
            fail();
            sent.add(List.copyOf(sprites));
        }

        @Override
        public void list(WireDrawList list) {
            fail();
            sent.add(list);
        }

        private void fail() {
            if (failures > 0) {
                failures--;
                throw new UncheckedIOException(new IOException("closed"));
            }
        }

        WireDrawList lastList() {
            for (int i = sent.size() - 1; i >= 0; i--) {
                if (sent.get(i) instanceof WireDrawList list) {
                    return list;
                }
            }
            return null;
        }

        long lists() {
            return sent.stream().filter(WireDrawList.class::isInstance).count();
        }
    }

    /**
     * Lays out the fixture and a client over it.
     *
     * @throws IOException if the bundled mapping can't be read
     */
    @BeforeEach
    void setUp() throws IOException {
        GameFixture game = new GameFixture();
        client = new Client(game.memory, game.map);
        drawList = client.getDrawList();
        goblin = client.npc(5);
    }

    private DrawItem.TileArea tile(Color stroke) {
        return new DrawItem.TileArea(Anchor.npc(goblin), 1, 1, stroke, null, 2);
    }

    private static BufferedImage image(int width, int height, int argb) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, argb);
            }
        }
        return image;
    }

    @Test
    void anAnchorNamesTheNpcByIndexAndCreationCycle() {
        Anchor anchor = Anchor.npc(goblin).offset(-64, 32).lift(240).offset(1, 1).lift(10);
        assertEquals(Anchor.Kind.NPC, anchor.kind());
        assertEquals(5, anchor.index());
        assertEquals(1000, anchor.spawnCycle());
        assertEquals(-63, anchor.dx());
        assertEquals(33, anchor.dy());
        assertEquals(250, anchor.lift());
        assertEquals(Anchor.Kind.NPC_TILE, Anchor.npcTile(goblin).kind());
    }

    @Test
    void itemsGoOutOnceAHookConnectsAndAgainOnReconnect() {
        DrawLayer layer = drawList.createLayer();
        layer.set(List.of(tile(Color.CYAN)));
        drawList.flush();
        assertFalse(drawList.isAvailable());

        drawList.setHook(hook);
        assertTrue(drawList.isAvailable());
        assertEquals(1, hook.lists());
        WireDrawList.Item item = hook.lastList().items().get(0);
        assertEquals(WireDrawList.TILE_AREA, item.kind());
        assertEquals(WireDrawList.ANCHOR_NPC, item.anchor());
        assertEquals(5, item.index());
        assertEquals(1000, item.spawnCycle());
        assertEquals(Color.CYAN.getRGB(), item.stroke());
        assertEquals(0, item.fill(), "no fill");
        assertEquals(32, item.strokeWidth(), "2 pixels in sixteenths");

        drawList.setHook(null);
        drawList.setHook(hook);
        assertEquals(2, hook.lists(), "a reconnect is sent everything again");
    }

    @Test
    void unchangedItemsArentSentAgain() {
        drawList.setHook(hook);
        DrawLayer layer = drawList.createLayer();
        layer.set(List.of(tile(Color.CYAN)));
        drawList.flush();
        layer.set(List.of(tile(Color.CYAN)));
        drawList.flush();
        assertEquals(1, hook.lists());

        layer.set(List.of(tile(Color.RED)));
        drawList.flush();
        assertEquals(2, hook.lists());
    }

    @Test
    void layersDrawInTheOrderTheyWereTakenAndClosingOneRemovesItsItems() {
        drawList.setHook(hook);
        DrawLayer first = drawList.createLayer();
        DrawLayer second = drawList.createLayer();
        second.set(List.of(tile(Color.RED)));
        first.set(List.of(tile(Color.CYAN)));
        drawList.flush();
        List<WireDrawList.Item> items = hook.lastList().items();
        assertEquals(Color.CYAN.getRGB(), items.get(0).stroke());
        assertEquals(Color.RED.getRGB(), items.get(1).stroke());

        first.close();
        drawList.flush();
        assertEquals(1, hook.lastList().items().size());
        assertThrows(IllegalStateException.class, () -> first.set(List.of()));

        second.clear();
        drawList.flush();
        assertTrue(hook.lastList().items().isEmpty(), "an empty list tells the hook to draw nothing");
    }

    @Test
    void anImageGoesIntoTheAtlasOncePremultipliedAndBeforeTheListUsingIt() {
        drawList.setHook(hook);
        BufferedImage label = image(3, 2, 0x80ff0000);
        Anchor anchor = Anchor.npc(goblin).lift(240);
        DrawLayer layer = drawList.createLayer();
        layer.set(List.of(new DrawItem.Sprite(anchor, label, -1, -2), new DrawItem.Sprite(anchor, label, 4, 5)));
        drawList.flush();

        assertEquals(2, hook.sent.size());
        @SuppressWarnings("unchecked")
        List<WireSprite> sprites = (List<WireSprite>) hook.sent.get(0);
        assertEquals(1, sprites.size(), "the same image is placed once");
        WireSprite sprite = sprites.get(0);
        assertEquals(3, sprite.width());
        assertEquals(2, sprite.height());
        assertTrue(sprite.y() > WireDrawList.ATLAS_WHITE, "below the white corner");
        int half = 0x80 << 24 | ((0xff * 0x80 + 127) / 255) << 16;
        assertArrayEquals(new int[]{half, half, half, half, half, half}, sprite.pixels());

        List<WireDrawList.Item> items = ((WireDrawList) hook.sent.get(1)).items();
        assertEquals(WireDrawList.SPRITE, items.get(0).kind());
        assertEquals(sprite.x(), items.get(0).atlasX());
        assertEquals(sprite.y(), items.get(0).atlasY());
        assertEquals(-1, items.get(0).offsetX());
        assertEquals(-2, items.get(0).offsetY());
        assertEquals(240, items.get(0).lift());
        assertEquals(4, items.get(1).offsetX());

        layer.set(List.of(new DrawItem.Sprite(anchor, label, 0, 0)));
        drawList.flush();
        assertEquals(3, hook.sent.size(), "a placed image isn't sent again");
    }

    @Test
    void aFailedSendSendsEverythingAgainNextPass() {
        drawList.setHook(hook);
        DrawLayer layer = drawList.createLayer();
        BufferedImage label = image(2, 2, 0xffffffff);
        hook.failures = 1;
        layer.set(List.of(new DrawItem.Sprite(Anchor.npc(goblin), label, 0, 0)));
        drawList.flush();
        assertTrue(hook.sent.isEmpty());

        drawList.flush();
        assertEquals(2, hook.sent.size(), "the sprite again, then the list");
    }

    @Test
    void spritesThatDontFitTheAtlasAreLeftOut() {
        drawList.setHook(hook);
        DrawLayer layer = drawList.createLayer();
        BufferedImage huge = image(WireDrawList.ATLAS_SIZE, WireDrawList.ATLAS_SIZE, 0xffffffff);
        layer.set(List.of(new DrawItem.Sprite(Anchor.npc(goblin), huge, 0, 0), tile(Color.CYAN)));
        drawList.flush();
        assertEquals(1, hook.sent.size(), "no sprites to send");
        assertEquals(1, hook.lastList().items().size(), "the tile still goes");
    }

    @Test
    void itemsPastTheCapAreLeftOut() {
        drawList.setHook(hook);
        List<DrawItem> items = new ArrayList<>();
        for (int i = 0; i < WireDrawList.MAX_ITEMS + 5; i++) {
            items.add(new DrawItem.TileArea(Anchor.npc(goblin).offset(i, 0), 1, 1, Color.CYAN, null, 1));
        }
        drawList.createLayer().set(items);
        drawList.flush();
        assertEquals(WireDrawList.MAX_ITEMS, hook.lastList().items().size());
    }

    @Test
    void aTileAreaKeepsItsSizeInRange() {
        Anchor anchor = Anchor.npc(goblin);
        assertThrows(IllegalArgumentException.class, () -> new DrawItem.TileArea(anchor, 0, 1, null, null, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new DrawItem.TileArea(anchor, 1, DrawItem.TileArea.MAX_SIZE + 1, null, null, 1));
        assertNotEquals(tile(Color.CYAN), tile(Color.RED));
    }
}
