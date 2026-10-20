package com.kraken.api.lua;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/**
 * Mirrors the client's Lua {@code osrs.IfType}: one interface component. A static component is designed in the cache
 * and has a fixed id ({@code group:component}); a dynamic one is made by a script at run time under a static
 * component (its root), and is told apart by its {@link #getSubId() sub id}.
 *
 * <p>Unlike the Lua object, which reads through to the live component, this is a snapshot of the component's fields
 * taken when it was retrieved (one read): retrieve it again for fresh values. The getters that follow links
 * ({@link #getParent()}, {@link #getChildren()}, {@link #getStaticParent()}, {@link #getScreenCoords()}) read the
 * game when called. A field this build's mapping lacks reads -1, false or "".
 *
 * <p>Positions and sizes are in client pixels, the units of {@link Graphics#clientDimensions()}.
 */
public final class IfType {

    /**
     * {@code IfTypes.LAYER}: a container. Only a layer has children.
     */
    public static final int LAYER = 0;

    /**
     * {@code IfTypes.INPUTBOX}.
     */
    public static final int INPUTBOX = 1;

    /**
     * {@code IfTypes.RECT}: a filled or outlined rectangle.
     */
    public static final int RECT = 3;

    /**
     * {@code IfTypes.TEXT}.
     */
    public static final int TEXT = 4;

    /**
     * {@code IfTypes.GRAPHIC}: a sprite, or an item in an inventory slot.
     */
    public static final int GRAPHIC = 5;

    /**
     * {@code IfTypes.MODEL}.
     */
    public static final int MODEL = 6;

    /**
     * {@code IfTypes.TOOLTIP}.
     */
    public static final int TOOLTIP = 8;

    /**
     * {@code IfTypes.LINE}.
     */
    public static final int LINE = 9;

    /**
     * {@code IfTypes.CIRCLE}.
     */
    public static final int CIRCLE = 10;

    /**
     * {@code IfTypes.CRMVIEW}.
     */
    public static final int CRMVIEW = 11;

    /**
     * {@code IfTypes.INPUTFIELD} (also {@code MAX_TYPE}).
     */
    public static final int INPUTFIELD = 12;

    private final InterfaceQuery query;
    private final long address;
    private final byte[] data;
    private final ByteBuffer buffer;
    private final int frame;

    IfType(InterfaceQuery query, long address, byte[] data, int frame) {
        this.query = query;
        this.address = address;
        this.data = data;
        this.frame = frame;
        this.buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
    }

    /**
     * The name of an {@code IfTypes} value.
     *
     * @param type the type, e.g. {@link #getType()}
     * @return its Lua name, e.g. "LAYER", or "TYPE_n" for a value Lua doesn't name
     */
    public static String typeName(int type) {
        return switch (type) {
            case LAYER -> "LAYER";
            case INPUTBOX -> "INPUTBOX";
            case RECT -> "RECT";
            case TEXT -> "TEXT";
            case GRAPHIC -> "GRAPHIC";
            case MODEL -> "MODEL";
            case TOOLTIP -> "TOOLTIP";
            case LINE -> "LINE";
            case CIRCLE -> "CIRCLE";
            case CRMVIEW -> "CRMVIEW";
            case INPUTFIELD -> "INPUTFIELD";
            default -> "TYPE_" + type;
        };
    }

    /**
     * Mirrors {@code IfType.id}: the group the component belongs to.
     *
     * @return the group id
     */
    public int getId() {
        return getPackedId() >> 16;
    }

    /**
     * Mirrors {@code IfType.componentIndex}: the component's index in its group, or its static root's for a dynamic
     * component.
     *
     * @return the component index
     */
    public int getComponentIndex() {
        return getPackedId() & 0xffff;
    }

    /**
     * The group and component index in one int, as the client keys components (RuneLite's widget id). For a dynamic
     * component, its static root's. Not in Lua.
     *
     * @return {@code group << 16 | component}
     */
    public int getPackedId() {
        return i32("packedId");
    }

    /**
     * Mirrors {@code IfType.subId}.
     *
     * @return -1 for a static component; for a dynamic one, its index among its static root's dynamic children
     */
    public int getSubId() {
        return i32("subId");
    }

    /**
     * Mirrors {@code IfType.isStatic}.
     *
     * @return true for a static component ({@link #getSubId()} is negative)
     */
    public boolean isStatic() {
        return getSubId() < 0;
    }

    /**
     * Mirrors {@code IfType.type}.
     *
     * @return the component type, one of the constants such as {@link #LAYER} or {@link #TEXT}
     */
    public int getType() {
        return i32("type");
    }

    /**
     * Mirrors {@code IfType.layerId}: the packed id of the component's parent. A dynamic component holds its static
     * root's id here, and its direct parent's sub id in a field Lua doesn't expose.
     *
     * @return the parent's packed id, or -1 for a top-level component
     */
    public int getLayerId() {
        return i32("layerId");
    }

    /**
     * Mirrors {@code IfType.layerIdXMin}.
     *
     * @return the first sub id (or, for a static layer, component index) that may belong to this layer
     */
    public int getLayerIdXMin() {
        return i32("layerIdXMin");
    }

    /**
     * Mirrors {@code IfType.layerIdXMax}.
     *
     * @return the last sub id (or component index) that may belong to this layer
     */
    public int getLayerIdXMax() {
        return i32("layerIdXMax");
    }

    /**
     * Mirrors {@code IfType.parent}: the direct parent, looked up now. Unlike what the Lua docs say, the client
     * returns the parent of a static component too; only a top-level component has none.
     *
     * @return the parent, or null for a top-level component or if it can't be read
     */
    public IfType getParent() {
        return getLayerId() == -1 ? null : query.lookup(getLayerId(), i32("layerSubId"));
    }

    /**
     * Mirrors {@code IfType.staticParent}: the static root of a dynamic component, looked up now.
     *
     * @return the static root, a fresh read of this component if it is static, or null if it can't be read
     */
    public IfType getStaticParent() {
        return query.lookup(isStatic() ? getPackedId() : getLayerId(), -1);
    }

    /**
     * Mirrors {@code IfType.children}: the dynamic children whose direct parent is this component, looked up now. As
     * in the client, a static layer's static children (the components of its group whose {@link #getLayerId()} is this
     * one's id) aren't listed.
     *
     * @return the children in sub id order; empty unless this is a {@link #LAYER}
     */
    public List<IfType> getChildren() {
        return query.children(this);
    }

    /**
     * Mirrors {@code IfType.isRendering}: whether the client had drawn the component in its latest frame when the
     * snapshot was taken (its draw stamp equals the client's frame counter).
     *
     * @return true if it was drawn
     */
    public boolean isRendering() {
        return frame >= 0 && i32("renderCycle") == frame;
    }

    /**
     * Mirrors {@code IfType.screenCoords}: the top-left corner on screen, the client's sum of {@link #getX()},
     * {@link #getY()} up the parent chain plus the group's offsets ({@link InterfaceCoordinates}). Read now. Like the
     * client, it doesn't subtract the scroll position of scrolling parents.
     *
     * @return the corner in client pixels, or null if it can't be read
     */
    public Vector2 getScreenCoords() {
        return query.screenCoords(this);
    }

    /**
     * Mirrors {@code IfType.x}.
     *
     * @return x relative to the parent
     */
    public int getX() {
        return i32("x");
    }

    /**
     * Mirrors {@code IfType.y}.
     *
     * @return y relative to the parent
     */
    public int getY() {
        return i32("y");
    }

    /**
     * Mirrors {@code IfType.width}.
     *
     * @return the width
     */
    public int getWidth() {
        return i32("width");
    }

    /**
     * Mirrors {@code IfType.height}.
     *
     * @return the height
     */
    public int getHeight() {
        return i32("height");
    }

    /**
     * Mirrors {@code IfType.hidden}: hidden by itself (a hidden parent also hides it; see {@link #isRendering()}).
     *
     * @return true if hidden
     */
    public boolean isHidden() {
        return u8("hidden") != 0;
    }

    /**
     * Mirrors {@code IfType.modeX}.
     *
     * @return how x is measured ({@code InterfaceQuery.AlignMode})
     */
    public int getModeX() {
        return i32("modeX");
    }

    /**
     * Mirrors {@code IfType.modeY}.
     *
     * @return how y is measured ({@code AlignMode})
     */
    public int getModeY() {
        return i32("modeY");
    }

    /**
     * Mirrors {@code IfType.modeWidth}.
     *
     * @return how the width is measured ({@code InterfaceQuery.SizeMode})
     */
    public int getModeWidth() {
        return i32("modeWidth");
    }

    /**
     * Mirrors {@code IfType.modeHeight}.
     *
     * @return how the height is measured ({@code SizeMode})
     */
    public int getModeHeight() {
        return i32("modeHeight");
    }

    /**
     * Mirrors {@code IfType.dataX}.
     *
     * @return x as designed, before the mode is applied
     */
    public int getDataX() {
        return i32("dataX");
    }

    /**
     * Mirrors {@code IfType.dataY}.
     *
     * @return y as designed
     */
    public int getDataY() {
        return i32("dataY");
    }

    /**
     * Mirrors {@code IfType.dataWidth}.
     *
     * @return the width as designed
     */
    public int getDataWidth() {
        return i32("dataWidth");
    }

    /**
     * Mirrors {@code IfType.dataHeight}.
     *
     * @return the height as designed
     */
    public int getDataHeight() {
        return i32("dataHeight");
    }

    /**
     * Mirrors {@code IfType.aspectRatio}.
     *
     * @return the aspect ratio pair, or null if this build's mapping lacks it
     */
    public Vector2 getAspectRatio() {
        return pair("aspectRatio");
    }

    /**
     * Mirrors {@code IfType.scrollPos}.
     *
     * @return the scroll position, or null if this build's mapping lacks it
     */
    public Vector2 getScrollPos() {
        return pair("scrollPos");
    }

    /**
     * Mirrors {@code IfType.scrollSize}.
     *
     * @return the scrollable size, or null if this build's mapping lacks it
     */
    public Vector2 getScrollSize() {
        return pair("scrollSize");
    }

    /**
     * Mirrors {@code IfType.text}.
     *
     * @return the text of a {@link #TEXT} component, with the client's markup ({@code <col=ff0000>}, {@code <br>})
     */
    public String getText() {
        return string("text");
    }

    /**
     * Mirrors {@code IfType.text2}.
     *
     * @return the secondary text
     */
    public String getText2() {
        return string("text2");
    }

    /**
     * Mirrors {@code IfType.sourceName}.
     *
     * @return the descriptor's label, usually ""
     */
    public String getSourceName() {
        return string("sourceName");
    }

    /**
     * Mirrors {@code IfType.opNames}: the right-click options.
     *
     * @return the op names in slot order; a slot without an op is ""
     */
    public List<String> getOpNames() {
        return query.opNames(this);
    }

    /**
     * Mirrors {@code IfType.baseOpName}: the name of what the ops act on, e.g. an item's name.
     *
     * @return the name, with markup
     */
    public String getBaseOpName() {
        return string("baseOpName");
    }

    /**
     * Mirrors {@code IfType.graphic}.
     *
     * @return the sprite id, or -1
     */
    public int getGraphic() {
        return i32("graphic");
    }

    /**
     * Mirrors {@code IfType.graphic2}.
     *
     * @return the secondary sprite id, or -1
     */
    public int getGraphic2() {
        return i32("graphic2");
    }

    /**
     * Mirrors {@code IfType.graphicURL}.
     *
     * @return the sprite URL, usually ""
     */
    public String getGraphicURL() {
        return string("graphicURL");
    }

    /**
     * Mirrors {@code IfType.transparency}.
     *
     * @return 0 (opaque) to 255 (transparent)
     */
    public int getTransparency() {
        return i32("transparency");
    }

    /**
     * Mirrors {@code IfType.colour}.
     *
     * @return the colour, or null if this build's mapping lacks it
     */
    public Colour getColour() {
        return colour("colour");
    }

    /**
     * Mirrors {@code IfType.colour2}.
     *
     * @return the secondary colour, or null if this build's mapping lacks it
     */
    public Colour getColour2() {
        return colour("colour2");
    }

    /**
     * Mirrors {@code IfType.colourOver}.
     *
     * @return the colour while the mouse is over the component, or null if this build's mapping lacks it
     */
    public Colour getColourOver() {
        return colour("colourOver");
    }

    /**
     * Mirrors {@code IfType.shadowColour}.
     *
     * @return the text shadow colour, or null if this build's mapping lacks it
     */
    public Colour getShadowColour() {
        return colour("shadowColour");
    }

    /**
     * Mirrors {@code IfType.fill}.
     *
     * @return true if a shape is filled
     */
    public boolean isFill() {
        return u8("fill") != 0;
    }

    /**
     * Mirrors {@code IfType.blendMode}.
     *
     * @return the blend mode ({@code InterfaceQuery.BlendMode})
     */
    public int getBlendMode() {
        return i32("blendMode");
    }

    /**
     * Mirrors {@code IfType.strokeWidth}.
     *
     * @return the outline width of a shape
     */
    public int getStrokeWidth() {
        return i32("strokeWidth");
    }

    /**
     * Mirrors {@code IfType.diagonalStartAtBottom}.
     *
     * @return true if a line runs from the bottom corner
     */
    public boolean isDiagonalStartAtBottom() {
        return u8("diagonalStartAtBottom") != 0;
    }

    /**
     * Mirrors {@code IfType.startAngle}.
     *
     * @return the start angle of an arc
     */
    public int getStartAngle() {
        return i32("startAngle");
    }

    /**
     * Mirrors {@code IfType.stopAngle}.
     *
     * @return the stop angle of an arc
     */
    public int getStopAngle() {
        return i32("stopAngle");
    }

    /**
     * Mirrors {@code IfType.rotate}.
     *
     * @return the sprite rotation
     */
    public int getRotate() {
        return i32("rotate");
    }

    /**
     * Mirrors {@code IfType.tiling}.
     *
     * @return true if the sprite tiles
     */
    public boolean isTiling() {
        return u8("tiling") != 0;
    }

    /**
     * Mirrors {@code IfType.outline}.
     *
     * @return the sprite outline width
     */
    public int getOutline() {
        return i32("outline");
    }

    /**
     * Mirrors {@code IfType.vFlip}.
     *
     * @return true if the sprite is flipped vertically
     */
    public boolean isVFlip() {
        return u8("vFlip") != 0;
    }

    /**
     * Mirrors {@code IfType.hFlip}.
     *
     * @return true if the sprite is flipped horizontally
     */
    public boolean isHFlip() {
        return u8("hFlip") != 0;
    }

    /**
     * Mirrors {@code IfType.model1Type}.
     *
     * @return what {@link #getModel1Id()} names ({@code InterfaceQuery.ModelTypes})
     */
    public int getModel1Type() {
        return i32("model1Type");
    }

    /**
     * Mirrors {@code IfType.model1Id}.
     *
     * @return the model id
     */
    public int getModel1Id() {
        return i32("model1Id");
    }

    /**
     * Mirrors {@code IfType.model2Type}.
     *
     * @return what {@link #getModel2Id()} names
     */
    public int getModel2Type() {
        return i32("model2Type");
    }

    /**
     * Mirrors {@code IfType.model2Id}.
     *
     * @return the second model id
     */
    public int getModel2Id() {
        return i32("model2Id");
    }

    /**
     * Mirrors {@code IfType.modelAnim}.
     *
     * @return the model's animation, or -1
     */
    public int getModelAnim() {
        return i32("modelAnim");
    }

    /**
     * Mirrors {@code IfType.modelAnim2}.
     *
     * @return the second model's animation, or -1
     */
    public int getModelAnim2() {
        return i32("modelAnim2");
    }

    /**
     * Mirrors {@code IfType.modelXOf}.
     *
     * @return the model's x offset
     */
    public int getModelXOf() {
        return i32("modelXOf");
    }

    /**
     * Mirrors {@code IfType.modelYOf}.
     *
     * @return the model's y offset
     */
    public int getModelYOf() {
        return i32("modelYOf");
    }

    /**
     * Mirrors {@code IfType.modelXAn}.
     *
     * @return the model's x angle
     */
    public int getModelXAn() {
        return i32("modelXAn");
    }

    /**
     * Mirrors {@code IfType.modelYAn}.
     *
     * @return the model's y angle
     */
    public int getModelYAn() {
        return i32("modelYAn");
    }

    /**
     * Mirrors {@code IfType.modelZAn}.
     *
     * @return the model's z angle
     */
    public int getModelZAn() {
        return i32("modelZAn");
    }

    /**
     * Mirrors {@code IfType.modelZoom}.
     *
     * @return the model's zoom
     */
    public int getModelZoom() {
        return i32("modelZoom");
    }

    /**
     * Mirrors {@code IfType.modelWid}.
     *
     * @return the model's width
     */
    public int getModelWid() {
        return i32("modelWid");
    }

    /**
     * Mirrors {@code IfType.modelHei}.
     *
     * @return the model's height
     */
    public int getModelHei() {
        return i32("modelHei");
    }

    /**
     * Mirrors {@code IfType.modelSpin}.
     *
     * @return the model's spin
     */
    public int getModelSpin() {
        return i32("modelSpin");
    }

    /**
     * Mirrors {@code IfType.orthog}.
     *
     * @return true if the model is drawn orthographically
     */
    public boolean isOrthog() {
        return u8("orthog") != 0;
    }

    /**
     * Mirrors {@code IfType.linkObjType}: the item an inventory slot component shows.
     *
     * @return the item id, or -1
     */
    public int getLinkObjType() {
        return i32("linkObjType");
    }

    /**
     * Mirrors {@code IfType.linkObjCount} (the docs call it {@code linkObjNumber}).
     *
     * @return the stack size of {@link #getLinkObjType()}
     */
    public int getLinkObjCount() {
        return i32("linkObjCount");
    }

    /**
     * Mirrors {@code IfType.event}.
     *
     * @return the event field
     */
    public int getEvent() {
        return i32("event");
    }

    /**
     * Mirrors {@code IfType.eventCode}.
     *
     * @return the event code
     */
    public int getEventCode() {
        return i32("eventCode");
    }

    /**
     * Mirrors {@code IfType.isEntityOverlay}. The docs say a number; the client registers a bool.
     *
     * @return true if the component belongs to the entity overlay system
     */
    public boolean isEntityOverlay() {
        return u8("isEntityOverlay") != 0;
    }

    /**
     * Mirrors {@code IfType.lastProcess}.
     *
     * @return the last process cycle
     */
    public int getLastProcess() {
        return i32("lastProcess");
    }

    /**
     * Mirrors {@code IfType.overlayType}.
     *
     * @return the overlay type ({@code InterfaceQuery.OverlayTypes})
     */
    public int getOverlayType() {
        return i32("overlayType");
    }

    /**
     * Mirrors {@code IfType.overlayId}.
     *
     * @return the overlay id
     */
    public int getOverlayId() {
        return i32("overlayId");
    }

    /**
     * Where the component lives in the client. Not in Lua.
     *
     * @return the native {@code IfType} address the snapshot was read from
     */
    public long getAddress() {
        return address;
    }

    /**
     * The id as the Lua docs and RuneLite write it.
     *
     * @return {@code group:component} for a static component, {@code group:component[sub]} for a dynamic one
     */
    @Override
    public String toString() {
        String id = getId() + ":" + getComponentIndex();
        return isStatic() ? id : id + "[" + getSubId() + "]";
    }

    int i32(String field) {
        int off = query.field(field);
        return off < 0 || off + 4 > data.length ? -1 : buffer.getInt(off);
    }

    long i64(String field) {
        int off = query.field(field);
        return off < 0 || off + 8 > data.length ? -1 : buffer.getLong(off);
    }

    private int u8(String field) {
        int off = query.field(field);
        return off < 0 || off >= data.length ? 0 : data[off] & 0xff;
    }

    private String string(String field) {
        return query.string(data, query.field(field));
    }

    private Vector2 pair(String field) {
        int off = query.field(field);
        return off < 0 || off + 8 > data.length ? null : new Vector2(buffer.getInt(off), buffer.getInt(off + 4));
    }

    private Colour colour(String field) {
        int off = query.field(field);
        return off < 0 || off + 4 > data.length ? null : Colour.of(buffer.getInt(off));
    }
}
