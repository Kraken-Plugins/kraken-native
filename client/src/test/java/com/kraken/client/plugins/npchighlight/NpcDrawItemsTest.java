package com.kraken.client.plugins.npchighlight;

import com.kraken.api.Client;
import com.kraken.api.actor.NPC;
import com.kraken.api.draw.Anchor;
import com.kraken.api.draw.DrawItem;
import com.kraken.api.mapping.Mapping;
import com.kraken.api.memory.Memory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.io.IOException;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tile areas NPC Indicators gives the draw list for each tile style: the same tiles its overlay outlines.
 */
class NpcDrawItemsTest {

    private static final Color STROKE = Color.CYAN;
    private static final Color FILL = new Color(0, 255, 255, 20);

    private final Anchor drawn = new Anchor(Anchor.Kind.NPC, 5, 1000, 0, 0, 0);
    private final Anchor server = new Anchor(Anchor.Kind.NPC_TILE, 5, 1000, 0, 0, 0);
    private Client client;

    @BeforeEach
    void setUp() throws IOException {
        client = new Client(new Zeros(), Objects.requireNonNull(Mapping.bundled("241.3"), "bundled 241.3 mapping"));
    }

    private HighlightedNpc styles(boolean tile, boolean trueTile, boolean swTile, boolean swTrueTile) {
        return HighlightedNpc.builder()
                .npc(new NPC(client, 5, 0, 5))
                .highlightColor(STROKE)
                .fillColor(FILL)
                .tile(tile)
                .trueTile(trueTile)
                .swTile(swTile)
                .swTrueTile(swTrueTile)
                .build();
    }

    private List<DrawItem.TileArea> items(HighlightedNpc highlighted, int size) {
        return NpcIndicatorsPlugin.tileItems(highlighted, drawn, server, size, STROKE, FILL, 2f);
    }

    @Test
    void theTileStyleCoversTheFootprintWhereTheNpcIsDrawn() {
        assertEquals(List.of(new DrawItem.TileArea(drawn, 2, 2, STROKE, FILL, 2f)),
                items(styles(true, false, false, false), 2));
    }

    @Test
    void theTrueTileIsCentredOnTheServerFootprint() {
        assertEquals(List.of(new DrawItem.TileArea(server.offset(64, 64), 2, 2, STROKE, FILL, 2f)),
                items(styles(false, true, false, false), 2), "half a tile in per extra tile, from the south-west");
        assertEquals(List.of(new DrawItem.TileArea(server, 1, 1, STROKE, FILL, 2f)),
                items(styles(false, true, false, false), 1));
    }

    @Test
    void theSouthWestTilesAreOneTile() {
        assertEquals(List.of(new DrawItem.TileArea(drawn.offset(-128, -128), 1, 1, STROKE, FILL, 2f),
                        new DrawItem.TileArea(server, 1, 1, STROKE, FILL, 2f)),
                items(styles(false, false, true, true), 3));
    }

    @Test
    void noTileStyleDrawsNoTiles() {
        assertTrue(items(styles(false, false, false, false), 1).isEmpty());
    }

    /**
     * A process image that reads zeros: a client with no game loaded.
     */
    private static final class Zeros implements Memory {
        @Override
        public byte[] read(long address, int length) {
            return new byte[length];
        }

        @Override
        public long moduleBase() {
            return 0;
        }

        @Override
        public long moduleSize() {
            return 0;
        }

        @Override
        public boolean isValid() {
            return true;
        }
    }
}
