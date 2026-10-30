package com.kraken.client.plugins.menuentryswapper;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Which option becomes the left click on a house portal.
 */
@Getter
@RequiredArgsConstructor
public enum HouseMode {
    ENTER("Enter"),
    HOME("Home"),
    BUILD_MODE("Build mode"),
    FRIENDS_HOUSE("Friend's House");

    private final String name;

    @Override
    public String toString() {
        return name;
    }
}
