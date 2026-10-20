package com.kraken.api.internal;

import java.util.List;
import java.util.Map;

/**
 * Conversions the compositions share: RuneLite's action arrays and param lookups over the decoded params.
 */
public final class Definitions {

    private Definitions() {
    }

    /**
     * Op texts as RuneLite's action arrays hold them.
     *
     * @param ops the decoded op texts, "" for an empty slot
     * @return the same texts with null for each empty slot
     */
    public static String[] actions(List<String> ops) {
        String[] actions = new String[ops.size()];
        for (int i = 0; i < actions.length; i++) {
            String op = ops.get(i);
            actions[i] = op == null || op.isEmpty() ? null : op;
        }
        return actions;
    }

    /**
     * An integer param.
     *
     * @param params  the decoded params
     * @param paramId the param id
     * @return the value, or -1 if it isn't set or isn't a number
     */
    public static int intValue(Map<Integer, Object> params, int paramId) {
        return params.get(paramId) instanceof Number number ? number.intValue() : -1;
    }

    /**
     * A string param.
     *
     * @param params  the decoded params
     * @param paramId the param id
     * @return the value, or "" if it isn't set or isn't a string
     */
    public static String stringValue(Map<Integer, Object> params, int paramId) {
        return params.get(paramId) instanceof String text ? text : "";
    }
}
