package com.kraken.api.internal.menu;

/**
 * A click the native hook reported: on a row of a plugin's own (which ran nothing in the client), or on a client
 * row a rule asked to be told about. Internal: the client's hook server hands these to
 * {@code GameSession.onMenuClick}.
 *
 * @param rule   true for a click a rule reported, false for a click on a plugin's row
 * @param id     the rule id, or the row id
 * @param parent the id of the row the clicked sub row hangs under, or -1 for a top row or a rule
 * @param x      the mouse x
 * @param y      the mouse y
 */
public record MenuClick(boolean rule, int id, int parent, int x, int y) {
}
