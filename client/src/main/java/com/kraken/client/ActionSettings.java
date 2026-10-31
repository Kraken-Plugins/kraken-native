package com.kraken.client;

import com.google.inject.Inject;
import com.kraken.api.Client;
import com.kraken.api.Point;
import com.kraken.api.action.ClickPolicy;
import com.kraken.api.eventbus.EventBus;
import com.kraken.client.events.ConfigChanged;

import java.util.Set;

/**
 * Applies the Settings page's action settings, the mouse click policy and its static click point, to the attached
 * client's interactions. The session injector creates it once the API attaches, so the settings hold from the first
 * action on, and it applies them again whenever one of them changes.
 */
public final class ActionSettings {

    /**
     * The config keys this applies.
     */
    private static final Set<String> KEYS = Set.of("clickPolicy", "staticClickPoint");

    private final KrakenConfig config;
    private final Client client;

    /**
     * Applies the settings now, and follows their changes.
     *
     * @param config   the settings
     * @param eventBus where setting changes are posted
     * @param client   the attached client, whose interactions take the settings
     */
    @Inject
    ActionSettings(KrakenConfig config, EventBus eventBus, Client client) {
        this.config = config;
        this.client = client;
        apply();
        eventBus.register(ConfigChanged.class, event -> {
            if (KrakenConfig.GROUP.equals(event.getGroup()) && KEYS.contains(event.getKey())) {
                apply();
            }
        }, 0);
    }

    /**
     * Copies the settings into the client's interactions.
     */
    private void apply() {
        ClickPolicy policy = config.clickPolicy();
        java.awt.Point at = config.staticClickPoint();
        client.getInteractions().setClickPolicy(policy);
        client.getInteractions().setStaticClickPoint(new Point(at.x, at.y));
    }
}
