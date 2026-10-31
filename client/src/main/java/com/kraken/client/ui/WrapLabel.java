package com.kraken.client.ui;

import java.awt.*;
import javax.swing.*;
import javax.swing.plaf.basic.BasicHTML;
import javax.swing.text.View;

/**
 * A label whose text wraps to the width its layout gives it, so text reflows when the sidebar is resized. Use it where
 * the layout decides the width (a BorderLayout's center or north, a vertical BoxLayout); it asks for the height it
 * needs at that width.
 */
public final class WrapLabel extends JLabel {
    /** Width to measure at before the first layout has given the label a real one. */
    private static final int FIRST_LAYOUT_WIDTH = 200;

    private final String align;

    /** Left-aligned plain text. */
    public WrapLabel(String text) {
        this(text, SwingConstants.LEFT);
    }

    /**
     * Plain text, aligned {@link SwingConstants#LEFT}, {@link SwingConstants#CENTER} or {@link SwingConstants#RIGHT}.
     *
     * @param text      the text
     * @param alignment the alignment; anything else is left
     */
    public WrapLabel(String text, int alignment) {
        this.align = switch (alignment) {
            case SwingConstants.CENTER -> "center";
            case SwingConstants.RIGHT -> "right";
            default -> "left";
        };
        setHorizontalAlignment(alignment);
        setPlainText(text);
    }

    public void setPlainText(String text) {
        setHtml(Theme.escape(text));
    }

    /** Shows an HTML fragment, e.g. {@code "<b>Title</b><br>Body"}. */
    public void setHtml(String html) {
        setText("<html><div style='text-align:" + align + "'>" + html + "</div></html>");
    }

    @Override
    public Dimension getPreferredSize() {
        View view = (View) getClientProperty(BasicHTML.propertyKey);
        if (isPreferredSizeSet() || view == null) {
            return super.getPreferredSize();
        }
        Insets insets = getInsets();
        fitText(view, getWidth() > 0 ? getWidth() : FIRST_LAYOUT_WIDTH);
        Icon icon = getIcon();
        int textHeight = (int) Math.ceil(view.getPreferredSpan(View.Y_AXIS));
        int height = icon == null ? textHeight
                : iconBeside() ? Math.max(textHeight, icon.getIconHeight())
                : textHeight + getIconTextGap() + icon.getIconHeight();
        // Only ask for the longest word's width, so the label never props its container open
        int minWidth = (int) Math.ceil(view.getMinimumSpan(View.X_AXIS)) + iconSpace();
        return new Dimension(minWidth + insets.left + insets.right, height + insets.top + insets.bottom);
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    /**
     * A new width re-wraps the text, which changes the height. The text is re-wrapped now so it paints right, but the
     * re-layout is deferred: a request made while the parent is mid-layout would be dropped.
     */
    @Override
    public void setBounds(int x, int y, int width, int height) {
        boolean widthChanged = width != getWidth();
        super.setBounds(x, y, width, height);
        View view = (View) getClientProperty(BasicHTML.propertyKey);
        if (widthChanged && view != null && !isPreferredSizeSet()) {
            fitText(view, width);
            SwingUtilities.invokeLater(this::revalidate);
        }
    }

    /** Wraps the text to fit a label {@code width} wide. */
    private void fitText(View view, int width) {
        Insets insets = getInsets();
        view.setSize(Math.max(1, width - insets.left - insets.right - iconSpace()), 0);
    }

    private boolean iconBeside() {
        return getIcon() != null && getHorizontalTextPosition() != SwingConstants.CENTER;
    }

    private int iconSpace() {
        return iconBeside() ? getIcon().getIconWidth() + getIconTextGap() : 0;
    }
}
