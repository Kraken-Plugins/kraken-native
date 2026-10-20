package com.kraken.client.ui.devtools.view;

import com.kraken.client.ui.Theme;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.swing.FontIcon;

import javax.swing.*;
import java.awt.event.ActionListener;

import static com.formdev.flatlaf.FlatClientProperties.BUTTON_TYPE;
import static com.formdev.flatlaf.FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON;

/** Small shared controls for tool views. */
final class Controls {
    private Controls() {
    }

    static JButton toolButton(Ikon ikon, String tooltip, ActionListener action) {
        JButton button = new JButton(FontIcon.of(ikon, 16, Theme.LIGHT));
        button.putClientProperty(BUTTON_TYPE, BUTTON_TYPE_TOOLBAR_BUTTON);
        button.setToolTipText(tooltip);
        button.setFocusable(false);
        button.addActionListener(action);
        return button;
    }
}
