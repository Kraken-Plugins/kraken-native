package com.kraken.client.ui.overlay;

import com.kraken.client.plugins.Plugin;
import com.kraken.client.ui.overlay.components.PanelComponent;
import lombok.Getter;
import lombok.Setter;

import java.awt.Dimension;
import java.awt.Graphics2D;

/**
 * An overlay that is one {@link PanelComponent}. A subclass adds this frame's lines to {@link #panelComponent} and
 * returns {@code super.render(graphics)}:
 *
 * <pre>{@code
 * @Override
 * public Dimension render(Graphics2D graphics) {
 *     panelComponent.getChildren().add(LineComponent.builder().left("Ticks:").right("120").build());
 *     return super.render(graphics);
 * }
 * }</pre>
 */
public abstract class OverlayPanel extends Overlay {

    /**
     * The panel; its children are cleared after each frame unless {@link #isClearChildren()} is false.
     */
    protected final PanelComponent panelComponent = new PanelComponent();

    /**
     * Whether the panel's children are cleared after each frame, so the next frame adds them afresh.
     */
    @Getter
    @Setter
    private boolean clearChildren = true;

    /**
     * A panel overlay that belongs to no plugin.
     */
    protected OverlayPanel() {
        super();
    }

    /**
     * A panel overlay belonging to a plugin.
     *
     * @param plugin the plugin
     */
    protected OverlayPanel(Plugin plugin) {
        super(plugin);
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        Dimension original = panelComponent.getPreferredSize();
        Dimension preferred = getPreferredSize();
        if (preferred != null) {
            panelComponent.setPreferredSize(preferred);
        }
        try {
            return panelComponent.render(graphics);
        } finally {
            if (clearChildren) {
                panelComponent.getChildren().clear();
            }
            panelComponent.setPreferredSize(original);
        }
    }
}
