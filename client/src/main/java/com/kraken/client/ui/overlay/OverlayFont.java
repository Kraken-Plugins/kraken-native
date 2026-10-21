package com.kraken.client.ui.overlay;

import com.formdev.flatlaf.fonts.jetbrains_mono.FlatJetBrainsMonoFont;
import lombok.AllArgsConstructor;

import java.awt.Font;
import javax.swing.*;

/**
 * The fonts overlays can be drawn in ({@code KrakenConfig.overlayFont}). Panels use the font itself; dynamic overlays,
 * the labels drawn over the scene, use its bold weight.
 */
@AllArgsConstructor
public enum OverlayFont {

    /**
     * The interface font.
     */
    REGULAR("Regular"),

    /**
     * The interface font, bold.
     */
    BOLD("Bold"),

    /**
     * A monospaced font, which keeps changing numbers from shifting the text around them.
     */
    MONOSPACED("Monospaced");

    private final String label;

    /**
     * The font anchored overlays (panels) start with.
     *
     * @param size the size in canvas pixels
     * @return the font
     */
    public Font panel(int size) {
        return base().deriveFont(this == BOLD ? Font.BOLD : Font.PLAIN, (float) size);
    }

    /**
     * The font dynamic overlays start with: the family's bold weight.
     *
     * @param size the size in canvas pixels
     * @return the font
     */
    public Font dynamic(int size) {
        return base().deriveFont(Font.BOLD, (float) size);
    }

    /**
     * The name the settings page shows.
     *
     * @return the label
     */
    @Override
    public String toString() {
        return label;
    }

    private Font base() {
        if (this == MONOSPACED) {
            return new Font(FlatJetBrainsMonoFont.FAMILY, Font.PLAIN, 12);
        }
        Font label = UIManager.getFont("Label.font");
        return label != null ? label : new Font(Font.DIALOG, Font.PLAIN, 12);
    }
}
