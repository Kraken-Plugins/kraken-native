package com.kraken.client.devtools.tools;

import com.kraken.api.KrakenApi;
import com.kraken.api.game.Camera;
import com.kraken.api.game.FineCoord;
import com.kraken.api.game.Graphics;
import com.kraken.api.game.Vector2;
import com.kraken.client.devtools.Category;
import com.kraken.client.devtools.DevTool;
import com.kraken.client.devtools.ToolOption;
import com.kraken.client.devtools.view.FactsView;
import com.kraken.client.devtools.view.ToolView;
import com.kraken.client.overlay.OverlayContext;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The camera ({@code Camera.getPos}, {@code getLookAt}, {@code getLookAtAngles}…) and the client's size
 * ({@code Graphics.clientDimensions}, {@code osrs.screenSize}), as an info box over the game and in a window.
 */
final class CameraTool extends DevTool<CameraTool.State> {
    record State(FineCoord pos, FineCoord lookAt, Vector2 angles, int pitch, int yaw, int zoom, int scale,
                 boolean controlled, boolean target, Vector2 client, Vector2 screen) {
    }

    private final ToolOption position = option("position", "Position", true);
    private final ToolOption angles = option("angles", "Angles", true);
    private final ToolOption zoom = option("zoom", "Zoom and scale", false);
    private final ToolOption size = option("size", "Client size", false);

    CameraTool() {
        super("camera", "Camera", Category.SCENE, MaterialDesignC.CAMERA_OUTLINE,
                "The camera's position, look-at point, pitch, yaw, zoom and projection scale, and the client's size.");
    }

    @Override
    public Support overlaySupport() {
        return Support.READY;
    }

    @Override
    public Support windowSupport() {
        return Support.READY;
    }

    @Override
    protected State read(KrakenApi api) {
        Camera camera = api.camera();
        Graphics graphics = api.graphics();
        FineCoord pos = camera.getPos();
        if (pos == null) {
            return null;
        }
        return new State(pos, camera.getLookAt(), camera.getLookAtAngles(), camera.getPitch(), camera.getYaw(),
                camera.getZoom(), camera.getScale(), camera.isControlled(), camera.hasTarget(),
                graphics.clientDimensions(), graphics.screenSize());
    }

    @Override
    protected void paintOverlay(State state, OverlayContext overlay) {
        overlay.infoPanel("Camera", facts(state, true));
    }

    @Override
    protected ToolView<State> createView() {
        return new FactsView<>(state -> facts(state, false));
    }

    /**
     * The rows to show.
     *
     * @param s           the camera's state
     * @param onlyEnabled apply the options (the window always shows everything)
     * @return the rows, in order
     */
    private Map<String, String> facts(State s, boolean onlyEnabled) {
        Map<String, String> rows = new LinkedHashMap<>();
        if (!onlyEnabled || position.isOn()) {
            rows.put("Position", String.valueOf(s.pos()));
            rows.put("Look at", s.lookAt() == null ? "?" : s.lookAt().toString());
        }
        if (!onlyEnabled || angles.isOn()) {
            Vector2 a = s.angles();
            rows.put("Pitch", a == null ? "?" : String.format(Locale.ROOT, "%.1f° (%d)", a.x(), s.pitch()));
            rows.put("Yaw", a == null ? "?" : String.format(Locale.ROOT, "%.1f° (%d)", a.y(), s.yaw()));
        }
        if (!onlyEnabled || zoom.isOn()) {
            rows.put("Zoom", String.valueOf(s.zoom()));
            rows.put("Scale", String.valueOf(s.scale()));
        }
        if (!onlyEnabled || size.isOn()) {
            rows.put("Client", size(s.client()));
            rows.put("Window", size(s.screen()));
        }
        if (!onlyEnabled) {
            rows.put("Plugin control", s.controlled() ? "yes" : "no");
            rows.put("Plugin target", s.target() ? "yes" : "no");
        }
        return rows;
    }

    private static String size(Vector2 v) {
        return v == null ? "?" : Math.round(v.x()) + " × " + Math.round(v.y());
    }
}
