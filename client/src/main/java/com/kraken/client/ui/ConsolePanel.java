package com.kraken.client.ui;

import com.google.inject.Singleton;
import lombok.Getter;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignA;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignT;
import org.kordamp.ikonli.swing.FontIcon;

import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.logging.*;
import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import javax.swing.text.*;

import static com.formdev.flatlaf.FlatClientProperties.*;

/**
 * Collapsible terminal-style log console docked at the bottom. Feed it by adding {@link #handler} to a JUL logger.
 * Collapsed, its header doubles as a status bar showing the latest message and warning/error counts.
 */
@Singleton
public final class ConsolePanel extends JPanel {
    private static final int MAX_LINES = 5_000;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final Formatter MESSAGE = new SimpleFormatter();
    private static final Color TIMESTAMP = new Color(0x5c6370);
    private static final Color SOURCE = new Color(0x56b6c2);

    private final JTextPane text = new JTextPane();
    private final JScrollPane scroll = new JScrollPane(text);
    private final JComponent grip = new JPanel();
    private final JLabel preview = new JLabel();
    private final JLabel warnings = counter("Warnings", MaterialDesignA.ALERT_OUTLINE, Theme.WARNING);
    private final JLabel errors = counter("Errors", MaterialDesignC.CLOSE_CIRCLE_OUTLINE, Theme.ERROR);
    private final JButton chevron = toolButton(MaterialDesignC.CHEVRON_DOWN, "Collapse", e -> setExpanded(!isExpanded()));
    private final JToggleButton follow = new JToggleButton(FontIcon.of(MaterialDesignA.ARROW_COLLAPSE_DOWN, 16, Theme.LIGHT), true);
    private int warningCount;
    private int errorCount;

    @Getter
    private final Handler handler = new Handler() {
        @Override
        public void publish(LogRecord record) {
            if (isLoggable(record)) {
                SwingUtilities.invokeLater(() -> append(record));
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    ConsolePanel() {
        super(new BorderLayout());

        text.setEditable(false);
        text.setBackground(Theme.DARKEST);
        text.setForeground(Theme.TEXT);
        text.setMargin(new Insets(6, 10, 6, 10));
        text.putClientProperty(STYLE_CLASS, "monospaced");
        ((DefaultCaret) text.getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
        scroll.setBorder(null);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setPreferredSize(new Dimension(0, 160));
        scroll.getVerticalScrollBar().putClientProperty(STYLE, "track: " + Theme.hex(Theme.DARKEST));

        JLabel title = new JLabel("Console", FontIcon.of(MaterialDesignC.CONSOLE_LINE, 16, Theme.ACCENT), SwingConstants.LEFT);
        title.putClientProperty(STYLE_CLASS, "semibold");
        preview.setBorder(new EmptyBorder(0, 12, 0, 12));

        follow.setSelectedIcon(FontIcon.of(MaterialDesignA.ARROW_COLLAPSE_DOWN, 16, Theme.ACCENT));
        follow.setToolTipText("Follow new output");
        follow.setFocusable(false);
        follow.putClientProperty(BUTTON_TYPE, BUTTON_TYPE_TOOLBAR_BUTTON);

        Box left = Box.createHorizontalBox();
        for (JComponent c : new JComponent[]{chevron, title, warnings, errors}) {
            left.add(c);
            left.add(Box.createHorizontalStrut(6));
        }
        Box right = Box.createHorizontalBox();
        right.add(follow);
        right.add(toolButton(MaterialDesignC.CONTENT_COPY, "Copy all", e -> Toolkit.getDefaultToolkit()
                .getSystemClipboard().setContents(new StringSelection(text.getText()), null)));
        right.add(toolButton(MaterialDesignT.TRASH_CAN_OUTLINE, "Clear", e -> clear()));

        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(Theme.DARKER);
        header.setBorder(new CompoundBorder(new MatteBorder(1, 0, 0, 0, Theme.DARK), new EmptyBorder(0, 4, 2, 6)));
        header.add(grip, BorderLayout.NORTH);
        header.add(left, BorderLayout.WEST);
        header.add(preview, BorderLayout.CENTER);
        header.add(right, BorderLayout.EAST);
        header.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                setExpanded(!isExpanded());
            }
        });

        // Dragging the strip above the header resizes the console, like an IDE tool window
        grip.setOpaque(false);
        grip.setPreferredSize(new Dimension(0, 4));
        grip.setCursor(Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR));
        MouseAdapter drag = new MouseAdapter() {
            private int startY;
            private int startHeight;

            @Override
            public void mousePressed(MouseEvent e) {
                startY = e.getYOnScreen();
                startHeight = scroll.getHeight();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                int max = getParent().getHeight() - 200;
                int height = Math.max(60, Math.min(max, startHeight + startY - e.getYOnScreen()));
                scroll.setPreferredSize(new Dimension(0, height));
                revalidate();
            }
        };
        grip.addMouseListener(drag);
        grip.addMouseMotionListener(drag);

        add(header, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        setExpanded(true);
    }

    boolean isExpanded() {
        return scroll.isVisible();
    }

    void setExpanded(boolean expanded) {
        scroll.setVisible(expanded);
        grip.setVisible(expanded);
        preview.setVisible(!expanded);
        chevron.setIcon(FontIcon.of(expanded ? MaterialDesignC.CHEVRON_DOWN : MaterialDesignC.CHEVRON_UP, 16, Theme.LIGHT));
        chevron.setToolTipText(expanded ? "Collapse" : "Expand");
        revalidate();
    }

    private void append(LogRecord record) {
        String level = level(record.getLevel());
        Color color = color(level);
        String message = MESSAGE.formatMessage(record);
        boolean problem = level.equals("WARN") || level.equals("ERROR");

        write(TIME.format(LocalTime.ofInstant(record.getInstant(), ZoneId.systemDefault())) + "  ", TIMESTAMP, false);
        write(String.format("%-5s  ", level), color, true);
        write(String.format("%-8s  ", shortName(record.getLoggerName())), SOURCE, false);
        write(message + "\n", problem ? color : Theme.TEXT, false);
        if (record.getThrown() != null) {
            StringWriter trace = new StringWriter();
            record.getThrown().printStackTrace(new PrintWriter(trace));
            write(trace.toString(), color.darker(), false);
        }

        Element root = text.getDocument().getDefaultRootElement();
        while (root.getElementCount() > MAX_LINES) {
            removeStart(root.getElement(0).getEndOffset());
        }
        if (follow.isSelected()) {
            text.setCaretPosition(text.getDocument().getLength());
        }

        preview.setText(message);
        preview.setForeground(problem ? color : Theme.LIGHT);
        if (level.equals("WARN")) {
            warnings.setText(String.valueOf(++warningCount));
            warnings.setVisible(true);
        } else if (level.equals("ERROR")) {
            errors.setText(String.valueOf(++errorCount));
            errors.setVisible(true);
        }
    }

    private void clear() {
        text.setText("");
        preview.setText("");
        warningCount = errorCount = 0;
        warnings.setVisible(false);
        errors.setVisible(false);
    }

    private void write(String s, Color color, boolean bold) {
        SimpleAttributeSet style = new SimpleAttributeSet();
        StyleConstants.setForeground(style, color);
        StyleConstants.setBold(style, bold);
        try {
            text.getDocument().insertString(text.getDocument().getLength(), s, style);
        } catch (BadLocationException e) {
            throw new IllegalStateException(e);
        }
    }

    private void removeStart(int length) {
        try {
            text.getDocument().remove(0, length);
        } catch (BadLocationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String level(Level level) {
        int v = level.intValue();
        return v >= Level.SEVERE.intValue() ? "ERROR"
                : v >= Level.WARNING.intValue() ? "WARN"
                : v >= Level.INFO.intValue() ? "INFO"
                : "DEBUG";
    }

    private static Color color(String level) {
        return switch (level) {
            case "ERROR" -> Theme.ERROR;
            case "WARN" -> Theme.WARNING;
            case "INFO" -> Theme.ACCENT;
            default -> Theme.LIGHT;
        };
    }

    private static String shortName(String logger) {
        return logger == null ? "" : logger.substring(logger.lastIndexOf('.') + 1);
    }

    private static JLabel counter(String tooltip, Ikon ikon, Color color) {
        JLabel label = new JLabel(FontIcon.of(ikon, 14, color));
        label.setForeground(color);
        label.setToolTipText(tooltip);
        label.setVisible(false);
        return label;
    }

    private static JButton toolButton(Ikon ikon, String tooltip, ActionListener action) {
        JButton button = new JButton(FontIcon.of(ikon, 16, Theme.LIGHT));
        button.putClientProperty(BUTTON_TYPE, BUTTON_TYPE_TOOLBAR_BUTTON);
        button.setToolTipText(tooltip);
        button.setFocusable(false);
        button.addActionListener(action);
        return button;
    }
}
