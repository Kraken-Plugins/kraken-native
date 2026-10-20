package com.kraken.api.widgets;

import com.kraken.api.Client;
import com.kraken.api.Point;

import java.awt.Rectangle;

/**
 * An interface component, as RuneLite's {@code Widget}. Get one from {@link Client#getWidget(int)}. A static widget is
 * designed in the cache and has a fixed id ({@link WidgetUtil#packComponentId}); a dynamic one is made by a script at
 * run time under a static widget, and is told apart by its {@link #getIndex() index}.
 *
 * <p>Unlike RuneLite's, a widget is a snapshot: its fields are read once, when it is fetched, so ask the client for it
 * again for fresh values. The getters that follow links ({@link #getParent()}, the children, {@link #isHidden()},
 * {@link #getCanvasLocation()} and {@link #getBounds()}) read the game when called. RuneLite plugins fetch widgets
 * where they use them, so this rarely shows.
 *
 * <p>Positions and sizes are in canvas pixels, the units of {@link Client#getCanvasWidth()}. The setters, listeners
 * and script hooks of RuneLite's widget aren't there: the API only reads.
 */
public interface Widget {

    /**
     * The widget's id: {@code group << 16 | component}. A dynamic widget has its static parent's id, as in RuneLite.
     *
     * @return the packed component id
     */
    int getId();

    /**
     * The widget's type.
     *
     * @return one of the {@link WidgetType} values (the native client has a few more: 1, 10 and 11)
     */
    int getType();

    /**
     * The widget's parent, read now. A group's top-level widgets have none: Kraken doesn't read which component a
     * group is opened in, where RuneLite returns that component.
     *
     * @return the parent, or null for a group's top-level widget
     */
    Widget getParent();

    /**
     * The id of the widget's parent.
     *
     * @return the parent's packed id (a dynamic widget's static parent's); -1 for a group's top-level widget, where
     *         RuneLite returns the id of the component the group is opened in
     */
    int getParentId();

    /**
     * One of the dynamic children, by index, read now.
     *
     * @param index the child's index ({@link #getIndex()})
     * @return the child, or null if there is none directly under this widget at that index
     */
    Widget getChild(int index);

    /**
     * The dynamic children, read now: the widgets a script created directly under this one. Each is at its
     * {@link #getIndex()}, with null where there is no child, as in RuneLite. Reads every child, so on a big container
     * (the bank) prefer {@link #getChild(int)} for a few.
     *
     * @return the children by index; empty rather than RuneLite's null when there are none
     */
    Widget[] getChildren();

    /**
     * The dynamic children without the gaps.
     *
     * @return the children in index order
     */
    Widget[] getDynamicChildren();

    /**
     * The static widgets whose parent is this one, read now (the whole group is read to find them).
     *
     * @return the static children in component order; empty for a dynamic widget
     */
    Widget[] getStaticChildren();

    /**
     * The widget's index among its static parent's dynamic children.
     *
     * @return the index, or -1 for a static widget
     */
    int getIndex();

    /**
     * Whether the widget is hidden: by its own flag or a parent's, or because its group isn't open, as RuneLite works
     * it out. Reads up the parent chain.
     *
     * @return true if the client won't draw it
     */
    boolean isHidden();

    /**
     * Whether the widget's own hidden flag is set (a hidden parent also hides it; see {@link #isHidden()}).
     *
     * @return true if hidden by itself
     */
    boolean isSelfHidden();

    /**
     * The widget's text.
     *
     * @return the text, with the client's markup ({@code <col=ff0000>}, {@code <br>}); "" if none
     */
    String getText();

    /**
     * The name of what the widget's actions act on, such as an item's name in an inventory slot.
     *
     * @return the name, with markup; "" if none
     */
    String getName();

    /**
     * The colour of the widget's text or shape.
     *
     * @return the colour as {@code 0xRRGGBB}; -1 if this build's mapping lacks it
     */
    int getTextColor();

    /**
     * How see-through the widget is.
     *
     * @return 0 (opaque) to 255 (invisible)
     */
    int getOpacity();

    /**
     * Whether a rectangle widget is filled.
     *
     * @return true if filled, false if outlined
     */
    boolean isFilled();

    /**
     * The sprite the widget draws.
     *
     * @return the sprite id, as in {@code gameval.SpriteID}; -1 for none
     */
    int getSpriteId();

    /**
     * Whether the sprite is tiled across the widget.
     *
     * @return true if it tiles
     */
    boolean getSpriteTiling();

    /**
     * Whether the sprite is drawn upside down.
     *
     * @return true if flipped vertically
     */
    boolean isFlippedVertically();

    /**
     * Whether the sprite is drawn mirrored.
     *
     * @return true if flipped horizontally
     */
    boolean isFlippedHorizontally();

    /**
     * The model the widget shows.
     *
     * @return the model id (an item, NPC or model id, depending on {@link #getModelType()}); -1 for none
     */
    int getModelId();

    /**
     * What {@link #getModelId()} names.
     *
     * @return the model type, the cache's value
     */
    int getModelType();

    /**
     * The model's animation.
     *
     * @return the animation id, or -1
     */
    int getAnimationId();

    /**
     * The model's rotation about x.
     *
     * @return the angle, 2048 per turn
     */
    int getRotationX();

    /**
     * The model's rotation about y.
     *
     * @return the angle, 2048 per turn
     */
    int getRotationY();

    /**
     * The model's rotation about z.
     *
     * @return the angle, 2048 per turn
     */
    int getRotationZ();

    /**
     * How far the model is zoomed.
     *
     * @return the zoom
     */
    int getModelZoom();

    /**
     * The item an inventory slot widget shows.
     *
     * @return the item id, or -1 for none
     */
    int getItemId();

    /**
     * The size of the stack {@link #getItemId()} shows.
     *
     * @return the quantity
     */
    int getItemQuantity();

    /**
     * The widget's right-click actions.
     *
     * @return the actions by slot, null for an empty slot; null if the widget has none, as in RuneLite
     */
    String[] getActions();

    /**
     * The widget's x relative to its parent, as laid out.
     *
     * @return x in canvas pixels
     */
    int getRelativeX();

    /**
     * The widget's y relative to its parent, as laid out.
     *
     * @return y in canvas pixels
     */
    int getRelativeY();

    /**
     * The widget's width, as laid out.
     *
     * @return the width in canvas pixels
     */
    int getWidth();

    /**
     * The widget's height, as laid out.
     *
     * @return the height in canvas pixels
     */
    int getHeight();

    /**
     * The widget's x as designed, before {@link #getXPositionMode()} applies.
     *
     * @return the designed x
     */
    int getOriginalX();

    /**
     * The widget's y as designed, before {@link #getYPositionMode()} applies.
     *
     * @return the designed y
     */
    int getOriginalY();

    /**
     * The widget's width as designed, before {@link #getWidthMode()} applies.
     *
     * @return the designed width
     */
    int getOriginalWidth();

    /**
     * The widget's height as designed, before {@link #getHeightMode()} applies.
     *
     * @return the designed height
     */
    int getOriginalHeight();

    /**
     * How the widget's x is measured.
     *
     * @return a {@link WidgetPositionMode} value
     */
    int getXPositionMode();

    /**
     * How the widget's y is measured.
     *
     * @return a {@link WidgetPositionMode} value
     */
    int getYPositionMode();

    /**
     * How the widget's width is measured.
     *
     * @return a {@link WidgetSizeMode} value
     */
    int getWidthMode();

    /**
     * How the widget's height is measured.
     *
     * @return a {@link WidgetSizeMode} value
     */
    int getHeightMode();

    /**
     * How far a scrolling layer is scrolled across.
     *
     * @return the scroll x, or -1 if this build's mapping lacks it
     */
    int getScrollX();

    /**
     * How far a scrolling layer is scrolled down.
     *
     * @return the scroll y, or -1 if this build's mapping lacks it
     */
    int getScrollY();

    /**
     * The width of a scrolling layer's content.
     *
     * @return the scroll width, or -1 if this build's mapping lacks it
     */
    int getScrollWidth();

    /**
     * The height of a scrolling layer's content.
     *
     * @return the scroll height, or -1 if this build's mapping lacks it
     */
    int getScrollHeight();

    /**
     * Where the widget's top-left corner is on the canvas, read now: the client's sum of the relative positions up the
     * parent chain, plus where its group is drawn. Like the client's, it doesn't take off the scroll of scrolling
     * parents.
     *
     * @return the corner in canvas pixels, or null if it can't be read
     */
    Point getCanvasLocation();

    /**
     * The widget's area on the canvas, read now.
     *
     * @return {@link #getCanvasLocation()} with the widget's size, or null if the location can't be read
     */
    Rectangle getBounds();

    /**
     * Whether a canvas point is on the widget.
     *
     * @param point the point, in canvas pixels
     * @return true if it is inside {@link #getBounds()}
     */
    boolean contains(Point point);

    /**
     * Whether the client drew the widget in its latest frame when the widget was read. Kraken extra, mirrors Lua
     * {@code IfType.isRendering}.
     *
     * @return true if it was drawn
     */
    boolean isRendering();

    /**
     * The label the widget's descriptor carries. Kraken extra, mirrors Lua {@code IfType.sourceName}.
     *
     * @return the label, usually ""
     */
    String getSourceName();

    /**
     * The clipping rectangle of the widget's group, as the client draws the group: nothing of the group shows outside
     * it. Kraken extra, from Lua's {@code InterfaceCoordinates}, read now.
     *
     * @return the rectangle in canvas pixels, or null if the group isn't open
     */
    Rectangle getGroupClipBounds();
}
