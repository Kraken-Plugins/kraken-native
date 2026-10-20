package com.kraken.client.ui.devtools.tools;

import com.kraken.api.Client;
import com.kraken.api.KrakenApi;
import com.kraken.client.ui.devtools.Category;
import com.kraken.client.ui.devtools.DevTool;
import com.kraken.client.ui.devtools.ToolOption;
import com.kraken.client.ui.devtools.view.FactsView;
import com.kraken.client.ui.devtools.view.ToolView;
import com.kraken.client.ui.overlay.OverlayContext;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;

import java.awt.*;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The camera (its position, the point it looks at, its angles, zoom and scale) and the client's size (the canvas and
 * the window), as an info box over the game and in a window. Positions are in local units of the top-level scene
 * (128 per tile), with height as the third value.
 */
final class CameraTool extends DevTool<CameraTool.State> {
    /**
     * The camera as one frame read it. Unreadable values are -1.
     *
     * @param x          the camera's local x
     * @param y          the camera's local y (north)
     * @param z          the camera's height
     * @param focalX     the focal point's local x
     * @param focalY     the focal point's local y (north)
     * @param focalZ     the focal point's height
     * @param pitch      the pitch, in JAU14 (16384 per turn)
     * @param yaw        the yaw, in JAU14
     * @param zoom       the zoom setting
     * @param scale      the projection scale
     * @param controlled whether a plugin controls the camera
     * @param target     whether the camera has a target
     * @param canvas     the canvas size, in the client's own pixels
     * @param window     the window's size
     */
    record State(int x, int y, int z, int focalX, int focalY, int focalZ, int pitch, int yaw, int zoom, int scale,
                 boolean controlled, boolean target, Dimension canvas, Dimension window) {
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
        Client client = api.client();
        if (client.getCameraX() < 0) {
            return null;
        }
        return new State(client.getCameraX(), client.getCameraY(), client.getCameraZ(),
                client.getCameraFocalPointX(), client.getCameraFocalPointY(), client.getCameraFocalPointZ(),
                client.getCameraPitch(), client.getCameraYaw(), client.getCameraZoom(), client.getScale(),
                client.isCameraControlled(), client.hasCameraTarget(),
                new Dimension(client.getCanvasWidth(), client.getCanvasHeight()),
                new Dimension(client.getScreenWidth(), client.getScreenHeight()));
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
            rows.put("Position", s.x() + ", " + s.y() + ", " + s.z());
            rows.put("Look at", known(s.focalX()) + ", " + known(s.focalY()) + ", " + known(s.focalZ()));
        }
        if (!onlyEnabled || angles.isOn()) {
            rows.put("Pitch", degrees(s.pitch()));
            rows.put("Yaw", degrees(s.yaw()));
        }
        if (!onlyEnabled || zoom.isOn()) {
            rows.put("Zoom", known(s.zoom()));
            rows.put("Scale", known(s.scale()));
        }
        if (!onlyEnabled || size.isOn()) {
            rows.put("Client", size(s.canvas()));
            rows.put("Window", size(s.window()));
        }
        if (!onlyEnabled) {
            rows.put("Plugin control", s.controlled() ? "yes" : "no");
            rows.put("Plugin target", s.target() ? "yes" : "no");
        }
        return rows;
    }

    /**
     * A value for display, or "?" when it couldn't be read.
     *
     * @param value the value, -1 when unreadable
     * @return the value, or "?"
     */
    private static String known(int value) {
        return value < 0 ? "?" : String.valueOf(value);
    }

    /**
     * An angle in JAU14 as degrees and as read.
     *
     * @param jau the angle in JAU14, -1 when unreadable
     * @return e.g. "12.3 deg (1234)", or "?"
     */
    private static String degrees(int jau) {
        return jau < 0 ? "?" : String.format(Locale.ROOT, "%.1f° (%d)", jau * 360.0 / 16384, jau);
    }

    /**
     * A size for display.
     *
     * @param v the size
     * @return e.g. "765 x 503"
     */
    private static String size(Dimension v) {
        return v.width + " × " + v.height;
    }
}
