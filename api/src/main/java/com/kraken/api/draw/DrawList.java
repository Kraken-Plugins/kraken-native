package com.kraken.api.draw;

import com.kraken.api.internal.draw.AtlasPacker;
import com.kraken.api.internal.draw.DrawHook;
import com.kraken.api.internal.draw.WireDrawList;
import com.kraken.api.internal.draw.WireSprite;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Items the client's native hook draws in the game's own frames, each tied to an NPC ({@link Anchor}). The hook keeps
 * the items and, in every frame, finds each anchor from the state that frame was drawn with, projects the item with
 * that frame's camera and draws it into the frame. Items move with the camera and with their NPC in the same frame,
 * and the game never waits for Java to draw them.
 *
 * <p>Each plugin takes a layer of its own ({@link #createLayer()}); the layers draw in the order they were taken.
 * Sprites go into an atlas of {@value WireDrawList#ATLAS_SIZE} pixels square in the hook, placed the first time an
 * image is used. Space isn't reused while the hook stays connected: once the atlas is full, sprites that don't fit are
 * left out and a warning is logged.
 *
 * <p>Items reach the client while its native hook is connected and draws the list ({@link #isAvailable()}). They are
 * remembered regardless, sent whole whenever the hook (re)connects, and sent again at the end of each pass of the
 * client thread in which they changed ({@link #flush}).
 *
 * <p>Thread-safe.
 */
public final class DrawList {

    private static final Logger log = Logger.getLogger("kraken.api");

    private static final int MAX_SIDE = 0xffff;
    private static final int MAX_OFFSET = Short.MAX_VALUE;
    private static final WireDrawList EMPTY = new WireDrawList(List.of());

    private final List<DrawLayer> layers = new ArrayList<>();
    private final AtlasPacker packer = new AtlasPacker(WireDrawList.ATLAS_SIZE, WireDrawList.ATLAS_WHITE);
    private final Map<BufferedImage, AtlasPacker.Place> placed = new WeakHashMap<>();

    private DrawHook hook;
    private WireDrawList sent;
    private boolean dirty;
    private boolean warnedItems;
    private boolean warnedAtlas;

    /**
     * Takes a new, empty layer for a plugin. Close it when done, which removes its items.
     *
     * @return the layer
     */
    public synchronized DrawLayer createLayer() {
        DrawLayer layer = new DrawLayer(this);
        layers.add(layer);
        return layer;
    }

    /**
     * Whether items reach the client now: its native hook is connected and draws the list.
     *
     * @return true while items are drawn
     */
    public synchronized boolean isAvailable() {
        return hook != null;
    }

    /**
     * Takes the connection to a hook that draws the list, or drops it; a new one is sent every sprite and item at
     * once. Internal: {@code GameSession.setDrawHook} calls it.
     *
     * @param hook the connection, or null while there is none
     */
    public void setHook(DrawHook hook) {
        synchronized (this) {
            this.hook = hook;
            forgetSent(true);
        }
        flush();
    }

    /**
     * Sends the sprites the items need and the items, if they changed since they were last sent. Called by the client
     * thread at the end of each pass.
     */
    public void flush() {
        DrawHook target;
        List<WireSprite> sprites = new ArrayList<>();
        WireDrawList wire;
        boolean sendList;
        synchronized (this) {
            if (hook == null || !dirty) {
                return;
            }
            dirty = false;
            wire = build(sprites);
            sendList = !wire.equals(sent);
            if (sprites.isEmpty() && !sendList) {
                return;
            }
            target = hook;
        }
        try {
            if (!sprites.isEmpty()) {
                target.sprites(sprites);
            }
            if (sendList) {
                target.list(wire);
            }
            synchronized (this) {
                if (hook == target) {
                    sent = wire;
                }
            }
        } catch (RuntimeException e) {
            synchronized (this) {
                if (hook == target) {
                    forgetSent(false);
                }
            }
            log.log(Level.FINE, "Could not send the draw list to the native hook", e);
        }
    }

    /**
     * Notes that a layer changed, so the next {@link #flush} sends the items.
     */
    synchronized void changed() {
        dirty = true;
    }

    /**
     * Drops a closed layer, so its items go at the next {@link #flush}.
     *
     * @param closed the layer
     */
    synchronized void closed(DrawLayer closed) {
        layers.remove(closed);
        dirty = true;
    }

    /**
     * The lock every layer changes under.
     *
     * @return the lock
     */
    Object lock() {
        return this;
    }

    /**
     * Forgets what the hook holds, so the next {@link #flush} sends every sprite and item again.
     *
     * @param fresh whether the hook just connected, and so holds no items; otherwise what it holds is unknown and the
     *              items are sent even if there are none
     */
    private void forgetSent(boolean fresh) {
        sent = fresh ? EMPTY : null;
        placed.clear();
        packer.reset();
        dirty = true;
    }

    /**
     * Combines every layer's items into the hook's, placing the images not yet in the atlas.
     *
     * @param sprites receives the images to copy into the atlas before the list is sent
     * @return the items
     */
    private WireDrawList build(List<WireSprite> sprites) {
        List<WireDrawList.Item> items = new ArrayList<>();
        for (DrawLayer layer : layers) {
            for (DrawItem item : layer.items()) {
                if (items.size() == WireDrawList.MAX_ITEMS) {
                    warnOnce(true, "More than " + WireDrawList.MAX_ITEMS + " draw list items; the rest aren't drawn");
                    return new WireDrawList(items);
                }
                WireDrawList.Item wire = toWire(item, sprites);
                if (wire != null) {
                    items.add(wire);
                }
            }
        }
        return new WireDrawList(items);
    }

    /**
     * One item as the hook takes it.
     *
     * @param item    the item
     * @param sprites receives its image if the atlas doesn't hold it yet
     * @return the item, or null if its image doesn't fit the atlas
     */
    private WireDrawList.Item toWire(DrawItem item, List<WireSprite> sprites) {
        Anchor a = item.anchor();
        int anchor = a.kind() == Anchor.Kind.NPC ? WireDrawList.ANCHOR_NPC : WireDrawList.ANCHOR_NPC_TILE;
        if (item instanceof DrawItem.TileArea area) {
            int width = Math.round(Math.max(0, Math.min(area.strokeWidth() * 16, MAX_SIDE)));
            return WireDrawList.Item.tileArea(anchor, a.index(), a.spawnCycle(), a.dx(), a.dy(), a.lift(),
                    area.sizeX(), area.sizeY(), argb(area.stroke()), argb(area.fill()), width);
        }
        DrawItem.Sprite sprite = (DrawItem.Sprite) item;
        AtlasPacker.Place place = place(sprite.image(), sprites);
        if (place == null) {
            return null;
        }
        BufferedImage image = sprite.image();
        int offsetX = Math.max(-MAX_OFFSET, Math.min(sprite.offsetX(), MAX_OFFSET));
        int offsetY = Math.max(-MAX_OFFSET, Math.min(sprite.offsetY(), MAX_OFFSET));
        return WireDrawList.Item.sprite(anchor, a.index(), a.spawnCycle(), a.dx(), a.dy(), a.lift(), place.x(),
                place.y(), image.getWidth(), image.getHeight(), offsetX, offsetY);
    }

    /**
     * Where an image is in the atlas, placing it (and queueing its pixels) the first time.
     *
     * @param image   the image
     * @param sprites receives its pixels when it is placed now
     * @return its place, or null if the atlas has no room for it
     */
    private AtlasPacker.Place place(BufferedImage image, List<WireSprite> sprites) {
        AtlasPacker.Place place = placed.get(image);
        if (place != null) {
            return place;
        }
        int width = image.getWidth();
        int height = image.getHeight();
        place = packer.place(width, height);
        if (place == null) {
            warnOnce(false, "The draw list's sprite atlas is full; new sprites aren't drawn until the native hook "
                    + "reconnects");
            return null;
        }
        placed.put(image, place);
        sprites.add(new WireSprite(place.x(), place.y(), width, height, premultiplied(image)));
        return place;
    }

    /**
     * An image's pixels as premultiplied ARGB, top row first.
     *
     * @param image the image
     * @return the pixels
     */
    static int[] premultiplied(BufferedImage image) {
        int[] pixels = image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            int alpha = p >>> 24;
            if (alpha == 255) {
                continue;
            }
            int red = ((p >> 16 & 0xff) * alpha + 127) / 255;
            int green = ((p >> 8 & 0xff) * alpha + 127) / 255;
            int blue = ((p & 0xff) * alpha + 127) / 255;
            pixels[i] = alpha << 24 | red << 16 | green << 8 | blue;
        }
        return pixels;
    }

    /**
     * A colour as ARGB, 0 for none.
     *
     * @param colour the colour, or null
     * @return its ARGB value
     */
    private static int argb(Color colour) {
        return colour == null ? 0 : colour.getRGB();
    }

    /**
     * Logs a warning the first time it comes up.
     *
     * @param items   whether it is the item cap's warning rather than the atlas's
     * @param message the warning
     */
    private void warnOnce(boolean items, String message) {
        if (items ? warnedItems : warnedAtlas) {
            return;
        }
        if (items) {
            warnedItems = true;
        } else {
            warnedAtlas = true;
        }
        log.warning(message);
    }
}
