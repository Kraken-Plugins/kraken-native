package com.kraken.client.ui.devtools.view;

import com.kraken.client.ui.Theme;
import org.kordamp.ikonli.materialdesign2.MaterialDesignA;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignM;
import org.kordamp.ikonli.materialdesign2.MaterialDesignT;
import org.kordamp.ikonli.swing.FontIcon;

import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.function.Function;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultCaret;

import static com.formdev.flatlaf.FlatClientProperties.*;

/**
 * An append-only log for event-style tools. Each snapshot is the batch of new lines since the last one. A text field
 * filters the shown lines (case-insensitive), and, when the view is given a way to tell a line's kind, a dropdown
 * narrows them to one kind. Filtering re-renders the lines kept so far, so it applies to past lines as well as new ones.
 */
public final class LogView implements ToolView<List<String>> {
    private static final int MAX_LINES = 5_000;
    private static final String ALL = "All";

    private final JTextArea text = new JTextArea();
    private final JToggleButton follow = new JToggleButton(FontIcon.of(MaterialDesignA.ARROW_COLLAPSE_DOWN, 16, Theme.LIGHT), true);
    private final JTextField filter = new JTextField();
    private final JComboBox<String> kinds = new JComboBox<>();
    private final JLabel count = new JLabel();
    private final JPanel root = new JPanel(new BorderLayout());
    private final Function<String, String> kindOf;
    private final String noun;

    /**
     * The newest lines, at most {@link #MAX_LINES}, whether shown or not.
     */
    private final Deque<String> lines = new ArrayDeque<>();
    /**
     * The kinds seen so far, sorted, as listed in the dropdown after "All".
     */
    private final TreeSet<String> seenKinds = new TreeSet<>();
    private int total;
    private int shown;

    /**
     * Builds a log with a text filter and no kind dropdown.
     */
    public LogView() {
        this("events", null);
    }

    /**
     * Builds a log with a text filter and, if {@code kindOf} is given, a dropdown of the kinds seen so far.
     *
     * @param noun   what a line is, plural, for the filter's placeholder and the count (e.g. "events")
     * @param kindOf the kind of a line (e.g. its event name), or null for no dropdown; may return null for a line
     *               with no kind, which then shows only under "All"
     */
    public LogView(String noun, Function<String, String> kindOf) {
        this.noun = noun;
        this.kindOf = kindOf;

        text.setEditable(false);
        text.putClientProperty(STYLE_CLASS, "monospaced");
        text.setBackground(Theme.DARKEST);
        text.setForeground(Theme.TEXT);
        text.setMargin(new Insets(6, 10, 6, 10));
        ((DefaultCaret) text.getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
        JScrollPane scroll = new JScrollPane(text);
        scroll.setBorder(BorderFactory.createEmptyBorder());

        follow.setSelectedIcon(FontIcon.of(MaterialDesignA.ARROW_COLLAPSE_DOWN, 16, Theme.ACCENT));
        follow.setToolTipText("Follow new " + noun);
        follow.setFocusable(false);
        follow.putClientProperty(BUTTON_TYPE, BUTTON_TYPE_TOOLBAR_BUTTON);
        count.setForeground(Theme.LIGHT);

        filter.putClientProperty(PLACEHOLDER_TEXT, "Filter " + noun);
        filter.putClientProperty(TEXT_FIELD_LEADING_ICON, FontIcon.of(MaterialDesignM.MAGNIFY, 16, Theme.LIGHT));
        filter.putClientProperty(TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
        filter.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                render();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                render();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                render();
            }
        });

        kinds.addItem(ALL);
        kinds.setToolTipText("Show only one kind of " + noun);
        kinds.setFocusable(false);
        kinds.setPrototypeDisplayValue("ItemContainerChanged");
        kinds.addActionListener(e -> render());

        Box buttons = Box.createHorizontalBox();
        buttons.add(follow);
        buttons.add(Controls.toolButton(MaterialDesignC.CONTENT_COPY, "Copy shown " + noun, e -> Toolkit
                .getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text.getText()), null)));
        buttons.add(Controls.toolButton(MaterialDesignT.TRASH_CAN_OUTLINE, "Clear", e -> clear()));

        JPanel filters = new JPanel(new BorderLayout(6, 0));
        filters.add(filter, BorderLayout.CENTER);
        if (kindOf != null) {
            filters.add(kinds, BorderLayout.WEST);
        }

        JPanel status = new JPanel(new BorderLayout());
        status.add(count, BorderLayout.WEST);
        status.add(buttons, BorderLayout.EAST);

        JPanel toolbar = new JPanel(new BorderLayout(0, 4));
        toolbar.setBorder(new EmptyBorder(8, 8, 4, 6));
        toolbar.add(filters, BorderLayout.NORTH);
        toolbar.add(status, BorderLayout.SOUTH);

        root.add(toolbar, BorderLayout.NORTH);
        root.add(scroll, BorderLayout.CENTER);
        root.setPreferredSize(new Dimension(560, 380));
        clear();
    }

    @Override
    public JComponent component() {
        return root;
    }

    @Override
    public void update(List<String> batch) {
        String query = query();
        String kind = selectedKind();
        StringBuilder appended = new StringBuilder();
        for (String line : batch) {
            lines.addLast(line);
            noteKind(line);
            if (matches(line, query, kind)) {
                appended.append(line).append('\n');
                shown++;
            }
        }
        text.append(appended.toString());
        // The shown lines are the kept lines that match, in order, so each dropped match is the text's first line
        int droppedShown = 0;
        while (lines.size() > MAX_LINES) {
            if (matches(lines.removeFirst(), query, kind)) {
                droppedShown++;
            }
        }
        removeFirstShown(droppedShown);
        total += batch.size();
        updateCount();
        scrollToEnd();
    }

    /**
     * Redraws the text area from the kept lines that pass the current filters.
     */
    private void render() {
        String query = query();
        String kind = selectedKind();
        StringBuilder all = new StringBuilder();
        shown = 0;
        for (String line : lines) {
            if (matches(line, query, kind)) {
                all.append(line).append('\n');
                shown++;
            }
        }
        text.setText(all.toString());
        updateCount();
        scrollToEnd();
    }

    /**
     * Removes the oldest shown lines.
     *
     * @param n how many lines to remove from the top of the text area
     */
    private void removeFirstShown(int n) {
        if (n <= 0) {
            return;
        }
        try {
            text.replaceRange("", 0, text.getLineEndOffset(n - 1));
            shown -= n;
        } catch (BadLocationException ignored) {
            // can't happen: the lines exist
        }
    }

    /**
     * Whether a line passes the filters.
     *
     * @param line  the line
     * @param query the lower-cased text filter, or "" for none
     * @param kind  the selected kind, or null for all
     * @return true if the line has the kind (when one is selected) and contains the text
     */
    private boolean matches(String line, String query, String kind) {
        if (kind != null && !kind.equals(kindOf.apply(line))) {
            return false;
        }
        return query.isEmpty() || line.toLowerCase(Locale.ROOT).contains(query);
    }

    /**
     * Adds a line's kind to the dropdown the first time it's seen, keeping the list sorted.
     *
     * @param line the line
     */
    private void noteKind(String line) {
        if (kindOf == null) {
            return;
        }
        String kind = kindOf.apply(line);
        if (kind != null && seenKinds.add(kind)) {
            // "All" is item 0, so a kind's index is its sorted position plus one
            kinds.insertItemAt(kind, seenKinds.headSet(kind).size() + 1);
        }
    }

    /**
     * The text filter.
     *
     * @return the field's text, trimmed and lower-cased
     */
    private String query() {
        return filter.getText().trim().toLowerCase(Locale.ROOT);
    }

    /**
     * The kind picked in the dropdown.
     *
     * @return the kind, or null for "All" or no dropdown
     */
    private String selectedKind() {
        Object selected = kinds.getSelectedItem();
        return kindOf == null || selected == null || ALL.equals(selected) ? null : selected.toString();
    }

    private void updateCount() {
        if (total == 0) {
            count.setText("Waiting for " + noun + "…");
        } else if (query().isEmpty() && selectedKind() == null) {
            count.setText(total + " " + noun);
        } else {
            count.setText(shown + " shown of " + total + " " + noun);
        }
    }

    private void scrollToEnd() {
        if (follow.isSelected()) {
            text.setCaretPosition(text.getDocument().getLength());
        }
    }

    private void clear() {
        lines.clear();
        text.setText("");
        total = 0;
        shown = 0;
        updateCount();
    }
}
