package com.kraken.client.plugins.menuentryswapper;

import com.kraken.api.menu.ShiftState;
import com.kraken.api.menu.TextMatch;

import java.util.function.BooleanSupplier;

/**
 * One built-in swap: when a row with an option (and, if given, a target) shows, the row below it with the swapped
 * option and the same target takes its place.
 *
 * @param option  the option that asks for the swap, such as "talk-to"
 * @param target  the target it must have, or null for any
 * @param swapped the option to bring up, exact or contained
 * @param enabled whether the setting behind the swap is on
 * @param shift   when the swap applies
 */
record Swap(String option, TextMatch target, TextMatch swapped, BooleanSupplier enabled, ShiftState shift) {
}
