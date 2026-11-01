package com.kraken.client.plugins.mossgiantkiller;

import com.google.inject.Inject;
import com.kraken.api.Client;
import com.kraken.api.Skill;
import com.kraken.client.ui.overlay.OverlayPanel;
import com.kraken.client.ui.overlay.OverlayPosition;
import com.kraken.client.ui.overlay.components.LineComponent;
import com.kraken.client.ui.overlay.components.TitleComponent;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;

/**
 * The Moss Giant Killer's status panel: what it's doing, the kills since it started, the food left and the
 * hitpoints. It reads what the plugin counted on the last tick, so drawing it costs no reads beyond the hitpoints.
 */
public final class MossGiantKillerOverlay extends OverlayPanel {

    private static final int WIDTH = 150;

    private final MossGiantKillerPlugin plugin;
    private final Client client;

    /**
     * Creates the panel at the top left.
     *
     * @param plugin the plugin whose status it shows
     * @param client the client, for the hitpoints
     */
    @Inject
    MossGiantKillerOverlay(MossGiantKillerPlugin plugin, Client client) {
        super(plugin);
        this.plugin = plugin;
        this.client = client;
        setPosition(OverlayPosition.TOP_LEFT);
        panelComponent.setPreferredSize(new Dimension(WIDTH, 0));
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        MossGiantKillerPlugin.State state = plugin.getState();
        panelComponent.getChildren().add(TitleComponent.builder().text("Moss Giant Killer")
                .color(state == MossGiantKillerPlugin.State.STOPPED ? Color.RED : Color.GREEN).build());
        panelComponent.getChildren().add(LineComponent.builder().left("Doing").right(state.toString()).build());
        panelComponent.getChildren().add(LineComponent.builder().left("Kills")
                .right(String.valueOf(plugin.getKills())).build());
        panelComponent.getChildren().add(LineComponent.builder().left("Food left")
                .right(String.valueOf(plugin.getFoodLeft())).build());
        panelComponent.getChildren().add(LineComponent.builder().left("Hitpoints")
                .right(client.getBoostedSkillLevel(Skill.HITPOINTS) + "/"
                        + client.getRealSkillLevel(Skill.HITPOINTS)).build());
        return super.render(graphics);
    }
}
