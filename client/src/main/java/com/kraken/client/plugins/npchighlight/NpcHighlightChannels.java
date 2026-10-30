package com.kraken.client.plugins.npchighlight;

import com.kraken.api.highlight.HighlightCategory;
import com.kraken.api.highlight.HighlightChannel;
import com.kraken.api.highlight.HighlightConfig;
import com.kraken.api.highlight.HighlightTarget;
import com.kraken.api.highlight.Highlights;
import lombok.extern.java.Log;

import java.awt.Color;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The game's own model highlights for the hull and outline styles: one NPC highlight channel per style in use (colour,
 * width, fill), taken when a style is first needed and released once no NPC uses it. The game draws them through walls,
 * as an overlay would.
 *
 * <p>Thread-safe: the channels are, and {@link #sync} and {@link #release} hold this object's lock.
 */
@Log(topic = "kraken")
final class NpcHighlightChannels {

    /**
     * The widest model outline drawn.
     */
    static final int MAX_WIDTH = 16;

    private final Highlights highlights;
    private final Map<HighlightConfig, HighlightChannel> channels = new HashMap<>();
    private boolean warnedFull;
    private boolean released;

    /**
     * Channels taken from the client's highlights.
     *
     * @param highlights the client's highlights
     */
    NpcHighlightChannels(Highlights highlights) {
        this.highlights = highlights;
    }

    /**
     * The game's style for an NPC's model, or null if neither the hull nor the outline is highlighted. The hull fills
     * the model with the fill colour's opacity and outlines it; the outline alone only outlines it.
     *
     * @param npc the NPC's highlight
     * @return the style, or null for none
     */
    static HighlightConfig modelStyle(HighlightedNpc npc) {
        if (!npc.isHull() && !npc.isOutline()) {
            return null;
        }
        Color highlight = npc.getHighlightColor();
        Color colour = npc.isHull()
                ? new Color(highlight.getRed(), highlight.getGreen(), highlight.getBlue(), npc.getFillColor().getAlpha())
                : new Color(highlight.getRGB() | 0xff000000, true);
        int width = Math.max(0, Math.min(Math.round(npc.getBorderWidth()), MAX_WIDTH));
        return HighlightConfig.DEFAULT
                .withHighlightColour(colour)
                .withOutlineWidth(width)
                .withEnableModelOutline(true)
                .withEnableModelFill(npc.isHull())
                .withAlwaysOnTop(true);
    }

    /**
     * Lights exactly these NPCs, each in its style: sets each style's channel to its NPCs, taking channels for new
     * styles and releasing those no longer used. A style that finds no free channel is left out and logged once.
     * Does nothing once {@link #release} has run.
     *
     * @param wanted the NPCs to light, by style
     */
    synchronized void sync(Map<HighlightConfig, Set<HighlightTarget>> wanted) {
        if (released) {
            return;
        }
        Iterator<Map.Entry<HighlightConfig, HighlightChannel>> held = channels.entrySet().iterator();
        while (held.hasNext()) {
            Map.Entry<HighlightConfig, HighlightChannel> entry = held.next();
            if (!wanted.containsKey(entry.getKey())) {
                entry.getValue().release();
                held.remove();
            }
        }
        for (Map.Entry<HighlightConfig, Set<HighlightTarget>> entry : wanted.entrySet()) {
            HighlightChannel channel = channels.get(entry.getKey());
            if (channel == null) {
                channel = take(entry.getKey());
                if (channel == null) {
                    continue;
                }
            }
            channel.set(entry.getValue());
        }
    }

    /**
     * Releases every channel for good: later syncs take none.
     */
    synchronized void release() {
        released = true;
        channels.values().forEach(HighlightChannel::release);
        channels.clear();
    }

    /**
     * The styles that hold a channel now.
     *
     * @return the styles
     */
    synchronized Set<HighlightConfig> styles() {
        return Set.copyOf(channels.keySet());
    }

    /**
     * Groups NPC highlights by their model style, leaving out those without one.
     *
     * @param npcs the highlights to light
     * @return the targets by style, in the order first seen
     */
    static Map<HighlightConfig, Set<HighlightTarget>> group(Collection<HighlightedNpc> npcs) {
        Map<HighlightConfig, Set<HighlightTarget>> wanted = new LinkedHashMap<>();
        for (HighlightedNpc npc : npcs) {
            HighlightConfig style = modelStyle(npc);
            if (style != null) {
                wanted.computeIfAbsent(style, s -> new HashSet<>()).add(HighlightTarget.npc(npc.getNpc()));
            }
        }
        return wanted;
    }

    private HighlightChannel take(HighlightConfig style) {
        try {
            HighlightChannel channel = highlights.channel(HighlightCategory.NPC);
            channel.configure(style);
            channels.put(style, channel);
            return channel;
        } catch (IllegalStateException e) {
            if (!warnedFull) {
                warnedFull = true;
                log.warning("NPC Indicators: every NPC highlight channel is taken, so some styles aren't drawn");
            }
            return null;
        }
    }
}
