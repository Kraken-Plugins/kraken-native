package com.kraken.client.devtools;

import com.kraken.client.devtools.DevTool.Support;
import com.kraken.client.ui.Theme;
import com.kraken.client.ui.WrapLabel;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignE;
import org.kordamp.ikonli.materialdesign2.MaterialDesignO;
import org.kordamp.ikonli.swing.FontIcon;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static com.formdev.flatlaf.FlatClientProperties.*;

/**
 * One tool in the sidebar: its icon and name, then an eye toggle for its overlay and a button for its window, in
 * fixed columns. Clicking the row expands its description and options. Planned tools are greyed out.
 */
final class ToolRow extends JPanel {
    private static final int BUTTON = 26;
    private static final Color PLANNED_TEXT = new Color(0x858585);
    private static final Color PLANNED_ICON = new Color(0x5a5a5a);
    private static final Color NEEDS = new Color(0xc9a45c);

    private final DevTool<?> tool;
    private final JLabel icon = new JLabel();
    private final Icon idleIcon;
    private final Icon activeIcon;
    private final JToggleButton overlay;
    private final JToggleButton window;
    private final JPanel header = new JPanel(new BorderLayout(8, 0));
    private final JPanel details = new JPanel();
    private final Map<ToolOption, JCheckBox> optionBoxes = new HashMap<>();
    private final String searchText;
    private boolean expanded;
    private boolean hovered;

    ToolRow(DevTool<?> tool, DevToolManager manager) {
        super(new BorderLayout());
        this.tool = tool;
        this.searchText = (tool.getName() + " " + tool.getDescription() + " " + tool.getCategory().getTitle()).toLowerCase(Locale.ROOT);
        putClientProperty(STYLE, "arc: 8");
        setOpaque(false);
        setBackground(null);

        idleIcon = FontIcon.of(tool.getIcon(), 18, tool.isPlanned() ? PLANNED_ICON : Theme.LIGHT);
        activeIcon = FontIcon.of(tool.getIcon(), 18, Theme.ACCENT);
        JLabel name = new JLabel(tool.getName());
        name.setForeground(tool.isPlanned() ? PLANNED_TEXT : Theme.TEXT);

        overlay = toggle(MaterialDesignE.EYE_OUTLINE, MaterialDesignE.EYE, tool.overlaySupport(), "Overlay");
        overlay.addActionListener(e -> manager.setOverlay(tool, overlay.isSelected()));
        window = toggle(MaterialDesignO.OPEN_IN_NEW, MaterialDesignO.OPEN_IN_NEW, tool.windowSupport(), "Window");
        window.addActionListener(e -> manager.setWindowOpen(tool, window.isSelected()));

        Box buttons = Box.createHorizontalBox();
        buttons.add(slot(overlay, tool.overlaySupport()));
        buttons.add(Box.createHorizontalStrut(2));
        buttons.add(slot(window, tool.windowSupport()));

        header.setOpaque(false);
        header.setBorder(new EmptyBorder(2, 8, 2, 4));
        header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        header.add(icon, BorderLayout.WEST);
        header.add(name, BorderLayout.CENTER);
        header.add(buttons, BorderLayout.EAST);

        buildDetails(manager);
        add(header, BorderLayout.NORTH);
        add(details, BorderLayout.CENTER);

        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                setHovered(true);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                setHovered(header.getMousePosition(true) != null);
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e) && !(e.getSource() instanceof AbstractButton)) {
                    setExpanded(!expanded);
                }
            }
        };
        for (Component c : new Component[]{header, icon, name, buttons, overlay, window}) {
            c.addMouseListener(mouse);
        }
        setExpanded(false);
        refreshState();
    }

    boolean matches(String query) {
        return query.isEmpty() || searchText.contains(query);
    }

    boolean isPlanned() {
        return tool.isPlanned();
    }

    boolean isActive() {
        return tool.isActive();
    }

    /** Re-reads the tool's state (overlay on, window open, options) into the controls. */
    void refreshState() {
        overlay.setSelected(tool.isOverlayOn());
        window.setSelected(tool.isWindowOpen());
        if (tool.overlaySupport() == Support.READY) {
            overlay.setToolTipText(tool.isOverlayOn() ? "Hide from the game" : "Show over the game");
        }
        if (tool.windowSupport() == Support.READY) {
            window.setToolTipText(tool.isWindowOpen() ? "Close the window" : "Open in a window");
        }
        icon.setIcon(tool.isActive() ? activeIcon : idleIcon);
        optionBoxes.forEach((option, box) -> box.setSelected(option.isOn()));
    }

    private void buildDetails(DevToolManager manager) {
        details.setLayout(new BoxLayout(details, BoxLayout.Y_AXIS));
        details.setOpaque(false);
        details.setBorder(new EmptyBorder(0, 34, 8, 8));

        details.add(small(tool.getDescription(), Theme.LIGHT));
        if (tool.needs() != null && (tool.overlaySupport() == Support.PLANNED || tool.windowSupport() == Support.PLANNED)) {
            details.add(Box.createVerticalStrut(6));
            String what = tool.isPlanned() ? "Planned" : tool.overlaySupport() == Support.PLANNED ? "Overlay planned" : "Window planned";
            details.add(small(what + ": needs " + tool.needs() + ".", NEEDS));
        }
        if (!tool.options().isEmpty()) {
            details.add(Box.createVerticalStrut(4));
        }
        for (ToolOption option : tool.options()) {
            JCheckBox box = new JCheckBox(option.getLabel(), option.isOn());
            box.putClientProperty(STYLE_CLASS, "small");
            box.setOpaque(false);
            box.setFocusable(false);
            box.setAlignmentX(LEFT_ALIGNMENT);
            box.addActionListener(e -> manager.setOption(tool, option, box.isSelected()));
            optionBoxes.put(option, box);
            details.add(box);
        }
    }

    private void setExpanded(boolean expanded) {
        this.expanded = expanded;
        details.setVisible(expanded);
        updateBackground();
        revalidate();
    }

    private void setHovered(boolean hovered) {
        if (this.hovered != hovered) {
            this.hovered = hovered;
            updateBackground();
        }
    }

    /** FlatLaf paints the rounded background even when not opaque, so "none" means inheriting the parent's color. */
    private void updateBackground() {
        setBackground(expanded ? Theme.DARKER : hovered ? Theme.HOVER : null);
        repaint();
    }

    /** A toolbar toggle; disabled with an explanatory tooltip when its surface is only planned. */
    private JToggleButton toggle(Ikon off, Ikon on, Support support, String surface) {
        JToggleButton button = new JToggleButton(FontIcon.of(off, 16, Theme.LIGHT));
        button.setSelectedIcon(FontIcon.of(on, 16, Theme.ACCENT));
        button.setDisabledIcon(FontIcon.of(off, 16, PLANNED_ICON));
        button.putClientProperty(BUTTON_TYPE, BUTTON_TYPE_TOOLBAR_BUTTON);
        button.setFocusable(false);
        Dimension size = new Dimension(BUTTON, BUTTON);
        button.setPreferredSize(size);
        button.setMinimumSize(size);
        button.setMaximumSize(size);
        if (support == Support.PLANNED) {
            button.setEnabled(false);
            button.setToolTipText(surface + " planned: needs " + tool.needs());
        }
        return button;
    }

    /** The button, or an empty space of the same size so the columns line up when a tool lacks that surface. */
    private static Component slot(JToggleButton button, Support support) {
        return support == Support.NONE ? Box.createRigidArea(new Dimension(BUTTON, BUTTON)) : button;
    }

    private static JLabel small(String text, Color color) {
        WrapLabel label = new WrapLabel(text);
        label.putClientProperty(STYLE_CLASS, "small");
        label.setForeground(color);
        label.setAlignmentX(LEFT_ALIGNMENT);
        return label;
    }
}
