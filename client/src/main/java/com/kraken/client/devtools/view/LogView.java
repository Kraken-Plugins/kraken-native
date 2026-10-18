package com.kraken.client.devtools.view;

import com.kraken.client.ui.Theme;
import org.kordamp.ikonli.materialdesign2.MaterialDesignA;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignT;
import org.kordamp.ikonli.swing.FontIcon;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultCaret;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.util.List;

import static com.formdev.flatlaf.FlatClientProperties.*;

/** An append-only log for event-style tools. Each snapshot is the batch of new lines since the last one. */
public final class LogView implements ToolView<List<String>> {
    private static final int MAX_LINES = 5_000;

    private final JTextArea text = new JTextArea();
    private final JToggleButton follow = new JToggleButton(FontIcon.of(MaterialDesignA.ARROW_COLLAPSE_DOWN, 16, Theme.LIGHT), true);
    private final JLabel count = new JLabel();
    private final JPanel root = new JPanel(new BorderLayout());
    private int total;

    public LogView() {
        text.setEditable(false);
        text.putClientProperty(STYLE_CLASS, "monospaced");
        text.setBackground(Theme.DARKEST);
        text.setForeground(Theme.TEXT);
        text.setMargin(new Insets(6, 10, 6, 10));
        ((DefaultCaret) text.getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
        JScrollPane scroll = new JScrollPane(text);
        scroll.setBorder(BorderFactory.createEmptyBorder());

        follow.setSelectedIcon(FontIcon.of(MaterialDesignA.ARROW_COLLAPSE_DOWN, 16, Theme.ACCENT));
        follow.setToolTipText("Follow new events");
        follow.setFocusable(false);
        follow.putClientProperty(BUTTON_TYPE, BUTTON_TYPE_TOOLBAR_BUTTON);
        count.setForeground(Theme.LIGHT);

        Box buttons = Box.createHorizontalBox();
        buttons.add(follow);
        buttons.add(Controls.toolButton(MaterialDesignC.CONTENT_COPY, "Copy all", e -> Toolkit.getDefaultToolkit()
                .getSystemClipboard().setContents(new StringSelection(text.getText()), null)));
        buttons.add(Controls.toolButton(MaterialDesignT.TRASH_CAN_OUTLINE, "Clear", e -> clear()));

        JPanel toolbar = new JPanel(new BorderLayout());
        toolbar.setBorder(new EmptyBorder(4, 10, 4, 6));
        toolbar.add(count, BorderLayout.WEST);
        toolbar.add(buttons, BorderLayout.EAST);

        root.add(toolbar, BorderLayout.NORTH);
        root.add(scroll, BorderLayout.CENTER);
        root.setPreferredSize(new Dimension(520, 360));
        clear();
    }

    @Override
    public JComponent component() {
        return root;
    }

    @Override
    public void update(List<String> lines) {
        for (String line : lines) {
            text.append(line + "\n");
        }
        int excess = text.getLineCount() - 1 - MAX_LINES;
        if (excess > 0) {
            try {
                text.replaceRange("", 0, text.getLineEndOffset(excess - 1));
            } catch (BadLocationException ignored) {
                // can't happen: the line exists
            }
        }
        total += lines.size();
        count.setText(total + (total == 1 ? " event" : " events"));
        if (follow.isSelected()) {
            text.setCaretPosition(text.getDocument().getLength());
        }
    }

    private void clear() {
        text.setText("");
        total = 0;
        count.setText("Waiting for events…");
    }
}
