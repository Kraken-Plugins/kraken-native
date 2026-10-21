package com.kraken.client.ui.devtools.tools;

import com.kraken.api.Client;
import com.kraken.api.GameSession;
import com.kraken.api.Point;
import com.kraken.api.widgets.Widget;
import com.kraken.api.widgets.WidgetType;
import com.kraken.client.ui.devtools.Category;
import com.kraken.client.ui.devtools.DevTool;
import com.kraken.client.ui.devtools.ToolOption;
import com.kraken.client.ui.devtools.tools.WidgetForest.Area;
import com.kraken.client.ui.devtools.tools.WidgetForest.Draft;
import com.kraken.client.ui.devtools.tools.WidgetForest.GroupDraft;
import com.kraken.client.ui.devtools.tools.WidgetForest.Kind;
import com.kraken.client.ui.devtools.tools.WidgetForest.Row;
import com.kraken.client.ui.devtools.tools.WidgetForest.WidgetNode;
import com.kraken.client.ui.devtools.view.ToolView;
import com.kraken.client.ui.overlay.OverlayContext;
import org.kordamp.ikonli.materialdesign2.MaterialDesignW;

import java.awt.*;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Browses every open interface as one tree, with the cache's names for groups and components: each group under the
 * component it is opened in, each widget with its static children, then its dynamic ones. The selected widget's fields
 * are read fresh every poll and it is outlined over the game; a widget can also be picked by clicking it in the game.
 *
 * <p>Reading every open group costs far more than one widget, so whole-tree reads are spaced out to take at most
 * {@link #READ_SHARE} of the poll thread's time, and repeated at once when a group opens or closes. The selected
 * widget is read every poll.
 */
final class WidgetInspectorTool extends DevTool<WidgetInspectorTool.Snapshot> {

    /**
     * The most widgets one read takes in, so a runaway read can't stall the poll thread.
     */
    private static final int MAX_WIDGETS = 25_000;

    /**
     * How deep dynamic children are followed.
     */
    private static final int MAX_DEPTH = 32;

    /**
     * The share of the poll thread's time the whole-tree reads may take.
     */
    private static final double READ_SHARE = 0.1;

    /**
     * How long text in a tree row's summary may be.
     */
    private static final int SUMMARY_TEXT = 48;

    /**
     * How long the selected widget counts as drawn after a read last saw the client draw it, in seconds. A widget's
     * draw stamp only matches the client's counter on the frame that drew it, which a read from outside the client
     * lands on now and then, not every time.
     */
    static final int DRAWN_MEMORY_SECONDS = 2;

    static final Color SELECTED = new Color(0x3cdcff);
    static final Color HOVERED = new Color(0xffcf40);
    private static final Color ON_SCREEN = new Color(255, 255, 255, 90);
    private static final Color SELECTED_FILL = new Color(0x3c, 0xdc, 0xff, 0x22);

    /**
     * What the window and overlay show.
     *
     * @param forest    every open group, as last read
     * @param detail    the selected node's fields, read this poll, or null if nothing is selected
     * @param marks     outlines over the game
     * @param overlayOn whether the tool's overlay is on
     */
    record Snapshot(WidgetForest forest, WidgetDetail detail, Marks marks, boolean overlayOn) {
    }

    private final ToolOption allOnScreen = option("drawn", "Outline everything on screen", false);

    /**
     * Chosen in the window (EDT), read on the poll thread.
     */
    private volatile String selected;
    private volatile String hovered;
    private volatile boolean readWanted;

    /**
     * Poll thread only.
     */
    private WidgetForest forest;
    private long nextReadAt;
    private String drawnKey;
    private long drawnAt;
    private boolean drawnSeen;

    WidgetInspectorTool() {
        super("widgets", "Widget inspector", Category.INTERFACE, MaterialDesignW.WIDGETS_OUTLINE,
                "Browse every open interface as a tree with the cache's names (149 is INVENTORY), its widgets' static "
                        + "and dynamic children, and each widget's fields and the code that fetches it. Pick a widget "
                        + "by clicking it in the game; the selected one is outlined over the game.");
    }

    @Override
    public Support overlaySupport() {
        return Support.READY;
    }

    @Override
    public Support windowSupport() {
        return Support.READY;
    }

    /**
     * Selects a node: its fields are read and it is outlined from the next poll.
     *
     * @param key the node's key, or null for none
     */
    void select(String key) {
        selected = key;
    }

    /**
     * The selected node.
     *
     * @return its key, or null for none
     */
    String selected() {
        return selected;
    }

    /**
     * Outlines a node in a second colour, for the tree row under the pointer.
     *
     * @param key the node's key, or null for none
     */
    void hover(String key) {
        hovered = key;
    }

    /**
     * Reads the whole tree again at the next poll.
     */
    void requestRead() {
        readWanted = true;
    }

    /**
     * Turns the tool's overlay on or off, as its eye in the sidebar does.
     *
     * @param on true to outline the selection in the game
     */
    void showInGame(boolean on) {
        Host host = host();
        if (host != null) {
            host.setOverlay(on);
        }
    }

    /**
     * The component the game is embedded in.
     *
     * @return the game area, or null before the dev tools adopt the tool
     */
    Component gameArea() {
        Host host = host();
        return host == null ? null : host.gameArea();
    }

    @Override
    protected Snapshot read(GameSession session) {
        Client client = session.client();
        List<Integer> open = client.getOpenWidgetGroups();
        long now = System.nanoTime();
        WidgetForest current = forest;
        if (current == null || readWanted || now >= nextReadAt || !new HashSet<>(open).equals(current.groupIds())) {
            readWanted = false;
            current = readForest(client, open);
            forest = current;
            nextReadAt = now + (long) ((System.nanoTime() - now) / READ_SHARE);
        }
        String key = selected;
        WidgetDetail detail = key == null ? null
                : WidgetDetail.read(client, current, key, WidgetNames.get(), this::drawnRecently);
        Marks marks = isOverlayOn() ? marks(current, detail, key, hovered) : Marks.NONE;
        return new Snapshot(current, detail, marks, isOverlayOn());
    }

    /**
     * Whether a read saw the client draw the selected widget within the last {@link #DRAWN_MEMORY_SECONDS}.
     *
     * @param key      the selected widget's key
     * @param drawnNow whether this read saw it drawn in the client's latest frame
     * @return true if a read saw it drawn recently
     */
    boolean drawnRecently(String key, boolean drawnNow) {
        long now = System.nanoTime();
        if (!key.equals(drawnKey)) {
            drawnKey = key;
            drawnSeen = false;
        }
        if (drawnNow) {
            drawnAt = now;
            drawnSeen = true;
        }
        return drawnSeen && now - drawnAt < TimeUnit.SECONDS.toNanos(DRAWN_MEMORY_SECONDS);
    }

    @Override
    protected void onStop() {
        // Runs on the EDT, so the poll thread's tree is left alone; the next start reads a fresh one
        readWanted = true;
    }

    /**
     * Reads every open group.
     *
     * @param client the client
     * @param open   the open groups' ids
     * @return the forest
     */
    private static WidgetForest readForest(Client client, List<Integer> open) {
        WidgetNames names = WidgetNames.get();
        int[] budget = {MAX_WIDGETS};
        List<GroupDraft> groups = new ArrayList<>();
        for (int groupId : open) {
            GroupDraft group = new GroupDraft(groupId, names.group(groupId));
            Widget[] components = client.getWidgetGroup(groupId);
            if (components != null) {
                readGroup(group, components, names, budget);
            }
            groups.add(group);
        }
        Dimension canvas = new Dimension(Math.max(1, client.getCanvasWidth()), Math.max(1, client.getCanvasHeight()));
        // The component each group is opened in; a mapping without the table leaves the forest to guess
        Map<Integer, Integer> openedIn = new HashMap<>();
        client.getComponentTable().forEach((component, node) -> openedIn.put(node.getId(), component));
        return WidgetForest.assemble(groups, openedIn, canvas);
    }

    /**
     * Reads one group's widgets: its static ones nested by parent, then each layer's dynamic children after its static
     * ones.
     *
     * @param group      the group, which receives where it is drawn and its top-level widgets
     * @param components the group's static widgets by component index, null where there is none
     * @param names      the cache's names
     * @param budget     how many more widgets may be read, in element 0
     */
    private static void readGroup(GroupDraft group, Widget[] components, WidgetNames names, int[] budget) {
        Map<Integer, Draft> byId = new HashMap<>();
        List<Widget> present = new ArrayList<>();
        for (Widget component : components) {
            if (component != null && budget[0]-- > 0) {
                present.add(component);
                byId.put(component.getId(), draft(component, names));
            }
        }
        group.components = present.size();
        if (present.isEmpty()) {
            return;
        }
        Widget any = present.get(0);
        Rectangle clip = any.getGroupClipBounds();
        Point offset = any.getGroupOffset();
        Point script = any.getGroupScriptOffset();
        group.drawn(clip == null ? null : new Area(clip.x, clip.y, clip.width, clip.height),
                offset == null ? 0 : offset.getX(), offset == null ? 0 : offset.getY(),
                script == null ? 0 : script.getX(), script == null ? 0 : script.getY());
        for (Widget component : present) {
            Draft draft = byId.get(component.getId());
            Draft parent = component.getLayerId() == component.getId() ? null : byId.get(component.getLayerId());
            if (parent != null) {
                parent.add(draft);
            } else {
                group.add(draft);
            }
        }
        for (Widget component : present) {
            readDynamic(byId.get(component.getId()), component, names, budget, 0);
        }
    }

    /**
     * Adds a layer's dynamic children, and theirs.
     *
     * @param draft  the layer's draft
     * @param layer  the layer
     * @param names  the cache's names
     * @param budget how many more widgets may be read, in element 0
     * @param depth  how many dynamic layers deep this is
     */
    private static void readDynamic(Draft draft, Widget layer, WidgetNames names, int[] budget, int depth) {
        if (layer.getType() != WidgetType.LAYER || depth >= MAX_DEPTH) {
            return;
        }
        for (Widget child : layer.getDynamicChildren()) {
            if (budget[0]-- <= 0) {
                return;
            }
            Draft childDraft = draft(child, names);
            draft.add(childDraft);
            readDynamic(childDraft, child, names, budget, depth + 1);
        }
    }

    /**
     * A widget's draft: its fields for the tree, from the snapshot it was read in.
     *
     * @param widget the widget
     * @param names  the cache's names
     * @return the draft, without children
     */
    private static Draft draft(Widget widget, WidgetNames names) {
        boolean dynamic = !widget.isStatic();
        Draft draft = new Draft(dynamic ? Kind.DYNAMIC : Kind.STATIC, widget.getId(), dynamic ? widget.getIndex() : -1,
                widget.getType())
                .at(widget.getRelativeX(), widget.getRelativeY(), widget.getWidth(), widget.getHeight())
                .scroll(Math.max(0, widget.getScrollX()), Math.max(0, widget.getScrollY()))
                .hidden(widget.isSelfHidden());
        WidgetNames.Name name = dynamic ? null : names.component(widget.getId());
        String text = WidgetForest.plain(widget.getText());
        String opBase = WidgetForest.plain(widget.getName());
        // Op names cost a read each, so they are only read for a widget with nothing else to show
        String ops = text.isEmpty() && opBase.isEmpty() && widget.getItemId() < 0 ? ops(widget) : "";
        String summary = summary(widget.getType(), text, opBase, widget.getItemId(), widget.getItemQuantity(), ops,
                widget.getSpriteId(), widget.getModelId(), names);
        return draft.text(name == null ? "" : name.field(), summary, text + " " + opBase + " " + ops);
    }

    /**
     * A tree row's short description of what a widget shows: its item, else its text, else the name its actions act
     * on, else its actions, else its sprite or model.
     *
     * @param type     the widget type
     * @param text     the text, without markup
     * @param opBase   the name its actions act on, without markup
     * @param itemId   the item it shows, or -1
     * @param quantity the item's quantity
     * @param ops      its actions, joined, or ""
     * @param sprite   its sprite, or -1
     * @param model    its model, or -1
     * @param names    the cache's names
     * @return the description, or ""
     */
    static String summary(int type, String text, String opBase, int itemId, int quantity, String ops, int sprite,
                          int model, WidgetNames names) {
        if (itemId >= 0) {
            String what = opBase.isEmpty() ? "item " + itemId : opBase;
            return quantity > 1 ? what + " ×" + NumberFormat.getIntegerInstance(Locale.ROOT).format(quantity) : what;
        }
        if (!text.isEmpty()) {
            return "\"" + (text.length() > SUMMARY_TEXT ? text.substring(0, SUMMARY_TEXT - 1) + "…" : text) + "\"";
        }
        if (!opBase.isEmpty()) {
            return opBase;
        }
        if (!ops.isEmpty()) {
            return ops;
        }
        if (type == WidgetType.GRAPHIC && sprite >= 0) {
            String name = names.sprite(sprite);
            return "sprite " + (name.isEmpty() ? Integer.toString(sprite) : name.toLowerCase(Locale.ROOT));
        }
        if (type == WidgetType.MODEL && model >= 0) {
            return "model " + model;
        }
        return "";
    }

    /**
     * A widget's non-empty actions.
     *
     * @param widget the widget
     * @return the actions joined with commas, or ""
     */
    static String ops(Widget widget) {
        String[] actions = widget.getActions();
        if (actions == null) {
            return "";
        }
        List<String> ops = new ArrayList<>();
        for (String op : actions) {
            if (op != null && !op.isEmpty()) {
                ops.add(WidgetForest.plain(op));
            }
        }
        return String.join(", ", ops);
    }

    /**
     * The outlines over the game: everything on screen if asked, the hovered row's node, then the selection with its
     * name.
     *
     * @param forest   the forest
     * @param detail   the selected node's fresh fields, or null
     * @param selected the selected node's key, or null
     * @param hovered  the hovered row's key, or null
     * @return the marks
     */
    private Marks marks(WidgetForest forest, WidgetDetail detail, String selected, String hovered) {
        Marks.Builder marks = new Marks.Builder(forest.getCanvas());
        if (allOnScreen.isOn()) {
            for (Row row : forest.onScreen()) {
                marks.outline(box(row.visible()), ON_SCREEN, null);
            }
        }
        WidgetNode hover = forest.node(hovered);
        if (hover != null && !hover.row().key().equals(selected)) {
            marks.outline(box(hover.row().bounds()), HOVERED, null);
        }
        WidgetNode node = forest.node(selected);
        Area area = detail != null && detail.bounds() != null ? detail.bounds()
                : node == null ? null : node.row().bounds();
        if (area != null) {
            marks.outline(box(area), SELECTED, SELECTED_FILL);
            String label = node == null ? selected : title(forest.path(selected));
            marks.label(label, new Point(area.x() + area.width() / 2, Math.max(8, area.y() - 8)), SELECTED);
        }
        return marks.build();
    }

    /**
     * A rectangle's corners.
     *
     * @param area the rectangle, or null
     * @return its corners clockwise from the top left, or null
     */
    private static Point[] box(Area area) {
        if (area == null || area.isEmpty()) {
            return null;
        }
        int x = area.x();
        int y = area.y();
        return new Point[]{new Point(x, y), new Point(x + area.width(), y),
                new Point(x + area.width(), y + area.height()), new Point(x, y + area.height())};
    }

    /**
     * What to call a node: a group's or static widget's name, a dynamic widget's index after its static root's name.
     *
     * @param path the rows from a root down to the node
     * @return e.g. {@code INVENTORY}, {@code ITEMS} or {@code ITEMS [3]}; its id where it has no name
     */
    static String title(List<Row> path) {
        if (path.isEmpty()) {
            return "";
        }
        Row row = path.get(path.size() - 1);
        return switch (row.kind()) {
            case GROUP -> row.name().isEmpty() ? "Interface " + row.id() : row.name();
            case STATIC -> row.name().isEmpty() ? row.key() : row.name();
            case DYNAMIC -> {
                Row base = row;
                for (int i = path.size() - 1; i >= 0 && base.kind() == Kind.DYNAMIC; i--) {
                    base = path.get(i);
                }
                String root = base.kind() == Kind.STATIC && !base.name().isEmpty() ? base.name()
                        : WidgetForest.component(row.id());
                yield root + " [" + row.index() + "]";
            }
        };
    }

    @Override
    protected void paintOverlay(Snapshot snapshot, OverlayContext overlay) {
        snapshot.marks().paint(overlay);
    }

    @Override
    protected ToolView<Snapshot> createView() {
        return new WidgetInspectorView(this);
    }
}
