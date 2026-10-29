package com.kraken.api.internal;

import java.util.Map;

/**
 * Internal. The int and string varcs at one read of the client's varc store, which the varc events compare poll to
 * poll. Varcs of other kinds (longs, the shared values of a few typed varcs) are in neither map.
 *
 * @param ints    each int varc's value, by varc id
 * @param strings each string varc's value, by varc id
 */
public record VarcValues(Map<Integer, Integer> ints, Map<Integer, String> strings) {
}
