package com.kraken.client.util;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.awt.Color;

/**
 * Colour helpers: the game's colour tags and colours with another alpha.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ColorUtil {

    /**
     * The tag that ends a coloured run of the game's text.
     */
    public static final String CLOSING_COLOR_TAG = "</col>";

    /**
     * The game's tag that colours the text after it, such as {@code <col=ff0000>}.
     *
     * @param color the colour; its alpha is dropped
     * @return the tag
     */
    public static String colorTag(Color color) {
        return "<col=" + colorToHexCode(color) + ">";
    }

    /**
     * A string with a colour tag in front.
     *
     * @param str   the string
     * @param color the colour
     * @return the tag followed by the string
     */
    public static String prependColorTag(String str, Color color) {
        return colorTag(color) + str;
    }

    /**
     * A string between a colour tag and a closing tag.
     *
     * @param str   the string
     * @param color the colour
     * @return the coloured string
     */
    public static String wrapWithColorTag(String str, Color color) {
        return prependColorTag(str, color) + CLOSING_COLOR_TAG;
    }

    /**
     * A colour's red, green and blue as six hex digits.
     *
     * @param color the colour
     * @return the digits, lower case, such as {@code ff9040}
     */
    public static String colorToHexCode(Color color) {
        return String.format("%06x", color.getRGB() & 0xffffff);
    }

    /**
     * The same colour with another alpha.
     *
     * @param color the colour
     * @param alpha the alpha, 0-255; out of range values are clamped
     * @return the colour
     */
    public static Color colorWithAlpha(Color color, int alpha) {
        int clamped = Math.max(0, Math.min(255, alpha));
        if (color.getAlpha() == clamped) {
            return color;
        }
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), clamped);
    }
}
