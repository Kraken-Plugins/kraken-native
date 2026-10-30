package com.kraken.client.plugins.npchighlight;

import com.kraken.api.actor.NPC;
import lombok.Builder;
import lombok.NonNull;
import lombok.Value;

import java.awt.Color;
import java.util.function.Predicate;

/**
 * How one NPC is highlighted: its colours, which shapes are drawn and whether it is drawn at all right now.
 */
@Value
@Builder
class HighlightedNpc {

    /**
     * The NPC.
     */
    @NonNull
    NPC npc;

    /**
     * The outline and name colour.
     */
    @NonNull
    Color highlightColor;

    /**
     * The fill colour; its alpha is the fill's opacity.
     */
    @Builder.Default
    Color fillColor = new Color(0, 0, 0, 50);

    /**
     * Whether the game fills and outlines the NPC's model.
     */
    boolean hull;

    /**
     * Whether the tiles the NPC is drawn on are outlined.
     */
    boolean tile;

    /**
     * Whether the tiles the NPC is on as the server sees it are outlined.
     */
    boolean trueTile;

    /**
     * Whether the south-west tile the NPC is drawn on is outlined.
     */
    boolean swTile;

    /**
     * Whether the south-west tile the NPC is on as the server sees it is outlined.
     */
    boolean swTrueTile;

    /**
     * Whether the game outlines the NPC's model.
     */
    boolean outline;

    /**
     * Whether the NPC's name is written above it.
     */
    boolean name;

    /**
     * Whether the NPC's name is written on the minimap.
     */
    boolean nameOnMinimap;

    /**
     * The outlines' width in pixels.
     */
    @Builder.Default
    float borderWidth = 2.0f;

    /**
     * How much of the model outline fades out, 0-4.
     */
    int outlineFeather;

    /**
     * Whether the NPC is drawn now; null to always draw it.
     */
    Predicate<NPC> render;

    /**
     * Whether the NPC is drawn now, by {@link #render}.
     *
     * @return false while the predicate rejects it
     */
    boolean isShown() {
        return render == null || render.test(npc);
    }
}
