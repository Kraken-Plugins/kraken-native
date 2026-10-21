package com.kraken.client.ui.devtools.tools;

import com.kraken.api.widgets.WidgetType;
import lombok.Getter;

import java.awt.Dimension;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Every open interface group as one tree, the way the client draws them: a widget's static children, then its dynamic
 * children, then the groups opened inside it. Built on the poll thread from {@link GroupDraft}s and immutable after,
 * so it can be handed to the EDT.
 *
 * <p>The client's open-interface map says where each group is drawn but not which component it was opened in, so a
 * group is put under the empty layer drawn exactly where the group is ({@link #assemble}). A group with no such layer
 * is a root.
 */
final class WidgetForest {

    /**
     * What a node is.
     */
    enum Kind {
        /**
         * An open interface group.
         */
        GROUP,
        /**
         * A widget designed in the cache, with a fixed id.
         */
        STATIC,
        /**
         * A widget a script made under a static one.
         */
        DYNAMIC
    }

    /**
     * A rectangle on the canvas, in the client's pixels.
     *
     * @param x      the left edge
     * @param y      the top edge
     * @param width  the width
     * @param height the height
     */
    record Area(int x, int y, int width, int height) {

        /**
         * Whether a point is inside.
         *
         * @param px the point's x
         * @param py the point's y
         * @return true if it is inside
         */
        boolean contains(int px, int py) {
            return px >= x && py >= y && px < x + width && py < y + height;
        }

        /**
         * The part both rectangles cover.
         *
         * @param other the other rectangle
         * @return the overlap, with no size if there is none
         */
        Area intersect(Area other) {
            int left = Math.max(x, other.x);
            int top = Math.max(y, other.y);
            int right = Math.min(x + width, other.x + other.width);
            int bottom = Math.min(y + height, other.y + other.height);
            return new Area(left, top, Math.max(0, right - left), Math.max(0, bottom - top));
        }

        /**
         * Whether the rectangle covers nothing.
         *
         * @return true if it has no width or no height
         */
        boolean isEmpty() {
            return width <= 0 || height <= 0;
        }
    }

    /**
     * What a tree row shows of one node.
     *
     * @param kind       group, static or dynamic
     * @param key        unique in the forest: {@code g149} for a group, {@code 149:0} for a static widget,
     *                   {@code 149:0[3]} for a dynamic one (its static root and index)
     * @param id         the group id for a group, else the widget's packed id (a dynamic one's static root's)
     * @param index      a dynamic widget's index, else -1
     * @param type       the {@link WidgetType}, or -1 for a group
     * @param name       the cache's name (the group's, or a static widget's), or ""
     * @param summary    a short description of what it shows, or ""
     * @param bounds     where it is on the canvas (a group's clip), or null if unknown
     * @param visible    the part of {@code bounds} its clipping lets show, or null if unknown
     * @param selfHidden hidden by its own flag
     * @param hidden     hidden by its own flag or an ancestor's
     * @param drawn      the client drew it in its latest frame (a group: any of its widgets)
     * @param search     lower-case text the search matches against
     */
    record Row(Kind kind, String key, int id, int index, int type, String name, String summary, Area bounds,
               Area visible, boolean selfHidden, boolean hidden, boolean drawn, String search) {

        /**
         * Whether this is a group.
         *
         * @return true for a group
         */
        boolean isGroup() {
            return kind == Kind.GROUP;
        }

        /**
         * The group this node is in.
         *
         * @return the group id
         */
        int group() {
            return kind == Kind.GROUP ? id : id >>> 16;
        }

        /**
         * The id as the tree shows it.
         *
         * @return {@code 149} for a group, {@code 149:0} for a static widget, {@code [3]} for a dynamic one
         */
        String idText() {
            return switch (kind) {
                case GROUP -> Integer.toString(id);
                case STATIC -> component(id);
                case DYNAMIC -> "[" + index + "]";
            };
        }
    }

    /**
     * A node with its children, in draw order.
     *
     * @param row      what the node's row shows
     * @param children its children
     */
    record WidgetNode(Row row, List<WidgetNode> children) {
    }

    /**
     * Where an open group is drawn.
     *
     * @param id         the group id
     * @param name       its name, or ""
     * @param clip       the rectangle nothing of it shows outside, or null if unknown
     * @param offX       x of the corner its widgets are placed from
     * @param offY       y of that corner
     * @param scriptOffX extra x a Lua plugin moved it by
     * @param scriptOffY extra y a Lua plugin moved it by
     * @param components how many static widgets it has
     * @param mount      the key of the widget it is drawn in, or null for a root
     */
    record GroupInfo(int id, String name, Area clip, int offX, int offY, int scriptOffX, int scriptOffY,
                     int components, String mount) {
    }

    /**
     * What a search shows: the matching nodes with their ancestors.
     *
     * @param roots   the roots that are left
     * @param matches the keys of the nodes that matched, which the view expands to
     */
    record Filtered(List<WidgetNode> roots, Set<String> matches) {
    }

    /**
     * A forest with nothing open.
     */
    static final WidgetForest EMPTY = assemble(List.of(), new Dimension(1, 1));

    @Getter
    private final List<WidgetNode> roots;
    @Getter
    private final Dimension canvas;
    @Getter
    private final int widgetCount;
    private final Map<String, WidgetNode> byKey;
    private final Map<String, String> parents;
    private final Map<Integer, GroupInfo> groups;
    private final List<Row> drawOrder;
    private final boolean drawnKnown;

    private WidgetForest(List<WidgetNode> roots, Map<Integer, GroupInfo> groups, Dimension canvas) {
        this.roots = List.copyOf(roots);
        this.groups = Map.copyOf(groups);
        this.canvas = new Dimension(canvas);
        Map<String, WidgetNode> keys = new HashMap<>();
        Map<String, String> up = new HashMap<>();
        List<Row> order = new ArrayList<>();
        for (WidgetNode root : roots) {
            index(root, null, keys, up, order);
        }
        this.byKey = Collections.unmodifiableMap(keys);
        this.parents = Collections.unmodifiableMap(up);
        this.drawOrder = List.copyOf(order);
        this.widgetCount = order.size();
        this.drawnKnown = order.stream().anyMatch(Row::drawn);
    }

    private static void index(WidgetNode node, String parent, Map<String, WidgetNode> keys, Map<String, String> up,
                              List<Row> order) {
        keys.put(node.row().key(), node);
        if (parent != null) {
            up.put(node.row().key(), parent);
        }
        if (!node.row().isGroup()) {
            order.add(node.row());
        }
        for (WidgetNode child : node.children()) {
            index(child, node.row().key(), keys, up, order);
        }
    }

    /**
     * How many groups are open.
     *
     * @return the group count
     */
    int groupCount() {
        return groups.size();
    }

    /**
     * The ids of the open groups.
     *
     * @return the ids, in no order
     */
    Set<Integer> groupIds() {
        return groups.keySet();
    }

    /**
     * Where a group is drawn.
     *
     * @param groupId the group id
     * @return its placement, or null if it isn't in the forest
     */
    GroupInfo groupInfo(int groupId) {
        return groups.get(groupId);
    }

    /**
     * A node by key.
     *
     * @param key the node's key
     * @return the node, or null if it isn't in the forest
     */
    WidgetNode node(String key) {
        return key == null ? null : byKey.get(key);
    }

    /**
     * The rows from a root down to a node.
     *
     * @param key the node's key
     * @return the root first and the node last; empty if it isn't in the forest
     */
    List<Row> path(String key) {
        List<Row> path = new ArrayList<>();
        for (String at = key; at != null && byKey.containsKey(at); at = parents.get(at)) {
            path.add(byKey.get(at).row());
            if (path.size() > byKey.size()) {
                break;
            }
        }
        Collections.reverse(path);
        return path;
    }

    /**
     * The widgets under a canvas point, topmost first. Only widgets the client drew and doesn't hide count, inside
     * their clipping. Widgets that draw something come before the layers that hold them, since a layer covers the
     * whole of its children's area and draws nothing itself.
     *
     * @param x the point's x, in canvas pixels
     * @param y the point's y
     * @return the widgets' rows; empty if there are none
     */
    List<Row> pick(int x, int y) {
        List<Row> drawing = new ArrayList<>();
        List<Row> layers = new ArrayList<>();
        for (int i = drawOrder.size() - 1; i >= 0; i--) {
            Row row = drawOrder.get(i);
            if (row.hidden() || drawnKnown && !row.drawn() || row.visible() == null || !row.visible().contains(x, y)) {
                continue;
            }
            (row.type() == WidgetType.LAYER ? layers : drawing).add(row);
        }
        drawing.addAll(layers);
        return drawing;
    }

    /**
     * The rows of every widget the client drew in its latest frame, in draw order.
     *
     * @return the rows
     */
    List<Row> drawn() {
        return drawOrder.stream().filter(row -> row.drawn() && !row.hidden()).toList();
    }

    /**
     * The nodes a search keeps: a node matches if its search text has the query in it, and keeps all its children; its
     * ancestors stay so it can be reached.
     *
     * @param query       the text to look for, any case; blank keeps everything
     * @param visibleOnly drop what the client didn't draw or hides
     * @return the nodes left
     */
    Filtered filter(String query, boolean visibleOnly) {
        String q = query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty() && !visibleOnly) {
            return new Filtered(roots, Set.of());
        }
        Set<String> matches = new HashSet<>();
        return new Filtered(filter(roots, q, visibleOnly, matches, false), Set.copyOf(matches));
    }

    private List<WidgetNode> filter(List<WidgetNode> nodes, String q, boolean visibleOnly, Set<String> matches,
                                    boolean inMatch) {
        List<WidgetNode> out = new ArrayList<>();
        for (WidgetNode node : nodes) {
            Row row = node.row();
            boolean self = !q.isEmpty() && row.search().contains(q);
            List<WidgetNode> children = filter(node.children(), q, visibleOnly, matches, inMatch || self);
            boolean shown = !visibleOnly || !row.hidden() && (row.drawn() || !drawnKnown);
            if (self && shown) {
                matches.add(row.key());
            }
            if (!children.isEmpty() || shown && (q.isEmpty() || inMatch || self)) {
                out.add(children.equals(node.children()) ? node : new WidgetNode(row, List.copyOf(children)));
            }
        }
        return out;
    }

    /**
     * Java that fetches a node through the API, using the cache's names where there are some.
     *
     * @param path  the rows from a root down to the node ({@link #path})
     * @param names the names
     * @return e.g. {@code client.getWidget(InterfaceID.Inventory.ITEMS).getChild(3)}; "" for an empty path
     */
    static String code(List<Row> path, WidgetNames names) {
        if (path.isEmpty()) {
            return "";
        }
        Row target = path.get(path.size() - 1);
        if (target.isGroup()) {
            String name = names.group(target.id());
            return "client.getWidgetGroup(" + (name.isEmpty() ? Integer.toString(target.id()) : "InterfaceID." + name)
                    + ")";
        }
        int root = path.size() - 1;
        while (root > 0 && path.get(root).kind() == Kind.DYNAMIC) {
            root--;
        }
        Row base = path.get(root);
        WidgetNames.Name name = names.component(base.id());
        StringBuilder code = new StringBuilder("client.getWidget(")
                .append(name != null ? name.constant() : (base.id() >>> 16) + ", " + (base.id() & 0xffff))
                .append(")");
        for (int i = root + 1; i < path.size(); i++) {
            code.append(".getChild(").append(path.get(i).index()).append(")");
        }
        return code.toString();
    }

    /**
     * A packed id as {@code group:component}.
     *
     * @param packedId the id
     * @return e.g. {@code 149:0}
     */
    static String component(int packedId) {
        return (packedId >>> 16) + ":" + (packedId & 0xffff);
    }

    /**
     * Text without the client's markup: tags dropped, line breaks as spaces, {@code <lt>} and {@code <gt>} as the
     * characters they stand for.
     *
     * @param text the text, with markup
     * @return the plain text, trimmed
     */
    static String plain(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return text.replaceAll("(?i)<br>", " ")
                .replaceAll("(?i)<lt>", "\u0000")
                .replaceAll("(?i)<gt>", "\u0001")
                .replaceAll("<[^>]*>", "")
                .replace('\u0000', '<')
                .replace('\u0001', '>')
                .replaceAll("\\s+", " ")
                .trim();
    }

    /**
     * Places every group's widgets on the canvas, puts each group under the widget it is drawn in, and freezes the
     * result.
     *
     * <p>A child's corner is its parent's corner plus its own x and y, less the parent's scroll; a group's roots start
     * from the group's offset and script offset. A layer clips its children to itself, inside its group's clip.
     *
     * <p>A group goes under a static layer of another group whose corner is exactly where the group is drawn. Of
     * several such layers, one whose area matches the group's clip wins, then an empty one (a component a group is
     * opened in has no children of its own), then the deepest. Smaller groups are placed first, and a group is never
     * put inside one that is already inside it.
     *
     * @param drafts the open groups as read
     * @param canvas the canvas's size
     * @return the forest
     */
    static WidgetForest assemble(List<GroupDraft> drafts, Dimension canvas) {
        for (GroupDraft group : drafts) {
            for (Draft root : group.roots) {
                place(root, group.offX + group.scriptOffX, group.offY + group.scriptOffY, group.clip, 0);
            }
        }
        Map<Integer, Draft> mounts = mounts(drafts);
        Map<Draft, List<GroupDraft>> opened = new IdentityHashMap<>();
        for (GroupDraft group : drafts) {
            Draft mount = mounts.get(group.id);
            if (mount != null) {
                opened.computeIfAbsent(mount, k -> new ArrayList<>()).add(group);
            }
        }
        opened.values().forEach(list -> list.sort(Comparator.comparingInt(g -> g.id)));

        Map<Integer, GroupInfo> infos = new HashMap<>();
        for (GroupDraft group : drafts) {
            Draft mount = mounts.get(group.id);
            infos.put(group.id, new GroupInfo(group.id, group.name, group.clip, group.offX, group.offY,
                    group.scriptOffX, group.scriptOffY, group.components, mount == null ? null : mount.key()));
        }
        List<WidgetNode> roots = new ArrayList<>();
        for (GroupDraft group : drafts) {
            if (!mounts.containsKey(group.id)) {
                roots.add(freeze(group, false, opened));
            }
        }
        roots.sort(Comparator.comparingInt((WidgetNode node) -> -size(node)).thenComparingInt(node -> node.row().id()));
        return new WidgetForest(roots, infos, canvas);
    }

    private static void place(Draft draft, int originX, int originY, Area clip, int depth) {
        draft.bounds = new Area(originX + draft.x, originY + draft.y, draft.width, draft.height);
        draft.visible = clip == null ? draft.bounds : draft.bounds.intersect(clip);
        draft.depth = depth;
        Area inner = draft.type == WidgetType.LAYER ? draft.visible : clip;
        for (Draft child : draft.children) {
            place(child, draft.bounds.x() - draft.scrollX, draft.bounds.y() - draft.scrollY, inner, depth + 1);
        }
    }

    /**
     * Finds the widget each group is drawn in.
     *
     * @param drafts the groups, already placed
     * @return the mount of each group that has one, by group id
     */
    private static Map<Integer, Draft> mounts(List<GroupDraft> drafts) {
        Map<Long, List<Candidate>> byCorner = new HashMap<>();
        for (GroupDraft group : drafts) {
            for (Draft root : group.roots) {
                collectLayers(root, group.id, byCorner);
            }
        }
        List<GroupDraft> order = new ArrayList<>(drafts);
        order.sort(Comparator.comparingLong((GroupDraft g) -> g.clip == null ? Long.MAX_VALUE
                        : (long) g.clip.width() * g.clip.height())
                .thenComparingInt(g -> g.components)
                .thenComparingInt(g -> g.id));
        Map<Integer, Integer> parentGroup = new HashMap<>();
        Map<Integer, Draft> mounts = new HashMap<>();
        for (GroupDraft group : order) {
            Candidate best = null;
            int bestScore = -1;
            for (Candidate candidate : byCorner.getOrDefault(corner(group.offX, group.offY), List.of())) {
                if (candidate.group == group.id || reaches(parentGroup, candidate.group, group.id)) {
                    continue;
                }
                Draft layer = candidate.draft;
                int score = (group.clip != null && (group.clip.equals(layer.visible) || group.clip.equals(layer.bounds))
                        ? 4 : 0) + (layer.children.isEmpty() ? 2 : 0);
                if (score > bestScore || score == bestScore && layer.depth > best.draft.depth) {
                    best = candidate;
                    bestScore = score;
                }
            }
            if (best != null) {
                mounts.put(group.id, best.draft);
                parentGroup.put(group.id, best.group);
            }
        }
        return mounts;
    }

    /**
     * A static layer a group could be drawn in.
     *
     * @param draft the layer
     * @param group the group the layer is in
     */
    private record Candidate(Draft draft, int group) {
    }

    private static void collectLayers(Draft draft, int group, Map<Long, List<Candidate>> byCorner) {
        if (draft.kind != Kind.STATIC) {
            return;
        }
        if (draft.type == WidgetType.LAYER) {
            byCorner.computeIfAbsent(corner(draft.bounds.x(), draft.bounds.y()), k -> new ArrayList<>())
                    .add(new Candidate(draft, group));
        }
        for (Draft child : draft.children) {
            collectLayers(child, group, byCorner);
        }
    }

    private static long corner(int x, int y) {
        return (long) x << 32 | y & 0xffffffffL;
    }

    /**
     * Whether a group is already inside another.
     *
     * @param parentGroup each placed group's parent group
     * @param from        the group to start from
     * @param target      the group to look for
     * @return true if {@code target} is {@code from} or one of its ancestors
     */
    private static boolean reaches(Map<Integer, Integer> parentGroup, int from, int target) {
        Integer at = from;
        for (int steps = 0; at != null && steps <= parentGroup.size(); steps++) {
            if (at == target) {
                return true;
            }
            at = parentGroup.get(at);
        }
        return false;
    }

    private static WidgetNode freeze(GroupDraft group, boolean hiddenAbove, Map<Draft, List<GroupDraft>> opened) {
        List<WidgetNode> children = new ArrayList<>();
        for (Draft root : group.roots) {
            children.add(freeze(root, hiddenAbove, opened));
        }
        boolean drawn = children.stream().anyMatch(child -> child.row().drawn());
        String search = (group.id + " " + group.name).toLowerCase(Locale.ROOT);
        Row row = new Row(Kind.GROUP, "g" + group.id, group.id, -1, -1, group.name,
                group.components == 1 ? "1 widget" : group.components + " widgets", group.clip, group.clip, false,
                hiddenAbove, drawn, search);
        return new WidgetNode(row, List.copyOf(children));
    }

    private static WidgetNode freeze(Draft draft, boolean hiddenAbove, Map<Draft, List<GroupDraft>> opened) {
        boolean hidden = hiddenAbove || draft.selfHidden;
        List<WidgetNode> children = new ArrayList<>();
        for (Draft child : draft.children) {
            children.add(freeze(child, hidden, opened));
        }
        for (GroupDraft group : opened.getOrDefault(draft, List.of())) {
            children.add(freeze(group, hidden, opened));
        }
        String key = draft.key();
        String search = (key + " " + draft.name + " " + draft.summary + " " + draft.search).toLowerCase(Locale.ROOT);
        Row row = new Row(draft.kind, key, draft.id, draft.index, draft.type, draft.name, draft.summary, draft.bounds,
                draft.visible, draft.selfHidden, hidden, draft.drawn, search);
        return new WidgetNode(row, List.copyOf(children));
    }

    private static int size(WidgetNode node) {
        int size = 1;
        for (WidgetNode child : node.children()) {
            size += size(child);
        }
        return size;
    }

    /**
     * A widget as read, before the forest places it: what it shows, where it is relative to its parent, and its
     * children (static, then dynamic). Set it up with the chained setters.
     */
    static final class Draft {
        final Kind kind;
        final int id;
        final int index;
        final int type;
        final List<Draft> children = new ArrayList<>();
        String name = "";
        String summary = "";
        String search = "";
        int x;
        int y;
        int width;
        int height;
        int scrollX;
        int scrollY;
        boolean selfHidden;
        boolean drawn;
        Area bounds;
        Area visible;
        int depth;

        /**
         * A widget.
         *
         * @param kind  static or dynamic
         * @param id    its packed id (a dynamic one's static root's)
         * @param index a dynamic one's index, else -1
         * @param type  its {@link WidgetType}
         */
        Draft(Kind kind, int id, int index, int type) {
            this.kind = kind;
            this.id = id;
            this.index = index;
            this.type = type;
        }

        /**
         * Sets the position relative to the parent and the size.
         *
         * @param x      x relative to the parent
         * @param y      y relative to the parent
         * @param width  the width
         * @param height the height
         * @return this draft
         */
        Draft at(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            return this;
        }

        /**
         * Sets how far a layer is scrolled.
         *
         * @param x the scroll across
         * @param y the scroll down
         * @return this draft
         */
        Draft scroll(int x, int y) {
            this.scrollX = x;
            this.scrollY = y;
            return this;
        }

        /**
         * Sets the hidden and drawn flags.
         *
         * @param selfHidden hidden by its own flag
         * @param drawn      drawn in the latest frame
         * @return this draft
         */
        Draft state(boolean selfHidden, boolean drawn) {
            this.selfHidden = selfHidden;
            this.drawn = drawn;
            return this;
        }

        /**
         * Sets the texts.
         *
         * @param name    the cache's name, or ""
         * @param summary a short description, or ""
         * @param search  more text the search should match, or ""
         * @return this draft
         */
        Draft text(String name, String summary, String search) {
            this.name = name;
            this.summary = summary;
            this.search = search;
            return this;
        }

        /**
         * Adds a child.
         *
         * @param child the child
         * @return this draft
         */
        Draft add(Draft child) {
            children.add(child);
            return this;
        }

        /**
         * The node's key.
         *
         * @return {@code 149:0} for a static widget, {@code 149:0[3]} for a dynamic one
         */
        String key() {
            return kind == Kind.DYNAMIC ? component(id) + "[" + index + "]" : component(id);
        }
    }

    /**
     * An open group as read: where it is drawn and its top-level widgets.
     */
    static final class GroupDraft {
        final int id;
        final String name;
        final List<Draft> roots = new ArrayList<>();
        Area clip;
        int offX;
        int offY;
        int scriptOffX;
        int scriptOffY;
        int components;

        /**
         * A group.
         *
         * @param id   the group id
         * @param name its name, or ""
         */
        GroupDraft(int id, String name) {
            this.id = id;
            this.name = name;
        }

        /**
         * Sets where the group is drawn.
         *
         * @param clip       the clipping rectangle, or null if unknown
         * @param offX       x of the corner its widgets are placed from
         * @param offY       y of that corner
         * @param scriptOffX extra x a Lua plugin moved it by
         * @param scriptOffY extra y a Lua plugin moved it by
         * @return this draft
         */
        GroupDraft drawn(Area clip, int offX, int offY, int scriptOffX, int scriptOffY) {
            this.clip = clip;
            this.offX = offX;
            this.offY = offY;
            this.scriptOffX = scriptOffX;
            this.scriptOffY = scriptOffY;
            return this;
        }

        /**
         * Adds a top-level widget.
         *
         * @param root the widget
         * @return this draft
         */
        GroupDraft add(Draft root) {
            roots.add(root);
            return this;
        }
    }
}
