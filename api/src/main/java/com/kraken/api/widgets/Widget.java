package com.kraken.api.widgets;

import com.kraken.api.Client;
import com.kraken.api.Point;
import com.kraken.api.action.ActionResult;
import com.kraken.api.actor.NPC;
import com.kraken.api.actor.Player;
import com.kraken.api.internal.action.Interactions;
import com.kraken.api.internal.reader.WidgetReader;
import com.kraken.api.scene.TileItem;
import com.kraken.api.scene.TileObject;
import lombok.Getter;

import java.awt.Rectangle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * An interface component. Get one from {@link Client#getWidget(int)}. A static widget is designed in the cache and has
 * a fixed id ({@link WidgetUtil#packComponentId}); a dynamic one is made by a script at run time under a static widget
 * (its root), and is told apart by its {@link #getIndex() index}.
 *
 * <p>A widget is a snapshot: its fields are read once, in one block, when it is fetched, so ask the client for it
 * again for fresh values. The getters that follow links ({@link #getParent()}, the children, {@link #isHidden()},
 * {@link #getCanvasLocation()}, {@link #getBounds()} and the group getters) read the game when called. A field this
 * build's mapping lacks reads -1, false or "".
 *
 * <p>Positions and sizes are in canvas pixels, the units of {@link Client#getCanvasWidth()}. The rest of the native
 * component's fields, which Lua's {@code IfType} names, have getters too.
 *
 * <p>The setters ({@link #setText}, {@link #setDragDeadTime}, {@link #setDragDeadZone},
 * {@link #setOnMouseRepeatListener}) change the game's component through the client's native hook, which looks the
 * component up by id on the game thread at the start of the next frame, so a write to a component that has gone since
 * does nothing. The snapshot shows the new value at once. A write made while the hook isn't connected is dropped.
 *
 * <p>Two widgets over the same native component are equal.
 */
public final class Widget {

    private static final Widget[] NONE = new Widget[0];

    /**
     * How far up the parent chain {@link #isHidden()} looks before giving up, as the screen position does.
     */
    private static final int MAX_DEPTH = 64;

    private final WidgetReader reader;

    /**
     * Where the component lives in the client: the native component's address.
     */
    @Getter
    private final long address;

    private final byte[] data;
    private final ByteBuffer buffer;
    private final int frame;

    /**
     * The text {@link #setText} set on this snapshot, or null to read the snapshot's own.
     */
    private String text;

    /**
     * A snapshot of one native component.
     * Internal: used by the API's own packages; plugins don't need it.
     *
     * @param reader  the reader the snapshot came from
     * @param address the native component's address
     * @param data    the component's fields, read in one block
     * @param frame   the client's frame counter, read with it
     */
    public Widget(WidgetReader reader, long address, byte[] data, int frame) {
        this.reader = reader;
        this.address = address;
        this.data = data;
        this.frame = frame;
        this.buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
    }

    /**
     * The widget's id: {@code group << 16 | component}. A dynamic widget has its static parent's id.
     *
     * @return the packed component id
     */
    public int getId() {
        return i32("packedId");
    }

    /**
     * The widget's type.
     *
     * @return one of the {@link WidgetType} values
     */
    public int getType() {
        return i32("type");
    }

    /**
     * The widget's parent, read now: the layer it is in, or for a group's top-level widget the component the group is
     * opened in ({@link Client#getComponentTable()}).
     *
     * @return the parent; null for a top-level widget of a group opened in no component (the top-level interface, or
     *         a group that isn't open)
     */
    public Widget getParent() {
        if (getLayerId() != -1) {
            return reader.lookup(getLayerId(), i32("layerSubId"));
        }
        int mount = reader.getMount(getId() >>> 16);
        return mount == -1 ? null : reader.lookup(mount, -1);
    }

    /**
     * The id of the widget's parent: the layer it is in, or for a group's top-level widget the component the group is
     * opened in. Read now for a top-level widget.
     *
     * @return the parent's packed id (a dynamic widget's static parent's); -1 for a top-level widget of a group opened
     *         in no component
     */
    public int getParentId() {
        return getLayerId() != -1 ? getLayerId() : reader.getMount(getId() >>> 16);
    }

    /**
     * The static root of a dynamic widget, read now. Mirrors Lua {@code IfType.staticParent}.
     *
     * @return the static root; a fresh read of this widget if it is static; null if it can't be read
     */
    public Widget getStaticParent() {
        return reader.lookup(isStatic() ? getId() : getLayerId(), -1);
    }

    /**
     * One of the dynamic children, by index, read now. It reads the one slot where {@link #getChildren()} reads them
     * all.
     *
     * @param index the child's index ({@link #getIndex()})
     * @return the child, or null if there is none directly under this widget at that index
     */
    public Widget getChild(int index) {
        return reader.child(this, index);
    }

    /**
     * The dynamic children, read now: the widgets a script created directly under this one. Each is at its
     * {@link #getIndex()}, with null where there is no child. Reads every child, so on a big container (the bank)
     * prefer {@link #getChild(int)} for a few.
     *
     * @return the children by index; empty when there are none
     */
    public Widget[] getChildren() {
        List<Widget> children = reader.children(this);
        if (children.isEmpty()) {
            return NONE;
        }
        int length = 0;
        for (Widget child : children) {
            length = Math.max(length, child.getIndex() + 1);
        }
        Widget[] byIndex = new Widget[length];
        for (Widget child : children) {
            byIndex[child.getIndex()] = child;
        }
        return byIndex;
    }

    /**
     * The dynamic children without the gaps (Lua's {@code IfType.children}). As in the client, a static layer's
     * static children aren't listed.
     *
     * @return the children in index order
     */
    public Widget[] getDynamicChildren() {
        return reader.children(this).toArray(NONE);
    }

    /**
     * The static widgets whose parent is this one, read now (the whole group is read to find them).
     *
     * @return the static children in component order; empty for a dynamic widget
     */
    public Widget[] getStaticChildren() {
        if (!isStatic()) {
            return NONE;
        }
        Widget[] group = reader.getGroup(getId() >>> 16);
        if (group == null) {
            return NONE;
        }
        List<Widget> out = new ArrayList<>();
        for (Widget component : group) {
            if (component != null && component.getLayerId() == getId() && component.getId() != getId()) {
                out.add(component);
            }
        }
        return out.toArray(NONE);
    }

    /**
     * The widget's index among its static parent's dynamic children.
     *
     * @return the index, or -1 for a static widget
     */
    public int getIndex() {
        return i32("subId");
    }

    /**
     * Whether this is a static widget, designed in the cache. Mirrors Lua {@code IfType.isStatic}.
     *
     * @return true if {@link #getIndex()} is negative
     */
    public boolean isStatic() {
        return getIndex() < 0;
    }

    /**
     * Whether the widget is hidden: by its own flag or a parent's (through the component its group is opened in, so an
     * inactive tab's interface is hidden), or because its group isn't open. Reads up the parent chain.
     *
     * @return true if the client won't draw it
     */
    public boolean isHidden() {
        Widget at = this;
        for (int depth = 0; depth < MAX_DEPTH && at != null; depth++) {
            if (at.isSelfHidden()) {
                return true;
            }
            Widget parent = at.getParent();
            if (parent == null) {
                return reader.getCoordinates(at.getId() >>> 16) == null;
            }
            at = parent;
        }
        return false;
    }

    /**
     * Whether the widget's own hidden flag is set (a hidden parent also hides it; see {@link #isHidden()}). Lua's
     * {@code IfType.hidden}.
     *
     * @return true if hidden by itself
     */
    public boolean isSelfHidden() {
        return u8("hidden") != 0;
    }

    /**
     * The widget's text.
     *
     * @return the text, with the client's markup ({@code <col=ff0000>}, {@code <br>}); "" if none
     */
    public String getText() {
        return text != null ? text : string("text");
    }

    /**
     * Sets the widget's text, as {@code cc_settext} does: the client takes the text unless the widget already shows
     * it, then marks the widget changed, so it is redrawn and anything subscribed to it hears of it. The game keeps
     * the text until something sets it again, such as the interface's own scripts or the client reloading the
     * interface.
     *
     * @param text the text, which may hold the client's markup ({@code <col=ff0000>}, {@code <br>}); null for none
     * @return this widget
     * @throws IllegalArgumentException if the text is longer than 4096 bytes as UTF-8
     */
    public Widget setText(String text) {
        String value = text == null ? "" : text;
        if (reader.writeText(this, "text", value)) {
            this.text = value;
        }
        return this;
    }

    /**
     * The widget's secondary text. Mirrors Lua {@code IfType.text2}.
     *
     * @return the text, with markup; "" if none
     */
    public String getText2() {
        return string("text2");
    }

    /**
     * The name of what the widget's actions act on, such as an item's name in an inventory slot (Lua's
     * {@code IfType.baseOpName}).
     *
     * @return the name, with markup; "" if none
     */
    public String getName() {
        return string("baseOpName");
    }

    /**
     * The colour of the widget's text or shape.
     *
     * @return the colour as {@code 0xRRGGBB}; -1 if this build's mapping lacks it
     */
    public int getTextColor() {
        return rgb("colour");
    }

    /**
     * The widget's secondary colour. Mirrors Lua {@code IfType.colour2}.
     *
     * @return the colour as {@code 0xRRGGBB}; -1 if this build's mapping lacks it
     */
    public int getTextColor2() {
        return rgb("colour2");
    }

    /**
     * The widget's colour while the mouse is over it. Mirrors Lua {@code IfType.colourOver}.
     *
     * @return the colour as {@code 0xRRGGBB}; -1 if this build's mapping lacks it
     */
    public int getTextColorOver() {
        return rgb("colourOver");
    }

    /**
     * The colour of the text's shadow. Mirrors Lua {@code IfType.shadowColour}.
     *
     * @return the colour as {@code 0xRRGGBB}; -1 if this build's mapping lacks it
     */
    public int getTextShadowColor() {
        return rgb("shadowColour");
    }

    /**
     * How see-through the widget is (Lua's {@code IfType.transparency}).
     *
     * @return 0 (opaque) to 255 (invisible)
     */
    public int getOpacity() {
        return i32("transparency");
    }

    /**
     * Whether a rectangle widget is filled.
     *
     * @return true if filled, false if outlined
     */
    public boolean isFilled() {
        return u8("fill") != 0;
    }

    /**
     * How a shape blends with what is under it. Mirrors Lua {@code IfType.blendMode}.
     *
     * @return the blend mode
     */
    public int getBlendMode() {
        return i32("blendMode");
    }

    /**
     * The width of a shape's outline or a line (Lua's {@code IfType.strokeWidth}).
     *
     * @return the width in pixels
     */
    public int getLineWidth() {
        return i32("strokeWidth");
    }

    /**
     * Whether a line runs from the bottom corner. Mirrors Lua {@code IfType.diagonalStartAtBottom}.
     *
     * @return true if it does
     */
    public boolean isDiagonalStartAtBottom() {
        return u8("diagonalStartAtBottom") != 0;
    }

    /**
     * The start angle of an arc. Mirrors Lua {@code IfType.startAngle}.
     *
     * @return the angle
     */
    public int getStartAngle() {
        return i32("startAngle");
    }

    /**
     * The stop angle of an arc. Mirrors Lua {@code IfType.stopAngle}.
     *
     * @return the angle
     */
    public int getStopAngle() {
        return i32("stopAngle");
    }

    /**
     * The sprite the widget draws (Lua's {@code IfType.graphic}).
     *
     * @return the sprite id, as in {@code gameval.SpriteID}; -1 for none
     */
    public int getSpriteId() {
        return i32("graphic");
    }

    /**
     * The widget's secondary sprite. Mirrors Lua {@code IfType.graphic2}.
     *
     * @return the sprite id, or -1 for none
     */
    public int getSpriteId2() {
        return i32("graphic2");
    }

    /**
     * The URL the widget's sprite is loaded from. Mirrors Lua {@code IfType.graphicURL}.
     *
     * @return the URL, usually ""
     */
    public String getSpriteUrl() {
        return string("graphicURL");
    }

    /**
     * How far the sprite is turned. Mirrors Lua {@code IfType.rotate}.
     *
     * @return the rotation
     */
    public int getSpriteRotation() {
        return i32("rotate");
    }

    /**
     * Whether the sprite is tiled across the widget.
     *
     * @return true if it tiles
     */
    public boolean getSpriteTiling() {
        return u8("tiling") != 0;
    }

    /**
     * The width of the sprite's outline (Lua's {@code IfType.outline}).
     *
     * @return the border type
     */
    public int getBorderType() {
        return i32("outline");
    }

    /**
     * Whether the sprite is drawn upside down.
     *
     * @return true if flipped vertically
     */
    public boolean isFlippedVertically() {
        return u8("vFlip") != 0;
    }

    /**
     * Whether the sprite is drawn mirrored.
     *
     * @return true if flipped horizontally
     */
    public boolean isFlippedHorizontally() {
        return u8("hFlip") != 0;
    }

    /**
     * The model the widget shows.
     *
     * @return the model id (an item, NPC or model id, depending on {@link #getModelType()}); -1 for none
     */
    public int getModelId() {
        return i32("model1Id");
    }

    /**
     * What {@link #getModelId()} names.
     *
     * @return the model type, the cache's value
     */
    public int getModelType() {
        return i32("model1Type");
    }

    /**
     * The widget's second model. Mirrors Lua {@code IfType.model2Id}.
     *
     * @return the model id, or -1 for none
     */
    public int getModelId2() {
        return i32("model2Id");
    }

    /**
     * What {@link #getModelId2()} names. Mirrors Lua {@code IfType.model2Type}.
     *
     * @return the model type
     */
    public int getModelType2() {
        return i32("model2Type");
    }

    /**
     * The model's animation.
     *
     * @return the animation id, or -1
     */
    public int getAnimationId() {
        return i32("modelAnim");
    }

    /**
     * The second model's animation. Mirrors Lua {@code IfType.modelAnim2}.
     *
     * @return the animation id, or -1
     */
    public int getAnimationId2() {
        return i32("modelAnim2");
    }

    /**
     * The model's x offset. Mirrors Lua {@code IfType.modelXOf}.
     *
     * @return the offset
     */
    public int getModelOffsetX() {
        return i32("modelXOf");
    }

    /**
     * The model's y offset. Mirrors Lua {@code IfType.modelYOf}.
     *
     * @return the offset
     */
    public int getModelOffsetY() {
        return i32("modelYOf");
    }

    /**
     * The model's rotation about x.
     *
     * @return the angle, 2048 per turn
     */
    public int getRotationX() {
        return i32("modelXAn");
    }

    /**
     * The model's rotation about y.
     *
     * @return the angle, 2048 per turn
     */
    public int getRotationY() {
        return i32("modelYAn");
    }

    /**
     * The model's rotation about z.
     *
     * @return the angle, 2048 per turn
     */
    public int getRotationZ() {
        return i32("modelZAn");
    }

    /**
     * How far the model is zoomed.
     *
     * @return the zoom
     */
    public int getModelZoom() {
        return i32("modelZoom");
    }

    /**
     * The model's width. Mirrors Lua {@code IfType.modelWid}.
     *
     * @return the width
     */
    public int getModelWidth() {
        return i32("modelWid");
    }

    /**
     * The model's height. Mirrors Lua {@code IfType.modelHei}.
     *
     * @return the height
     */
    public int getModelHeight() {
        return i32("modelHei");
    }

    /**
     * How fast the model spins. Mirrors Lua {@code IfType.modelSpin}.
     *
     * @return the spin
     */
    public int getModelSpin() {
        return i32("modelSpin");
    }

    /**
     * Whether the model is drawn orthographically. Mirrors Lua {@code IfType.orthog}.
     *
     * @return true if it is
     */
    public boolean isOrthographic() {
        return u8("orthog") != 0;
    }

    /**
     * The item an inventory slot widget shows (Lua's {@code IfType.linkObjType}).
     *
     * @return the item id, or -1 for none
     */
    public int getItemId() {
        return i32("linkObjType");
    }

    /**
     * The size of the stack {@link #getItemId()} shows (Lua's {@code IfType.linkObjCount}).
     *
     * @return the quantity
     */
    public int getItemQuantity() {
        return i32("linkObjCount");
    }

    /**
     * The widget's right-click actions (Lua's {@code IfType.opNames}).
     *
     * @return the actions by slot, null for an empty slot; null if the widget has none
     */
    public String[] getActions() {
        List<String> ops = reader.opNames(i32("opCount"), i64("opNames"));
        if (ops.isEmpty()) {
            return null;
        }
        String[] actions = new String[ops.size()];
        for (int i = 0; i < actions.length; i++) {
            String op = ops.get(i);
            actions[i] = op == null || op.isEmpty() ? null : op;
        }
        return actions;
    }

    /**
     * Runs one of the widget's options, as clicking it in the menu does. An item slot with no ops of its own offers
     * Use followed by the item's inventory actions. The option is matched loosely: markup and case are ignored, and
     * spaces, {@code -} and {@code _} count as the same, so "withdraw 10" matches "Withdraw-10".
     *
     * @param option the option's text, such as "Withdraw-10"
     * @return {@link ActionResult#SENT}, or why nothing was sent: {@link ActionResult#GONE} once the widget isn't
     *         loaded or its slot holds another item, {@link ActionResult#NO_SUCH_OPTION} if it has no such option
     * @throws NullPointerException if the option is null
     */
    public ActionResult interact(String option) {
        return interactions().interact(this, option, null);
    }

    /**
     * Runs one of the widget's options with one of the option's sub-options, the rows of the sub-menu the client
     * opens under the option ({@link #getSubOptions}). Both are matched as {@link #interact(String)} matches.
     *
     * @param option    the option's text, such as "Drop"
     * @param subOption the sub-option's text, such as "All"
     * @return {@link ActionResult#SENT}, or why nothing was sent; {@link ActionResult#NO_SUCH_OPTION} if the option or
     *         its sub-option isn't there
     * @throws NullPointerException if the option or sub-option is null
     */
    public ActionResult interact(String option, String subOption) {
        Objects.requireNonNull(subOption, "subOption");
        return interactions().interact(this, option, subOption);
    }

    /**
     * Runs one of the widget's options by its number, as the menu numbers them.
     *
     * @param op the op, 1-10
     * @return {@link ActionResult#SENT}, or why nothing was sent; {@link ActionResult#NO_SUCH_OPTION} for a number
     *         out of range or an empty slot
     */
    public ActionResult interact(int op) {
        return interactions().interact(this, op, 0);
    }

    /**
     * Runs one of the widget's options by its number with one of its sub-options.
     *
     * @param op    the op, 1-10
     * @param subOp the sub-option, 1-based as {@link #getSubOptions} lists them
     * @return {@link ActionResult#SENT}, or why nothing was sent; {@link ActionResult#NO_SUCH_OPTION} if the op or the
     *         sub-option isn't there
     */
    public ActionResult interact(int op, int subOp) {
        return interactions().interact(this, op, subOp);
    }

    /**
     * Clicks the widget: runs the first of these it has: its first named option, its pause button ("Click here to
     * continue") or its old-style button's action (by {@link #getEvent()}: a plain, close, toggle or set button).
     *
     * @return {@link ActionResult#SENT}, or why nothing was sent; {@link ActionResult#NO_SUCH_OPTION} if it has none
     *         of them
     */
    public ActionResult click() {
        return interactions().click(this);
    }

    /**
     * Uses the widget's item or spell on an NPC, as Use (or a spell's Cast) and a click on the NPC do: the widget is
     * selected with its slot and item and the NPC's targeted row runs, in one batch. A spellbook component is a
     * static widget with no item, so it selects with -1 for both, as the spellbook does.
     *
     * @param target the NPC
     * @return {@link ActionResult#SENT}, or why nothing was sent; {@link ActionResult#NO_SUCH_OPTION} if the widget's
     *         target mask ({@link #getClickMask()}, {@link WidgetConfig#USE_NPC}) leaves out NPCs
     */
    public ActionResult useOn(NPC target) {
        return interactions().useOn(this, target);
    }

    /**
     * Uses the widget's item or spell on a player; see {@link #useOn(NPC)}.
     *
     * @param target the player
     * @return {@link ActionResult#SENT}, or why nothing was sent; {@link ActionResult#NO_SUCH_OPTION} if the widget's
     *         target mask leaves out players ({@link WidgetConfig#USE_PLAYER})
     */
    public ActionResult useOn(Player target) {
        return interactions().useOn(this, target);
    }

    /**
     * Uses the widget's item or spell on an object, at the tile it was placed on; see {@link #useOn(NPC)}.
     *
     * @param target the object
     * @return {@link ActionResult#SENT}, or why nothing was sent; {@link ActionResult#NO_SUCH_OPTION} if the widget's
     *         target mask leaves out objects ({@link WidgetConfig#USE_OBJECT})
     */
    public ActionResult useOn(TileObject target) {
        return interactions().useOn(this, target);
    }

    /**
     * Uses the widget's item or spell on an item on the ground; see {@link #useOn(NPC)}.
     *
     * @param target the ground item
     * @return {@link ActionResult#SENT}, or why nothing was sent; {@link ActionResult#NO_SUCH_OPTION} if the widget's
     *         target mask leaves out ground items ({@link WidgetConfig#USE_GROUND_ITEM})
     */
    public ActionResult useOn(TileItem target) {
        return interactions().useOn(this, target);
    }

    /**
     * Uses the widget's item or spell on another widget, such as one item on another, or High Alchemy on an inventory
     * item; see {@link #useOn(NPC)}.
     *
     * @param target the widget to use it on
     * @return {@link ActionResult#SENT}, or why nothing was sent; {@link ActionResult#NO_SUCH_OPTION} if this widget's
     *         target mask leaves out widgets ({@link WidgetConfig#USE_WIDGET}) or the target doesn't take a selection
     *         ({@link WidgetConfig#WIDGET_USE_TARGET})
     */
    public ActionResult useOn(Widget target) {
        return interactions().useOn(this, target);
    }

    private Interactions interactions() {
        return reader.getClient().getInteractions();
    }

    /**
     * The widget's key listener ({@code IfType.onKey}, set by {@code cc_setonkey}): its arguments, the script id
     * first. A component with one takes the keyboard, as the chatbox does.
     *
     * @return the listener's arguments, or null if the widget has no key listener or this build's mapping lacks the
     * listener layout
     */
    public Object[] getOnKeyListener() {
        return reader.listener(data, "onKey");
    }

    /**
     * The widget's mouse-repeat listener ({@code IfType.onMouseRepeat}, set by {@code cc_setonmouserepeat}): the
     * script the client runs every client cycle while the mouse is over the widget, and its arguments.
     *
     * @return the listener's arguments, the script id first; null if the widget has none or this build's mapping
     * lacks the listener layout
     */
    public Object[] getOnMouseRepeatListener() {
        return reader.listener(data, "onMouseRepeat");
    }

    /**
     * Clears the widget's mouse-repeat listener, so the client stops running it, as {@code cc_setonmouserepeat} with
     * no script does. The client frees the listener's arguments itself. Only clearing is supported: the API can't
     * build a script's arguments in the game.
     *
     * @param args null or no arguments, to clear the listener
     * @throws UnsupportedOperationException if arguments are given
     */
    public void setOnMouseRepeatListener(Object... args) {
        if (args != null && args.length > 0) {
            throw new UnsupportedOperationException("only clearing a mouse-repeat listener is supported");
        }
        reader.clearListener(this, data, "onMouseRepeat");
    }

    /**
     * How long the mouse must be held on the widget before a drag starts ({@code IfType.dragDeadTime}, set by
     * {@code cc_setdragdeadtime}).
     *
     * @return the time in client cycles (20 ms each); -1 if this build's mapping lacks it
     */
    public int getDragDeadTime() {
        return i32("dragDeadTime");
    }

    /**
     * Sets how long the mouse must be held on the widget before a drag starts.
     *
     * @param deadTime the time in client cycles (20 ms each)
     */
    public void setDragDeadTime(int deadTime) {
        reader.writeInt(this, data, "dragDeadTime", deadTime);
    }

    /**
     * How far the mouse must move while held on the widget before a drag starts ({@code IfType.dragDeadZone}, set by
     * {@code cc_setdragdeadzone}).
     *
     * @return the distance in pixels, along either axis; -1 if this build's mapping lacks it
     */
    public int getDragDeadZone() {
        return i32("dragDeadZone");
    }

    /**
     * Sets how far the mouse must move while held on the widget before a drag starts.
     *
     * @param deadZone the distance in pixels, along either axis
     */
    public void setDragDeadZone(int deadZone) {
        reader.writeInt(this, data, "dragDeadZone", deadZone);
    }

    /**
     * The sub-options of one of the widget's actions: the rows of the sub-menu the client opens under the action's
     * row, in order ({@code IfType.subOpNames}). A menu entry of the action with a sub-option names one of these,
     * 1-based, and the client sends the op with the sub-option.
     *
     * @param op the action, 1-based as {@link #getActions()} numbers them
     * @return the sub-option names; null if the action has no sub-menu or this build's mapping lacks the table
     */
    public String[] getSubOptions(int op) {
        List<String> names = reader.subOpNames(i32("subOpCount"), i64("subOpNames"), op);
        return names.isEmpty() ? null : names.toArray(new String[0]);
    }

    /**
     * The widget's click mask, the bits {@link WidgetConfig} names: whether it is a pause button, which of its first
     * ten ops go to the server, what a spell or item selected from it can be used on (its target mask), how it drags
     * and whether a selection can be used on it. Read now, as the client's menu reads it: the events the server set
     * for this widget's slot when it set any, else the widget's own ({@code IfType.events}). {@code cc_gettargetmask}
     * pushes bits 11-16 of it.
     *
     * @return the click mask; the widget's own when the server's can't be read; -1 if the server set none and this
     *         build's mapping lacks the widget's own
     */
    public int getClickMask() {
        return reader.clickMask(getId(), getIndex(), i32("events"));
    }

    /**
     * The widget's x relative to its parent, as laid out.
     *
     * @return x in canvas pixels
     */
    public int getRelativeX() {
        return i32("x");
    }

    /**
     * The widget's y relative to its parent, as laid out.
     *
     * @return y in canvas pixels
     */
    public int getRelativeY() {
        return i32("y");
    }

    /**
     * The widget's width, as laid out.
     *
     * @return the width in canvas pixels
     */
    public int getWidth() {
        return i32("width");
    }

    /**
     * The widget's height, as laid out.
     *
     * @return the height in canvas pixels
     */
    public int getHeight() {
        return i32("height");
    }

    /**
     * The widget's x as designed, before {@link #getXPositionMode()} applies.
     *
     * @return the designed x
     */
    public int getOriginalX() {
        return i32("dataX");
    }

    /**
     * The widget's y as designed, before {@link #getYPositionMode()} applies.
     *
     * @return the designed y
     */
    public int getOriginalY() {
        return i32("dataY");
    }

    /**
     * The widget's width as designed, before {@link #getWidthMode()} applies.
     *
     * @return the designed width
     */
    public int getOriginalWidth() {
        return i32("dataWidth");
    }

    /**
     * The widget's height as designed, before {@link #getHeightMode()} applies.
     *
     * @return the designed height
     */
    public int getOriginalHeight() {
        return i32("dataHeight");
    }

    /**
     * How the widget's x is measured.
     *
     * @return a {@link WidgetPositionMode} value
     */
    public int getXPositionMode() {
        return i32("modeX");
    }

    /**
     * How the widget's y is measured.
     *
     * @return a {@link WidgetPositionMode} value
     */
    public int getYPositionMode() {
        return i32("modeY");
    }

    /**
     * How the widget's width is measured.
     *
     * @return a {@link WidgetSizeMode} value
     */
    public int getWidthMode() {
        return i32("modeWidth");
    }

    /**
     * How the widget's height is measured.
     *
     * @return a {@link WidgetSizeMode} value
     */
    public int getHeightMode() {
        return i32("modeHeight");
    }

    /**
     * The widget's aspect ratio pair. Mirrors Lua {@code IfType.aspectRatio}.
     *
     * @return the pair as x and y, or null if this build's mapping lacks it
     */
    public Point getAspectRatio() {
        return pair("aspectRatio");
    }

    /**
     * How far a scrolling layer is scrolled across.
     *
     * @return the scroll x, or -1 if this build's mapping lacks it
     */
    public int getScrollX() {
        Point pos = pair("scrollPos");
        return pos == null ? -1 : pos.getX();
    }

    /**
     * How far a scrolling layer is scrolled down.
     *
     * @return the scroll y, or -1 if this build's mapping lacks it
     */
    public int getScrollY() {
        Point pos = pair("scrollPos");
        return pos == null ? -1 : pos.getY();
    }

    /**
     * The width of a scrolling layer's content.
     *
     * @return the scroll width, or -1 if this build's mapping lacks it
     */
    public int getScrollWidth() {
        Point size = pair("scrollSize");
        return size == null ? -1 : size.getX();
    }

    /**
     * The height of a scrolling layer's content.
     *
     * @return the scroll height, or -1 if this build's mapping lacks it
     */
    public int getScrollHeight() {
        Point size = pair("scrollSize");
        return size == null ? -1 : size.getY();
    }

    /**
     * Where the widget's top-left corner is on the canvas, read now: the client's sum of the relative positions up the
     * parent chain, plus where its group is drawn (Lua's {@code IfType.screenCoords}). Like the client's, it doesn't
     * take off the scroll of scrolling parents.
     *
     * @return the corner in canvas pixels, or null if it can't be read
     */
    public Point getCanvasLocation() {
        return reader.screenCoords(this);
    }

    /**
     * The widget's area on the canvas, read now.
     *
     * @return {@link #getCanvasLocation()} with the widget's size, or null if the location can't be read
     */
    public Rectangle getBounds() {
        Point corner = getCanvasLocation();
        return corner == null ? null : new Rectangle(corner.getX(), corner.getY(), getWidth(), getHeight());
    }

    /**
     * Whether a canvas point is on the widget.
     *
     * @param point the point, in canvas pixels
     * @return true if it is inside {@link #getBounds()}
     */
    public boolean contains(Point point) {
        Rectangle bounds = getBounds();
        return bounds != null && bounds.contains(point.getX(), point.getY());
    }

    /**
     * Whether the client drew the widget in its latest frame when the widget was read: its draw stamp equals the
     * client's frame counter. Mirrors Lua {@code IfType.isRendering}.
     *
     * @return true if it was drawn
     */
    public boolean isRendering() {
        return frame >= 0 && i32("renderCycle") == frame;
    }

    /**
     * The label the widget's descriptor carries. Mirrors Lua {@code IfType.sourceName}.
     *
     * @return the label, usually ""
     */
    public String getSourceName() {
        return string("sourceName");
    }

    /**
     * The first index (or, for a static layer, component index) that may belong to this layer. Mirrors Lua
     * {@code IfType.layerIdXMin}.
     *
     * @return the lowest child index
     */
    public int getLayerIdXMin() {
        return i32("layerIdXMin");
    }

    /**
     * The last index (or component index) that may belong to this layer. Mirrors Lua
     * {@code IfType.layerIdXMax}.
     *
     * @return the highest child index
     */
    public int getLayerIdXMax() {
        return i32("layerIdXMax");
    }

    /**
     * The widget's event field. Mirrors Lua {@code IfType.event}.
     *
     * @return the event
     */
    public int getEvent() {
        return i32("event");
    }

    /**
     * The widget's event code. Mirrors Lua {@code IfType.eventCode}.
     *
     * @return the event code
     */
    public int getEventCode() {
        return i32("eventCode");
    }

    /**
     * Whether the widget belongs to the entity overlay system. Mirrors Lua
     * {@code IfType.isEntityOverlay}.
     *
     * @return true if it does
     */
    public boolean isEntityOverlay() {
        return u8("isEntityOverlay") != 0;
    }

    /**
     * The last cycle the client processed the widget. Mirrors Lua {@code IfType.lastProcess}.
     *
     * @return the cycle
     */
    public int getLastProcess() {
        return i32("lastProcess");
    }

    /**
     * The widget's overlay type. Mirrors Lua {@code IfType.overlayType}.
     *
     * @return the overlay type
     */
    public int getOverlayType() {
        return i32("overlayType");
    }

    /**
     * The widget's overlay id. Mirrors Lua {@code IfType.overlayId}.
     *
     * @return the overlay id
     */
    public int getOverlayId() {
        return i32("overlayId");
    }

    /**
     * The clipping rectangle of the widget's group, as the client draws the group: nothing of the group shows outside
     * it. Reads Lua's {@code InterfaceCoordinates} now.
     *
     * @return the rectangle in canvas pixels, or null if the group isn't open
     */
    public Rectangle getGroupClipBounds() {
        WidgetReader.Coordinates group = reader.getCoordinates(getId() >>> 16);
        return group == null ? null : new Rectangle(group.clipX1(), group.clipY1(), group.clipX2() - group.clipX1(),
                group.clipY2() - group.clipY1());
    }

    /**
     * The offset the client draws every widget of this one's group at. Reads Lua's {@code InterfaceCoordinates} now.
     *
     * @return the offset in canvas pixels, or null if the group isn't open
     */
    public Point getGroupOffset() {
        WidgetReader.Coordinates group = reader.getCoordinates(getId() >>> 16);
        return group == null ? null : new Point(group.offX(), group.offY());
    }

    /**
     * The extra offset a Lua plugin gave this widget's group ({@code setRenderOffset}). Reads Lua's
     * {@code InterfaceCoordinates} now.
     *
     * @return the offset in canvas pixels, or null if the group isn't open
     */
    public Point getGroupScriptOffset() {
        WidgetReader.Coordinates group = reader.getCoordinates(getId() >>> 16);
        return group == null ? null : new Point(group.scriptOffX(), group.scriptOffY());
    }

    /**
     * The packed id of the layer the widget is in, within its group (a dynamic widget's static root's). Unlike
     * {@link #getParentId()} it doesn't look up the component a group is opened in, so it reads nothing. Mirrors Lua
     * {@code IfType.layerId}.
     *
     * @return the layer's id, or -1 for a group's top-level widget
     */
    public int getLayerId() {
        return i32("layerId");
    }

    /**
     * A 32-bit field of the snapshot.
     *
     * @param field the {@code IfType} field
     * @return its value, or -1 if the mapping lacks it
     */
    int i32(String field) {
        int off = reader.field(field);
        return off < 0 || off + 4 > data.length ? -1 : buffer.getInt(off);
    }

    /**
     * A 64-bit field of the snapshot.
     *
     * @param field the {@code IfType} field
     * @return its value, or -1 if the mapping lacks it
     */
    long i64(String field) {
        int off = reader.field(field);
        return off < 0 || off + 8 > data.length ? -1 : buffer.getLong(off);
    }

    private int u8(String field) {
        int off = reader.field(field);
        return off < 0 || off >= data.length ? 0 : data[off] & 0xff;
    }

    private String string(String field) {
        return reader.string(data, reader.field(field));
    }

    private Point pair(String field) {
        int off = reader.field(field);
        return off < 0 || off + 8 > data.length ? null : new Point(buffer.getInt(off), buffer.getInt(off + 4));
    }

    private int rgb(String field) {
        int off = reader.field(field);
        return off < 0 || off + 4 > data.length ? -1 : buffer.getInt(off) & 0xffffff;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Widget widget && widget.address == address;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(address);
    }

    /**
     * The id, as the string form writes it.
     *
     * @return {@code Widget[group:component]} for a static widget, {@code Widget[group:component[index]]} for a
     *         dynamic one
     */
    @Override
    public String toString() {
        String id = (getId() >>> 16) + ":" + (getId() & 0xffff);
        return "Widget[" + (isStatic() ? id : id + "[" + getIndex() + "]") + "]";
    }
}
