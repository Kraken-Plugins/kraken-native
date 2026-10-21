package com.kraken.client.ui.devtools.tools;

import com.kraken.api.Client;
import com.kraken.api.Point;
import com.kraken.api.definition.ItemComposition;
import com.kraken.api.widgets.Widget;
import com.kraken.api.widgets.WidgetType;
import com.kraken.client.ui.devtools.tools.WidgetForest.Area;
import com.kraken.client.ui.devtools.tools.WidgetForest.GroupInfo;
import com.kraken.client.ui.devtools.tools.WidgetForest.Kind;
import com.kraken.client.ui.devtools.tools.WidgetForest.Row;

import java.awt.Color;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The selected node's fields, read fresh each poll and grouped into sections for the detail panel. Fields left at
 * their empty value (no sprite, no text, no rotation) are left out. Immutable.
 *
 * @param key      the node's key
 * @param bounds   where the widget is on the canvas now, with its parents' scroll taken off; a group's clip; null if
 *                 it can't be read
 * @param gone     the node is no longer there (its group closed, or a script removed it)
 * @param sections the fields, by section
 */
record WidgetDetail(String key, Area bounds, boolean gone, List<Section> sections) {

    /**
     * How far up the parent chain the position is summed.
     */
    private static final int MAX_DEPTH = 64;

    /**
     * A titled list of fields.
     *
     * @param title  the section's title
     * @param fields its fields, in order
     */
    record Section(String title, List<Field> fields) {
    }

    /**
     * Remembers when the selected widget was last seen drawn.
     */
    @FunctionalInterface
    interface DrawMemory {
        /**
         * Notes one read of the widget's draw stamp.
         *
         * @param key      the widget's key
         * @param drawnNow whether this read saw it drawn in the client's latest frame
         * @return whether a read saw it drawn recently
         */
        boolean drawnRecently(String key, boolean drawnNow);
    }

    /**
     * One field.
     *
     * @param label   what it is
     * @param value   its value as text
     * @param swatch  a colour to show beside the value, or null
     * @param tooltip more about the value (text with its markup), or null
     */
    record Field(String label, String value, Color swatch, String tooltip) {
    }

    /**
     * Reads a node's fields.
     *
     * @param client the client
     * @param forest the forest the node was selected in
     * @param key    the node's key
     * @param names  the cache's names
     * @param drawn  remembers when the widget was last seen drawn
     * @return the fields; {@link #gone} set if the node isn't in the forest or can't be read
     */
    static WidgetDetail read(Client client, WidgetForest forest, String key, WidgetNames names, DrawMemory drawn) {
        List<Row> path = forest.path(key);
        if (path.isEmpty()) {
            return new WidgetDetail(key, null, true, List.of());
        }
        Row row = path.get(path.size() - 1);
        if (row.isGroup()) {
            return group(forest, row, names);
        }
        Widget widget = fetch(client, path);
        if (widget == null) {
            return new WidgetDetail(key, null, true, List.of());
        }
        return widget(client, path, widget, names, drawn);
    }

    /**
     * Reads a widget again: its static root by id, then down the dynamic indices.
     *
     * @param client the client
     * @param path   the rows from a root down to the widget
     * @return the widget, or null if the client no longer has it
     */
    private static Widget fetch(Client client, List<Row> path) {
        int root = path.size() - 1;
        while (root > 0 && path.get(root).kind() == Kind.DYNAMIC) {
            root--;
        }
        Widget widget = client.getWidget(path.get(root).id());
        for (int i = root + 1; i < path.size() && widget != null; i++) {
            widget = widget.getChild(path.get(i).index());
        }
        return widget;
    }

    /**
     * A group's fields, from the forest.
     *
     * @param forest the forest
     * @param row    the group's row
     * @param names  the cache's names
     * @return the fields
     */
    private static WidgetDetail group(WidgetForest forest, Row row, WidgetNames names) {
        GroupInfo info = forest.groupInfo(row.id());
        Sections out = new Sections();
        out.section("Interface")
                .add("Group", Integer.toString(row.id()))
                .addIf(!names.group(row.id()).isEmpty(), "Name", names.group(row.id()))
                .add("Widgets", Integer.toString(info == null ? 0 : info.components()));
        if (info != null) {
            WidgetForest.WidgetNode mount = forest.node(info.mount());
            out.section("Placement")
                    .add("Opened in", mount == null ? "nothing (a root)"
                            : label(mount.row()) + (info.guessed() ? "  (guessed from where the group is drawn)" : ""))
                    .add("Offset", info.offX() + ", " + info.offY())
                    .addIf(info.scriptOffX() != 0 || info.scriptOffY() != 0, "Script offset",
                            info.scriptOffX() + ", " + info.scriptOffY())
                    .addIf(info.clip() != null, "Clip", area(info.clip()));
        }
        return new WidgetDetail(row.key(), info == null ? null : info.clip(), false, out.build());
    }

    /**
     * A widget's fields, from a fresh read of it.
     *
     * @param client the client
     * @param path   the rows from a root down to the widget
     * @param w      the widget, read now
     * @param names  the cache's names
     * @param drawn  remembers when the widget was last seen drawn
     * @return the fields
     */
    private static WidgetDetail widget(Client client, List<Row> path, Widget w, WidgetNames names,
                                       DrawMemory drawn) {
        Row row = path.get(path.size() - 1);
        Row parent = path.size() > 1 ? path.get(path.size() - 2) : null;
        Area bounds = canvasBounds(w);
        Sections out = new Sections();
        int type = w.getType();

        out.section("Identity")
                .add("Id", row.key() + "   0x" + String.format("%08x", w.getId()) + "   " + w.getId())
                .add("Type", WidgetType.name(type) + " (" + type + ")")
                .add("Parent", parent == null ? "none" : label(parent))
                .addIf(!w.getSourceName().isEmpty(), "Source name", w.getSourceName())
                .addIf(type == WidgetType.LAYER && w.getLayerIdXMax() >= 0, "Child range",
                        w.getLayerIdXMin() + " to " + w.getLayerIdXMax());

        Sections.Builder layout = out.section("Layout")
                .add("Canvas", bounds == null ? "?" : bounds.x() + ", " + bounds.y())
                .add("Size", w.getWidth() + " × " + w.getHeight())
                .add("Relative", w.getRelativeX() + ", " + w.getRelativeY())
                .add("Designed x", w.getOriginalX() + "  " + xMode(w.getXPositionMode()))
                .add("Designed y", w.getOriginalY() + "  " + yMode(w.getYPositionMode()))
                .add("Designed size", w.getOriginalWidth() + "  " + sizeMode(w.getWidthMode()) + "   "
                        + w.getOriginalHeight() + "  " + sizeMode(w.getHeightMode()));
        if (w.getScrollWidth() > 0 || w.getScrollHeight() > 0) {
            layout.add("Scroll", w.getScrollX() + ", " + w.getScrollY() + " of " + w.getScrollWidth() + " × "
                    + w.getScrollHeight());
        }
        Point aspect = w.getAspectRatio();
        layout.addIf(aspect != null && (aspect.getX() != 0 || aspect.getY() != 0), "Aspect ratio",
                aspect == null ? "" : aspect.getX() + " : " + aspect.getY());
        layout.addIf(row.visible() != null && !row.visible().equals(row.bounds()), "Visible part",
                area(row.visible()));

        content(client, out, w, names);

        String[] actions = w.getActions();
        Sections.Builder ops = out.section("Actions")
                .addIf(!w.getName().isEmpty(), "Acts on", WidgetForest.plain(w.getName()), w.getName());
        if (actions != null) {
            for (int i = 0; i < actions.length; i++) {
                ops.addIf(actions[i] != null && !actions[i].isEmpty(), "Op " + (i + 1), WidgetForest.plain(
                        actions[i] == null ? "" : actions[i]), null);
            }
        }

        String onScreen = w.isSelfHidden() ? "no, hidden by its own flag" : w.isHidden() ? "no, hidden by a parent"
                : row.visible() != null && row.visible().isEmpty() ? "no, outside its parent's area" : "yes";
        String seconds = WidgetInspectorTool.DRAWN_MEMORY_SECONDS + " seconds";
        out.section("State")
                .add("On screen", onScreen)
                .add("Drawn", drawn.drawnRecently(row.key(), w.isRendering()) ? "in the last " + seconds
                        : "not in the last " + seconds)
                .addIf(w.getEvent() > 0 || w.getEventCode() > 0, "Event", w.getEvent() + ", code " + w.getEventCode())
                .addIf(w.getOverlayType() > 0, "Overlay", "type " + w.getOverlayType() + ", id " + w.getOverlayId())
                .addIf(w.isEntityOverlay(), "Entity overlay", "yes")
                .add("Address", "0x" + Long.toHexString(w.getAddress()));
        return new WidgetDetail(row.key(), bounds, false, out.build());
    }

    /**
     * Adds what the widget shows: its text, item, sprite, model and colours, by what its type uses.
     *
     * @param client the client, for the item's name
     * @param out    where the sections go
     * @param w      the widget
     * @param names  the cache's names
     */
    private static void content(Client client, Sections out, Widget w, WidgetNames names) {
        int type = w.getType();
        Sections.Builder content = out.section("Content")
                .addIf(!w.getText().isEmpty(), "Text", WidgetForest.plain(w.getText()), w.getText())
                .addIf(!w.getText2().isEmpty(), "Text 2", WidgetForest.plain(w.getText2()), w.getText2());
        if (w.getItemId() >= 0) {
            ItemComposition item = client.getItemDefinition(w.getItemId());
            String name = item == null ? "" : item.getName();
            content.add("Item", w.getItemId() + (name == null || name.isEmpty() ? "" : "  " + name) + "  ×"
                    + NumberFormat.getIntegerInstance(Locale.ROOT).format(w.getItemQuantity()));
        }
        content.addIf(w.getSpriteId() >= 0, "Sprite", sprite(w.getSpriteId(), names))
                .addIf(w.getSpriteId2() >= 0, "Sprite 2", sprite(w.getSpriteId2(), names))
                .addIf(!w.getSpriteUrl().isEmpty(), "Sprite URL", w.getSpriteUrl())
                .addIf(w.getSpriteTiling(), "Tiled", "yes")
                .addIf(w.getSpriteRotation() > 0, "Rotation", Integer.toString(w.getSpriteRotation()))
                .addIf(w.getBorderType() > 0, "Outline", Integer.toString(w.getBorderType()))
                .addIf(w.isFlippedHorizontally() || w.isFlippedVertically(), "Flipped",
                        (w.isFlippedHorizontally() ? "across " : "") + (w.isFlippedVertically() ? "down" : ""));
        if (w.getModelId() >= 0) {
            content.add("Model", w.getModelId() + "  type " + w.getModelType())
                    .addIf(w.getModelId2() >= 0, "Model 2", w.getModelId2() + "  type " + w.getModelType2())
                    .addIf(w.getAnimationId() >= 0, "Animation", Integer.toString(w.getAnimationId()))
                    .addIf(w.getAnimationId2() >= 0, "Animation 2", Integer.toString(w.getAnimationId2()))
                    .add("Zoom", Integer.toString(w.getModelZoom()))
                    .add("Rotation", w.getRotationX() + ", " + w.getRotationY() + ", " + w.getRotationZ())
                    .addIf(w.getModelOffsetX() != 0 || w.getModelOffsetY() != 0, "Offset",
                            w.getModelOffsetX() + ", " + w.getModelOffsetY())
                    .addIf(w.getModelSpin() != 0, "Spin", Integer.toString(w.getModelSpin()))
                    .addIf(w.isOrthographic(), "Orthographic", "yes");
        }
        boolean shape = type == WidgetType.TEXT || type == WidgetType.RECTANGLE || type == WidgetType.LINE
                || type == WidgetType.CIRCLE;
        colour(content, "Colour", w.getTextColor(), shape);
        colour(content, "Colour 2", w.getTextColor2(), false);
        colour(content, "Colour over", w.getTextColorOver(), false);
        colour(content, "Shadow", w.getTextShadowColor(), false);
        content.addIf(w.getOpacity() > 0, "Transparency", w.getOpacity() + " of 255")
                .addIf(type == WidgetType.RECTANGLE, "Filled", w.isFilled() ? "yes" : "no")
                .addIf(w.getBlendMode() > 0, "Blend mode", Integer.toString(w.getBlendMode()))
                .addIf(w.getLineWidth() > 1, "Line width", Integer.toString(w.getLineWidth()))
                .addIf(type == WidgetType.CIRCLE, "Arc", w.getStartAngle() + " to " + w.getStopAngle());
    }

    /**
     * Adds a colour with its swatch.
     *
     * @param section where it goes
     * @param label   what it is
     * @param rgb     the colour as {@code 0xRRGGBB}, or -1 if unknown
     * @param always  add black too, which for other fields means none
     */
    private static void colour(Sections.Builder section, String label, int rgb, boolean always) {
        if (rgb < 0 || rgb == 0 && !always) {
            return;
        }
        section.add(new Field(label, String.format("#%06x", rgb), new Color(rgb), null));
    }

    /**
     * Where a widget is on the canvas: its x and y summed up the parent chain within its group, less each parent's
     * scroll, plus where its group is drawn. It stops at the group's top rather than going on into the component the
     * group is opened in, which the group's offset already accounts for.
     *
     * @param widget the widget
     * @return its area, or null if a parent can't be read
     */
    static Area canvasBounds(Widget widget) {
        long x = 0;
        long y = 0;
        Widget at = widget;
        for (int depth = 0; depth < MAX_DEPTH; depth++) {
            x += at.getRelativeX();
            y += at.getRelativeY();
            Widget parent = at.getParent();
            if (parent == null || parent.getId() >>> 16 != at.getId() >>> 16) {
                Point offset = at.getGroupOffset();
                Point script = at.getGroupScriptOffset();
                if (offset != null) {
                    x += offset.getX() + (script == null ? 0 : script.getX());
                    y += offset.getY() + (script == null ? 0 : script.getY());
                }
                return new Area((int) x, (int) y, widget.getWidth(), widget.getHeight());
            }
            x -= Math.max(0, parent.getScrollX());
            y -= Math.max(0, parent.getScrollY());
            at = parent;
        }
        return null;
    }

    /**
     * A node as a field shows it.
     *
     * @param row the node's row
     * @return e.g. {@code 149:0 ITEMS}
     */
    private static String label(Row row) {
        String id = row.isGroup() ? "interface " + row.id() : row.kind() == Kind.DYNAMIC ? row.key() : row.idText();
        return row.name().isEmpty() ? id : id + "  " + row.name();
    }

    private static String sprite(int id, WidgetNames names) {
        String name = names.sprite(id);
        return name.isEmpty() ? Integer.toString(id) : id + "  " + name;
    }

    private static String area(Area area) {
        return area.x() + ", " + area.y() + "   " + area.width() + " × " + area.height();
    }

    private static String xMode(int mode) {
        return switch (mode) {
            case 0 -> "from the left";
            case 1 -> "from the centre";
            case 2 -> "from the right";
            case 3 -> "16384ths from the left";
            case 4 -> "16384ths from the centre";
            case 5 -> "16384ths from the right";
            default -> "mode " + mode;
        };
    }

    private static String yMode(int mode) {
        return switch (mode) {
            case 0 -> "from the top";
            case 1 -> "from the centre";
            case 2 -> "from the bottom";
            case 3 -> "16384ths from the top";
            case 4 -> "16384ths from the centre";
            case 5 -> "16384ths from the bottom";
            default -> "mode " + mode;
        };
    }

    private static String sizeMode(int mode) {
        return switch (mode) {
            case 0 -> "absolute";
            case 1 -> "parent minus";
            case 2 -> "16384ths of parent";
            default -> "mode " + mode;
        };
    }

    /**
     * Collects sections, dropping the ones left empty.
     */
    private static final class Sections {
        private final List<Builder> sections = new ArrayList<>();

        Builder section(String title) {
            Builder builder = new Builder(title);
            sections.add(builder);
            return builder;
        }

        List<Section> build() {
            return sections.stream().filter(b -> !b.fields.isEmpty())
                    .map(b -> new Section(b.title, List.copyOf(b.fields))).toList();
        }

        /**
         * One section's fields.
         */
        private static final class Builder {
            private final String title;
            private final List<Field> fields = new ArrayList<>();

            Builder(String title) {
                this.title = title;
            }

            Builder add(Field field) {
                fields.add(field);
                return this;
            }

            Builder add(String label, String value) {
                return add(new Field(label, value, null, null));
            }

            Builder addIf(boolean when, String label, String value) {
                return when ? add(label, value) : this;
            }

            Builder addIf(boolean when, String label, String value, String tooltip) {
                return when ? add(new Field(label, value, null, tooltip)) : this;
            }
        }
    }
}
