package com.kraken.api.internal;

import com.kraken.api.Point;
import com.kraken.api.mapping.Mapping;
import com.kraken.api.memory.Memory;
import com.kraken.api.widgets.Widget;
import com.kraken.api.widgets.WidgetType;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads interface components ({@link Widget}s) the way the client's Lua {@code InterfaceQuery} functions
 * ({@code retrieveIfType}, {@code retrieveIfGroup}) do. Plumbing for {@code Client.getWidget}, which plugins use.
 *
 * <p>The client keeps the components of every interface group it has loaded in its interface store: per group, a slot
 * per static component, and per static component, a slot per dynamic child. A slot is a {@code jag::shared_ptr} to the
 * component. The groups on screen are also in a hash map from group id to where they are drawn
 * ({@link Coordinates}).
 */
public final class WidgetReader extends GameReader {

    /**
     * The {@code IfType} fields a widget reads, resolved from the mapping's {@code structs.IfType}.
     */
    private static final String[] FIELDS = {
            "packedId", "subId", "layerSubId", "sourceName", "type", "event", "eventCode", "isEntityOverlay",
            "lastProcess", "modeX", "modeY", "modeWidth", "modeHeight", "dataX", "dataY", "dataWidth", "dataHeight", "x",
            "y", "width", "height", "aspectRatio", "layerId", "hidden", "layerIdXMin", "layerIdXMax", "scrollPos",
            "scrollSize", "overlayType", "colour", "colour2", "colourOver", "fill", "blendMode", "transparency",
            "strokeWidth", "diagonalStartAtBottom", "startAngle", "stopAngle", "graphic", "graphic2", "graphicURL",
            "rotate", "tiling", "outline", "shadowColour", "vFlip", "hFlip", "model1Type", "model1Id", "model2Type",
            "model2Id", "modelAnim", "modelAnim2", "modelXOf", "modelYOf", "modelXAn", "modelYAn", "modelZAn",
            "modelZoom", "modelWid", "modelHei", "modelSpin", "orthog", "text", "text2", "overlayId", "baseOpName",
            "opCount", "opNames", "linkObjType", "linkObjCount", "dynCount", "dynSlots", "renderCycle"
    };

    private static final int MAX_SLOTS = 1 << 16;
    private static final int MAX_OPS = 64;
    private static final int MAX_CHAIN = 4096;
    private static final int MAX_DEPTH = 64;

    private final Map<String, Integer> fields = new HashMap<>();
    private final int snapshotSize;
    private final int cStore;
    private final int cRenderCycle;
    private final int cOpen;
    private final int cOpenCount;
    private final int storeGroupCount;
    private final int storeGroups;
    private final int groupStride;
    private final int groupCount;
    private final int groupSlots;
    private final int slotStride;
    private final int slotObject;
    private final int nodeKey;
    private final int nodeValue;
    private final int nodeNext;
    private final int[] coordinates;
    private final boolean storeReady;
    private final boolean dynamicReady;

    /**
     * Resolves the interface keys. {@code Client} makes the one it reads widgets through.
     *
     * @param memory  the client process's memory
     * @param mapping the client build's mapping
     */
    public WidgetReader(Memory memory, Mapping mapping) {
        super(memory, mapping);
        int end = 0;
        for (String name : FIELDS) {
            int off = optionalOffset("IfType." + name);
            if (off >= 0) {
                fields.put(name, off);
                end = Math.max(end, off + structs.stringSize());
            }
        }
        this.snapshotSize = end;
        this.cStore = optionalOffset("Client.interfaceStore");
        this.cRenderCycle = optionalOffset("Client.renderCycle");
        this.cOpen = optionalOffset("Client.interfaces.buckets");
        this.cOpenCount = optionalOffset("Client.interfaces.bucketCount");
        this.storeGroupCount = optionalOffset("IfStore.groupCount");
        this.storeGroups = optionalOffset("IfStore.groups");
        this.groupStride = optionalOffset("IfStoreGroup.stride");
        this.groupCount = optionalOffset("IfStoreGroup.count");
        this.groupSlots = optionalOffset("IfStoreGroup.slots");
        this.slotStride = optionalOffset("IfStoreGroup.slotStride");
        this.slotObject = mapping.container("shared_ptr.ptr");
        this.nodeKey = optionalOffset("IfGroupNode.key");
        this.nodeValue = optionalOffset("IfGroupNode.value");
        this.nodeNext = optionalOffset("IfGroupNode.next");
        this.storeReady = cStore >= 0 && storeGroupCount >= 0 && storeGroups >= 0 && groupStride > 0 && groupCount >= 0
                && groupSlots >= 0 && slotStride > 0;
        this.dynamicReady = field("dynCount") >= 0 && field("dynSlots") >= 0 && field("layerSubId") >= 0;
        String[] coords = {"clipX1", "clipY1", "clipX2", "clipY2", "offX", "offY", "scriptOffX", "scriptOffY"};
        this.coordinates = new int[coords.length];
        for (int i = 0; i < coords.length; i++) {
            coordinates[i] = optionalOffset("InterfaceCoordinates." + coords[i]);
        }
    }

    /**
     * A static component by group and component id (Lua's {@code retrieveIfType}). The client loads a group's
     * components from the cache the first time it is asked for one; this reader can't, so a group the client hasn't
     * loaded yet gives null.
     *
     * @param groupId     the interface group, e.g. 149 for the inventory
     * @param componentId the component's index in the group, from 0
     * @return a snapshot of the component, or null if the client holds none there
     */
    public Widget getWidget(int groupId, int componentId) {
        return lookup((groupId << 16) + componentId, -1);
    }

    /**
     * Every static component of a group (Lua's {@code retrieveIfGroup}). As {@link #getWidget}, a group the client
     * hasn't loaded gives null.
     *
     * @param groupId the interface group
     * @return the group's static components by component index, null where the client holds none; null if the
     *         client holds no components for the group
     */
    public Widget[] getGroup(int groupId) {
        long entry = groupEntry(groupId);
        long count = entry == 0 ? 0 : safeLong(entry + groupCount, 0);
        long slots = entry == 0 ? 0 : safePointer(entry + groupSlots);
        if (count <= 0 || count > MAX_SLOTS || slots == 0) {
            return null;
        }
        Widget[] components = new Widget[(int) count];
        int frame = frame();
        for (int i = 0; i < count; i++) {
            components[i] = read(safePointer(slots + (long) i * slotStride + slotObject), frame);
        }
        return components;
    }

    /**
     * The groups on screen: the keys of the client's open-interface map.
     *
     * @return the open group ids, sorted; empty if they can't be read
     */
    public List<Integer> getOpenGroups() {
        List<Integer> groups = new ArrayList<>();
        forEachOpenNode(node -> {
            groups.add(safeInt(node + nodeKey, -1));
            return false;
        });
        Collections.sort(groups);
        return groups;
    }

    /**
     * Where an open group is drawn (Lua's {@code retrieveIfGroup(id).coordinates}, without reading the components).
     *
     * @param groupId the interface group
     * @return its coordinates, or null if it isn't open or can't be read
     */
    public Coordinates getCoordinates(int groupId) {
        long node = openNode(groupId);
        if (node == 0 || coordinates[0] < 0) {
            return null;
        }
        long value = node + nodeValue;
        int[] v = new int[coordinates.length];
        for (int i = 0; i < v.length; i++) {
            v[i] = safeInt(value + coordinates[i], 0);
        }
        return new Coordinates(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7]);
    }

    /**
     * The client's component lookup: a static component by packed id, or the dynamic child {@code subId} of it.
     *
     * @param packedId the static component's {@code group << 16 | component}
     * @param subId    -1 for the static component itself, else the dynamic child's sub id
     * @return a snapshot, or null where the client holds no component
     */
    public Widget lookup(int packedId, int subId) {
        long root = staticComponent(packedId);
        if (root == 0 || subId < 0) {
            return read(root, frame());
        }
        return read(dynamicChild(root, subId), frame());
    }

    /**
     * {@code IfType.children} as the client lists them: the dynamic children of the component's static root whose
     * direct parent is this component. The root's own children are the ones whose parent sub id is -1; a dynamic
     * layer's are searched for in its {@code layerIdXMin..layerIdXMax} range.
     *
     * @param self the component
     * @return its children in sub id order
     */
    public List<Widget> children(Widget self) {
        if (self.getType() != WidgetType.LAYER || !dynamicReady) {
            return List.of();
        }
        long root = self.isStatic() ? self.getAddress() : staticComponent(self.getParentId());
        if (root == 0) {
            return List.of();
        }
        long count = Math.min(safeLong(root + field("dynCount"), 0), MAX_SLOTS);
        long first = 0;
        long end = count;
        if (root != self.getAddress()) {
            // The client treats a negative bound as unsigned, so such a range is empty; it doesn't cap the range at
            // the root's child count, the reader does
            first = self.getLayerIdXMin();
            end = Math.min((long) self.getLayerIdXMax() + 1, count);
            if (first < 0) {
                return List.of();
            }
        }
        long slots = safePointer(root + field("dynSlots"));
        if (slots == 0) {
            return List.of();
        }
        int parentSubId = self.getIndex();
        int layerSubId = field("layerSubId");
        int frame = frame();
        List<Widget> out = new ArrayList<>();
        for (long i = first; i < end; i++) {
            long child = safePointer(slots + i * slotStride + slotObject);
            if (child != 0 && safeInt(child + layerSubId, Integer.MIN_VALUE) == parentSubId) {
                Widget snapshot = read(child, frame);
                if (snapshot != null) {
                    out.add(snapshot);
                }
            }
        }
        return out;
    }

    /**
     * One entry of {@link #children}: the dynamic child with this sub id, if its direct parent is the component. It
     * reads one slot where {@link #children} reads them all, and applies the same rules (only a
     * {@link WidgetType#LAYER} has children; a dynamic layer's are in its {@code layerIdXMin..layerIdXMax} range).
     *
     * @param self  the component
     * @param subId the child's sub id
     * @return a snapshot of the child, or null if there is none with that sub id directly under the component
     */
    public Widget child(Widget self, int subId) {
        if (subId < 0 || self.getType() != WidgetType.LAYER || !dynamicReady) {
            return null;
        }
        long root = self.isStatic() ? self.getAddress() : staticComponent(self.getParentId());
        if (root == 0 || root != self.getAddress()
                && (subId < self.getLayerIdXMin() || subId > self.getLayerIdXMax())) {
            return null;
        }
        long child = dynamicChild(root, subId);
        if (child == 0 || safeInt(child + field("layerSubId"), Integer.MIN_VALUE) != self.getIndex()) {
            return null;
        }
        return read(child, frame());
    }

    /**
     * {@code IfType.screenCoords} as the client works it out: x and y summed up the parent chain, plus the group's
     * offset and script offset at the top.
     *
     * @param self the component
     * @return the screen position in client pixels, or null if it can't be read
     */
    public Point screenCoords(Widget self) {
        long x = 0;
        long y = 0;
        Widget at = self;
        for (int depth = 0; depth < MAX_DEPTH; depth++) {
            x += at.getRelativeX();
            y += at.getRelativeY();
            Widget parent = at.getParent();
            if (parent == null) {
                Coordinates group = getCoordinates(at.getId() >> 16);
                if (group != null) {
                    x += group.offX() + group.scriptOffX();
                    y += group.offY() + group.scriptOffY();
                }
                return new Point((int) x, (int) y);
            }
            at = parent;
        }
        return null;
    }

    /**
     * The client's frame counter, which a component is stamped with when drawn ({@code IfType.isRendering} compares
     * the two).
     *
     * @return the counter, or -1 if it can't be read
     */
    private int frame() {
        long client = clientBase();
        return client == 0 || cRenderCycle < 0 ? -1 : safeInt(client + cRenderCycle, -1);
    }

    /**
     * {@code IfType.opNames}: the op name strings a component's op array holds.
     *
     * @param count the component's op count
     * @param names the address of its op name array
     * @return the op names; a slot without an op is ""
     */
    public List<String> opNames(int count, long names) {
        if (count <= 0 || count > MAX_OPS || names == 0 || names == -1) {
            return List.of();
        }
        List<String> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            out.add(structs.string(names + (long) i * structs.stringSize()));
        }
        return out;
    }

    /**
     * An {@code IfType} field's offset.
     *
     * @param name the field, e.g. "width"
     * @return its offset, or -1 if this build's mapping lacks it
     */
    public int field(String name) {
        return fields.getOrDefault(name, -1);
    }

    /**
     * Decodes a string field held in a snapshot.
     *
     * @param data   the snapshot
     * @param offset the string's offset, or -1
     * @return the text, or ""
     */
    public String string(byte[] data, int offset) {
        return offset < 0 ? "" : structs.string(data, offset);
    }

    /**
     * Snapshots a component.
     *
     * @param address the native {@code IfType}
     * @param frame   the client's frame counter, read with it
     * @return the snapshot, or null for a null or unreadable address
     */
    private Widget read(long address, int frame) {
        if (address == 0 || snapshotSize == 0) {
            return null;
        }
        ByteBuffer block = readBlock(address, snapshotSize);
        return block == null ? null : new Widget(this, address, block.array(), frame);
    }

    /**
     * The client's static lookup: group {@code packedId >> 16}, slot {@code packedId & 0xffff}.
     *
     * @param packedId the component's {@code group << 16 | component}
     * @return the component's address, or 0 where the client holds none (including a group it hasn't loaded)
     */
    private long staticComponent(int packedId) {
        long entry = groupEntry(packedId >> 16);
        if (entry == 0) {
            return 0;
        }
        int index = packedId & 0xffff;
        long count = safeLong(entry + groupCount, 0);
        if (index >= count) {
            return 0;
        }
        long slots = safePointer(entry + groupSlots);
        return slots == 0 ? 0 : safePointer(slots + (long) index * slotStride + slotObject);
    }

    /**
     * The client's dynamic lookup: slot {@code subId} of a static component's dynamic children.
     *
     * @param root  the static component
     * @param subId the child's sub id, 0 or more
     * @return the child's address, or 0 past the last child
     */
    private long dynamicChild(long root, int subId) {
        if (!dynamicReady || subId >= safeLong(root + field("dynCount"), 0)) {
            return 0;
        }
        long slots = safePointer(root + field("dynSlots"));
        return slots == 0 ? 0 : safePointer(slots + (long) subId * slotStride + slotObject);
    }

    /**
     * A group's entry in the interface store.
     *
     * @param groupId the group
     * @return the entry's address, or 0 for a group outside the store or if the store can't be read
     */
    private long groupEntry(int groupId) {
        long client = clientBase();
        if (client == 0 || !storeReady || groupId < 0) {
            return 0;
        }
        long store = safePointer(client + cStore);
        if (store == 0 || groupId >= safeLong(store + storeGroupCount, 0)) {
            return 0;
        }
        long groups = safePointer(store + storeGroups);
        return groups == 0 ? 0 : groups + (long) groupId * groupStride;
    }

    /**
     * An open group's node in the client's open-interface map, found the way the client looks it up: bucket
     * {@code groupId % bucketCount}, then along the chain.
     *
     * @param groupId the group
     * @return the node, or 0 if the group isn't open
     */
    private long openNode(int groupId) {
        long client = clientBase();
        if (client == 0 || cOpen < 0 || nodeNext < 0 || groupId < 0) {
            return 0;
        }
        long buckets = safePointer(client + cOpen);
        int count = safeInt(client + cOpenCount, 0);
        if (buckets == 0 || count <= 0 || count > MAX_SLOTS) {
            return 0;
        }
        long node = safePointer(buckets + (groupId % count) * 8L);
        for (int steps = 0; node != 0 && steps < MAX_CHAIN; steps++) {
            if (safeInt(node + nodeKey, -1) == groupId) {
                return node;
            }
            node = safePointer(node + nodeNext);
        }
        return 0;
    }

    /**
     * Walks every chain of the open-interface map until the visitor returns true.
     *
     * @param visitor called with each node's address; returns true to stop
     */
    private void forEachOpenNode(NodeVisitor visitor) {
        long client = clientBase();
        if (client == 0 || cOpen < 0 || nodeNext < 0) {
            return;
        }
        int count = safeInt(client + cOpenCount, 0);
        ByteBuffer heads = count <= 0 || count > MAX_SLOTS ? null : readBlock(safePointer(client + cOpen), count * 8);
        if (heads == null) {
            return;
        }
        int steps = 0;
        for (int b = 0; b < count; b++) {
            long node = heads.getLong(b * 8);
            while (node != 0 && steps++ < MAX_CHAIN) {
                if (visitor.visit(node)) {
                    return;
                }
                node = safePointer(node + nodeNext);
            }
        }
    }

    @FunctionalInterface
    private interface NodeVisitor {
        boolean visit(long node);
    }

    /**
     * Where an open interface group is drawn, in canvas pixels (Lua's {@code IfType.InterfaceCoordinates}).
     *
     * @param clipX1     left edge of the group's clipping region
     * @param clipY1     top edge of the clipping region
     * @param clipX2     right edge of the clipping region
     * @param clipY2     bottom edge of the clipping region
     * @param offX       x offset applied to every component of the group
     * @param offY       y offset applied to every component of the group
     * @param scriptOffX extra x offset a Lua plugin applied ({@code setRenderOffset})
     * @param scriptOffY extra y offset a Lua plugin applied
     */
    public record Coordinates(int clipX1, int clipY1, int clipX2, int clipY2, int offX, int offY, int scriptOffX,
                       int scriptOffY) {
    }
}
