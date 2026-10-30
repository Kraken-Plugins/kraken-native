package com.kraken.client.plugins.npchighlight;

import com.kraken.api.Client;
import com.kraken.api.actor.NPC;
import com.kraken.api.eventbus.EventBus;
import com.kraken.api.highlight.HighlightCategory;
import com.kraken.api.highlight.HighlightConfig;
import com.kraken.api.highlight.HighlightTarget;
import com.kraken.api.highlight.Highlights;
import com.kraken.api.mapping.Mapping;
import com.kraken.api.memory.Memory;
import com.kraken.client.plugins.config.ConfigManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Color;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The game highlight styles of the hull and outline, the channel per style, and how tag styles pick the shapes.
 */
class NpcHighlightChannelsTest {

    @TempDir
    Path dir;

    private ScheduledExecutorService scheduler;
    private Mapping mapping;
    private Client client;

    @BeforeEach
    void setUp() throws IOException {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        mapping = Objects.requireNonNull(Mapping.bundled("241.3"), "bundled 241.3 mapping");
        client = new Client(new Zeros(), mapping);
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    @Test
    void theHullFillsWithTheFillOpacityAndTheOutlineDoesNot() {
        HighlightConfig hull = NpcHighlightChannels.modelStyle(npc(1, Color.CYAN, true, false, 2f));
        assertTrue(hull.enableModelOutline());
        assertTrue(hull.enableModelFill());
        assertTrue(hull.alwaysOnTop(), "drawn through walls, as an overlay is");
        assertEquals(new Color(0, 255, 255, 20), hull.highlightColour());
        assertEquals(2, hull.outlineWidth());

        HighlightConfig outline = NpcHighlightChannels.modelStyle(npc(1, new Color(255, 0, 0, 100), false, true, 3.4f));
        assertTrue(outline.enableModelOutline());
        assertFalse(outline.enableModelFill());
        assertEquals(new Color(255, 0, 0), outline.highlightColour(), "the outline is opaque");
        assertEquals(3, outline.outlineWidth());

        assertNull(NpcHighlightChannels.modelStyle(npc(1, Color.CYAN, false, false, 2f)), "tile styles only");
        assertEquals(NpcHighlightChannels.MAX_WIDTH,
                NpcHighlightChannels.modelStyle(npc(1, Color.CYAN, false, true, 50f)).outlineWidth());
    }

    @Test
    void npcsAreGroupedByStyle() {
        Map<HighlightConfig, Set<HighlightTarget>> grouped = NpcHighlightChannels.group(List.of(
                npc(1, Color.CYAN, true, false, 2f), npc(2, Color.CYAN, true, false, 2f),
                npc(3, Color.RED, true, false, 2f), npc(4, Color.CYAN, false, false, 2f)));
        assertEquals(2, grouped.size(), "two colours; the tile-only NPC isn't lit");
        assertEquals(2, grouped.get(NpcHighlightChannels.modelStyle(npc(1, Color.CYAN, true, false, 2f))).size());
    }

    @Test
    void eachStyleHoldsAChannelUntilNoNpcUsesIt() {
        Highlights highlights = new Highlights(mapping);
        NpcHighlightChannels channels = new NpcHighlightChannels(highlights);
        channels.sync(NpcHighlightChannels.group(List.of(npc(1, Color.CYAN, true, false, 2f),
                npc(2, Color.RED, false, true, 2f))));
        assertEquals(2, held(highlights));
        assertEquals(2, channels.styles().size());

        channels.sync(NpcHighlightChannels.group(List.of(npc(2, Color.RED, false, true, 2f))));
        assertEquals(1, held(highlights), "the hull's channel is released");

        channels.release();
        assertEquals(0, held(highlights));
        channels.sync(NpcHighlightChannels.group(List.of(npc(2, Color.RED, false, true, 2f))));
        assertEquals(0, held(highlights), "nothing is taken once released");
    }

    @Test
    void aTagStyleReplacesTheSettingsShapes() {
        ConfigManager manager = new ConfigManager(dir.resolve("settings.properties"), scheduler, new EventBus());
        NpcIndicatorsConfig config = manager.getConfig(NpcIndicatorsConfig.class);
        assertEquals(new RenderStyle(true, false, false, false, false, false), RenderStyle.of(null, config));
        manager.setConfiguration(NpcIndicatorsConfig.GROUP, "highlightTile", true);
        assertEquals(new RenderStyle(true, true, false, false, false, false), RenderStyle.of(null, config));
        assertEquals(new RenderStyle(false, false, false, false, true, false),
                RenderStyle.of(RenderStyle.SW_TRUE_TILE, config));
        assertEquals(new RenderStyle(false, false, false, false, false, true), RenderStyle.of(RenderStyle.OUTLINE, config));
    }

    private int held(Highlights highlights) {
        return highlights.heldChannels().getOrDefault(HighlightCategory.NPC, Set.of()).size();
    }

    private HighlightedNpc npc(int index, Color colour, boolean hull, boolean outline, float width) {
        return HighlightedNpc.builder()
                .npc(new NPC(client, index, 0, index))
                .highlightColor(colour)
                .fillColor(new Color(0, 255, 255, 20))
                .hull(hull)
                .outline(outline)
                .borderWidth(width)
                .build();
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
