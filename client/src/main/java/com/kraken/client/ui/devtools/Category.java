package com.kraken.client.ui.devtools;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** The collapsible sections of the dev tools sidebar, in display order. */
@Getter
@AllArgsConstructor
public enum Category {
    ACTORS("Actors"),
    STATE("Client state"),
    SCENE("Scene"),
    MAP("Map & movement"),
    INTERFACE("Interface"),
    DIAGNOSTICS("Diagnostics");

    private final String title;
}
