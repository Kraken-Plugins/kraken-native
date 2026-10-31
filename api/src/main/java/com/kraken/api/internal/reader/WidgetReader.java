package com.kraken.api.internal.reader;

import com.kraken.api.Client;
import com.kraken.api.Point;
import com.kraken.api.internal.OpenInterfaces;
import com.kraken.api.internal.widget.ComponentOp;
import com.kraken.api.internal.widget.ComponentWrites;
import com.kraken.api.mapping.Mapping;
import com.kraken.api.memory.Memory;
import com.kraken.api.widgets.Widget;
import com.kraken.api.widgets.WidgetNode;
import com.kraken.api.widgets.WidgetType;
import lombok.Getter;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads interface components ({@link Widget}s) the way the client's Lua {@code InterfaceQuery} functions
 * ({@code retrieveIfType}, {@code retrieveIfGroup}) do. Plumbing for {@code Client.getWidget}, which plugins use.
 *
 * <p>The client keeps the components of every interface group it has loaded in its interface store: per group, a slot
 * per static component, and per static component, a slot per dynamic child. A slot is a {@code jag::shared_ptr} to the
 * component. The groups on screen are also in a hash map from group id to where they are drawn
 * ({@link Coordinates}), and the groups opened inside a component in a hash map from that component's id to the group
 * and the mode it was opened with (its sub-interfaces; the layer drawer draws the group it finds there after the
 * layer's own children). The top-level interface, opened in no component, is a field of the client.
 *
 * <p>It also holds the writes {@link Widget}'s setters make ({@link #getWrites()}), which the native hook makes on the
 * game thread.
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
            "opCount", "opNames", "linkObjType", "linkObjCount", "dynCount", "dynSlots", "renderCycle", "subOpCount",
            "subOpNames", "onKey", "onMouseRepeat", "dragDeadZone", "dragDeadTime", "events"
    };

    /**
     * The most arguments a component listener is read with; a count beyond it is a bad read.
     */
    private static final int MAX_LISTENER_ARGS = 64;

    /**
     * The size of a vector of strings in the sub-option table: its begin, end and capacity pointers.
     */
    private static final int STRING_VECTOR_SIZE = 24;

    private static final int MAX_SLOTS = 1 << 16;
    private static final int MAX_OPS = 64;
    private static final int MAX_CHAIN = 4096;
    private static final int MAX_DEPTH = 64;
    private static final int MAX_GROUP = 0xffff;
    private static final int MAX_MODE = 0xff;

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
    private final int cSub;
    private final int cSubCount;
    private final int subKey;
    private final int subGroup;
    private final int subNext;
    private final int subMode;
    private final int subLow;
    private final int subSpan;
    private final int cEvents;
    private final int cEventsCount;
    private final int eventsKey;
    private final int eventsFrom;
    private final int eventsTo;
    private final int eventsMask;
    private final int eventsNext;
    private final int cTopLevel;
    private final int listenerCount;
    private final int listenerArgs;
    private final int valueSize;
    private final int valueType;
    private final int kindInt;
    private final int kindString;
    private final int kindLong;
    private final int[] coordinates;
    private final boolean storeReady;
    private final boolean dynamicReady;

    /**
     * The writes the widgets' setters make, waiting for the client thread to send them.
     */
    @Getter
    private final ComponentWrites writes = new ComponentWrites();

    /**
     * The client the widgets belong to, which their interaction methods act through.
     */
    @Getter
    private final Client client;

    /**
     * Resolves the interface keys. {@code Client} makes the one it reads widgets through.
     *
     * @param memory  the client process's memory
     * @param mapping the client build's mapping
     * @param client  the client the widgets belong to
     */
    public WidgetReader(Memory memory, Mapping mapping, Client client) {
        super(memory, mapping);
        this.client = client;
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
        this.cSub = optionalOffset("Client.subInterfaces.buckets");
        this.cSubCount = optionalOffset("Client.subInterfaces.bucketCount");
        this.subKey = optionalOffset("SubInterfaceNode.key");
        this.subGroup = optionalOffset("SubInterfaceNode.group");
        this.subNext = optionalOffset("SubInterfaceNode.next");
        this.subMode = optionalOffset("SubInterfaceNode.type");
        this.cEvents = optionalOffset("Client.ifEvents.buckets");
        this.cEventsCount = optionalOffset("Client.ifEvents.bucketCount");
        this.eventsKey = optionalOffset("IfEventsNode.key");
        this.eventsFrom = optionalOffset("IfEventsNode.from");
        this.eventsTo = optionalOffset("IfEventsNode.to");
        this.eventsMask = optionalOffset("IfEventsNode.events");
        this.eventsNext = optionalOffset("IfEventsNode.next");
        this.cTopLevel = optionalOffset("Client.topLevelInterface");
        this.listenerCount = optionalOffset("IfListener.count");
        this.listenerArgs = optionalOffset("IfListener.args");
        this.valueSize = optionalOffset("VarValue.size");
        this.valueType = optionalOffset("VarValue.type");
        this.kindInt = mapping.enumValue("varValueType", "INT", -1);
        this.kindString = mapping.enumValue("varValueType", "STRING", -1);
        this.kindLong = mapping.enumValue("varValueType", "LONG", -1);
        int low = Math.min(Math.min(subKey, subGroup), subNext);
        int high = Math.max(Math.max(subKey + 4, subGroup + 4), subNext + 8);
        if (subMode >= 0) {
            low = Math.min(low, subMode);
            high = Math.max(high, subMode + 4);
        }
        this.subLow = low;
        this.subSpan = high - low;
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
     * Whether this build's mapping has the sub-interface table's keys.
     *
     * @return true if {@link #getSubInterfaces()} can read the table
     */
    public boolean hasSubInterfaces() {
        return cSub >= 0 && cSubCount >= 0 && subKey >= 0 && subGroup >= 0 && subNext >= 0;
    }

    /**
     * The groups opened inside components: the client's sub-interface table, read now.
     *
     * @return each group, with the mode it was opened with, by the packed id of the component it is opened in; empty if
     *         the table can't be read or this build's mapping lacks it
     */
    public Map<Integer, WidgetNode> getComponentTable() {
        Map<Integer, WidgetNode> table = readComponentTable(false);
        return table == null ? new LinkedHashMap<>() : table;
    }

    /**
     * The top-level interface: the group opened in no component, which the others hang from.
     *
     * @return its group id; -1 while there is none (on the login screen), if it can't be read or this build's mapping
     *         lacks it
     */
    public int getTopLevelInterface() {
        long client = clientBase();
        return client == 0 || cTopLevel < 0 ? -1 : safeInt(client + cTopLevel, -1);
    }

    /**
     * Internal. The top-level interface and the component table in one read, for the interface events. Strict where
     * {@link #getComponentTable()} is lenient: a read that fails part way, or finds an entry no open interface could
     * have (the table changing under the read), gives null rather than fewer entries.
     *
     * @return the open interfaces, or null if they can't be read now or this build's mapping lacks the table
     */
    public OpenInterfaces readOpenInterfaces() {
        long client = clientBase();
        if (client == 0 || !hasSubInterfaces()) {
            return null;
        }
        int top = -1;
        if (cTopLevel >= 0) {
            ByteBuffer field = readBlock(client + cTopLevel, 4);
            if (field == null) {
                return null;
            }
            top = field.getInt(0);
        }
        Map<Integer, WidgetNode> table = readComponentTable(true);
        return table == null ? null : new OpenInterfaces(top, table);
    }

    /**
     * Internal. How many static components the client holds for a group: 0 from when it opens the group until it has
     * loaded the group's components from the cache.
     *
     * @param groupId the group
     * @return the component count, or -1 if the interface store can't be read or doesn't reach the group
     */
    public int getGroupSize(int groupId) {
        long entry = groupEntry(groupId);
        return entry == 0 ? -1 : (int) Math.min(safeLong(entry + groupCount, -1), Integer.MAX_VALUE);
    }

    /**
     * The component a group is opened in, from the sub-interface table.
     *
     * @param groupId the group
     * @return the component's packed id, or -1 for a group opened in none (the top-level interface) or if the table
     *         can't be read
     */
    public int getMount(int groupId) {
        for (Map.Entry<Integer, WidgetNode> entry : getComponentTable().entrySet()) {
            if (entry.getValue().getId() == groupId) {
                return entry.getKey();
            }
        }
        return -1;
    }

    /**
     * Walks the sub-interface table, reading each node (component, group, mode, next) at once.
     *
     * @param strict give null for a read that fails part way or an entry out of range, instead of skipping it
     * @return each group by the packed id of the component it is opened in; null if strict and the read failed
     */
    private Map<Integer, WidgetNode> readComponentTable(boolean strict) {
        Map<Integer, WidgetNode> out = new LinkedHashMap<>();
        long client = clientBase();
        if (client == 0 || !hasSubInterfaces()) {
            return strict ? null : out;
        }
        int count = safeInt(client + cSubCount, 0);
        ByteBuffer heads = count <= 0 || count > MAX_SLOTS ? null : readBlock(safePointer(client + cSub), count * 8);
        if (heads == null) {
            return strict ? null : out;
        }
        int steps = 0;
        for (int b = 0; b < count; b++) {
            long node = heads.getLong(b * 8);
            while (node != 0) {
                ByteBuffer fields = steps++ < MAX_CHAIN ? readBlock(node + subLow, subSpan) : null;
                if (fields == null) {
                    if (strict) {
                        return null;
                    }
                    break;
                }
                int component = fields.getInt(subKey - subLow);
                int group = fields.getInt(subGroup - subLow);
                int mode = subMode >= 0 ? fields.getInt(subMode - subLow) : -1;
                if (component >= 0 && group >= 0 && group <= MAX_GROUP && mode >= -1 && mode <= MAX_MODE) {
                    out.put(component, new WidgetNode(group, mode));
                } else if (strict) {
                    return null;
                }
                node = fields.getLong(subNext - subLow);
            }
        }
        return out;
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
     * {@code IfType.screenCoords} as the client works it out: x and y summed up the parent chain within the group,
     * plus the group's offset and script offset at its top. It doesn't go on into the component the group is opened
     * in; the group's offset already places it there.
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
            if (parent == null || parent.getId() >>> 16 != at.getId() >>> 16) {
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
     * A component's click mask, read now as the client's menu and {@code cc_gettargetmask} resolve it: the events the
     * server set (IF_SETEVENTS) on a slot range of the component holding its slot, else the component's own
     * {@code IfType.events}. The server's ranges are a hash multimap on the client from the component's packed id
     * (bucket {@code id % bucketCount}), each node holding the first and last slot of a range and its events.
     *
     * @param packedId the component's packed id ({@code IfType.packedId}; a dynamic child has its static parent's)
     * @param subId    the component's slot: its index among its static parent's dynamic children, -1 for a static
     *                 component
     * @param own      the component's own events, as its snapshot holds them
     * @return the events of the first range holding the slot; {@code own} if none does, or if the server's ranges
     *         can't be read or this build's mapping lacks them
     */
    public int clickMask(int packedId, int subId, int own) {
        long client = clientBase();
        if (client == 0 || cEvents < 0 || cEventsCount < 0 || eventsKey < 0 || eventsFrom < 0 || eventsTo < 0
                || eventsMask < 0 || eventsNext < 0) {
            return own;
        }
        long buckets = safePointer(client + cEvents);
        long count = safeInt(client + cEventsCount, 0) & 0xffffffffL;
        if (buckets == 0 || count == 0 || count > MAX_SLOTS) {
            return own;
        }
        long node = safePointer(buckets + Integer.toUnsignedLong(packedId) % count * 8L);
        for (int steps = 0; node != 0 && steps < MAX_CHAIN; steps++) {
            if (safeInt(node + eventsKey, -1) == packedId && safeInt(node + eventsFrom, Integer.MAX_VALUE) <= subId
                    && subId <= safeInt(node + eventsTo, Integer.MIN_VALUE)) {
                return safeInt(node + eventsMask, own);
            }
            node = safePointer(node + eventsNext);
        }
        return own;
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
     * {@code IfType.subOpNames}: the sub-option names of one of a component's ops, the rows the op's sub-menu opens.
     *
     * @param count the component's sub-option list count
     * @param table the address of its sub-option table, one vector of strings per op
     * @param op    the op, 1-based
     * @return the sub-option names in order; empty if the op has none or the table can't be read
     */
    public List<String> subOpNames(int count, long table, int op) {
        if (op < 1 || op > count || count > MAX_OPS || table == 0 || table == -1) {
            return List.of();
        }
        long vector = table + (long) (op - 1) * STRING_VECTOR_SIZE;
        long first = safePointer(vector);
        long last = safePointer(vector + 8);
        int size = structs.stringSize();
        long span = last - first;
        if (first == 0 || span <= 0 || span % size != 0 || span / size > MAX_OPS) {
            return List.of();
        }
        List<String> out = new ArrayList<>((int) (span / size));
        for (long s = first; s < last; s += size) {
            out.add(structs.string(s));
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
     * Reads a component's listener held in a snapshot: its arguments, the script id first, as the client's
     * {@code cc_setonkey} and its siblings stored them.
     *
     * @param data the snapshot
     * @param name the listener field, e.g. "onKey"
     * @return the arguments ({@code Integer}s, {@code String}s and {@code Long}s, null for other kinds), or null if
     * the component has no such listener or this build's mapping lacks the listener layout
     */
    public Object[] listener(byte[] data, String name) {
        int offset = field(name);
        if (offset < 0 || listenerCount < 0 || listenerArgs < 0 || valueSize <= 0 || valueType < 0
                || offset + Math.max(listenerCount, listenerArgs) + 8 > data.length) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        long count = buffer.getLong(offset + listenerCount);
        long args = buffer.getLong(offset + listenerArgs);
        if (count <= 0 || count > MAX_LISTENER_ARGS || args == 0) {
            return null;
        }
        ByteBuffer block = readBlock(args, (int) count * valueSize);
        if (block == null) {
            return null;
        }
        Object[] values = new Object[(int) count];
        for (int i = 0; i < values.length; i++) {
            int at = i * valueSize;
            int kind = block.get(at + valueType) & 0xff;
            if (kind == kindInt) {
                values[i] = block.getInt(at);
            } else if (kind == kindString) {
                values[i] = structs.string(args + at);
            } else if (kind == kindLong) {
                values[i] = block.getLong(at);
            }
        }
        return values;
    }

    /**
     * Queues a write of a component's int32 field for the native hook and shows the new value in the snapshot.
     *
     * @param widget the component
     * @param data   its snapshot
     * @param field  the {@code IfType} field
     * @param value  the value
     * @return false if this build's mapping lacks the field or no hook that writes components is connected
     */
    public boolean writeInt(Widget widget, byte[] data, String field, int value) {
        int offset = field(field);
        if (offset < 0 || !writes.add(new ComponentOp(widget.getId(), widget.getIndex(), ComponentOp.Op.SET_I32,
                offset, value))) {
            return false;
        }
        if (offset + 4 <= data.length) {
            ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value);
        }
        return true;
    }

    /**
     * Queues the clearing of one of a component's listeners for the native hook, which clears it through the client's
     * own function, and shows the listener as gone in the snapshot.
     *
     * @param widget the component
     * @param data   its snapshot
     * @param name   the listener field, e.g. "onMouseRepeat"
     * @return false if this build's mapping lacks the listener or the clearing function, or no hook that writes
     * components is connected
     */
    public boolean clearListener(Widget widget, byte[] data, String name) {
        int offset = field(name);
        if (offset < 0 || listenerCount < 0 || !mapping.hasFunction("ifListenerClear")
                || !writes.add(new ComponentOp(widget.getId(), widget.getIndex(), ComponentOp.Op.CLEAR_LISTENER,
                offset, 0))) {
            return false;
        }
        if (offset + listenerCount + 8 <= data.length) {
            ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).putLong(offset + listenerCount, 0);
        }
        return true;
    }

    /**
     * Queues a text write for the native hook, which sets the string as {@code cc_settext} does: through the client's
     * own string assignment, then marking the component changed so the client redraws it.
     *
     * @param widget the component
     * @param field  the string field, e.g. "text"
     * @param text   the text
     * @return false if this build's mapping lacks the field, the string assignment or the invalidate, or no hook that
     * writes components is connected
     * @throws IllegalArgumentException if the text is longer than {@link ComponentOp#MAX_TEXT} UTF-8 bytes
     */
    public boolean writeText(Widget widget, String field, String text) {
        int offset = field(field);
        ComponentOp op = new ComponentOp(widget.getId(), widget.getIndex(), ComponentOp.Op.SET_TEXT,
                Math.max(offset, 0), 0, text);
        return offset >= 0 && mapping.hasFunction("stringAssign") && mapping.hasFunction("ifInvalidate")
                && writes.add(op);
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
     * Snapshots the component at an address, such as one the script engine points at.
     *
     * @param address the native {@code IfType}
     * @return the snapshot, or null for a null or unreadable address
     */
    public Widget at(long address) {
        return read(address, frame());
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
