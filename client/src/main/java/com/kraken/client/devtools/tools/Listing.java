package com.kraken.client.devtools.tools;

import java.util.List;

/**
 * A snapshot for tools with both a table window and an overlay: the table's rows and the overlay's marks.
 *
 * @param rows  the table rows
 * @param marks what the overlay draws ({@link Marks#NONE} while the overlay is off)
 * @param <R>   the row type
 */
record Listing<R>(List<R> rows, Marks marks) {
}
