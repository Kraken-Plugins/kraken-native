package com.kraken.api.config;

/**
 * A config file didn't decode: it ran past its end or used an opcode the decoder doesn't know.
 */
public final class ConfigFormatException extends RuntimeException {

    /**
     * Creates the exception.
     *
     * @param message what went wrong and where in the file
     */
    public ConfigFormatException(String message) {
        super(message);
    }
}
