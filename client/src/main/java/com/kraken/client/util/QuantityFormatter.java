package com.kraken.client.util;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.text.NumberFormat;
import java.util.Locale;

/**
 * Formats item quantities and prices.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class QuantityFormatter {

    private static final String[] SUFFIXES = {"", "K", "M", "B"};

    /**
     * A short form of a quantity, at most six characters: the number with commas below ten thousand, else up to four
     * significant characters and a K, M or B suffix.
     *
     * @param quantity the quantity, such as {@code 9450}, {@code 2_140_000_000} or {@code 100_000}
     * @return the text, such as {@code 9,450}, {@code 2.14B} or {@code 100K}
     */
    public static String quantityToStackSize(long quantity) {
        if (quantity < 0) {
            return "-" + quantityToStackSize(quantity == Long.MIN_VALUE ? Long.MAX_VALUE : -quantity);
        }
        NumberFormat format = NumberFormat.getInstance(Locale.ENGLISH);
        if (quantity < 10_000) {
            return format.format(quantity);
        }
        String suffix = SUFFIXES[0];
        long divideBy = 1;
        for (int i = SUFFIXES.length - 1; i >= 0; i--) {
            divideBy = (long) Math.pow(10, i * 3);
            if ((double) quantity / divideBy >= 1) {
                suffix = SUFFIXES[i];
                break;
            }
        }
        String text = format.format((double) quantity / divideBy);
        text = text.length() > 4 ? text.substring(0, 4) : text;
        return (text.endsWith(".") ? text.substring(0, 3) : text) + suffix;
    }

    /**
     * A number with commas between thousands.
     *
     * @param number the number
     * @return the text, such as {@code 1,234,567}
     */
    public static String formatNumber(long number) {
        return NumberFormat.getInstance(Locale.ENGLISH).format(number);
    }
}
