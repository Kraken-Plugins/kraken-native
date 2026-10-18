package com.kraken.client.ui;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.fonts.jetbrains_mono.FlatJetBrainsMonoFont;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BaseMultiResolutionImage;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Objects;

/** RuneLite's ColorScheme greys with an accent taken from the Kraken logo, plus logo scaling. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class Theme {
    public static final Color DARKEST = new Color(0x141414);
    public static final Color DARKER = new Color(0x1e1e1e);
    public static final Color DARK = new Color(0x282828);
    public static final Color MEDIUM = new Color(0x4d4d4d);
    public static final Color LIGHT = new Color(0xa5a5a5);
    public static final Color TEXT = new Color(0xdcdcdc);
    public static final Color ACCENT = new Color(0x76b041);
    public static final Color WARNING = new Color(0xdc8a00);
    public static final Color ERROR = new Color(0xe65050);

    private static final BufferedImage LOGO = read("/kraken.png");

    public static void install() {
        FlatJetBrainsMonoFont.install();
        FlatLaf.setPreferredMonospacedFontFamily(FlatJetBrainsMonoFont.FAMILY);
        FlatLaf.setGlobalExtraDefaults(Map.ofEntries(
                Map.entry("@background", hex(DARK)),
                Map.entry("@accentColor", hex(ACCENT)),
                Map.entry("TitlePane.background", hex(DARKER)),
                Map.entry("TitlePane.inactiveBackground", hex(DARKER)),
                Map.entry("TitlePane.iconSize", "20,20"),
                Map.entry("ToggleButton.tab.selectedBackground", hex(DARK)),
                Map.entry("ToggleButton.tab.underlineHeight", "3"),
                Map.entry("ToggleButton.toolbar.selectedBackground", "#333333"),
                Map.entry("ScrollBar.width", "10"),
                Map.entry("ScrollBar.thumbArc", "999"),
                Map.entry("ScrollBar.thumbInsets", "2,2,2,2")));
        FlatDarkLaf.setup();
    }

    /** The logo at {@code size} px wide. Halves step by step, as one big bilinear jump would alias the pixel art. */
    static BufferedImage logo(int size) {
        BufferedImage img = LOGO;
        do {
            int w = Math.max(size, img.getWidth() / 2);
            int h = Math.max(1, img.getHeight() * w / img.getWidth());
            BufferedImage next = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = next.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(img, 0, 0, w, h, null);
            g.dispose();
            img = next;
        } while (img.getWidth() > size);
        return img;
    }

    /** HiDPI-aware logo: Java2D picks the variant that matches the display scale. */
    public static Image logoImage(int size) {
        return new BaseMultiResolutionImage(logo(size), logo(size * 3 / 2), logo(size * 2));
    }

    public static Icon logoIcon() {
        return new ImageIcon(logoImage(26));
    }

    public static String hex(Color c) {
        return String.format("#%06x", c.getRGB() & 0xffffff);
    }

    private static BufferedImage read(String resource) {
        try {
            return ImageIO.read(Objects.requireNonNull(Theme.class.getResource(resource)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
