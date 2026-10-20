package com.kraken.api.impl;

import com.kraken.api.Point;
import com.kraken.api.lua.Colour;
import com.kraken.api.lua.IfGroup;
import com.kraken.api.lua.IfType;
import com.kraken.api.lua.InterfaceCoordinates;
import com.kraken.api.lua.InterfaceQuery;
import com.kraken.api.lua.Vector2;
import com.kraken.api.widgets.Widget;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link Widget} over one {@link IfType} snapshot. Field getters answer from the snapshot; the link getters go through
 * the snapshot's own link getters ({@code getParent}, {@code getChildren}, {@code getScreenCoords}) and
 * {@link InterfaceQuery}, which read the game. Two widgets over the same native component are equal.
 */
final class WidgetImpl implements Widget {

    private static final Widget[] NONE = new Widget[0];

    /**
     * How far up the parent chain {@link #isHidden()} looks before giving up, as {@code IfType.getScreenCoords} does.
     */
    private static final int MAX_DEPTH = 64;

    private final InterfaceQuery interfaces;
    private final IfType type;

    private WidgetImpl(InterfaceQuery interfaces, IfType type) {
        this.interfaces = interfaces;
        this.type = type;
    }

    /**
     * Wraps a snapshot.
     *
     * @param interfaces the reader the snapshot came from
     * @param type       the snapshot, or null
     * @return the widget, or null for a null snapshot
     */
    static WidgetImpl of(InterfaceQuery interfaces, IfType type) {
        return type == null ? null : new WidgetImpl(interfaces, type);
    }

    @Override
    public int getId() {
        return type.getPackedId();
    }

    @Override
    public int getType() {
        return type.getType();
    }

    @Override
    public Widget getParent() {
        return of(interfaces, type.getParent());
    }

    @Override
    public int getParentId() {
        return type.getLayerId();
    }

    @Override
    public Widget getChild(int index) {
        return of(interfaces, type.getChild(index));
    }

    @Override
    public Widget[] getChildren() {
        List<IfType> children = type.getChildren();
        if (children.isEmpty()) {
            return NONE;
        }
        int length = 0;
        for (IfType child : children) {
            length = Math.max(length, child.getSubId() + 1);
        }
        Widget[] byIndex = new Widget[length];
        for (IfType child : children) {
            byIndex[child.getSubId()] = of(interfaces, child);
        }
        return byIndex;
    }

    @Override
    public Widget[] getDynamicChildren() {
        List<IfType> children = type.getChildren();
        Widget[] out = new Widget[children.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = of(interfaces, children.get(i));
        }
        return out;
    }

    @Override
    public Widget[] getStaticChildren() {
        if (!type.isStatic()) {
            return NONE;
        }
        IfGroup group = interfaces.retrieveIfGroup(type.getId());
        if (group == null) {
            return NONE;
        }
        List<Widget> out = new ArrayList<>();
        for (IfType component : group.components()) {
            if (component != null && component.getLayerId() == type.getPackedId()
                    && component.getPackedId() != type.getPackedId()) {
                out.add(of(interfaces, component));
            }
        }
        return out.toArray(NONE);
    }

    @Override
    public int getIndex() {
        return type.getSubId();
    }

    /**
     * RuneLite's rule: hidden by its own flag or any parent's, and a group's top-level widget counts as hidden while
     * its group isn't open.
     *
     * @return true if the client won't draw it
     */
    @Override
    public boolean isHidden() {
        IfType at = type;
        for (int depth = 0; depth < MAX_DEPTH && at != null; depth++) {
            if (at.isHidden()) {
                return true;
            }
            IfType parent = at.getParent();
            if (parent == null) {
                return interfaces.getCoordinates(at.getId()) == null;
            }
            at = parent;
        }
        return false;
    }

    @Override
    public boolean isSelfHidden() {
        return type.isHidden();
    }

    @Override
    public String getText() {
        return type.getText();
    }

    @Override
    public String getName() {
        return type.getBaseOpName();
    }

    @Override
    public int getTextColor() {
        Colour colour = type.getColour();
        return colour == null ? -1 : colour.rgb();
    }

    @Override
    public int getOpacity() {
        return type.getTransparency();
    }

    @Override
    public boolean isFilled() {
        return type.isFill();
    }

    @Override
    public int getSpriteId() {
        return type.getGraphic();
    }

    @Override
    public boolean getSpriteTiling() {
        return type.isTiling();
    }

    @Override
    public boolean isFlippedVertically() {
        return type.isVFlip();
    }

    @Override
    public boolean isFlippedHorizontally() {
        return type.isHFlip();
    }

    @Override
    public int getModelId() {
        return type.getModel1Id();
    }

    @Override
    public int getModelType() {
        return type.getModel1Type();
    }

    @Override
    public int getAnimationId() {
        return type.getModelAnim();
    }

    @Override
    public int getRotationX() {
        return type.getModelXAn();
    }

    @Override
    public int getRotationY() {
        return type.getModelYAn();
    }

    @Override
    public int getRotationZ() {
        return type.getModelZAn();
    }

    @Override
    public int getModelZoom() {
        return type.getModelZoom();
    }

    @Override
    public int getItemId() {
        return type.getLinkObjType();
    }

    @Override
    public int getItemQuantity() {
        return type.getLinkObjCount();
    }

    @Override
    public String[] getActions() {
        List<String> ops = type.getOpNames();
        return ops.isEmpty() ? null : Definitions.actions(ops);
    }

    @Override
    public int getRelativeX() {
        return type.getX();
    }

    @Override
    public int getRelativeY() {
        return type.getY();
    }

    @Override
    public int getWidth() {
        return type.getWidth();
    }

    @Override
    public int getHeight() {
        return type.getHeight();
    }

    @Override
    public int getOriginalX() {
        return type.getDataX();
    }

    @Override
    public int getOriginalY() {
        return type.getDataY();
    }

    @Override
    public int getOriginalWidth() {
        return type.getDataWidth();
    }

    @Override
    public int getOriginalHeight() {
        return type.getDataHeight();
    }

    @Override
    public int getXPositionMode() {
        return type.getModeX();
    }

    @Override
    public int getYPositionMode() {
        return type.getModeY();
    }

    @Override
    public int getWidthMode() {
        return type.getModeWidth();
    }

    @Override
    public int getHeightMode() {
        return type.getModeHeight();
    }

    @Override
    public int getScrollX() {
        Vector2 pos = type.getScrollPos();
        return pos == null ? -1 : (int) pos.x();
    }

    @Override
    public int getScrollY() {
        Vector2 pos = type.getScrollPos();
        return pos == null ? -1 : (int) pos.y();
    }

    @Override
    public int getScrollWidth() {
        Vector2 size = type.getScrollSize();
        return size == null ? -1 : (int) size.x();
    }

    @Override
    public int getScrollHeight() {
        Vector2 size = type.getScrollSize();
        return size == null ? -1 : (int) size.y();
    }

    @Override
    public Point getCanvasLocation() {
        Vector2 corner = type.getScreenCoords();
        return corner == null ? null : new Point((int) corner.x(), (int) corner.y());
    }

    @Override
    public Rectangle getBounds() {
        Point corner = getCanvasLocation();
        return corner == null ? null : new Rectangle(corner.getX(), corner.getY(), type.getWidth(), type.getHeight());
    }

    @Override
    public boolean contains(Point point) {
        Rectangle bounds = getBounds();
        return bounds != null && bounds.contains(point.getX(), point.getY());
    }

    @Override
    public boolean isRendering() {
        return type.isRendering();
    }

    @Override
    public String getSourceName() {
        return type.getSourceName();
    }

    @Override
    public Rectangle getGroupClipBounds() {
        InterfaceCoordinates group = interfaces.getCoordinates(type.getId());
        return group == null ? null : new Rectangle(group.clipX1(), group.clipY1(), group.clipX2() - group.clipX1(),
                group.clipY2() - group.clipY1());
    }

    /**
     * The snapshot this widget answers from.
     *
     * @return the Lua mirror's component
     */
    IfType snapshot() {
        return type;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof WidgetImpl widget && widget.type.getAddress() == type.getAddress();
    }

    @Override
    public int hashCode() {
        return Long.hashCode(type.getAddress());
    }

    @Override
    public String toString() {
        return "Widget[" + type + "]";
    }
}
