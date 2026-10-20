package com.kraken.api;

/**
 * A definition with params (items, NPCs, objects), as RuneLite's {@code ParamHolder}, read-only.
 *
 * <p>RuneLite returns a param's default value when the definition doesn't set it. Kraken doesn't decode the param
 * definitions that hold those defaults, so an unset param reads as -1 or "" instead.
 */
public interface ParamHolder {

    /**
     * An integer param.
     *
     * @param paramId the param id
     * @return the param's value, or -1 if the definition doesn't set it (or sets a string)
     */
    int getIntValue(int paramId);

    /**
     * A string param.
     *
     * @param paramId the param id
     * @return the param's value, or "" if the definition doesn't set it (or sets an integer)
     */
    String getStringValue(int paramId);
}
