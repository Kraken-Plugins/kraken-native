package com.kraken.client.plugins.menuentryswapper;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Which fairy ring option becomes the left click.
 */
@Getter
@RequiredArgsConstructor
public enum FairyRingMode {
    ZANARIS("Zanaris"),
    LAST_DESTINATION("Last-Destination"),
    CONFIGURE("Configure"),
    OFF("Off");

    private final String name;

    @Override
    public String toString() {
        return name;
    }
}
