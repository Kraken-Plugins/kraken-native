package com.kraken.api.game;

/**
 * An RGBA colour, mirroring the Lua {@code osrs.Colour}. The client keeps interface colours as one {@code 0xAARRGGBB}
 * int and splits it the same way.
 *
 * @param r red, 0-255
 * @param g green, 0-255
 * @param b blue, 0-255
 * @param a alpha, 0-255 (the int's top byte; interface colours usually leave it 0)
 */
public record Colour(int r, int g, int b, int a) {

    /**
     * Splits a {@code 0xAARRGGBB} int.
     *
     * @param argb the packed colour
     * @return the colour
     */
    public static Colour of(int argb) {
        return new Colour((argb >> 16) & 0xff, (argb >> 8) & 0xff, argb & 0xff, argb >>> 24);
    }

    /**
     * The colour without its alpha.
     *
     * @return {@code 0xRRGGBB}
     */
    public int rgb() {
        return (r << 16) | (g << 8) | b;
    }
}
