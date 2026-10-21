package com.kraken.client.ui.devtools.tools;

import com.kraken.api.item.EquipmentInventorySlot;
import com.kraken.client.ui.Theme;
import com.kraken.client.ui.devtools.view.ToolView;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignB;
import org.kordamp.ikonli.materialdesign2.MaterialDesignH;
import org.kordamp.ikonli.materialdesign2.MaterialDesignN;
import org.kordamp.ikonli.materialdesign2.MaterialDesignR;
import org.kordamp.ikonli.materialdesign2.MaterialDesignS;
import org.kordamp.ikonli.materialdesign2.MaterialDesignT;
import org.kordamp.ikonli.materialdesign2.MaterialDesignW;
import org.kordamp.ikonli.swing.FontIcon;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;

/**
 * The worn equipment and the backpack drawn as slots, laid out as the game's equipment and inventory tabs. Each
 * filled slot shows the item's name, its id and, for stacks, its quantity in the game's colours; a slot whose item or
 * quantity changes lights up briefly. Hovering a slot shows its details below, and clicking pins them.
 */
final class InventorySlotsView implements ToolView<InventoryTool.Snapshot> {
    private static final int TILE_W = 72;
    private static final int TILE_H = 64;
    private static final int GAP = 6;
    private static final int BACKPACK_COLUMNS = 4;
    private static final int BACKPACK_SIZE = 28;

    /**
     * How long a changed slot stays lit, in milliseconds.
     */
    private static final long FLASH_MS = 900;

    private static final Color EMPTY_FILL = new Color(0x1c1c1c);
    private static final Color EMPTY_EDGE = new Color(0x2f2f2f);
    private static final Color EMPTY_ICON = new Color(0x4a4a4a);
    private static final Color FILLED_FILL = new Color(0x262626);
    private static final Color FILLED_EDGE = new Color(0x3a3a3a);
    private static final Color FAINT = new Color(0x5c5c5c);
    private static final Color ID_TEXT = new Color(0x858585);
    private static final Color QUANTITY = new Color(0xffff00);
    private static final Color QUANTITY_K = new Color(0xffffff);
    private static final Color QUANTITY_M = new Color(0x00ff80);

    /**
     * One slot's place on a board.
     *
     * @param slot  the slot index in its inventory
     * @param col   the column on the board
     * @param row   the row on the board
     * @param icon  what an empty slot shows, or null
     * @param label the slot's name in the details
     */
    private record Cell(int slot, int col, int row, Ikon icon, String label) {
    }

    private final Board worn = new Board("Worn", wornCells(), 3, 5);
    private final Board backpack = new Board("Backpack", backpackCells(), BACKPACK_COLUMNS,
            BACKPACK_SIZE / BACKPACK_COLUMNS);
    private final JLabel wornCount = counter();
    private final JLabel backpackCount = counter();
    private final JLabel detail = new JLabel();
    private final JPanel root = new JPanel(new BorderLayout());
    private final Timer flashTimer = new Timer(33, e -> animate());
    private Board pinnedBoard;
    private int pinnedCell = -1;
    private Board hoverBoard;
    private int hoverCell = -1;
    private InventoryTool.Snapshot last;

    InventorySlotsView() {
        JPanel boards = new JPanel(new GridBagLayout());
        boards.setBorder(new EmptyBorder(14, 16, 14, 16));
        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.NORTH;
        c.gridy = 0;
        c.gridx = 0;
        boards.add(section("Equipment", wornCount, worn), c);
        c.gridx = 1;
        c.insets = new Insets(0, 28, 0, 0);
        boards.add(section("Backpack", backpackCount, backpack), c);

        JPanel center = new JPanel(new BorderLayout());
        center.add(boards, BorderLayout.NORTH);

        detail.setBorder(new EmptyBorder(9, 14, 9, 14));
        detail.setForeground(Theme.TEXT);
        JPanel footer = new JPanel(new BorderLayout());
        footer.setBackground(Theme.DARKER);
        footer.setBorder(new MatteBorder(1, 0, 0, 0, Theme.DARKEST));
        footer.add(detail, BorderLayout.CENTER);

        JScrollPane scroll = new JScrollPane(center);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        root.add(scroll, BorderLayout.CENTER);
        root.add(footer, BorderLayout.SOUTH);
        showDetail();
    }

    @Override
    public JComponent component() {
        return root;
    }

    @Override
    public void update(InventoryTool.Snapshot snapshot) {
        if (snapshot.equals(last)) {
            return;
        }
        boolean first = last == null;
        last = snapshot;
        worn.setItems(snapshot.worn(), !first);
        backpack.setItems(snapshot.backpack(), !first);
        wornCount.setText(snapshot.worn() == null ? "" : snapshot.worn().size() + " / " + worn.cells.size());
        backpackCount.setText(snapshot.backpack() == null ? ""
                : snapshot.backpack().size() + " / " + BACKPACK_SIZE + "  ·  "
                + (BACKPACK_SIZE - snapshot.backpack().size()) + " free");
        if (worn.flashing() || backpack.flashing()) {
            flashTimer.start();
        }
        showDetail();
    }

    /**
     * The equipment tab's layout: helmet on top; cape, amulet and ammo; weapon, body and shield; legs; then gloves,
     * boots and ring.
     *
     * @return the worn slots' cells
     */
    private static List<Cell> wornCells() {
        return List.of(
                wornCell(EquipmentInventorySlot.HEAD, 1, 0, MaterialDesignW.WIZARD_HAT),
                wornCell(EquipmentInventorySlot.CAPE, 0, 1, MaterialDesignH.HANGER),
                wornCell(EquipmentInventorySlot.AMULET, 1, 1, MaterialDesignN.NECKLACE),
                wornCell(EquipmentInventorySlot.AMMO, 2, 1, MaterialDesignB.BULLET),
                wornCell(EquipmentInventorySlot.WEAPON, 0, 2, MaterialDesignS.SWORD),
                wornCell(EquipmentInventorySlot.BODY, 1, 2, MaterialDesignT.TSHIRT_CREW_OUTLINE),
                wornCell(EquipmentInventorySlot.SHIELD, 2, 2, MaterialDesignS.SHIELD_OUTLINE),
                wornCell(EquipmentInventorySlot.LEGS, 1, 3, MaterialDesignW.WALK),
                wornCell(EquipmentInventorySlot.GLOVES, 0, 4, MaterialDesignB.BOXING_GLOVE),
                wornCell(EquipmentInventorySlot.BOOTS, 1, 4, MaterialDesignS.SHOE_FORMAL),
                wornCell(EquipmentInventorySlot.RING, 2, 4, MaterialDesignR.RING));
    }

    /**
     * A worn slot's cell.
     *
     * @param slot the equipment slot
     * @param col  the column
     * @param row  the row
     * @param icon what the slot shows when empty
     * @return the cell
     */
    private static Cell wornCell(EquipmentInventorySlot slot, int col, int row, Ikon icon) {
        String name = slot.name();
        return new Cell(slot.getSlotIdx(), col, row, icon, name.charAt(0) + name.substring(1).toLowerCase(Locale.ROOT));
    }

    /**
     * The backpack's layout: four columns, filled left to right and top to bottom.
     *
     * @return the backpack's cells
     */
    private static List<Cell> backpackCells() {
        List<Cell> cells = new ArrayList<>();
        for (int slot = 0; slot < BACKPACK_SIZE; slot++) {
            cells.add(new Cell(slot, slot % BACKPACK_COLUMNS, slot / BACKPACK_COLUMNS, null, "slot " + slot));
        }
        return cells;
    }

    /**
     * A board under its title, with a counter on the right.
     *
     * @param title   the section's title
     * @param counter the counter label
     * @param board   the board
     * @return the section
     */
    private static JComponent section(String title, JLabel counter, Board board) {
        JLabel label = new JLabel(title.toUpperCase(Locale.ROOT));
        Font small = UIManager.getFont("small.font");
        label.setFont((small != null ? small : label.getFont()).deriveFont(Font.BOLD));
        label.setForeground(Theme.LIGHT);

        JPanel header = new JPanel(new BorderLayout(12, 0));
        header.setBorder(new EmptyBorder(0, 2, 8, 2));
        header.add(label, BorderLayout.WEST);
        header.add(counter, BorderLayout.EAST);

        JPanel section = new JPanel(new BorderLayout());
        section.add(header, BorderLayout.NORTH);
        section.add(board, BorderLayout.CENTER);
        return section;
    }

    /**
     * A section's counter label.
     *
     * @return the label
     */
    private static JLabel counter() {
        JLabel label = new JLabel();
        Font small = UIManager.getFont("small.font");
        if (small != null) {
            label.setFont(small);
        }
        label.setForeground(FAINT);
        return label;
    }

    /**
     * Repaints lit slots, and stops the timer once none are lit.
     */
    private void animate() {
        worn.repaint();
        backpack.repaint();
        if (!worn.flashing() && !backpack.flashing()) {
            flashTimer.stop();
        }
    }

    /**
     * Handles the pointer entering or leaving a slot.
     *
     * @param board the board
     * @param cell  the cell's index on the board, or -1 when off every slot
     */
    private void hover(Board board, int cell) {
        if (cell < 0 && hoverBoard != board) {
            return;
        }
        hoverBoard = cell < 0 ? null : board;
        hoverCell = cell;
        showDetail();
    }

    /**
     * Pins a slot's details, or unpins them if that slot is already pinned.
     *
     * @param board the board
     * @param cell  the cell's index on the board
     */
    private void click(Board board, int cell) {
        boolean same = pinnedBoard == board && pinnedCell == cell;
        if (pinnedBoard != null) {
            pinnedBoard.repaint();
        }
        pinnedBoard = same ? null : board;
        pinnedCell = same ? -1 : cell;
        board.repaint();
        showDetail();
    }

    /**
     * Fills the footer with the hovered slot, else the pinned one, else a hint.
     */
    private void showDetail() {
        Board board = hoverBoard != null ? hoverBoard : pinnedBoard;
        int cell = hoverBoard != null ? hoverCell : pinnedCell;
        if (board == null || cell < 0) {
            detail.setText(html(muted(last == null ? "Waiting for the game" : "Hover a slot for details, click to pin")));
            return;
        }
        Cell where = board.cells.get(cell);
        String place = board.title + " · " + where.label()
                + (board == worn ? " " + muted("(slot " + where.slot() + ")") : "");
        InventoryTool.Row item = board.items.get(where.slot());
        if (item == null) {
            detail.setText(html(place + "&nbsp;&nbsp;&nbsp;" + muted(board.present ? "empty" : "not loaded")));
            return;
        }
        String name = item.name().isEmpty() ? muted("unnamed") : "<b>" + Theme.escape(item.name()) + "</b>";
        detail.setText(html(place + "&nbsp;&nbsp;&nbsp;" + name
                + "&nbsp;&nbsp;&nbsp;" + muted("id") + " " + item.item()
                + "&nbsp;&nbsp;&nbsp;" + muted("×") + " " + NumberFormat.getIntegerInstance(Locale.ROOT).format(item.count())
                + "&nbsp;&nbsp;&nbsp;" + muted(Boolean.TRUE.equals(item.stackable()) ? "stacks" : "doesn't stack")));
    }

    private static String html(String body) {
        return "<html>" + body + "</html>";
    }

    private static String muted(String text) {
        return "<span style='color:" + Theme.hex(Theme.LIGHT) + "'>" + text + "</span>";
    }

    /**
     * A stack's quantity as the game writes it on a slot: the number below 100,000, thousands with a K below ten
     * million, millions with an M above.
     *
     * @param count the quantity
     * @return the text
     */
    static String quantityText(int count) {
        if (count < 100_000) {
            return Integer.toString(count);
        }
        if (count < 10_000_000) {
            return count / 1_000 + "K";
        }
        return count / 1_000_000 + "M";
    }

    /**
     * The colour the game writes a stack's quantity in: yellow, white for thousands, green for millions.
     *
     * @param count the quantity
     * @return the colour
     */
    static Color quantityColor(int count) {
        if (count < 100_000) {
            return QUANTITY;
        }
        return count < 10_000_000 ? QUANTITY_K : QUANTITY_M;
    }

    /**
     * A muted colour picked from an item id, so the same item keeps the same tint everywhere.
     *
     * @param item the item id
     * @return the colour
     */
    private static Color tint(int item) {
        float hue = ((item * 0x9E3779B1) >>> 8 & 0xffff) / 65536f;
        return Color.getHSBColor(hue, 0.5f, 0.9f);
    }

    /**
     * Mixes two colours.
     *
     * @param base   the first colour
     * @param over   the second colour
     * @param amount how much of the second, 0 to 1
     * @return the mix
     */
    private static Color mix(Color base, Color over, float amount) {
        float keep = 1 - amount;
        return new Color(Math.round(base.getRed() * keep + over.getRed() * amount),
                Math.round(base.getGreen() * keep + over.getGreen() * amount),
                Math.round(base.getBlue() * keep + over.getBlue() * amount));
    }

    /**
     * A colour at a given opacity.
     *
     * @param color the colour
     * @param alpha the opacity, 0 to 1
     * @return the colour with that opacity
     */
    private static Color alpha(Color color, float alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), Math.round(255 * Math.max(0, alpha)));
    }

    /**
     * Breaks an item's name into lines that fit a slot, ending the last line with an ellipsis if it doesn't all fit.
     *
     * @param text     the name
     * @param metrics  the font's metrics
     * @param width    the line width
     * @param maxLines the most lines
     * @return the lines
     */
    static List<String> wrap(String text, FontMetrics metrics, int width, int maxLines) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.trim().split("\\s+")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (metrics.stringWidth(candidate) <= width) {
                line.setLength(0);
                line.append(candidate);
                continue;
            }
            if (!line.isEmpty()) {
                lines.add(line.toString());
                line.setLength(0);
            }
            while (metrics.stringWidth(word) > width && word.length() > 1) {
                int cut = word.length() - 1;
                while (cut > 1 && metrics.stringWidth(word.substring(0, cut)) > width) {
                    cut--;
                }
                lines.add(word.substring(0, cut));
                word = word.substring(cut);
            }
            line.append(word);
        }
        if (!line.isEmpty()) {
            lines.add(line.toString());
        }
        if (lines.size() <= maxLines) {
            return lines;
        }
        List<String> kept = new ArrayList<>(lines.subList(0, maxLines));
        String last = kept.get(maxLines - 1);
        while (!last.isEmpty() && metrics.stringWidth(last + "…") > width) {
            last = last.substring(0, last.length() - 1);
        }
        kept.set(maxLines - 1, last.stripTrailing() + "…");
        return kept;
    }

    /**
     * One inventory's slots, painted as tiles.
     */
    private final class Board extends JComponent {
        private final String title;
        private final List<Cell> cells;
        private final long[] changedAt;
        private Map<Integer, InventoryTool.Row> items = Map.of();
        private boolean present;

        Board(String title, List<Cell> cells, int columns, int rows) {
            this.title = title;
            this.cells = cells;
            this.changedAt = new long[cells.size()];
            setPreferredSize(new Dimension(columns * TILE_W + (columns - 1) * GAP, rows * TILE_H + (rows - 1) * GAP));
            setMinimumSize(getPreferredSize());
            MouseAdapter mouse = new MouseAdapter() {
                @Override
                public void mouseMoved(MouseEvent e) {
                    int cell = cellAt(e.getPoint());
                    if (hoverBoard != Board.this || hoverCell != cell) {
                        hover(Board.this, cell);
                        repaint();
                    }
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hover(Board.this, -1);
                    repaint();
                }

                @Override
                public void mousePressed(MouseEvent e) {
                    int cell = cellAt(e.getPoint());
                    if (cell >= 0) {
                        click(Board.this, cell);
                    }
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
        }

        /**
         * Shows new contents, lighting up slots whose item or quantity changed.
         *
         * @param rows  the filled slots' rows, or null when the client doesn't hold this inventory
         * @param flash whether to light up changed slots; slots never light up as the inventory first appears
         */
        void setItems(List<InventoryTool.Row> rows, boolean flash) {
            Map<Integer, InventoryTool.Row> next = new HashMap<>();
            if (rows != null) {
                for (InventoryTool.Row row : rows) {
                    next.put(row.slot(), row);
                }
            }
            long now = System.currentTimeMillis();
            for (int i = 0; i < cells.size(); i++) {
                int slot = cells.get(i).slot();
                InventoryTool.Row before = items.get(slot);
                InventoryTool.Row after = next.get(slot);
                boolean changed = !Objects.equals(before == null ? null : before.item(), after == null ? null : after.item())
                        || (before != null && after != null && before.count() != after.count());
                if (flash && present && rows != null && changed) {
                    changedAt[i] = now;
                }
            }
            items = next;
            present = rows != null;
            repaint();
        }

        /**
         * Whether any slot is still lit.
         *
         * @return true while a change is showing
         */
        boolean flashing() {
            long now = System.currentTimeMillis();
            for (long at : changedAt) {
                if (now - at < FLASH_MS) {
                    return true;
                }
            }
            return false;
        }

        /**
         * The cell under a point.
         *
         * @param p the point, in this board's coordinates
         * @return the cell's index, or -1
         */
        private int cellAt(Point p) {
            int col = p.x / (TILE_W + GAP);
            int row = p.y / (TILE_H + GAP);
            if (p.x < 0 || p.y < 0 || p.x - col * (TILE_W + GAP) >= TILE_W || p.y - row * (TILE_H + GAP) >= TILE_H) {
                return -1;
            }
            for (int i = 0; i < cells.size(); i++) {
                if (cells.get(i).col() == col && cells.get(i).row() == row) {
                    return i;
                }
            }
            return -1;
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            Object hints = Toolkit.getDefaultToolkit().getDesktopProperty("awt.font.desktophints");
            if (hints instanceof Map<?, ?> map) {
                g.addRenderingHints(map);
            } else {
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            }
            if (!present) {
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.45f));
            }
            long now = System.currentTimeMillis();
            for (int i = 0; i < cells.size(); i++) {
                paintCell(g, i, now);
            }
            if (!present && last != null) {
                g.setComposite(AlphaComposite.SrcOver);
                String text = "Not loaded";
                g.setFont(getFont().deriveFont(Font.BOLD));
                FontMetrics fm = g.getFontMetrics();
                g.setColor(Theme.LIGHT);
                g.drawString(text, (getWidth() - fm.stringWidth(text)) / 2, getHeight() / 2 + fm.getAscent() / 2);
            }
            g.dispose();
        }

        /**
         * Paints one slot.
         *
         * @param g   the graphics
         * @param i   the cell's index
         * @param now the current time, for the change highlight
         */
        private void paintCell(Graphics2D g, int i, long now) {
            Cell cell = cells.get(i);
            int x = cell.col() * (TILE_W + GAP);
            int y = cell.row() * (TILE_H + GAP);
            RoundRectangle2D shape = new RoundRectangle2D.Float(x + 0.5f, y + 0.5f, TILE_W - 1, TILE_H - 1, 10, 10);
            InventoryTool.Row item = items.get(cell.slot());
            boolean hovered = hoverBoard == this && hoverCell == i;
            boolean pinned = pinnedBoard == this && pinnedCell == i;

            Color edge;
            if (item == null) {
                g.setColor(hovered ? mix(EMPTY_FILL, Theme.TEXT, 0.04f) : EMPTY_FILL);
                g.fill(shape);
                edge = EMPTY_EDGE;
                if (cell.icon() != null) {
                    FontIcon icon = FontIcon.of(cell.icon(), 24, EMPTY_ICON);
                    icon.paintIcon(this, g, x + (TILE_W - icon.getIconWidth()) / 2, y + (TILE_H - icon.getIconHeight()) / 2);
                }
            } else {
                Color tint = tint(item.item());
                g.setColor(mix(FILLED_FILL, tint, hovered ? 0.16f : 0.10f));
                g.fill(shape);
                edge = mix(FILLED_EDGE, tint, 0.30f);
                paintItem(g, item, x, y);
            }

            Font mono = mono(9f);
            g.setFont(mono);
            String index = Integer.toString(cell.slot());
            g.setColor(FAINT);
            g.drawString(index, x + TILE_W - 5 - g.getFontMetrics().stringWidth(index), y + 12);

            float lit = 1 - (now - changedAt[i]) / (float) FLASH_MS;
            if (lit > 0) {
                g.setColor(alpha(Theme.ACCENT, 0.35f * lit));
                g.fill(shape);
                edge = mix(edge, Theme.ACCENT, lit);
            }
            if (pinned) {
                edge = Theme.ACCENT;
            } else if (hovered) {
                edge = mix(edge, Theme.TEXT, 0.45f);
            }
            g.setColor(edge);
            g.setStroke(new BasicStroke(pinned ? 2f : 1f));
            g.draw(pinned ? new RoundRectangle2D.Float(x + 1, y + 1, TILE_W - 2, TILE_H - 2, 9, 9) : shape);
        }

        /**
         * Paints a filled slot's quantity, name and id.
         *
         * @param g    the graphics
         * @param item the slot's row
         * @param x    the slot's left
         * @param y    the slot's top
         */
        private void paintItem(Graphics2D g, InventoryTool.Row item, int x, int y) {
            int nameTop = y + 15;
            if (Boolean.TRUE.equals(item.stackable()) || item.count() > 1) {
                g.setFont(getFont().deriveFont(Font.BOLD, 10.5f));
                String text = quantityText(item.count());
                g.setColor(Color.BLACK);
                g.drawString(text, x + 6, y + 14);
                g.setColor(quantityColor(item.count()));
                g.drawString(text, x + 5, y + 13);
            }

            g.setFont(getFont().deriveFont(10f));
            FontMetrics fm = g.getFontMetrics();
            int nameBottom = y + TILE_H - 13;
            int maxLines = Math.max(1, (nameBottom - nameTop) / fm.getHeight());
            List<String> lines = wrap(item.name().isEmpty() ? "?" : item.name(), fm, TILE_W - 10, maxLines);
            int block = lines.size() * fm.getHeight();
            int baseline = nameTop + Math.max(0, (nameBottom - nameTop - block) / 2) + fm.getAscent();
            g.setColor(Theme.TEXT);
            for (String line : lines) {
                g.drawString(line, x + (TILE_W - fm.stringWidth(line)) / 2, baseline);
                baseline += fm.getHeight();
            }

            g.setFont(mono(9f));
            String id = Integer.toString(item.item());
            g.setColor(ID_TEXT);
            g.drawString(id, x + TILE_W - 5 - g.getFontMetrics().stringWidth(id), y + TILE_H - 5);
        }

        /**
         * The monospaced font at a size.
         *
         * @param size the point size
         * @return the font
         */
        private Font mono(float size) {
            Font font = UIManager.getFont("monospaced.font");
            return (font != null ? font : new Font(Font.MONOSPACED, Font.PLAIN, 12)).deriveFont(size);
        }
    }
}
