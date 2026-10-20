package com.kraken.api.events;

import lombok.Data;

/**
 * A varp or varbit changed, as RuneLite's {@code VarbitChanged}. Kraken posts one event per changed varp
 * ({@code varbitId} -1, {@code value} the varp's value), preceded by one per varbit on that varp whose value changed
 * (both ids set, {@code value} the varbit's value).
 *
 * <p>Like RuneLite's, it follows the varps as the client holds them ({@code Client.getVarpValue}), which includes
 * changes the client makes locally. Lua's {@code ON_VAR_TRANSMIT} and {@code ON_VARBIT_UPDATE} follow the server's copy
 * instead.
 */
@Data
public class VarbitChanged {

    /**
     * The varp that changed, or that the changed varbit is on.
     */
    private int varpId = -1;

    /**
     * The varbit that changed, or -1 for a varp's own event.
     */
    private int varbitId = -1;

    /**
     * The varp's or varbit's new value.
     */
    private long value;

    /**
     * The new value, as RuneLite's {@code getValue}.
     *
     * @return the varp's or varbit's value
     */
    public int getValue() {
        return (int) value;
    }

    /**
     * Sets the new value.
     *
     * @param value the varp's or varbit's value
     */
    public void setValue(int value) {
        this.value = value;
    }

    /**
     * The new value as a long, as RuneLite's {@code getLongValue}.
     *
     * @return the varp's or varbit's value
     */
    public long getLongValue() {
        return value;
    }

    /**
     * Sets the new value as a long.
     *
     * @param value the varp's or varbit's value
     */
    public void setLongValue(long value) {
        this.value = value;
    }
}
