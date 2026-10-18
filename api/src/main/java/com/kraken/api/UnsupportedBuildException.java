package com.kraken.api;

/** Thrown when there is no committed mapping for the client build that is running. */
public class UnsupportedBuildException extends RuntimeException {
    private final String sha256;

    public UnsupportedBuildException(String sha256) {
        super("No mapping for client build " + sha256);
        this.sha256 = sha256;
    }

    public String sha256() {
        return sha256;
    }
}
