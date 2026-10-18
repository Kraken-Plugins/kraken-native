package com.kraken.api;

/** Thrown when there is no committed mapping for the client build that is running. */
public class UnsupportedBuildException extends RuntimeException {
    private final String version;
    private final String sha256;

    public UnsupportedBuildException(ClientBuild build, String reason) {
        super("No mapping for client " + build.version() + " (build " + build.sha256().substring(0, 16) + "): " + reason);
        this.version = build.version();
        this.sha256 = build.sha256();
    }

    /** The running client's version, e.g. {@code "241.3"}. */
    public String version() {
        return version;
    }

    public String sha256() {
        return sha256;
    }
}
