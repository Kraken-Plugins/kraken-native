package com.kraken.client.plugins.grounditems;

import com.google.inject.Inject;
import com.kraken.api.Client;
import com.kraken.api.Perspective;
import com.kraken.api.Point;
import com.kraken.api.actor.Player;
import com.kraken.api.coords.LocalPoint;
import com.kraken.api.coords.WorldPoint;
import com.kraken.api.gameval.ItemID;
import com.kraken.api.gameval.VarbitID;
import com.kraken.api.scene.WorldView;
import com.kraken.client.plugins.grounditems.config.DespawnTimerMode;
import com.kraken.client.plugins.grounditems.config.ItemHighlightMode;
import com.kraken.client.plugins.grounditems.config.OwnershipFilterMode;
import com.kraken.client.plugins.grounditems.config.PriceDisplayMode;
import com.kraken.client.ui.overlay.Overlay;
import com.kraken.client.ui.overlay.OverlayLayer;
import com.kraken.client.ui.overlay.OverlayPosition;
import com.kraken.client.ui.overlay.OverlayUtil;
import com.kraken.client.util.QuantityFormatter;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Stroke;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Writes each shown ground item's name, quantity and prices over its tile, stacking the lines of one tile upwards, and
 * outlines the tiles if the setting is on. Runs on the client thread, like the plugin's state.
 *
 * <p>The client's item piles hold no despawn times, so the timers have no spawn time to count from and aren't drawn.
 */
class GroundItemsOverlay extends Overlay {

    /**
     * How far from the local player items are drawn, in local units.
     */
    private static final int MAX_DISTANCE = 2500;

    /**
     * How far above the ground the text goes, in local units, so it doesn't cover the items.
     */
    private static final int OFFSET_Z = 20;

    /**
     * The gap between the lines of one tile, in pixels.
     */
    private static final int STRING_GAP = 15;

    private static final Color PUBLIC_TIMER_COLOR = Color.YELLOW;
    private static final Color PRIVATE_TIMER_COLOR = Color.GREEN;
    private static final int TIMER_OVERLAY_DIAMETER = 10;
    private static final long TICK_MILLIS = 600;

    private final Client client;
    private final GroundItemsPlugin plugin;
    private final GroundItemsConfig config;
    private final StringBuilder itemStringBuilder = new StringBuilder();

    /**
     * How many lines each tile has drawn so far this frame.
     */
    private final Map<WorldPoint, Integer> offsetMap = new HashMap<>();

    /**
     * The overlay, over the scene.
     *
     * @param client the game
     * @param plugin the plugin, holding the items
     * @param config the settings
     */
    @Inject
    GroundItemsOverlay(Client client, GroundItemsPlugin plugin, GroundItemsConfig config) {
        super(plugin);
        this.client = client;
        this.plugin = plugin;
        this.config = config;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        ItemHighlightMode highlightMode = config.itemHighlightMode();
        boolean hotkey = plugin.isHotKeyPressed();
        boolean dontShowOverlay = (highlightMode == ItemHighlightMode.MENU || highlightMode == ItemHighlightMode.NONE
                || plugin.isHideAll()) && !hotkey;
        if (dontShowOverlay && !config.highlightTiles()) {
            return null;
        }
        Player player = client.getLocalPlayer();
        LocalPoint localLocation = player == null ? null : player.getLocalLocation();
        GroundItemRules rules = plugin.getRules();
        if (localLocation == null || rules == null) {
            return null;
        }
        WorldView view = client.getTopLevelWorldView();
        FontMetrics fm = graphics.getFontMetrics();
        offsetMap.clear();
        DespawnTimerMode groundItemTimers = config.groundItemTimers();
        boolean outline = config.textOutline();
        OwnershipFilterMode ownershipFilterMode = config.ownershipFilterMode();
        int accountType = client.getVarbitValue(VarbitID.IRONMAN);

        for (GroundItem item : plugin.groundItems()) {
            LocalPoint groundPoint = LocalPoint.fromWorld(view, item.getLocation());
            if (groundPoint == null || localLocation.distanceTo(groundPoint) > MAX_DISTANCE
                    || !GroundItemRules.shouldDisplayItem(ownershipFilterMode, item.getOwnership(), accountType)) {
                continue;
            }
            rules.updateItemColor(item);
            if (!item.highlighted && !hotkey && (item.hidden || config.showHighlightedOnly())) {
                continue;
            }
            Color color = item.color;
            if (config.highlightTiles()) {
                Polygon poly = Perspective.getCanvasTilePoly(client, groundPoint);
                if (poly != null) {
                    OverlayUtil.renderPolygon(graphics, poly, color);
                }
            }
            if (dontShowOverlay) {
                continue;
            }
            String itemString = itemText(item);
            Point textPoint = Perspective.getCanvasTextLocation(client, graphics, groundPoint, itemString, OFFSET_Z);
            if (textPoint == null) {
                continue;
            }
            int offset = offsetMap.compute(item.getLocation(), (k, v) -> v != null ? v + 1 : 0);
            item.setOffset(offset);
            int textX = textPoint.getX();
            int textY = textPoint.getY() - STRING_GAP * offset;

            if (groundItemTimers == DespawnTimerMode.PIE || hotkey) {
                drawTimerPieOverlay(graphics, textX, textY, item);
            } else if (groundItemTimers == DespawnTimerMode.SECONDS || groundItemTimers == DespawnTimerMode.TICKS) {
                Instant despawnTime = calculateDespawnTime(item);
                Color timerColor = getItemTimerColor(item);
                if (despawnTime != null && timerColor != null) {
                    long left = despawnTime.toEpochMilli() - Instant.now().toEpochMilli();
                    String timerText = groundItemTimers == DespawnTimerMode.SECONDS
                            ? String.format(" - %.1f", left / 1000f)
                            : String.format(" - %d", left / TICK_MILLIS);
                    drawText(graphics, timerText, textX + fm.stringWidth(itemString), textY, timerColor, outline);
                }
            }
            drawText(graphics, itemString, textX, textY, color, outline);
        }
        return null;
    }

    /**
     * An item's line: its name, its quantity if more than one, and its prices as the settings choose (none for
     * coins).
     *
     * @param item the item
     * @return the text
     */
    private String itemText(GroundItem item) {
        itemStringBuilder.setLength(0);
        itemStringBuilder.append(item.getName());
        if (item.getQuantity() > 1) {
            itemStringBuilder.append(" (").append(QuantityFormatter.quantityToStackSize(item.getQuantity()))
                    .append(')');
        }
        if (item.getId() != ItemID.COINS) {
            PriceDisplayMode displayMode = config.priceDisplayMode();
            if (displayMode == PriceDisplayMode.BOTH) {
                if (item.getGePrice() > 0) {
                    itemStringBuilder.append(" (GE: ").append(QuantityFormatter.quantityToStackSize(item.getGePrice()))
                            .append(" gp)");
                }
                if (item.getHaPrice() > 0) {
                    itemStringBuilder.append(" (HA: ").append(QuantityFormatter.quantityToStackSize(item.getHaPrice()))
                            .append(" gp)");
                }
            } else if (displayMode != PriceDisplayMode.OFF) {
                long price = displayMode == PriceDisplayMode.GE ? item.getGePrice() : item.getHaPrice();
                if (price > 0) {
                    itemStringBuilder.append(" (").append(QuantityFormatter.quantityToStackSize(price))
                            .append(" gp)");
                }
            }
        }
        return itemStringBuilder.toString();
    }

    /**
     * Draws text with a black outline, or a drop shadow.
     *
     * @param graphics where to draw
     * @param text     the text
     * @param x        the baseline's left end
     * @param y        the baseline
     * @param color    the text's colour
     * @param outline  true for an outline, false for a shadow
     */
    private static void drawText(Graphics2D graphics, String text, int x, int y, Color color, boolean outline) {
        graphics.setColor(Color.BLACK);
        if (outline) {
            graphics.drawString(text, x, y + 1);
            graphics.drawString(text, x, y - 1);
            graphics.drawString(text, x + 1, y);
            graphics.drawString(text, x - 1, y);
        } else {
            graphics.drawString(text, x + 1, y + 1);
        }
        graphics.setColor(color);
        graphics.drawString(text, x, y);
    }

    /**
     * When an item despawns.
     *
     * @param item the item
     * @return the time; null if its spawn time is unknown or the time has passed
     */
    private static Instant calculateDespawnTime(GroundItem item) {
        Instant spawnTime = item.getSpawnTime();
        if (spawnTime == null) {
            return null;
        }
        Instant despawnTime = spawnTime.plus(item.getDespawnTime());
        return Instant.now().isAfter(despawnTime) ? null : despawnTime;
    }

    /**
     * The colour of an item's timer: green while only its owner sees it, yellow once everyone does.
     *
     * @param item the item
     * @return the colour; null if its spawn time is unknown or it has despawned
     */
    private static Color getItemTimerColor(GroundItem item) {
        Instant spawnTime = item.getSpawnTime();
        if (spawnTime == null) {
            return null;
        }
        Instant now = Instant.now();
        Instant despawnTime = spawnTime.plus(item.getDespawnTime());
        if (item.isPrivate()) {
            return despawnTime.isAfter(now) ? PRIVATE_TIMER_COLOR : null;
        }
        if (spawnTime.plus(item.getVisibleTime()).isAfter(now)) {
            return PRIVATE_TIMER_COLOR;
        }
        return despawnTime.isAfter(now) ? PUBLIC_TIMER_COLOR : null;
    }

    /**
     * Draws an item's timer as a pie left of its text, emptying as the item nears despawning.
     *
     * @param graphics where to draw
     * @param textX    the text's left end
     * @param textY    the text's baseline
     * @param item     the item
     */
    private static void drawTimerPieOverlay(Graphics2D graphics, int textX, int textY, GroundItem item) {
        Instant spawnTime = item.getSpawnTime();
        Instant despawnTime = calculateDespawnTime(item);
        Color fillColor = getItemTimerColor(item);
        if (spawnTime == null || despawnTime == null || fillColor == null) {
            return;
        }
        long now = Instant.now().toEpochMilli();
        float percent = (float) (now - spawnTime.toEpochMilli())
                / (despawnTime.toEpochMilli() - spawnTime.toEpochMilli());
        double x = textX - TIMER_OVERLAY_DIAMETER - TIMER_OVERLAY_DIAMETER / 2.0;
        double y = textY - TIMER_OVERLAY_DIAMETER;
        Stroke original = graphics.getStroke();
        graphics.setColor(fillColor);
        graphics.fill(new Arc2D.Double(x, y, TIMER_OVERLAY_DIAMETER, TIMER_OVERLAY_DIAMETER, 90,
                -360.0 * (1 - percent), Arc2D.PIE));
        graphics.setStroke(new BasicStroke(1));
        graphics.draw(new Ellipse2D.Double(x, y, TIMER_OVERLAY_DIAMETER, TIMER_OVERLAY_DIAMETER));
        graphics.setStroke(original);
    }
}
