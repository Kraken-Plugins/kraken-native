package com.kraken.client.devtools.view;

import com.kraken.client.ui.Theme;
import org.kordamp.ikonli.materialdesign2.MaterialDesignM;
import org.kordamp.ikonli.swing.FontIcon;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

import static com.formdev.flatlaf.FlatClientProperties.*;

/**
 * A live table for any tool that lists things (NPCs, players, ground items, widgets, vars…). Sortable and filterable,
 * keeps the sort and selection across updates, and Ctrl+C copies the selected rows as tab-separated text.
 *
 * <pre>{@code
 * TableView.<Row>builder("NPCs")
 *         .column("Id", Integer.class, 60, Row::id)
 *         .column("Name", String.class, 160, Row::name)
 *         .key(Row::index)
 *         .build();
 * }</pre>
 *
 * @param <R> the row type, ideally a record
 */
public final class TableView<R> implements ToolView<List<R>> {
    private final List<Column<R>> columns;
    private final Function<R, ?> key;
    private final String noun;
    private final Model model = new Model();
    private final TableRowSorter<Model> sorter;
    private final JTable table;
    private final JTextField filter = new JTextField();
    private final JLabel count = new JLabel();
    private final JPanel root = new JPanel(new BorderLayout());

    private record Column<R>(String name, Class<?> type, int width, Function<R, ?> value) {
    }

    private TableView(Builder<R> builder) {
        this.columns = List.copyOf(builder.columns);
        this.key = builder.key;
        this.noun = builder.noun;

        // Built here, not as field initializers: both read the columns, which are only set above
        sorter = new TableRowSorter<>(model);
        table = new JTable(model) {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                if (getRowCount() == 0) {
                    paintEmpty((Graphics2D) g);
                }
            }
        };
        table.setRowSorter(sorter);
        table.setRowHeight(22);
        table.setFillsViewportHeight(true);
        table.setShowVerticalLines(false);
        table.getTableHeader().setReorderingAllowed(false);
        for (int i = 0; i < columns.size(); i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(columns.get(i).width());
        }
        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createEmptyBorder());

        filter.putClientProperty(PLACEHOLDER_TEXT, "Filter " + noun);
        filter.putClientProperty(TEXT_FIELD_LEADING_ICON, FontIcon.of(MaterialDesignM.MAGNIFY, 16, Theme.LIGHT));
        filter.putClientProperty(TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
        filter.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                applyFilter();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                applyFilter();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                applyFilter();
            }
        });
        count.setForeground(Theme.LIGHT);

        JPanel toolbar = new JPanel(new BorderLayout(10, 0));
        toolbar.setBorder(new EmptyBorder(8, 8, 8, 10));
        toolbar.add(filter, BorderLayout.CENTER);
        toolbar.add(count, BorderLayout.EAST);

        root.add(toolbar, BorderLayout.NORTH);
        root.add(scroll, BorderLayout.CENTER);
        root.setPreferredSize(new Dimension(Math.max(420, columns.stream().mapToInt(Column::width).sum() + 40), 420));
        updateCount();
    }

    public static <R> Builder<R> builder(String noun) {
        return new Builder<>(noun);
    }

    @Override
    public JComponent component() {
        return root;
    }

    @Override
    public void update(List<R> rows) {
        if (rows.equals(model.rows)) {
            return;
        }
        Set<Object> selected = new HashSet<>();
        for (int viewRow : table.getSelectedRows()) {
            selected.add(key.apply(model.rows.get(table.convertRowIndexToModel(viewRow))));
        }
        model.setRows(rows);
        if (!selected.isEmpty()) {
            ListSelectionModel selection = table.getSelectionModel();
            selection.setValueIsAdjusting(true);
            for (int i = 0; i < rows.size(); i++) {
                int viewRow = table.convertRowIndexToView(i);
                if (viewRow >= 0 && selected.contains(key.apply(rows.get(i)))) {
                    selection.addSelectionInterval(viewRow, viewRow);
                }
            }
            selection.setValueIsAdjusting(false);
        }
        updateCount();
    }

    private void applyFilter() {
        String text = filter.getText().trim();
        sorter.setRowFilter(text.isEmpty() ? null : RowFilter.regexFilter("(?i)" + Pattern.quote(text)));
        updateCount();
    }

    private void updateCount() {
        int total = model.getRowCount();
        int shown = table.getRowCount();
        count.setText(shown == total ? total + " " + noun : shown + " of " + total + " " + noun);
    }

    private void paintEmpty(Graphics2D g) {
        String text = model.getRowCount() == 0 ? "No " + noun : "No matches";
        Rectangle visible = table.getVisibleRect();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        g.setColor(Theme.LIGHT);
        FontMetrics metrics = g.getFontMetrics(table.getFont());
        g.setFont(table.getFont());
        g.drawString(text, visible.x + (visible.width - metrics.stringWidth(text)) / 2, visible.y + visible.height / 2);
    }

    public static final class Builder<R> {
        private final String noun;
        private final List<Column<R>> columns = new ArrayList<>();
        private Function<R, ?> key = Function.identity();

        private Builder(String noun) {
            this.noun = noun;
        }

        /** Adds a column. Number types sort numerically and align right. */
        public Builder<R> column(String name, Class<?> type, int width, Function<R, ?> value) {
            columns.add(new Column<>(name, type, width, value));
            return this;
        }

        /** What identifies a row across updates (e.g. an NPC's index), so a selection follows it. */
        public Builder<R> key(Function<R, ?> key) {
            this.key = key;
            return this;
        }

        public TableView<R> build() {
            return new TableView<>(this);
        }
    }

    private final class Model extends AbstractTableModel {
        private List<R> rows = List.of();

        void setRows(List<R> rows) {
            this.rows = rows;
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return columns.size();
        }

        @Override
        public String getColumnName(int column) {
            return columns.get(column).name();
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return columns.get(column).type();
        }

        @Override
        public Object getValueAt(int row, int column) {
            return columns.get(column).value().apply(rows.get(row));
        }
    }
}
